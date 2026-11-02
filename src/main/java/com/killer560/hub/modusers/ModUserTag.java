package com.killer560.hub.modusers;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * The Mod User Tag (killer560, 2026-11-01): one bold K in front of the name, in the mod's orange (the menu's accent,
 * {@code MenuStyle.OR} #FF8A1F) for a legit user and red for a cheat user. His asks, in order: "Make it look like something
 * that more resembles our mod"; of a [K560] badge, "it needs to be short"; then of the K/star/diamond choice, "make k the
 * only option get rid of the other one". So there is no style setting; a saved {@code tagStyle} key is ignored.
 */
public final class ModUserTag {

    public static final int ORANGE = 0xFF8A1F;
    public static final int CHEAT_RED = 0xE05555;

    private ModUserTag() {
    }

    /** The tag with its trailing space, for a player shown as {@code variant} ("legit" or "cheat"). The K is a CHILD of an
     *  unstyled root: a styled root would pass its color and bold on to the name appended after it. */
    public static MutableComponent prefix(String variant) {
        Component k = Component.literal("K").withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb("cheat".equals(variant) ? CHEAT_RED : ORANGE)).withBold(true));
        return Component.empty().append(k).append(" ");
    }

    /** {@code name} with the tag in front. */
    public static Component tag(String variant, Component name) {
        return prefix(variant).append(name);
    }
}
