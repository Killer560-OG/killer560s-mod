package com.killer560.hub.foraging;

import com.killer560.hub.foraging.BeaconTuner.Beat;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.macrostop.MacroStop;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ServerCorrections;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
 * Auto Tune (cheat build, default OFF, a row of the Beacon Tuner card): in the beacon's Tune Frequency and Upgrade Signal
 * Strength menus it clicks your Color, Speed and Pitch buttons to match the beacon's beat the short way (left click
 * forward, right click back, wrapping: 13 colors, Speed 5 to 1, Pitch High to Low) and lines your pane up with the
 * beacon's using Pause. The reading is {@link BeaconTuner}'s.
 * <p>
 * A tune completes only with color, speed and pitch matched AND your pane in the beacon's slot (killer560, 2026-11-23).
 * So it matches Color and Speed, leaves Pitch ONE click short of the beacon's (Pitch does not change a beat's stepping), lines
 * the panes up, and ends with the one left click on Pitch, sent mid-period when both panes have just stepped onto the same
 * slot: the menu cannot complete before everything is in line, and the last click is the one that completes it. Lining up:
 * a pane out of step with the beacon's is paused, and unpaused on the tick the beacon's pane steps onto its slot. Timing
 * assumption (recorded once, unpause 11097, next step 11149 at a 55 tick period): after an unpause your pane's next step
 * comes about one period later, so both then step together. Nothing here counts clicks: every decision reads what the
 * panes and buttons actually show, so a click that did not take or a step that came early is simply redone; two failed
 * lining-ups or a finished set of buttons that does not end the menu stop it with a chat line.
 * <p>
 * One container click a tick at most, through {@link ActionGate} and the normal click path, at least Click Delay ticks
 * apart; his own clicks are never held or touched.
 */
final class BeaconAutoTuner {

    private static final int NO_ANSWER_TICKS = 12;
    private static final int MAX_ALIGNS = 8;
    private static final int LAST = 2;

    private static boolean running;
    private static boolean gaveUp;
    private static long lastClick = -1000;
    private static int pendingSlot = -1;
    private static String pendingSig = "";
    /** Which beacon beat each of your beats takes, chosen once every dial of every beacon beat has been read and kept (the beacon's panes pass each other, so the pairing must not follow their order). */
    private static int[] pair;
    private static final int[][] tgt = new int[2][];
    private static final Beat[] beat = new Beat[2];
    private static int aligns;
    private static int attempts;
    private static long finalAt;
    /** Per player beat: pane slot index in its row, tick it last stepped, whether a real step was seen, unpause tick, Pitch click sent. */
    private static final int[] idx = {-1, -1};
    private static final long[] moved = new long[2];
    private static final boolean[] stepped = new boolean[2];
    private static final long[] unpausedAt = new long[2];
    private static final boolean[] fin = new boolean[2];

    private BeaconAutoTuner() {
    }

    static void define(IslandConfig c) {
        c.flag("autoTune", false).num("tuneDelay", 4, 2, 20);
        MacroStop.register("Auto Tune", MacroStop.Kind.CLICK, () -> running, why -> halt("you pressed " + why));
    }

    static void reset() {
        running = false;
        gaveUp = false;
        lastClick = -1000;
        pendingSlot = -1;
        pair = null;
        aligns = 0;
        attempts = 0;
        Arrays.fill(idx, -1);
        Arrays.fill(stepped, false);
        Arrays.fill(unpausedAt, 0);
        Arrays.fill(fin, false);
    }

    /** The menu closed (done, or he closed it) or the world changed. */
    static void stop() {
        if (running) {
            ModChat.send("Auto Tune", ModChat.text(fin[0] ? "done" : "stopped, the menu closed"));
        }
        reset();
    }

    private static void halt(String why) {
        if (running) {
            ModChat.send("Auto Tune", ModChat.text("stopped, " + why));
        }
        running = false;
        gaveUp = true;
    }

    private static String sig(ItemStack s) {
        return s.getItem() + String.valueOf(s.get(DataComponents.LORE));
    }

    /** One tick with the menu open and {@link BeaconTuner#step} just read it. */
    static void tick(Minecraft mc, AbstractContainerScreen<?> screen, long now) {
        int[][] cur = BeaconTuner.curS;
        int[][] raw = BeaconTuner.tgtRaw;
        int[][] ctl = BeaconTuner.ctlS;
        Beat[] rawBeat = BeaconTuner.beatRaw;
        int n = ctl.length;
        if (gaveUp || mc.gameMode == null || mc.player == null || cur.length != n || raw.length < n || rawBeat.length != raw.length) {
            return;
        }
        var slots = screen.getMenu().slots;
        if (slots.size() <= ctl[n - 1][3]) {
            return;
        }
        if (!running) {
            running = true;
            ModChat.send("Auto Tune", ModChat.text("started"));
        }
        for (int g = 0; g < n; g++) {
            int at = -1;
            for (int i = 0; i < BeaconTuneSpec.LEN && cur[g][0] >= 0; i++) {
                if (BeaconTuneSpec.COLORS.get(cur[g][0]).equals(BeaconTuneSolver.colorOf(slots.get(BeaconTuneSpec.BOTTOM + i).getItem()))) {
                    at = i;
                    break;
                }
            }
            if (at >= 0 && at != idx[g]) {
                stepped[g] = idx[g] >= 0;
                idx[g] = at;
                moved[g] = now;
            }
        }
        if (now - lastClick < BeaconTuner.CFG.num("tuneDelay")) {
            return;
        }
        if (pendingSlot >= 0) {
            if (!sig(slots.get(pendingSlot).getItem()).equals(pendingSig)) {
                pendingSlot = -1;
            } else if (now - lastClick > NO_ANSWER_TICKS) {
                ServerCorrections.reportEvent("Auto Tune", "the server did not answer a click, trying again");
                pendingSlot = -1;
            } else {
                return;
            }
        }
        for (int g = 0; g < n; g++) {
            for (int k = 0; k < 3; k++) {
                if (cur[g][k] < 0 || raw[g][k] < 0) {
                    return;
                }
            }
        }
        if (pair == null) {
            pair = BeaconTuneSolver.assign(cur, raw);
        }
        for (int g = 0; g < n; g++) {
            tgt[g] = raw[pair[g]];
            beat[g] = rawBeat[pair[g]];
        }
        for (int g = 0; g < n; g++) {
            for (int k = 0; k < 3; k++) {
                int want = k == LAST && !fin[g] ? Math.floorMod(tgt[g][k] - 1, BeaconTuneSpec.WRAP[k]) : tgt[g][k];
                int p = BeaconTuneSolver.presses(cur[g][k], want, BeaconTuneSpec.WRAP[k]);
                if (p != 0) {
                    click(mc, screen, ctl[g][k], p < 0, now);
                    return;
                }
            }
        }
        boolean ready = true;
        for (int g = 0; g < n; g++) {
            Beat b = beat[g];
            if (b == null || b.slot < 0 || b.period <= 0 || idx[g] < 0) {
                return;
            }
            boolean paused = BeaconTuneSolver.paused(slots.get(ctl[g][3]).getItem());
            if (paused) {
                ready = false;
                if (b.slot - BeaconTuneSpec.TOP == idx[g] && now - b.moved <= 1) {
                    if (click(mc, screen, ctl[g][3], false, now)) {
                        unpausedAt[g] = now;
                    }
                    return;
                }
            } else if (unpausedAt[g] > 0 && !(stepped[g] && moved[g] > unpausedAt[g])) {
                ready = false;
                if (now - unpausedAt[g] > 3L * b.period) {
                    unpausedAt[g] = 0;
                }
            } else {
                unpausedAt[g] = 0;
                int tol = Math.max(2, b.period / 5);
                long since = now - Math.max(b.moved, moved[g]);
                boolean synced = b.slot - BeaconTuneSpec.TOP == idx[g] && stepped[g] && Math.abs(moved[g] - b.moved) <= tol;
                if (since < tol + 1 || !stepped[g]) {
                    ready = false;
                } else if (!synced) {
                    ready = false;
                    if (++aligns > MAX_ALIGNS) {
                        halt("the panes would not line up");
                        return;
                    }
                    click(mc, screen, ctl[g][3], false, now);
                    return;
                } else if (since > b.period - tol - 4) {
                    ready = false;
                }
            }
        }
        if (!ready) {
            return;
        }
        for (int g = 0; g < n; g++) {
            if (!fin[g]) {
                if (click(mc, screen, ctl[g][LAST], false, now)) {
                    fin[g] = true;
                    finalAt = now;
                }
                return;
            }
        }
        if (now - finalAt > 60 && ++attempts >= 2) {
            halt("everything matches but the menu did not finish");
        } else if (now - finalAt > 60) {
            Arrays.fill(fin, false);
        }
    }

    private static boolean click(Minecraft mc, AbstractContainerScreen<?> screen, int slot, boolean right, long now) {
        if (!ActionGate.tryAct(ActionGate.Actor.BEACON_TUNE, screen)) {
            return false;
        }
        pendingSig = sig(screen.getMenu().slots.get(slot).getItem());
        pendingSlot = slot;
        lastClick = now;
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot, right ? 1 : 0, ContainerInput.PICKUP, mc.player);
        ModChat.log("Auto Tune", ModChat.text((right ? "right" : "left") + " click on slot " + slot));
        return true;
    }
}
