package com.killer560.hub.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A hand-drawn box in a menu page or drawer (the Profiles page's cards): it only paints, never takes a click
 * ({@code active} is false, so a press falls through to the real buttons placed on it), and the menu's tooltip lookup
 * prefers any real widget on top of it. Its message is the label the menu, its search and the testkit read for it.
 * Add it BEFORE the widgets that sit on it: the content pane draws in list order.
 */
public final class PaintedPanel extends AbstractWidget {

    /** Draws the box at its current (scrolled) position. */
    @FunctionalInterface
    public interface Painter {
        void paint(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hovered);
    }

    private final Painter painter;

    public PaintedPanel(int x, int y, int w, int h, Component label, Painter painter) {
        super(x, y, w, h, label);
        this.painter = painter;
        this.active = false;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        painter.paint(graphics, getX(), getY(), getWidth(), getHeight(), isHovered);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
