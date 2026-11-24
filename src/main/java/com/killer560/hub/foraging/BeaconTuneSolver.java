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
