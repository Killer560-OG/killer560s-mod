package com.killer560.hub.proxy.gui;

import com.killer560.hub.proxy.config.ProxyConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * A {@link Button} whose label always reflects the current proxy state. The
 * label is refreshed every frame in {@link #extractContents}, so it stays
 * correct without relying on the parent screen re-initialising or ticking.
 */
public class ProxyToggleButton extends Button {

    public ProxyToggleButton(int x, int y, int width, int height, OnPress onPress) {
        super(x, y, width, height, buildLabel(), onPress, Button.DEFAULT_NARRATION);
    }

    private static Component buildLabel() {
        boolean enabled = ProxyConfig.getInstance().isEnabled();
        MutableComponent state = enabled
                ? Component.literal("Enabled").withStyle(ChatFormatting.GREEN)
                : Component.literal("Disabled").withStyle(ChatFormatting.RED);
        return Component.literal("Proxy: ").append(state);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // Refresh label from live config before each draw.
        this.setMessage(buildLabel());
        this.extractDefaultSprite(graphics);
        this.extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
    }
}
