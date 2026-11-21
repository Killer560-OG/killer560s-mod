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
 * Auto Melody's Harp (cheat build only; Odin's and Nebulune's "Auto Harp", brought over from staging-islands): while a
 * "Harp - &lt;song&gt;" menu is the open screen, plays its notes. Notes fall down seven columns; a note is played by
 * middle-clicking its column's slot in the click row (slots 37-43) while that slot shows a Block of Quartz (Odin's
 * rule). One click a tick at most, through {@link ActionGate} ({@code ISLAND_HARP}), container clicks only. It never
 * moves or turns you, and a server correction changes nothing here.
 *
 * <p>Timing: a click lands on the server one ping after it is sent, and a note stays in the click row for one step of
 * the falling rows. When the ping is longer than half a step the click goes {@code k} rows early, from the wool note
 * {@code k} rows above the click row; the lead is the measured ping (Auto Detect Ping) plus the manual offset. Auto
 * Detect Lag measures the step from how the note rows actually advance (the median of the last few), so a laggy
 * server's slower steps are followed; with it off the step is assumed {@link #NOMINAL_STEP_MS}. Every click is a
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

    private static int lastHash;
    private static int lastContainer = -1;
    private static boolean active;
    private static boolean settling;
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
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("AutoHarpFeature.tick", bit, AutoHarpFeature::tick));
    }

    static boolean isHarpTitle(String title) {
        return title != null && title.startsWith("Harp - ") && title.length() < 64;
    }

    private static String path(ItemStack s) {
        return s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
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
            lastEarlyRows = 0;
            ModChat.send("Auto Harp", ModChat.good("Started."));
        }
        int hash = rowsHash(menu);
        if (hash != lastHash) {
            lastHash = hash;
            long gap = now - lastStepMs;
            // A step's slot updates can arrive split over client ticks (rows first, the click row after): a change this
            // soon after the last is the same step, and nothing is decided until the board holds still for a tick, or
            // the old quartz still in the click row would be clicked again.
            if (gap >= MIN_STEP_MS) {
                lastStepMs = now;
                if (gap <= 3000) {
                    gaps[gapNext] = gap;
                    gapNext = (gapNext + 1) % RING;
                    gapCount = Math.min(RING, gapCount + 1);
                }
                settling = true;
            }
            if (settling) {
                return;
            }
        } else if (settling) {
            settling = false;
            Arrays.fill(played, false);
            Arrays.fill(arrived, false);
        }
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
                early = rows > 0 && above >= 0 && !played[c]
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

    /** A cheap fingerprint of the falling-note rows (slots 0-35), which changes each time the notes move. */
    private static int rowsHash(AbstractContainerMenu menu) {
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
