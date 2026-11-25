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
 * What the server does (recordings 11-23/24): a tune completes only with color, speed and pitch matched AND each of your
 * panes in its beacon pane's slot, unpaused, checked on a click. A pane steps when the ticks since its last step reach its
 * Speed's period (a Speed click changes the period, not the count); Pause freezes that count and unpause resumes it.
 * <p>
 * So it starts as soon as the beacon's colors and speeds are read (each step's sound gives the pitch later), Speed first,
 * since lining up needs the periods equal. A pane ahead of its beacon pane by d ticks of travel is paused at once for d
 * ticks: it is unpaused when the beacon pane is the click's round trip short of it, so both then step together (11-24
 * slow runs: it waited for its own step to pause and for a step after the unpause, and a long pause read as a step,
 * which swapped your two panes and re-turned Pitch over and over). A pane behind by more than it gains in
 * {@link #BEHIND} ticks catches up at Speed 5 when that is quicker than a pause of nearly a lap. Color and Pitch are turned
 * while it waits; the click that makes everything match is the one the server completes on. Nothing counts clicks: every
 * decision reads what the panes and buttons show, so a click that did not take is redone; when everything matches and the
 * menu stays open it turns Pitch away and back (the click back is checked), or tries the other way round when the two
 * beacon pitches could only be heard together; {@link #MAX_ALIGNS} pauses or three such tries stop it with a chat line.
 * <p>
 * One container click a tick at most, through {@link ActionGate} and the normal click path, at least Click Delay ticks
 * apart; his own clicks are never held or touched.
 */
final class BeaconAutoTuner {

    private static final int NO_ANSWER_TICKS = 12;
    private static final int MAX_ALIGNS = 8;
    /** Ticks a pane may run ahead of (leaves first) or behind its beacon pane and still count as in step. */
    private static final int AHEAD = 3;
    private static final int BEHIND = 6;
    /** Ticks both panes must still have in their shared slot for a checking click to land before either steps. */
    private static final int MARGIN = 5;
    /** Ticks after the last click before a menu that stays open with everything matched is tried again. */
    private static final int NO_DONE = 20;

    private static boolean running;
    private static boolean gaveUp;
    /** Client ticks the menu has been open: Click Delay counts these, since the world time can jump after a lag. */
    private static long ticks;
    private static long lastClick = -1000;
    private static int pendingSlot = -1;
    private static String pendingSig = "";
    /** Client ticks from a click to the server's answer, the lead an unpause is sent with. */
    private static int rtt = 2;
    /** Which beacon beat each of your beats takes, chosen once the beacon's colors and speeds are read. */
    private static int[] pair;
    private static final int[][] tgt = new int[2][];
    private static final Beat[] beat = new Beat[2];
    /** Per player beat: running at Speed 5 to catch its beacon pane up. */
    private static final boolean[] boost = new boolean[2];
    private static int aligns;
    private static int attempts;
    /** The last click sent should have completed the tune. */
    private static boolean fin;

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
        rtt = 2;
        pair = null;
        aligns = 0;
        attempts = 0;
        fin = false;
        Arrays.fill(boost, false);
    }

    /** The menu closed (done, or he closed it) or the world changed. */
    static void stop() {
        if (running) {
            ModChat.send("Auto Tune", ModChat.text(fin ? "done" : "stopped, the menu closed"));
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

    /** Where a pane is along its row, in slots: its slot plus the part of its period already counted. */
    private static double pos(Beat b, int period, long now) {
        return b.slot + Math.min(Math.max(now - b.moved, 0), period - 1) / (double) period;
    }

    /** {@code x} wrapped onto the 7-slot row, 0 to under 7. */
    private static double wrap7(double x) {
        return ((x % 7) + 7) % 7;
    }

    /** Presses still needed on every dial whose target is known. */
    private static int left(int[][] cur) {
        int sum = 0;
        for (int g = 0; g < cur.length; g++) {
            for (int k = 0; k < 3; k++) {
                sum += tgt[g][k] < 0 ? 1 : Math.abs(BeaconTuneSolver.presses(cur[g][k], tgt[g][k], BeaconTuneSpec.WRAP[k]));
            }
        }
        return sum;
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
        if (pendingSlot >= 0) {
            if (!sig(slots.get(pendingSlot).getItem()).equals(pendingSig)) {
                pendingSlot = -1;
                rtt = (int) Math.max(1, Math.min(10, ticks - lastClick));
            } else if (ticks - lastClick > NO_ANSWER_TICKS) {
                ServerCorrections.reportEvent("Auto Tune", "the server did not answer a click, trying again");
                pendingSlot = -1;
            } else {
                return;
            }
        }
        if (ticks - lastClick < BeaconTuner.CFG.num("tuneDelay")) {
            return;
        }
        for (int g = 0; g < n; g++) {
            for (int k = 0; k < 3; k++) {
                if (cur[g][k] < 0) {
                    return;
                }
            }
        }
        if (pair == null) {
            for (int[] t : raw) {
                if (t[0] < 0 || t[1] < 0) {
                    return;
                }
            }
            pair = BeaconTuneSolver.assign(cur, raw);
        }
        for (int g = 0; g < n; g++) {
            tgt[g] = raw[pair[g]];
            beat[g] = rawBeat[pair[g]];
        }
        // Speed first (or 5 while catching up): lining up needs the periods.
        for (int g = 0; g < n; g++) {
            int p = BeaconTuneSolver.presses(cur[g][1], boost[g] ? 5 : tgt[g][1], 5);
            if (p != 0) {
                click(mc, screen, ctl[g][1], p < 0, false);
                return;
            }
        }
        boolean inLine = true;
        for (int g = 0; g < n; g++) {
            Beat b = beat[g];
            Beat m = mine[g];
            if (b.slot < 0 || m.slot < 0) {
                inLine = false;
                continue;
            }
            int pb = BeaconTuneSolver.period(tgt[g][1]);
            double here = pos(b, pb, now);
            double ahead = wrap7(pos(m, BeaconTuneSolver.period(cur[g][1]), now) - here);
            if (boost[g]) {
                inLine = false;
                boost[g] = wrap7(pos(m, pb, now) - here) >= 1; // passed it: back to its speed
                continue;
            }
            double aheadT = ahead * pb;
            double behindT = (7 - ahead) * pb;
            boolean paused = BeaconTuneSolver.paused(slots.get(ctl[g][3]).getItem());
            if (paused) {
                inLine = false;
                if (aheadT <= rtt || behindT <= BEHIND) {
                    click(mc, screen, ctl[g][3], false, left(cur) == 0 && othersInStep(g));
                    return;
                }
            } else if (aheadT > AHEAD && behindT > BEHIND) {
                inLine = false;
                double catchUp = pb > 15 ? (7 - ahead) / (1.0 / 15 - 1.0 / pb) + 2 * BeaconTuner.CFG.num("tuneDelay") : Double.MAX_VALUE;
                if (catchUp < aheadT) {
                    boost[g] = true;
                    return;
                }
                if (++aligns > MAX_ALIGNS) {
                    halt("the panes would not line up");
                    return;
                }
                click(mc, screen, ctl[g][3], false, false);
                return;
            }
        }
        boolean waiting = false;
        for (int g = 0; g < n; g++) {
            for (int k = 0; k < 3; k += 2) {
                if (tgt[g][k] < 0) {
                    waiting = true;
                    continue;
                }
                int p = BeaconTuneSolver.presses(cur[g][k], tgt[g][k], BeaconTuneSpec.WRAP[k]);
                if (p != 0) {
                    click(mc, screen, ctl[g][k], p < 0, inLine && left(cur) == 1);
                    return;
                }
            }
        }
        if (!inLine || waiting || ticks - lastClick < NO_DONE) {
            return;
        }
        for (int g = 0; g < n; g++) {
            int p = BeaconTuneSolver.period(tgt[g][1]);
            if (Math.min(p - (now - beat[g].moved), p - (now - mine[g].moved)) < MARGIN) {
                return;
            }
        }
        // Everything matches and is in step, yet the menu is open: the last checked click missed, or the guessed pitches are swapped.
        if (++attempts > 3) {
            halt("everything matches but the menu did not finish");
        } else if (BeaconTuner.guessing) {
            BeaconTuner.swap = !BeaconTuner.swap;
        } else {
            click(mc, screen, ctl[0][2], false, false);
        }
    }

    /** Every other beat is unpaused and in step with its beacon pane, so a click now completes the tune. */
    private static boolean othersInStep(int self) {
        for (int g = 0; g < BeaconTuner.mine.length; g++) {
            if (g != self && (boost[g] || BeaconTuner.paused[g] || BeaconTuner.mine[g].slot != beat[g].slot)) {
                return false;
            }
        }
        return true;
    }

    private static boolean click(Minecraft mc, AbstractContainerScreen<?> screen, int slot, boolean right, boolean completes) {
        if (!ActionGate.tryAct(ActionGate.Actor.BEACON_TUNE, screen)) {
            return false;
        }
        pendingSig = sig(screen.getMenu().slots.get(slot).getItem());
        pendingSlot = slot;
        lastClick = ticks;
        fin = completes;
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot, right ? 1 : 0, ContainerInput.PICKUP, mc.player);
        ModChat.log("Auto Tune", ModChat.text((right ? "right" : "left") + " click on slot " + slot));
        return true;
    }
}
