package com.killer560.hub.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/** Button label for a color setting - "Label: ■" with the square drawn in the setting's real color instead of the
 *  button's white text (2026-09-14, killer560: "for all of the color picker, instead of having the internal white
 *  color in the menu can you make it match the ingame color"). */
public final class ColorSwatch {

    private ColorSwatch() {
    }

    public static MutableComponent label(String label, int argb) {
        return Component.literal(label + ": ").append(
                Component.literal("■").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(argb & 0xFFFFFF))));
    }
}
