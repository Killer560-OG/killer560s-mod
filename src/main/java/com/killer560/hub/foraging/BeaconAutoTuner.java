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
 * A tune completes only with color, speed and pitch matched AND the panes in line (killer560, 2026-11-23), and the server
 * checks it on a click (11-24: matched while paused, completed only at his unpause click). So it matches Color and Speed,
 * leaves Pitch ONE click short of the beacon's (Pitch does not change a beat's stepping), lines the panes up, and ends with
 * the one left click on Pitch while both panes sit in the same slot with at least {@link #MARGIN} ticks left there: the
 * menu cannot complete before everything is in line, and the last click is the one that completes it. Lining up: Pause
 * FREEZES a pane's step timer and unpause resumes it (11-24 recording: five unpauses, each next step came the rest of the
 * period later, none a whole period). So a pane out of the beacon's slot is paused on the tick it has just stepped (its
 * timer then holds almost a whole period) and unpaused on the tick the beacon's pane steps onto its slot: both then stay
 * there for most of a period. Nothing here counts clicks: every decision reads what the panes and buttons actually show,
 * so a click that did not take or a step that came early is simply redone; {@link #MAX_ALIGNS} pauses or a finished set of
 * buttons that does not end the menu stop it with a chat line.
 * <p>
 * One container click a tick at most, through {@link ActionGate} and the normal click path, at least Click Delay ticks
 * apart; his own clicks are never held or touched.
 */
final class BeaconAutoTuner {

    private static final int NO_ANSWER_TICKS = 12;
    private static final int MAX_ALIGNS = 8;
    private static final int LAST = 2;
    /** Ticks both panes must still have in their shared slot for the last click to land before either steps. */
    private static final int MARGIN = 5;

    private static boolean running;
    private static boolean gaveUp;
    /** Client ticks the menu has been open: Click Delay counts these, since the world time can jump after a lag. */
    private static long ticks;
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
    /** Per player beat: its Pitch click was sent. */
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
        Beat[] mine = BeaconTuner.mine;
        int n = ctl.length;
        ticks++;
        if (gaveUp || mc.gameMode == null || mc.player == null || cur.length != n || raw.length < n || rawBeat.length != raw.length || mine.length != n) {
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
        if (ticks - lastClick < BeaconTuner.CFG.num("tuneDelay")) {
            return;
        }
        if (pendingSlot >= 0) {
            if (!sig(slots.get(pendingSlot).getItem()).equals(pendingSig)) {
                pendingSlot = -1;
            } else if (ticks - lastClick > NO_ANSWER_TICKS) {
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
                    click(mc, screen, ctl[g][k], p < 0);
                    return;
                }
            }
        }
        boolean ready = true;
        for (int g = 0; g < n; g++) {
            Beat b = beat[g];
            Beat m = mine[g];
            if (b == null || b.slot < 0 || b.period <= 0 || m.slot < 0) {
                return;
            }
            if (BeaconTuneSolver.paused(slots.get(ctl[g][3]).getItem())) {
                ready = false;
                if (b.slot == m.slot && now - b.moved <= 1) {
                    click(mc, screen, ctl[g][3], false);
                    return;
                }
            } else if (b.slot != m.slot) {
                ready = false;
                if (m.steps > 0 && now - m.moved <= 1) {
                    if (++aligns > MAX_ALIGNS) {
                        halt("the panes would not line up");
                        return;
                    }
                    click(mc, screen, ctl[g][3], false);
                    return;
                }
            } else if (Math.min(b.period - (now - b.moved), b.period - (now - m.moved)) < MARGIN) {
                ready = false;
            }
        }
        if (!ready) {
            return;
        }
        for (int g = 0; g < n; g++) {
            if (!fin[g]) {
                if (click(mc, screen, ctl[g][LAST], false)) {
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

    private static boolean click(Minecraft mc, AbstractContainerScreen<?> screen, int slot, boolean right) {
        if (!ActionGate.tryAct(ActionGate.Actor.BEACON_TUNE, screen)) {
            return false;
        }
        pendingSig = sig(screen.getMenu().slots.get(slot).getItem());
        pendingSlot = slot;
        lastClick = ticks;
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot, right ? 1 : 0, ContainerInput.PICKUP, mc.player);
        ModChat.log("Auto Tune", ModChat.text((right ? "right" : "left") + " click on slot " + slot));
        return true;
    }
}
