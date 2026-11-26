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
 * which swapped your two panes and re-turned Pitch over and over). In step means stepping on the same tick ({@link #PHASE}),
 * not only sharing the slot: a pane that steps a few ticks after its beacon pane is paused for the rest of the lap (11-25,
 * killer560: "the top would move a hair before the bottom it needed to pause it and wait a cycle"), or catches up at Speed 5
 * when that is quicker. Color and Pitch are turned while it waits; the click that makes everything match is the one the
 * server completes on. Nothing counts clicks: every decision reads what the panes and buttons show, so a click that did not
 * take is redone; when everything matches and the menu stays open it turns Pitch away and back, tries the other way round
 * and back when the two beacon pitches could only be heard together, then pauses beat 0 and unpauses it on the same slot (every
 * completion recorded 11-25 came on an unpause); {@link #MAX_ALIGNS} pauses or five such tries stop it with a chat line.
 * Running at Speed 5 instead of pausing is chosen whenever it is quicker ({@link BeaconTuneSolver#boostQuicker}).
 * Nothing is lined up before your pane has stepped once: until then its step timer is unknown.
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
    /** Ticks after the last click before a menu that stays open with everything matched is tried again. */
    private static final int NO_DONE = 8;

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
    /** Beat paused only to finish: unpaused at once on its beacon pane's slot, the click the server completes on. */
    private static int tap = -1;

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
        tap = -1;
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
        // Your two panes share a color and which is whose is still a guess: Color first, whose click tells them apart.
        for (int g = 0; g < n && !BeaconTuner.known; g++) {
            int p = BeaconTuneSolver.presses(cur[g][0], tgt[g][0], BeaconTuneSpec.WRAP[0]);
            if (p != 0) {
                click(mc, screen, ctl[g][0], p < 0);
                return;
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
            int step = Math.max(BeaconTuner.CFG.num("tuneDelay"), rtt);
            if (boost[g]) {
                inLine = false;
                // Back down on the tick whose clicks land it in step, or ahead by a pause it can make (at least a click apart).
                int d = BeaconTuneSolver.landing(m.slot, (int) (now - m.moved), b.slot, (int) (now - b.moved), tgt[g][1], rtt, step);
                if (d >= 0 && (d <= PHASE || d >= step)) {
                    boost[g] = false;
                    click(mc, screen, ctl[g][1], BeaconTuneSolver.presses(5, tgt[g][1], 5) < 0);
                    return;
                }
                continue;
            }
            // Whole ticks: as a fraction of slots a pane 1 tick ahead read 1.0000001 now and then, and an in-step pane was paused for a lap.
            long aheadT = Math.round(ahead * pb);
            long behindT = 7L * pb - aheadT;
            if (paused) {
                inLine = false;
                if (tap == g && (m.slot != b.slot || pb - (now - b.moved) <= rtt)) {
                    tap = -1; // the beacon pane left first (a lag): line up the normal way
                }
                if (tap == g || aheadT <= rtt || behindT <= PHASE) {
                    tap = -1;
                    click(mc, screen, ctl[g][3], false);
                    return;
                }
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
        if (!inLine || waiting || ticks - lastClick < NO_DONE) {
            return;
        }
        // The tap below pauses beat 0 and unpauses it after the pause's answer: every beacon pane must stay in its slot till then.
        int lead = Math.max(BeaconTuner.CFG.num("tuneDelay"), rtt) + rtt + 2;
        for (int g = 0; g < n; g++) {
            if (BeaconTuneSolver.period(tgt[g][1]) - (now - beat[g].moved) < lead) {
                return;
            }
        }
        // Everything matches and steps with the beacon, yet the menu is open: turn Pitch away and back (a click checks, and an
        // in-step pane stays in step, 11-23), then flip the guessed pitches, then finish on an unpause on the slot (needs only
        // the slot; every tune of 11-25 completed on one).
        // Two pitches heard together are told apart only by reads that can miss (your own pane's sound in the same tick), so
        // the other way round is tried even when the reads were not a tie, and back again.
        if (++attempts > 5) {
            halt("everything matches but the menu did not finish");
        } else if (attempts == 1) {
            click(mc, screen, ctl[0][2], false);
        } else if (BeaconTuner.joint() && attempts <= 3) {
            BeaconTuner.swap = !BeaconTuner.swap;
        } else if (click(mc, screen, ctl[0][3], false)) {
            tap = 0;
        }
    }

    /** Beat {@code g}'s pane and its beacon pane have both been seen stepping, so their step ticks can be compared. */
    private static boolean stepsSeen(int g) {
        return BeaconTuner.mine[g].steps > 0 && beat[g].steps > 1;
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
