package com.killer560.hub.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Orange-themed client-side chat (2026-09-14, killer560's own rule: "all client side stuff should be that orange
 * theme unless it is a meaningful color like green or red"). Same accent as the mod menu ({@code 0xCC6600},
 * see MenuRowWidget.ACCENT). Use {@link #send} for every local-only message: an orange "[Feature]" prefix,
 * then body parts built with {@link #text} (neutral), {@link #value} (light orange - numbers, names, times),
 * {@link #good}/{@link #bad} (green/red, only where the color itself carries meaning), {@link #dim}.
 */
public final class ModChat {

    public static final int ORANGE = 0xCC6600;
    public static final int LIGHT_ORANGE = 0xFFA040;
    public static final int TEXT = 0xF0E6DC;
    public static final int DIM = 0x9A8C80;
    public static final int GOOD = 0x55FF55;
    public static final int BAD = 0xFF5555;

    private ModChat() {
    }

    public static MutableComponent prefix(String feature) {
        return colored("[" + feature + "] ", ORANGE);
    }

    public static MutableComponent text(String s) {
        return colored(s, TEXT);
    }

    public static MutableComponent value(String s) {
        return colored(s, LIGHT_ORANGE);
    }

    public static MutableComponent dim(String s) {
        return colored(s, DIM);
    }

    public static MutableComponent good(String s) {
        return colored(s, GOOD);
    }

    public static MutableComponent bad(String s) {
        return colored(s, BAD);
    }

    public static MutableComponent colored(String s, int rgb) {
        return Component.literal(s).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
    }

    /** Builds "[feature] " + parts. */
    public static MutableComponent line(String feature, Component... parts) {
        MutableComponent out = prefix(feature);
        for (Component part : parts) {
            out.append(part);
        }
        return out;
    }

    /** Sends a local-only (client-side) message: "[feature] " + parts. No-op without a player. */
    public static void send(String feature, Component... parts) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(line(feature, parts));
        }
    }
}
