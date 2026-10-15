package com.killer560.hub.trajectories;

import com.killer560.hub.autopuzzles.AutoPuzzleUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which held item is a Skyblock shortbow, and how many arrows one shot fires - for {@link TrajectoriesFeature}.
 *
 * <p>A shortbow is an item whose lore carries Hypixel's "Shortbow: Instantly shoots!" line (the one test the auto
 * puzzles already use, {@link AutoPuzzleUtil#isShortbow}; case-insensitive, since the wiki prints it "Instantly
 * Shoots!"), or whose Skyblock id is {@code TERMINATOR} or ends in {@code SHORTBOW} (Artisanal, Juju, Mosquito, Spider,
 * Dragon, Machine Gun...). The display name is never read: reforges, stars and renames all change it.
 *
 * <p>Arrow count: the Terminator fires three (hypixelskyblock.minecraft.wiki: "Shoots 3 arrows at once", one straight
 * and one to each side). Its id alone is enough; any other item whose lore has the same anchored "Shoots N arrows at
 * once" line gets N (bounded to a single digit, 2-9). Everything else fires one.
 */
public final class Shortbows {

    /** Yaw between neighbouring arrows of a multi-arrow shot, in degrees - QUOI's Terminator side arrows (AutoBlaze
     *  shoots them at {@code yaw +- 5}), the same pitch as the centre one. */
    public static final float SPREAD_DEGREES = 5f;

    private static final Pattern ARROW_COUNT = Pattern.compile("^Shoots ([2-9]) arrows at once\\.?$");

    private Shortbows() {
    }

    public static boolean isShortbow(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (AutoPuzzleUtil.isShortbow(stack)) {
            return true;
        }
        String id = AutoPuzzleUtil.skyblockId(stack);
        return id != null && (id.equals("TERMINATOR") || id.endsWith("SHORTBOW"));
    }

    public static boolean isTerminator(ItemStack stack) {
        return "TERMINATOR".equals(AutoPuzzleUtil.skyblockId(stack));
    }

    /** Arrows one shot of {@code stack} fires (1 for anything that is not a multi-arrow bow). */
    public static int arrowCount(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 1;
        }
        if (isTerminator(stack)) {
            return 3;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return 1;
        }
        for (Component line : lore.lines()) {
            String s = ChatFormatting.stripFormatting(line.getString());
            if (s == null) {
                continue;
            }
            Matcher m = ARROW_COUNT.matcher(s.trim());
            if (m.matches()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return 1;
    }

    /** Yaw offsets (degrees) of each arrow of an {@code n}-arrow shot, centred: 1 -> {0}, 3 -> {-5, 0, 5}. */
    public static float[] yawOffsets(int n) {
        float[] out = new float[Math.max(1, n)];
        for (int i = 0; i < out.length; i++) {
            out[i] = (i - (out.length - 1) / 2f) * SPREAD_DEGREES;
        }
        return out;
    }
}
