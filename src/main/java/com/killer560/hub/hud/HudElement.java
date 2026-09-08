package com.killer560.hub.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A draggable HUD overlay whose position is stored in {@link HudConfig} under {@link #id()}. */
public interface HudElement {

    String id();

    String displayName();

    int defaultX();

    int defaultY();

    int width();

    int height();

    /** Draws the element's real content at the given top-left position (already resolved from config). */
    void render(GuiGraphicsExtractor graphics, int x, int y);
}
