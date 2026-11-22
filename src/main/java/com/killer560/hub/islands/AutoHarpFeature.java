package com.killer560.hub.islands;

import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.PingTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
 * Auto Melody's Harp (cheat build only; brought over from staging-islands): while a
 * "Harp - &lt;song&gt;" menu is the open screen, plays its notes. Notes fall down seven columns; a note is played by
 * middle-clicking its column's slot in the click row (slots 37-43) while that slot shows a Block of Quartz.
 * One click a tick at most, through {@link ActionGate} ({@code ISLAND_HARP}), container clicks only. It never
 * moves or turns you, and a server correction changes nothing here.
 *
 * <p>Timing: a click lands on the server one ping after it is sent, and a note stays in the click row for one step of
 * the falling rows. When the lead is over {@link #LATEST_AT_ONCE_MS} the click goes {@code k} rows early, from the wool note
 * {@code k} rows above the click row; the lead is the measured ping (Auto Detect Ping) plus the manual offset. Auto
 * Detect Lag measures the step from how the note rows actually advance (the mean of the last few, a stalled step left
 * out), so a laggy server's slower steps are followed; with it off the step is assumed {@link #NOMINAL_STEP_MS}. An
 * early click is timed from when the step's slot packets reached the network thread, to land {@link #AIM_MS} into its
 * note's step (or at once when that is already past, up to {@link #LATEST_AT_ONCE_MS}), on the first frame it falls
 * due, never by client ticks: a tick-timed click landed anywhere from the step before to the very end of the note's
 * step, as the client's ticks fell (harpmiss-1122). A note that reaches the click row is clicked the moment its slot
 * packet is handled (AutoHarpSlotMixin), and a board that moved several rows at once (a server that froze and caught
 * up) is read at once, so a note a short server lag left on screen for only a tick is still reached (harpmiss5-1121).
 * Every click is a note: an early click is counted, and the quartz that note later becomes is not clicked again.
 * Automation: can get you banned; keep it off on the main server.
 */
public final class AutoHarpFeature {

    public static final IslandConfig CFG = IslandConfig.of("island", "autoharp")
            .flag("detectLag", true).flag("detectPing", true).num("offset", 0, -300, 300);

    static final int FIRST_SLOT = 37;
    static final int COLUMNS = 7;
    /** Rows above the click row that hold falling notes (slots 1-7, 10-16, 19-25, 28-34). */
    static final int MAX_EARLY = 4;
    /** Assumed ms between steps of the falling rows until (or unless) it is measured. */
    static final long NOMINAL_STEP_MS = 250;
    /** Two changes of the note rows closer than this (two client ticks) are one step. */
    private static final long MIN_STEP_MS = 150;
    private static final int RING = 8;
    /** An early click aims to reach the server this far into its note's step (one server tick of room both ways). */
    static final long AIM_MS = 50;
    /** The latest into its step a click sent the moment its board arrives may land; past it the click goes a row earlier
     *  and waits instead, so a wait never runs within a few frames of the next board (which moves its note on). */
    private static final long LATEST_AT_ONCE_MS = 140;
    /** Ms of lead change the early-row count ignores, so a jittering ping does not flip it note to note. */
    private static final long ROWS_HYSTERESIS_MS = 20;

    private static ClientTickEvents.EndTick guardedTick;
    /** Network-thread arrival of the last few harp slot packets, looked up by the packet when the render thread handles it. */
    private static final Object[] NET_PACKETS = new Object[8];
    private static final long[] NET_MS = new long[8];
    private static int netNext;
    /** Arrival (ms) of the first row or click-row slot packet that changed the board since the last tick decision, or 0. */
    private static long changeMs;
    /** When the next early click falls due (ms), for the frame check; 0 when none is waiting. */
    private static long frameDueMs;
    private static int lastHash;
    private static volatile int lastContainer = -1;
    private static volatile boolean active;
    private static boolean settling;
    /** Wool columns of rows 0-3 on the last decided board: d steps on, row r shows row r-d and the click row row 4-d. */
    private static final int[] decidedWool = new int[4];
    private static int decidedQuartz;
    private static long lastStepMs;
    private static final long[] gaps = new long[RING];
    private static int gapCount;
    private static final long[] SORTED = new long[RING];
    private static long measuredStep = NOMINAL_STEP_MS;
    private static int gapNext;
    private static final boolean[] played = new boolean[COLUMNS];
    /** The quartz now in the click row has been accounted for (a pending early click consumed, or a late click made). */
    private static final boolean[] arrived = new boolean[COLUMNS];
    /** Early clicks per column whose note has not yet reached the click row, and when the oldest was sent. */
    private static final int[] pending = new int[COLUMNS];
    private static final long[] pendingSince = new long[COLUMNS];
    private static int clicks;
    private static int earlyClicks;
    private static int lastEarlyRows;

    private AutoHarpFeature() {
    }

    public static void register() {
        int bit = FeatureBits.register("Auto Melody's Harp", CFG::isEnabledRaw);
        PingTracker.addDemand(() -> CFG.isEnabledRaw() && CFG.on("detectPing") && active);
        guardedTick = FeatureGuard.end("AutoHarpFeature.tick", bit, AutoHarpFeature::tick);
        ClientTickEvents.END_CLIENT_TICK.register(guardedTick);
        // An early click goes on the first frame after it falls due, not up to a client tick later.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (isHarpTitle(screen.getTitle().getString())) {
                ScreenEvents.afterExtract(screen).register((x, g, mx, my, delta) -> {
                    if (frameDueMs != 0 && System.nanoTime() / 1_000_000L >= frameDueMs) {
                        frameDueMs = 0;
                        guardedTick.onEndTick(client);
                    }
                });
            }
        });
    }

    static boolean isHarpTitle(String title) {
        return title != null && title.startsWith("Harp - ") && title.length() < 64;
    }

    private static String path(ItemStack s) {
        return s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    /** Bit c set when the slot {@code first + c} holds an item whose id ends with {@code suffix}. */
    private static int mask(AbstractContainerMenu menu, int first, String suffix) {
        int m = 0;
        for (int c = 0; c < COLUMNS && first + c < menu.slots.size(); c++) {
            if (path(menu.slots.get(first + c).getItem()).endsWith(suffix)) {
                m |= 1 << c;
            }
        }
        return m;
    }

    /** From AutoHarpSlotMixin at the HEAD of every slot packet, which runs first on the network thread (that call records
     *  when the packet arrived) and again on the render thread before the slot is set (that call notes the arrival of
     *  the first packet that changes a note slot). */
    public static void onSlotHead(Object packet, int containerId, int slot, ItemStack item) {
        if (!active || containerId != lastContainer || slot < 0 || slot >= FIRST_SLOT + COLUMNS) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        long now = System.nanoTime() / 1_000_000L;
        if (!client.isSameThread()) {
            synchronized (NET_MS) {
                NET_PACKETS[netNext] = packet;
                NET_MS[netNext] = now;
                netNext = (netNext + 1) % NET_MS.length;
            }
            return;
        }
        AbstractContainerMenu menu = client.player == null ? null : client.player.containerMenu;
        if (changeMs != 0 || menu == null || menu.containerId != containerId || slot >= menu.slots.size()
                || menu.slots.get(slot).getItem().getItem() == item.getItem()) {
            return;
        }
        changeMs = now;
        synchronized (NET_MS) {
            for (int i = 0; i < NET_MS.length; i++) {
                if (NET_PACKETS[i] == packet) {
                    changeMs = NET_MS[i];
                    NET_PACKETS[i] = null;
                }
            }
        }
    }

    /** From AutoHarpSlotMixin on the render thread: a click-row slot of the playing harp turned to quartz is decided and
     *  clicked at once, up to a client tick sooner than the tick's end would (a short note after a server freeze). */
    public static void onSlot(int containerId, int slot) {
        if (active && guardedTick != null && containerId == lastContainer && slot >= FIRST_SLOT && slot < FIRST_SLOT + COLUMNS) {
            Minecraft client = Minecraft.getInstance();
            if (com.killer560.hub.compat.McCompat.screen(client) instanceof AbstractContainerScreen<?> s
                    && "quartz_block".equals(path(s.getMenu().getSlot(slot).getItem()))) {
                guardedTick.onEndTick(client);
            }
        }
    }

    private static void tick(Minecraft client) {
        if (!CFG.isEnabled() || client.player == null || client.gameMode == null
                || !(com.killer560.hub.compat.McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)
                || !isHarpTitle(screen.getTitle().getString())) {
            if (active) {
                active = false;
                lastContainer = -1;
                ModChat.send("Auto Harp", ModChat.dim("Stopped."));
            }
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        long now = System.nanoTime() / 1_000_000L;
        if (!active || menu.containerId != lastContainer) {
            active = true;
            lastContainer = menu.containerId;
            lastHash = rowsHash(menu);
            lastStepMs = now;
            gapCount = 0;
            Arrays.fill(pending, 0);
            frameDueMs = 0;
            Arrays.fill(played, false);
            Arrays.fill(arrived, false);
            settling = false;
            readWool(menu, decidedWool);
            decidedQuartz = mask(menu, FIRST_SLOT, "quartz_block");
            lastEarlyRows = rowsFor(lead(), NOMINAL_STEP_MS);
            ModChat.send("Auto Harp", ModChat.good("Started."));
        }
        int hash = rowsHash(menu);
        boolean changed = hash != lastHash;
        long seen = changeMs != 0 && changeMs <= now ? changeMs : now;
        changeMs = 0;
        if (changed) {
            lastHash = hash;
            long gap = seen - lastStepMs;
            // A step's slot updates can arrive split over client ticks (rows first, the click row after): a change this
            // soon after the last is the same step, and nothing is decided until the click row shows this step's
            // quartz (the last board's row-3 wool) or the board holds still for a tick, or the old quartz still in the
            // click row would be clicked again. Waiting the tick every step cost a note under load (harpmiss-1121).
            // A server that froze and caught up in a burst moves the rows several steps at once, or twice within
            // MIN_STEP_MS: a board that is the last decided one moved down d rows, click row included, is d new steps
            // and is decided at once whatever the gap (harpmiss5-1121).
            int d = shift(menu, gap < MIN_STEP_MS);
            if (d > 0 || gap >= MIN_STEP_MS) {
                lastStepMs = seen;
                if (gap >= MIN_STEP_MS && gap <= 3000) {
                    gaps[gapNext] = gap / Math.max(1, d);
                    gapNext = (gapNext + 1) % RING;
                    gapCount = Math.min(RING, gapCount + 1);
                    measureStep();
                }
                settling = true;
            }
            if (settling && d == 0) {
                return;
            }
        }
        // An early click k rows up goes when it will reach the server AIM_MS into its note's step: k steps after this
        // board arrived, less the lead, plus AIM_MS. Past that by more than the rest of the step less a tick of room it
        // would land in the next step, so it is not sent (the note is then clicked late from the click row, as before).
        // A quartz already in the click row goes at once.
        if (settling) {
            settling = false;
            Arrays.fill(played, false);
            Arrays.fill(arrived, false);
        }
        readWool(menu, decidedWool);
        decidedQuartz = mask(menu, FIRST_SLOT, "quartz_block");
        int rows = earlyRows();
        long step = stepMs();
        long due = lastStepMs + rows * step - lead() + AIM_MS;
        boolean earlyTime = rows > 0 && now >= due && now < due + step - 2 * AIM_MS;
        long nextDue = 0;
        long expire = (MAX_EARLY + 2) * step;
        for (int c = 0; c < COLUMNS; c++) {
            if (pending[c] > 0 && now - pendingSince[c] > expire) {
                pending[c] = 0;
            }
            int slot = FIRST_SLOT + c;
            if (slot >= menu.slots.size()) {
                break;
            }
            boolean quartz = "quartz_block".equals(path(menu.slots.get(slot).getItem()));
            boolean late = false;
            if (quartz && !arrived[c]) {
                arrived[c] = true;
                if (pending[c] > 0) { // this note was already clicked on its way down
                    pending[c]--;
                } else {
                    late = true;
                }
            }
            boolean early = false;
            if (!late) {
                int above = slot - 9 * rows;
                early = rows > 0 && above >= 0 && !played[c] && path(menu.slots.get(above).getItem()).endsWith("_wool");
                nextDue = early && now < due ? due : nextDue;
                early &= earlyTime;
                if (!early) {
                    continue;
                }
            }
            frameDueMs = 0; // the next tick end looks again
            if (!ActionGate.tryAct(ActionGate.Actor.ISLAND_HARP, screen)) {
                arrived[c] &= !late; // a late click the gate refused is tried again next tick
                return;
            }
            client.gameMode.handleContainerInput(menu.containerId, slot, 2, ContainerInput.CLONE, client.player);
            played[c] = true;
            HarpHelperFeature.noteClick(menu, slot);
            clicks++;
            if (early) {
                if (pending[c]++ == 0) {
                    pendingSince[c] = now;
                }
                earlyClicks++;
            }
            ModChat.log("Auto Harp", ModChat.text("column " + c + (rows > 0 ? " " + rows + " row(s) early" : "")));
            return;
        }
        frameDueMs = nextDue;
    }

    /** Ms between observed steps of the note rows, or the nominal step before one is measured / with Auto Detect Lag off. */
    static long stepMs() {
        return !CFG.on("detectLag") || gapCount == 0 ? NOMINAL_STEP_MS : measuredStep;
    }

    /** The mean of the last gaps less any over 1.5x their median (a stalled step). A median alone swung by a whole
     *  tick of arrival jitter from one step to the next and moved early clicks with it (harpmiss-1122). */
    private static void measureStep() {
        System.arraycopy(gaps, 0, SORTED, 0, gapCount);
        Arrays.sort(SORTED, 0, gapCount);
        long cap = SORTED[gapCount / 2] * 3 / 2;
        long sum = 0;
        int n = 0;
        for (int i = 0; i < gapCount; i++) {
            if (SORTED[i] <= cap) {
                sum += SORTED[i];
                n++;
            }
        }
        measuredStep = sum / n;
    }

    /** The lead in ms: the measured round trip (Auto Detect Ping) plus Click Offset, or the offset alone. */
    static long lead() {
        int ping = !CFG.on("detectPing") ? 0
                : CFG.on("detectLag") ? PingTracker.automationMsLagFree() : PingTracker.automationMs();
        return Math.max(0, ping + CFG.num("offset"));
    }

    /** Rows early for a lead: the fewest with which a click sent the moment its board arrives lands no later than
     *  LATEST_AT_ONCE_MS into its note's step ({@code lead - k * step}); it waits until AIM_MS when that would be sooner. */
    static int rowsFor(long lead, long step) {
        return (int) Math.min(MAX_EARLY, Math.max(0, Math.ceilDiv(lead - LATEST_AT_ONCE_MS, step)));
    }

    /** How many rows above the click row a note is clicked; kept while it still fits the lead within the hysteresis. */
    static int earlyRows() {
        long lead = lead();
        long step = stepMs();
        if (lastEarlyRows < rowsFor(lead - ROWS_HYSTERESIS_MS, step) || lastEarlyRows > rowsFor(lead + ROWS_HYSTERESIS_MS, step)) {
            lastEarlyRows = rowsFor(lead, step);
        }
        return lastEarlyRows;
    }

    private static final int[] SCRATCH = new int[4];

    /** Wool masks of the four falling rows (slots 1-7, 10-16, 19-25, 28-34) into {@code out}. */
    private static int[] readWool(AbstractContainerMenu menu, int[] out) {
        for (int r = 0; r < 4; r++) {
            out[r] = mask(menu, 1 + 9 * r, "_wool");
        }
        return out;
    }

    /** How many steps (1-4) the board moved since the last decided one, judged by every row including the click row's
     *  quartz; 0 when it does not read as a whole move (a step's slot updates still arriving, or no move). One step a
     *  step's time after the last is judged by the click row alone, as before; anything else by every row too. */
    private static int shift(AbstractContainerMenu menu, boolean rowsToo) {
        int q = mask(menu, FIRST_SLOT, "quartz_block");
        int[] now = readWool(menu, SCRATCH);
        for (int d = 1; d <= MAX_EARLY; d++) {
            // Two or more steps at once only behind a click row that changed: a split update's stale click row (the rows
            // already moved, the quartz not yet) can match an older row by chance on a sparse board.
            boolean ok = q == decidedWool[4 - d] && (d == 1 || q != decidedQuartz);
            for (int r = d; r < 4 && ok && (rowsToo || d > 1); r++) {
                ok = now[r] == decidedWool[r - d];
            }
            if (ok) {
                return d;
            }
        }
        return 0;
    }

    /** A cheap fingerprint of the falling-note rows (slots 0-35), which changes each time the notes move. */
    static int rowsHash(AbstractContainerMenu menu) {
        int h = 1;
        for (int i = 0; i < 36 && i < menu.slots.size(); i++) {
            ItemStack s = menu.slots.get(i).getItem();
            h = h * 31 + (s.isEmpty() ? 0 : BuiltInRegistries.ITEM.getKey(s.getItem()).hashCode());
        }
        return h;
    }

    /** Harness: notes clicked, how many of them early, and the early-row count of the last tick. */
    public static int clickCount() {
        return clicks;
    }

    public static int earlyClickCount() {
        return earlyClicks;
    }

    public static int lastEarlyRows() {
        return lastEarlyRows;
    }
}
