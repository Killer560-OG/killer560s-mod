package com.killer560.hub.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** Drop-in replacement for vanilla {@link AbstractSliderButton} that only overrides its rendering
 *  (vanilla draws its own fixed grey sprite track/handle with no way to recolor it) - every other bit
 *  of behavior (drag-to-set-value, click-to-jump, keyboard nudging, {@code updateMessage()}/
 *  {@code applyValue()}) is fully inherited unchanged, since {@code extractWidgetRenderState} is the
 *  only non-final method vanilla exposes for this. Existing anonymous subclasses just need
 *  {@code AbstractSliderButton} swapped for {@code ThemedSliderButton} at their `new` site - same
 *  constructor shape, same abstract methods to implement.
 *  <p>
 *  Built (2026-09-07), part of the black+amber GUI overhaul, per killer560's "make those sliding bars fit
 *  the theme as well... make the handle itself orange." Track uses the same dark-box style as
 *  {@link SettingsButtonWidget} (dim amber border, brightening on hover); the handle is a solid bright
 *  amber block so it reads clearly as the draggable element against the darker filled/unfilled track. */
public abstract class ThemedSliderButton extends AbstractSliderButton {

    private static final int TRACK_BG = 0xFF1A1A1A;
    private static final int TRACK_BORDER = 0xFF663D1A;
    private static final int TRACK_BORDER_HOVER = 0xFFCC6600;
    private static final int FILLED = 0xFF3D2A14;
    private static final int HANDLE = 0xFFCC6600;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int HANDLE_WIDTH = 6;

    protected ThemedSliderButton(int x, int y, int width, int height, Component message, double value) {
        super(x, y, width, height, message, value);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x0 = getX();
        int y0 = getY();
        int w = getWidth();
        int h = getHeight();
        graphics.fill(x0, y0, x0 + w, y0 + h, TRACK_BG);
        int handleX = x0 + (int) Math.round(this.value * (w - HANDLE_WIDTH));
        // A subtle "filled up to here" strip behind the handle, same idea as vanilla's own slider
        // (the portion already dragged past reads visually distinct from the empty remainder).
        graphics.fill(x0 + 1, y0 + 1, handleX, y0 + h - 1, FILLED);
        graphics.outline(x0, y0, w, h, isHovered ? TRACK_BORDER_HOVER : TRACK_BORDER);
        graphics.fill(handleX, y0, handleX + HANDLE_WIDTH, y0 + h, HANDLE);
        graphics.centeredText(Minecraft.getInstance().font, getMessage(), x0 + w / 2, y0 + (h - 8) / 2, TEXT);
    }
}
