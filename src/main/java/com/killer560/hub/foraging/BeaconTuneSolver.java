package com.killer560.hub.foraging;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moonglade Beacon Tuner, the arithmetic and the readers. A beat is {color, speed 1-5, pitch 0-2} with -1 for unknown.
 * Pure functions: nothing is sent.
 */
public final class BeaconTuneSolver {

    private static final Pattern CURRENT = Pattern.compile("Current (speed|pitch): (\\w+)");

    private BeaconTuneSolver() {
    }

    /** Signed presses for one dial: positive = left click (forward), negative = right click, 0 = matched; wraps the short way. */
    public static int presses(int current, int target, int wrap) {
        int d = Math.floorMod(target - current, wrap);
        return d > wrap / 2 ? d - wrap : d;
    }

    /** Ticks between a beat's steps at {@code speed}. */
    public static int period(int speed) {
        return 15 + 10 * (5 - speed);
    }

    /** Where a pane is along its row, in slots: its slot plus the part of its period already counted. */
    static double pos(BeaconTuner.Beat b, int period, long now) {
        return b.slot + Math.min(Math.max(now - b.moved, 0), period - 1) / (double) period;
    }

    /** {@code x} wrapped onto the 7-slot row, 0 to under 7. */
    static double wrap7(double x) {
        return ((x % 7) + 7) % 7;
    }

    /** Speed clicks from {@code from} to {@code to} the short way. */
    static int speedClicks(int from, int to) {
        return Math.abs(presses(from, to, 5));
    }

    /**
     * Running at Speed 5 to catch the beacon pane up is quicker than pausing: a pane {@code ahead} slots in front of its
     * beacon pane (the beacon at {@code target} speed) either pauses {@code ahead} slots of the beacon's travel, or makes up the
     * {@code 7 - ahead} slots it is behind at Speed 5, plus {@code clicks} Speed clicks {@code step} ticks each (killer560
     * 11-25: on a slow beacon a pause waits about 20 s for the loop to come around; "put the matching one to max speed").
     */
    static boolean boostQuicker(double ahead, int target, int clicks, int step) {
        int pb = period(target);
        return pb > 15 && clicks * step + (7 - ahead) / (1.0 / 15 - 1.0 / pb) < ahead * pb;
    }

    /**
     * Slots a pane running at Speed 5 still gains on a beacon pane at {@code target} speed while it is clicked back down:
     * {@code rtt} ticks until the first click lands, then {@code step} ticks at each speed passed on the short way.
     */
    static double downGain(int target, int step, int rtt) {
        double pb = period(target);
        double gain = rtt * (1.0 / 15 - 1 / pb);
        for (int s = 5; s != target; ) {
            s = Math.floorMod(s - 1 + Integer.signum(presses(s, target, 5)), 5) + 1;
            gain += s == target ? 0 : step * (1.0 / period(s) - 1 / pb);
        }
        return gain;
    }

    /**
     * Ticks a pane running at Speed 5 ends ahead of its beacon pane (negative: behind, on the loop of 7 beacon periods)
     * once it is clicked back down to {@code target} starting now: the clicks land {@code rtt}, then {@code step} ticks
     * apart, and every tick both step timers count up as the server's do (a step when the count reaches the period). The
     * timers, not the slots, decide it: a Speed click keeps the count and changes the period, so a pane level with the
     * beacon's at Speed 5 lands a period's worth behind it at the slower speed (11-25: Speed 5 and back four times).
     */
    static int landing(int mySlot, int mySince, int slot, int since, int target, int rtt, int step) {
        int pb = period(target);
        int speed = 5;
        int x = Math.min(mySince, period(5) - 1);
        int y = Math.min(since, pb - 1);
        int dir = Integer.signum(presses(5, target, 5));
        int last = rtt + (speedClicks(5, target) - 1) * step;
        for (int t = 1; t <= last; t++) {
            if (t >= rtt && (t - rtt) % step == 0) {
                speed = Math.floorMod(speed - 1 + dir, 5) + 1;
            }
            if (++x >= period(speed)) {
                x = 0;
                mySlot++;
            }
            if (++y >= pb) {
                y = 0;
                slot++;
            }
        }
        int lap = BeaconTuneSpec.LEN * pb;
        int d = Math.floorMod((mySlot - slot) * pb + x - y, lap);
        return d > lap / 2 ? d - lap : d;
    }

    /** A beat's speed from the ticks between two of its steps: 15 ticks at speed 5, 10 more per speed down. */
    public static int speedFromPeriod(int ticks) {
        return Math.max(1, Math.min(5, 5 - Math.round((ticks - 15) / 10f)));
    }

    /** The Pitch level a note block bass sound pitch belongs to. */
    public static int pitchLevel(float soundPitch) {
        int best = 0;
        for (int i = 1; i < BeaconTuneSpec.PITCH_SOUND.length; i++) {
            if (Math.abs(BeaconTuneSpec.PITCH_SOUND[i] - soundPitch) < Math.abs(BeaconTuneSpec.PITCH_SOUND[best] - soundPitch)) {
                best = i;
            }
        }
        return best;
    }

    /** The dye color name of a stained glass pane or a dye item ("lime"), else null (gray panes and everything else). */
    static String colorOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String p = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        String c = p.endsWith("_stained_glass_pane") ? p.substring(0, p.length() - 19) : p.endsWith("_dye") ? p.substring(0, p.length() - 4)
                : switch (p) {
                    case "lapis_lazuli" -> "blue";
                    case "cocoa_beans" -> "brown";
                    case "bone_meal" -> "white";
                    default -> "";
                };
        return BeaconTuneSpec.COLORS.contains(c) ? c : null;
    }

    /** The button's own "Current speed: 5" / "Current pitch: Low" lore line as a value (speed number, pitch level), else -1. */
    static int current(ItemStack stack, String dial) {
        ItemLore lore = stack == null ? null : stack.get(DataComponents.LORE);
        if (lore == null) {
            return -1;
        }
        for (var line : lore.lines()) {
            Matcher m = CURRENT.matcher(line.getString());
            if (m.find() && m.group(1).equals(dial)) {
                String v = m.group(2);
                return dial.equals("speed") ? (v.length() == 1 && Character.isDigit(v.charAt(0)) ? v.charAt(0) - '0' : -1)
                        : BeaconTuneSpec.PITCHES.indexOf(v.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return -1;
    }

    /** True when the Pause button's lore says "Currently: PAUSED" (an unpaused one says UNPAUSED). */
    static boolean paused(ItemStack stack) {
        ItemLore lore = stack == null ? null : stack.get(DataComponents.LORE);
        return lore != null && lore.lines().stream().anyMatch(l -> l.getString().contains("Currently: PAUSED"));
    }

    /** Total presses to turn beat {@code cur} into {@code tgt}; an unknown dial counts 0. */
    static int cost(int[] cur, int[] tgt) {
        int n = 0;
        for (int i = 0; i < 3; i++) {
            n += cur[i] < 0 || tgt[i] < 0 ? 0 : Math.abs(presses(cur[i], tgt[i], BeaconTuneSpec.WRAP[i]));
        }
        return n;
    }

    /** Which target beat each player beat should take (two of each in the upgrade: the pairing needing fewer presses). */
    public static int[] assign(int[][] cur, int[][] tgt) {
        int n = Math.min(cur.length, tgt.length);
        if (n == 2 && cost(cur[0], tgt[1]) + cost(cur[1], tgt[0]) < cost(cur[0], tgt[0]) + cost(cur[1], tgt[1])) {
            return new int[]{1, 0};
        }
        int[] a = new int[cur.length];
        for (int i = 0; i < a.length; i++) {
            a[i] = Math.min(i, tgt.length - 1);
        }
        return a;
    }

    /** A beacon beat in words: "lime, speed 1, high", with ? for what is not known yet. */
    public static String target(int[] t) {
        return (t[0] < 0 ? "?" : BeaconTuneSpec.COLORS.get(t[0]).replace('_', ' ')) + ", speed " + (t[1] < 0 ? "?" : t[1]) + ", "
                + (t[2] < 0 ? "?" : BeaconTuneSpec.PITCHES.get(t[2]));
    }
}
