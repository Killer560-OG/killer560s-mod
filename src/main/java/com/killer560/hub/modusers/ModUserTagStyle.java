package com.killer560.hub.modusers;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * How the Mod User Tag looks (killer560, 2026-11-01: "I dont like how the current tag looks. Make it look like something that
 * more resembles our mod"; then, of a first [K560] badge: "i dont like that symbol being so long though it needs to be
 * short"). Every style is ONE glyph in front of the name. The mod's look is its orange (the menu's accent,
 * {@code MenuStyle.OR} #FF8A1F) and its name, Killer560's Mod. Each style still tells legit from cheat, as the old diamond
 * did. Picked in the Mod User Tag tab; it changes only how YOU see tags (yours and others'), nothing is shared.
 */
public enum ModUserTagStyle {

    /** A bold orange K (the mod's initial), red for a cheat user. The default. */
    MARK("K"),
    /** One orange star (the Cosmetics card's mark), red-orange for cheat. */
    EMBER("Ember"),
    /** The first look (2026-10-19): a diamond, green for legit and red for cheat. */
    CLASSIC("Classic");

    public static final int ORANGE = 0xFF8A1F;
    public static final int CHEAT_RED = 0xE05555;

    public final String label;

    ModUserTagStyle(String label) {
        this.label = label;
    }

    /** A saved name; anything unknown (the removed BADGE included) falls back to {@link #MARK}. */
    public static ModUserTagStyle byName(String name) {
        for (ModUserTagStyle s : values()) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return MARK;
    }

    /** The tag put in front of a name, with its trailing space, for a player shown as {@code variant} ("legit"/"cheat"). */
    public MutableComponent prefix(String variant) {
        boolean cheat = "cheat".equals(variant);
        // The glyph is a CHILD of an unstyled root: a styled root would pass its color (and bold) on to the name appended
        // after it, which the first version did - the name took the tag's color.
        Component glyph = switch (this) {
            case MARK -> Component.literal("K").withStyle(color(cheat ? CHEAT_RED : ORANGE).withBold(true));
            case EMBER -> Component.literal("✦").withStyle(color(cheat ? 0xFF4A2A : ORANGE));
            case CLASSIC -> Component.literal("◆").withStyle(color(cheat ? 0xFF5555 : 0x55FF55));
        };
        return Component.empty().append(glyph).append(" ");
    }

    /** {@code name} with this style's tag in front. */
    public Component tag(String variant, Component name) {
        return prefix(variant).append(name);
    }

    private static Style color(int rgb) {
        return Style.EMPTY.withColor(TextColor.fromRgb(rgb));
    }
}
