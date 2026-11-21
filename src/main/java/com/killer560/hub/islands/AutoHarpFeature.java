package com.killer560.hub.islands;

import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.PingTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
 * the falling rows. When the ping is longer than half a step the click goes {@code k} rows early, from the wool note
 * {@code k} rows above the click row; the lead is the measured ping (Auto Detect Ping) plus the manual offset. Auto
 * Detect Lag measures the step from how the note rows actually advance (the median of the last few), so a laggy
 * server's slower steps are followed; with it off the step is assumed {@link #NOMINAL_STEP_MS}. A note that reaches
 * the click row is clicked the moment its slot packet is handled (AutoHarpSlotMixin), and a board that moved several
 * rows at once (a server that froze and caught up) is read at once, so a note a short server lag left on screen for
 * only a tick is still reached (harpmiss5-1121). Every click is a
 * note: an early click is counted, and the quartz that note later becomes is not clicked again.
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
    private static final int RING = 5;
    /** Ms a click loses besides the ping: the settle tick, client tick and server tick it waits for. */
    private static final long TICK_LAG_MS = 100;

    private static ClientTickEvents.EndTick guardedTick;
    /** Set while a click-row slot packet runs the tick logic; {@code endTicks} counts real tick ends. */
    private static boolean slotCall;
    private static long endTicks;
    /** The tick end from which early clicks may go for the board decided last. */
    private static long earlyFrom;
    /** ...and not sooner than about a tick after the change was seen in ms, as client ticks run back to back after a hitch. */
    private static long earlyFromMs;
    private static int lastHash;
    private static int lastContainer = -1;
    private static boolean active;
    private static boolean settling;
    /** Wool columns of rows 0-3 on the last decided board: d steps on, row r shows row r-d and the click row row 4-d. */
    private static final int[] decidedWool = new int[4];
    private static int decidedQuartz;
    private static long lastStepMs;
    private static final long[] gaps = new long[RING];
    private static int gapCount;
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
        guardedTick = FeatureGuard.end("AutoHarpFeature.tick", bit, c -> {
            endTicks += slotCall ? 0 : 1;
            tick(c);
        });
        ClientTickEvents.END_CLIENT_TICK.register(guardedTick);
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

    /** From AutoHarpSlotMixin on the render thread: a click-row slot of the playing harp turned to quartz is decided and
     *  clicked at once, up to a client tick sooner than the tick's end would (a short note after a server freeze). */
    public static void onSlot(int containerId, int slot) {
        if (active && guardedTick != null && containerId == lastContainer && slot >= FIRST_SLOT && slot < FIRST_SLOT + COLUMNS) {
            Minecraft client = Minecraft.getInstance();
            if (com.killer560.hub.compat.McCompat.screen(client) instanceof AbstractContainerScreen<?> s
                    && "quartz_block".equals(path(s.getMenu().getSlot(slot).getItem()))) {
                slotCall = true;
                try {
                    guardedTick.onEndTick(client);
                } finally {
                    slotCall = false;
                }
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
            Arrays.fill(played, false);
            Arrays.fill(arrived, false);
            settling = false;
            readWool(menu, decidedWool);
            decidedQuartz = mask(menu, FIRST_SLOT, "quartz_block");
            lastEarlyRows = 0;
            ModChat.send("Auto Harp", ModChat.good("Started."));
        }
        int hash = rowsHash(menu);
        boolean changed = hash != lastHash;
        if (changed) {
            lastHash = hash;
            long gap = now - lastStepMs;
            // A step's slot updates can arrive split over client ticks (rows first, the click row after): a change this
            // soon after the last is the same step, and nothing is decided until the click row shows this step's
            // quartz (the last board's row-3 wool) or the board holds still for a tick, or the old quartz still in the
            // click row would be clicked again. Waiting the tick every step cost a note under load (harpmiss-1121).
            // A server that froze and caught up in a burst moves the rows several steps at once, or twice within
            // MIN_STEP_MS: a board that is the last decided one moved down d rows, click row included, is d new steps
            // and is decided at once whatever the gap (harpmiss5-1121).
            int d = shift(menu, gap < MIN_STEP_MS);
            if (d > 0 || gap >= MIN_STEP_MS) {
                lastStepMs = now;
                if (gap >= MIN_STEP_MS && gap <= 3000) {
                    gaps[gapNext] = gap / Math.max(1, d);
                    gapNext = (gapNext + 1) % RING;
                    gapCount = Math.min(RING, gapCount + 1);
                }
                settling = true;
                earlyFrom = endTicks + (slotCall ? 2 : 1);
                earlyFromMs = now + 45;
            }
            if (settling && d == 0) {
                return;
            }
        }
        // Early clicks keep the settle tick: their lead (TICK_LAG_MS) was measured with it, and without it a click
        // one row early at a ping near half a step reached the board a step too soon. They go only at a tick end, the
        // one after the tick end that would have seen the step change first (earlyFrom); a quartz already in the click
        // row goes at once.
        if (settling) {
            settling = false;
            Arrays.fill(played, false);
            Arrays.fill(arrived, false);
        }
        readWool(menu, decidedWool);
        decidedQuartz = mask(menu, FIRST_SLOT, "quartz_block");
        int rows = earlyRows();
        long expire = (MAX_EARLY + 2) * stepMs();
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
                early = !slotCall && endTicks >= earlyFrom && now >= earlyFromMs && rows > 0 && above >= 0 && !played[c]
                        && path(menu.slots.get(above).getItem()).endsWith("_wool");
                if (!early) {
                    continue;
                }
            }
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
    }

    /** Median ms between observed steps of the note rows, or the nominal step before one is measured / with Auto Detect Lag off. */
    static long stepMs() {
        if (!CFG.on("detectLag") || gapCount == 0) {
            return NOMINAL_STEP_MS;
        }
        long[] s = Arrays.copyOf(gaps, gapCount);
        Arrays.sort(s);
        return s[gapCount / 2];
    }

    /** How many rows above the click row a note is clicked: the click should reach the server mid-step. */
    static int earlyRows() {
        int ping = !CFG.on("detectPing") ? 0
                : CFG.on("detectLag") ? PingTracker.automationMsLagFree() : PingTracker.automationMs();
        long step = stepMs();
        double v = (ping + TICK_LAG_MS + CFG.num("offset") - step / 2) / (double) step;
        // Hysteresis: a jittering ping or step measure must not flip the lead between two row counts note to note.
        if (Math.abs(v - lastEarlyRows) >= 0.6) {
            lastEarlyRows = (int) Math.max(0, Math.min(MAX_EARLY, Math.round(v)));
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
