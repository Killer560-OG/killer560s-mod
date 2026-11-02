package com.killer560.hub.modusers;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * How the Mod User Tag looks (killer560, 2026-11-01: "I dont like how the current tag looks. Make it look like something that
 * more resembles our mod"). The mod's look is its orange (the menu's accent, {@code MenuStyle.OR} #FF8A1F, and its lighter
 * hover #FFB060) and its name, Killer560's Mod. Each style still tells legit from cheat, as the old diamond did. Picked in the
 * Mod User Tag tab; it changes only how YOU see tags (yours and others'), nothing is shared.
 */
public enum ModUserTagStyle {

    /** {@code [K560]}: the letters fade orange to light orange, in brackets that are orange for legit and red for cheat. */
    BADGE("Badge"),
    /** One orange star (the Cosmetics card's mark), red-orange for cheat. */
    EMBER("Ember"),
    /** The first look (2026-10-19): a diamond, green for legit and red for cheat. */
    CLASSIC("Classic");

    public static final int ORANGE = 0xFF8A1F;
    public static final int ORANGE_LIGHT = 0xFFB060;
    public static final int ORANGE_DARK = 0xB85A0A;
    public static final int CHEAT_RED = 0xE05555;

    public final String label;

    ModUserTagStyle(String label) {
        this.label = label;
    }

    public static ModUserTagStyle byName(String name) {
        for (ModUserTagStyle s : values()) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return BADGE;
    }

    /** The tag put in front of a name, with its trailing space, for a player shown as {@code variant} ("legit"/"cheat"). */
    public MutableComponent prefix(String variant) {
        boolean cheat = "cheat".equals(variant);
        return switch (this) {
            case BADGE -> {
                int bracket = cheat ? CHEAT_RED : ORANGE_DARK;
                MutableComponent out = Component.literal("[").withStyle(color(bracket));
                String word = "K560";
                for (int i = 0; i < word.length(); i++) {
                    out.append(Component.literal(String.valueOf(word.charAt(i)))
                            .withStyle(color(lerp(ORANGE, ORANGE_LIGHT, i / (float) (word.length() - 1))).withBold(true)));
                }
                yield out.append(Component.literal("] ").withStyle(color(bracket)));
            }
            case EMBER -> Component.literal("✦ ").withStyle(color(cheat ? 0xFF4A2A : ORANGE));
            case CLASSIC -> Component.literal("◆ ").withStyle(color(cheat ? 0xFF5555 : 0x55FF55));
        };
    }

    /** {@code name} with this style's tag in front. */
    public Component tag(String variant, Component name) {
        return prefix(variant).append(name);
    }

    private static Style color(int rgb) {
        return Style.EMPTY.withColor(TextColor.fromRgb(rgb));
    }

    private static int lerp(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
