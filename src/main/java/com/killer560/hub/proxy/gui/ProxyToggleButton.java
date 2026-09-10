package com.killer560.hub.proxy.gui;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.proxy.config.ProxyConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Builds the "Proxy: Enabled/Disabled" button shown on the multiplayer server-list screen, styled to
 * match the mod's own black+amber theme (2026-09-09, per killer560's "make that text in the top right
 * fit the mod") instead of a plain vanilla {@link net.minecraft.client.gui.components.Button}. The
 * label is computed once at creation - {@code JoinMultiplayerScreenMixin}'s {@code init()} injection
 * re-creates this button every time the screen is (re)displayed (vanilla calls {@code init()} on every
 * {@code Minecraft.setScreen}, including right after Apply/Reset closes back to it from
 * {@link ProxyConfigScreen}), so the label is always current without needing its own per-frame refresh.
 */
public final class ProxyToggleButton {

    private ProxyToggleButton() {
    }

    public static SettingsButtonWidget create(int x, int y, int width, int height, SettingsButtonWidget.OnPress onPress) {
        return SettingsButtonWidget.builder(buildLabel(), onPress)
                .bounds(x, y, width, height)
                .build();
    }

    private static Component buildLabel() {
        boolean enabled = ProxyConfig.getInstance().isEnabled();
        MutableComponent state = enabled
                ? Component.literal("Enabled").withStyle(ChatFormatting.GREEN)
                : Component.literal("Disabled").withStyle(ChatFormatting.RED);
        return Component.literal("Proxy: ").append(state);
    }
}
