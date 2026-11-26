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
 * Every one of the 11 upgrades of 11-26 2:36 AM completed on an UNPAUSE whose pane only shared its beacon pane's slot (up
 * to 140 ticks out of step), the other beat stepping with its beacon pane.
 * <p>
 * So one beat, the finisher ({@link #finisher}), is only paused where its beacon pane comes soonest (or run at Speed 5 to
 * get ahead of it first) and unpaused on that pane's slot once everything else matches; only the other beat is put in step.
 * A lining-up unpause due within a click gap holds every other click. It starts as soon as the beacon's colors and speeds
 * are read (each step's sound gives the pitch later), Speed first,
 * since lining up needs the periods equal. A pane ahead of its beacon pane by d ticks of travel is paused at once for d
 * ticks: it is unpaused when the beacon pane has caught up by the count kept from the Pause click's sending, so both then
 * step together (the round trip falls out, see {@link #pauseSeen}; 11-24
 * slow runs: it waited for its own step to pause and for a step after the unpause, and a long pause read as a step,
 * which swapped your two panes and re-turned Pitch over and over). In step means stepping on the same tick ({@link #PHASE}),
 * not only sharing the slot: a pane that steps a few ticks after its beacon pane is paused for the rest of the lap (11-25,
 * killer560: "the top would move a hair before the bottom it needed to pause it and wait a cycle"), or catches up at Speed 5
 * when that is quicker. Color and Pitch are turned while it waits. Nothing counts clicks: every decision reads what the
 * panes and buttons show, so a click that did not take is redone; a finishing unpause that does not complete pauses the
 * finisher again (the two beacon pitches the other way round when they could only be heard together); {@link #MAX_ALIGNS}
 * pauses or five such tries stop it with a chat line.
 * Running at Speed 5 instead of pausing is chosen whenever it is quicker ({@link BeaconTuneSolver#boostQuicker}), and ended
 * only once the pause left is quicker than running on. Nothing is lined up before your pane has stepped once (until then
 * its step timer is unknown), nor judged again after a line-up before it has stepped since.
 * <p>
 * One container click a tick at most, through {@link ActionGate} and the normal click path, at least Click Delay ticks
 * apart; his own clicks are never held or touched.
 */
final class BeaconAutoTuner {

    private static final int NO_ANSWER_TICKS = 12;
    private static final int MAX_ALIGNS = 8;
    /**
     * Ticks apart a pane may step from its beacon pane and still count as in step. Sharing the slot is not enough: his VIII
     * upgrade of 11-25 had his white pane stepping 5 ticks after the beacon's (the old 6-tick slack read that as in step), and
     * no Pitch click completed it until he paused it for most of a lap.
     */
    private static final int PHASE = 1;
    /** Ticks from either end of your pane's step count a lining-up pause may not freeze it at. */
    private static final int FREEZE_MARGIN = 2;
    /**
     * Ticks apart a pane that was lined up may be seen stepping from its beacon pane and still be left alone: the steps of a
     * server tick reach the client up to a tick apart under load, so one tick out read as two and a lined-up pane was run at
     * Speed 5 again right before the finish was due, a lap lost (testkit 1124 run1, 11-26: 474 ticks against 299). If a finish
     * then does not complete, that pane is judged again strictly (see {@code retry}), so this costs one try, never laps.
     */
    private static final int LINED_UP_SLACK = 2;

    private static boolean running;
    private static boolean gaveUp;
    /** Client ticks the menu has been open: Click Delay counts these, since the world time can jump after a lag. */
    private static long ticks;
    private static long lastClick = -1000;
    private static int pendingSlot = -1;
    private static String pendingSig = "";
    /** Client ticks from a click to the server's answer: the click gap and the finisher's time on its slot allow for it. */
    private static int rtt = 2;
    /** When this tick's {@code now} is, for the click bookkeeping. */
    private static long nowT;
    /** The {@code now} a Pause click went out per beat (MIN_VALUE: none waiting for its answer). */
    private static final long[] sentAt = new long[2];
    /** The last round trips, newest at {@code rttN % 5}. */
    private static final int[] rtts = new int[5];
    private static int rttN;
    /** Which beacon beat each of your beats takes, chosen once the beacon's colors and speeds are read. */
    private static int[] pair;
    private static final int[][] tgt = new int[2][];
    private static final Beat[] beat = new Beat[2];
    /** Per player beat: running at Speed 5 to catch its beacon pane up. */
    private static final boolean[] boost = new boolean[2];
    private static int aligns;
    private static int attempts;
    /** The beat finished last by its unpause on its beacon pane's slot ({@link #finisher}); the others are put in step. */
    private static int fin = -1;
    /** The finishing unpause went out: if the finisher is still unpaused afterwards (the menu stayed open), it is a retry. */
    private static boolean retry;
    /** The finisher was handed to the other beat once this menu ({@link #swapRoles}). */
    private static boolean swapped;
    /** Per beat: when it was last lined up (an unpause or a Speed landing); it is judged again only once its pane has stepped since. */
    private static final long[] settle = new long[2];

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
        rttN = 0;
        pair = null;
        aligns = 0;
        attempts = 0;
        fin = -1;
        retry = false;
        swapped = false;
        Arrays.fill(settle, Long.MIN_VALUE);
        Arrays.fill(sentAt, Long.MIN_VALUE);
        Arrays.fill(boost, false);
    }

    /** The menu closed (done, or he closed it) or the world changed. */
    static void stop() {
        if (running) {
            // The server closes the menu on the click that completes (11-25: the same tick, a Speed click too): a close within a
            // round trip of the last click is done, whichever button it was.
            ModChat.send("Auto Tune", ModChat.text(ticks - lastClick <= rtt + 3 ? "done" : "stopped, the menu closed"));
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
        nowT = now;
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
            // Answered only when the button shows a new value: the click's own pickup empties the slot at once and the old
            // button can come back before the answer (11-25 4:54 PM: a Speed click answered 4 ticks later was clicked again
            // on the stale value and overshot).
            ItemStack shown = slots.get(pendingSlot).getItem();
            if (!shown.isEmpty() && !sig(shown).equals(pendingSig)) {
                pendingSlot = -1;
                // The median of the last answers: one answered late by a server stall sent the next line-up unpause that
                // much early (sim 11-26 Torrhus III: it landed 2 ticks before the beacon pane's step and the finish failed).
                rtts[rttN++ % rtts.length] = (int) Math.max(1, Math.min(10, ticks - lastClick));
                int[] r = Arrays.copyOf(rtts, Math.min(rttN, rtts.length));
                Arrays.sort(r);
                rtt = r[r.length / 2];
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
        // Your two panes share a color and which is whose is still a guess: Color first, whose click tells them apart.
        for (int g = 0; g < n && !BeaconTuner.known; g++) {
            int p = BeaconTuneSolver.presses(cur[g][0], tgt[g][0], BeaconTuneSpec.WRAP[0]);
            if (p != 0) {
                click(mc, screen, ctl[g][0], p < 0);
                return;
            }
        }
        int step = Math.max(BeaconTuner.CFG.num("tuneDelay"), rtt);
        if (fin < 0) {
            fin = finisher(n, now, step);
            if (fin < 0) {
                return;
            }
        }
        // A lining-up unpause due before the next click could go waits for no other click (11-26: beat 0's clicks took every
        // gap while beat 1's unpause came due, and it waited a whole lap, 19 s at speed 1).
        for (int g = 0; g < n; g++) {
            if (g != fin && beat[g].slot >= 0 && mine[g].slot >= 0 && mine[g].steps > 0 && !boost[g]
                    && BeaconTuneSolver.paused(slots.get(ctl[g][3]).getItem())) {
                long due = aheadTicks(g, cur, now);
                if (due <= 0) {
                    // Due now: it goes before the finisher's own clicks, which come first in the loop below (sim 11-26: the
                    // finisher's pause took that tick and the unpause waited a lap).
                    if (click(mc, screen, ctl[g][3], false)) {
                        settle[g] = now;
                    }
                    return;
                }
                if (due < step) {
                    return;
                }
            }
        }
        // Speed first (or 5 while catching up): lining up needs the periods.
        for (int g = 0; g < n; g++) {
            int p = BeaconTuneSolver.presses(cur[g][1], boost[g] ? 5 : tgt[g][1], 5);
            if (p != 0) {
                click(mc, screen, ctl[g][1], p < 0);
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
            boolean paused = BeaconTuneSolver.paused(slots.get(ctl[g][3]).getItem());
            if (g == fin) {
                // The last beat needs only its beacon pane's slot (every completion of 11-26 came on its unpause there, as far as
                // 140 ticks out of step): pause it where the beacon pane comes soonest, or run it at Speed 5 to get there first.
                if (paused) {
                    continue;
                }
                int pb = BeaconTuneSolver.period(tgt[g][1]);
                int k = Math.floorMod(m.slot - b.slot, BeaconTuneSpec.LEN);
                if (retry) {
                    // That unpause did not finish. The pitch guess is wrong only if the other beats were really in step: seen
                    // stepping on the same tick as their beacon panes since lining up. Else they are judged again from their
                    // steps (sim 11-26 Torrhus III: a beat 2 ticks out was taken as in step and four pitch swaps failed).
                    retry = false;
                    boolean inStep = true;
                    for (int o = 0; o < n; o++) {
                        if (o != fin && !(stepsSeen(o) && mine[o].moved > settle[o] && mine[o].slot == beat[o].slot
                                && Math.abs(mine[o].moved - beat[o].moved) <= 1)) {
                            inStep = false;
                            settle[o] = Long.MIN_VALUE;
                        }
                    }
                    if (inStep && attempts <= 2 && BeaconTuner.joint()) {
                        BeaconTuner.swap = !BeaconTuner.swap; // the pitches heard together the other way
                        return;
                    }
                    if (inStep && n == 2 && swapRoles(mc, screen, ctl[g][3], cur, now, step)) {
                        return;
                    }
                }
                long stay = pb - (now - b.moved); // ticks the beacon pane still stays on its slot
                // A pause on the slot must leave time for every click still to make (11-26 sim: Pitch clicks after a retry
                // missed the window, a lap lost).
                int need = finishLead(step) + left(cur) * step;
                if (k == 0 && stay <= need) {
                    continue; // whichever pane steps first decides: yours ahead (pause next) or behind
                }
                if (k == BeaconTuneSpec.LEN - 1 && m.steps > 0
                        && BeaconTuneSolver.period(cur[g][1]) - (now - m.moved) + finishLead(step) < stay) { // its clicks go in while it waits
                    continue; // a slot behind, but it steps onto the beacon pane's slot with time to finish there (not a lap)
                }
                if (k > 1 && m.steps == 0) {
                    continue; // its step timer is unknown until it steps once: no lap-long pause or Speed 5 run on a guess
                }
                if (k <= 1 || !boost[g] && !BeaconTuneSolver.boostQuicker(k - (now - b.moved) / (double) pb, tgt[g][1],
                        BeaconTuneSolver.speedClicks(cur[g][1], 5) + BeaconTuneSolver.speedClicks(5, tgt[g][1]), step)) {
                    if (click(mc, screen, ctl[g][3], false)) {
                        boost[g] = false;
                    }
                    return;
                }
                if (!boost[g]) {
                    boost[g] = true;
                    return;
                }
                continue;
            }
            if (m.steps == 0) {
                // Your pane's step timer is unknown until it steps once (11-25: a guess sent a pane 10 ticks behind a slow
                // beacon to Speed 5 and straight back); a pane paused before it ever stepped is let go so it can.
                inLine = false;
                if (paused) {
                    click(mc, screen, ctl[g][3], false);
                    return;
                }
                continue;
            }
            int pb = BeaconTuneSolver.period(tgt[g][1]);
            double ahead = BeaconTuneSolver.wrap7(BeaconTuneSolver.pos(m, BeaconTuneSolver.period(cur[g][1]), now) - BeaconTuneSolver.pos(b, pb, now));
            if (boost[g]) {
                inLine = false;
                // Back down on the tick whose clicks land it in step, or ahead by a pause that is now quicker than running on and
                // at least a click long. Any pane ahead counted before (11-26: a pane 2-3 slots ahead went to Speed 5 and straight
                // back ten times, since boosting had been chosen over that very pause).
                int d = BeaconTuneSolver.landing(m.slot, (int) (now - m.moved), b.slot, (int) (now - b.moved), tgt[g][1], rtt, step);
                if (d >= 0 && (d <= PHASE || d >= step && !BeaconTuneSolver.boostQuicker(d / (double) pb, tgt[g][1], 0, step))) {
                    boost[g] = false;
                    settle[g] = d <= PHASE ? now : Long.MIN_VALUE;
                    click(mc, screen, ctl[g][1], BeaconTuneSolver.presses(5, tgt[g][1], 5) < 0);
                    return;
                }
                continue;
            }
            long aheadT = aheadTicks(g, cur, now);
            long behindT = 7L * pb - aheadT;
            if (paused) {
                inLine = false;
                if (aheadT <= 0 || behindT <= PHASE) {
                    if (click(mc, screen, ctl[g][3], false)) {
                        settle[g] = now;
                    }
                    return;
                }
            } else if (now - m.moved >= BeaconTuneSolver.period(cur[g][1])) {
                // Overdue at its new Speed (the click kept the count): it steps on the server's next tick, so its place now says
                // nothing (sim 11-26: judged right after Speed 3 to 4 it read a slot back and was paused for most of a lap).
                inLine = false;
            } else if (settle[g] != Long.MIN_VALUE && (m.moved <= settle[g] || aheadT <= LINED_UP_SLACK || behindT <= LINED_UP_SLACK)) {
                // Lined up (an unpause or a Speed landing) and not seen stepping since: taken as in step, never judged again from
                // the guess, whose 1-2 ticks of beacon arrival jitter read as 2 behind and ran it at Speed 5 again (11-26 2:36 AM,
                // a line-up undone). Once it has stepped it stays lined up within LINED_UP_SLACK; a finish that then does not
                // complete judges it again strictly.
                continue;
            } else if (aheadT > PHASE && behindT > PHASE) {
                inLine = false;
                if (BeaconTuneSolver.boostQuicker(ahead, tgt[g][1], BeaconTuneSolver.speedClicks(tgt[g][1], 5) + BeaconTuneSolver.speedClicks(5, tgt[g][1]), step)) {
                    boost[g] = true;
                    return;
                }
                // Freeze it mid-step: the unpause must land while the beacon pane is on this slot, and a count frozen next to
                // a step leaves no room for a tick of error in the round trip (11-25 phase lag 3: it landed a slot early and
                // took a second lap). The click lands between 2 ticks short of a round trip and a full one after it was sent
                // (11-25 testkit: frozen at 0 and 1 when a round trip said 2), so every count in that range must clear the
                // margin. Waiting costs at most the same few ticks later at the end.
                int pm = BeaconTuneSolver.period(cur[g][1]);
                long x = Math.floorMod(now - m.moved + Math.max(0, rtt - 2), pm);
                if (x < FREEZE_MARGIN || x + 2 >= pm - FREEZE_MARGIN) {
                    return;
                }
                if (++aligns > MAX_ALIGNS) {
                    halt("the panes would not line up");
                    return;
                }
                click(mc, screen, ctl[g][3], false);
                return;
            } else if (!stepsSeen(g)) {
                inLine = false; // lined up on paper, but its step tick is not seen yet
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
                    click(mc, screen, ctl[g][k], p < 0);
                    return;
                }
            }
        }
        // Everything matches and every other beat steps with its beacon pane: the unpause of the last beat on its beacon pane's
        // slot finishes, the click landing before that pane steps on. One that does not finish is paused and tried again (the
        // two pitches heard together the other way round first, see above).
        Beat b = beat[fin];
        Beat m = mine[fin];
        if (!inLine || waiting || m.slot < 0 || m.slot != b.slot || !BeaconTuneSolver.paused(slots.get(ctl[fin][3]).getItem())
                || BeaconTuneSolver.period(tgt[fin][1]) - (now - b.moved) <= rtt + 1) {
            return;
        }
        if (++attempts > 5) {
            halt("everything matches but the menu did not finish");
            return;
        }
        retry = click(mc, screen, ctl[fin][3], false);
    }

    /**
     * A finish failed with the other beat seen in step and no pitch in doubt: that beat is out by a tick or two the client
     * cannot see (testkit 11-26 under load: a line-up landed 2 ticks out while every step read 0-1 apart), and lining it up
     * again cost the finisher's beacon pane a lap (421 ticks). When the finisher's pane is ahead of its beacon pane by at least
     * a click gap and less than that pane's stay on the slot, it is paused to be lined up instead, and the other beat, already
     * on its beacon pane's slot, finishes: an unpause there needs only the slot. Once a menu.
     */
    private static boolean swapRoles(Minecraft mc, AbstractContainerScreen<?> screen, int pauseSlot, int[][] cur, long now, int step) {
        long ahead = aheadTicks(fin, cur, now);
        long stay = BeaconTuneSolver.period(tgt[fin][1]) - (now - beat[fin].moved);
        if (swapped || ahead < step || ahead + 2 > stay || !click(mc, screen, pauseSlot, false)) {
            return false;
        }
        swapped = true;
        settle[fin] = Long.MIN_VALUE;
        fin = 1 - fin;
        boost[fin] = false;
        return true;
    }

    /** Ticks a pause and an unpause of the last beat need, the unpause landing before its beacon pane steps on. */
    private static int finishLead(int step) {
        return step + rtt + 2;
    }

    /**
     * The beat finished last, by an unpause that needs only the slot: the one dearer to put in step (its pane further from its
     * beacon pane's, in beacon ticks of pausing or of running at Speed 5; on a tie the slower beacon beat). -1 until placed.
     */
    private static int finisher(int n, long now, int step) {
        int best = 0;
        double worst = -1;
        for (int g = 0; g < n; g++) {
            if (beat[g].slot < 0 || BeaconTuner.mine[g].slot < 0 || tgt[g][1] < 0) {
                return -1;
            }
            int pb = BeaconTuneSolver.period(tgt[g][1]);
            int k = Math.floorMod(BeaconTuner.mine[g].slot - beat[g].slot, BeaconTuneSpec.LEN);
            double cost = (double) k * pb;
            if (pb > 15) {
                cost = Math.min(cost, 2 * step + (BeaconTuneSpec.LEN - k) / (1.0 / 15 - 1.0 / pb));
            }
            cost += pb / 100.0;
            if (cost > worst) {
                worst = cost;
                best = g;
            }
        }
        return best;
    }

    /** Ticks of beacon travel beat {@code g}'s pane is ahead of its beacon pane, 0 to under a lap. */
    private static long aheadTicks(int g, int[][] cur, long now) {
        int pb = BeaconTuneSolver.period(tgt[g][1]);
        double ahead = BeaconTuneSolver.wrap7(BeaconTuneSolver.pos(BeaconTuner.mine[g], BeaconTuneSolver.period(cur[g][1]), now)
                - BeaconTuneSolver.pos(beat[g], pb, now));
        // Whole ticks: as a fraction of slots a pane 1 tick ahead read 1.0000001 now and then, and an in-step pane was paused for a lap.
        return Math.round(ahead * pb);
    }

    /** Beat {@code g}'s pane and its beacon pane have both been seen stepping, so their step ticks can be compared. */
    private static boolean stepsSeen(int g) {
        return BeaconTuner.mine[g].steps > 0 && beat[g].steps > 1;
    }

    /**
     * Beat {@code g}'s Pause button was just seen to flip after our click: its count is kept as frozen (or running) from the
     * tick the click was SENT, not from its answer. The server acts on a click some ticks later (on Hypixel the effect is seen
     * with the answer: his 11-26 recording, a Speed click that made a pane overdue stepped it in the very packet that answered
     * it, 6 of 6 times, never before), but the Pause and the unpause are late by the same ticks, so from the sending the
     * delay falls out and the unpause goes when the count says in step. A lead learned from each landing chased one tick of
     * load jitter instead (d670a34a0).
     */
    static void pauseSeen(int g, boolean nowPaused, long now) {
        if (g >= sentAt.length || g >= BeaconTuner.mine.length || sentAt[g] == Long.MIN_VALUE || now - sentAt[g] > 12) {
            return;
        }
        long x = now - sentAt[g];
        BeaconTuner.mine[g].moved += nowPaused ? x : -x;
        sentAt[g] = Long.MIN_VALUE;
    }

    private static boolean click(Minecraft mc, AbstractContainerScreen<?> screen, int slot, boolean right) {
        if (!ActionGate.tryAct(ActionGate.Actor.BEACON_TUNE, screen)) {
            return false;
        }
        pendingSig = sig(screen.getMenu().slots.get(slot).getItem());
        pendingSlot = slot;
        lastClick = ticks;
        for (int g = 0; g < Math.min(sentAt.length, BeaconTuner.ctlS.length); g++) {
            if (BeaconTuner.ctlS[g][3] == slot) {
                sentAt[g] = nowT;
            }
        }
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot, right ? 1 : 0, ContainerInput.PICKUP, mc.player);
        ModChat.log("Auto Tune", ModChat.text((right ? "right" : "left") + " click on slot " + slot));
        return true;
    }
}
