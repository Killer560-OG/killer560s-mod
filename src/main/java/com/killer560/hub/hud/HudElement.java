package com.killer560.hub.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A draggable HUD overlay whose position is stored in {@link HudConfig} under {@link #id()}. */
public interface HudElement {

    String id();

    String displayName();

    /** Preferred top-left X when the player has never moved this element. It does not have to fit the
     *  screen - {@link HudElementRegistry#resolvePosition} clamps an unsaved default fully into view. */
    int defaultX();

    /** Preferred top-left Y when the player has never moved this element (see {@link #defaultX()}). */
    int defaultY();

    int width();

    int height();

    /**
     * Whether this element belongs to what the player is doing right now - the HUD editor only lists
     * elements for which this is true (unless its "Show All" toggle is on). killer560: "for editing huds
     * have it only edit huds that are supposed to be open right now. So for instance if i am in dungeons
     * i dont need to edit the rng meter hud." Elements override this with the same gate their render
     * path uses (feature enabled + Skyblock / dungeon / floor / boss-phase check); the default keeps an
     * element that has no such gate always listed. Must never throw - the editor treats an exception as
     * "relevant" so a broken check can't make an element uneditable.
     */
    default boolean isRelevantNow() {
        return true;
    }

    /** Draws the element's real content at the given top-left position (already resolved from config). */
    void render(GuiGraphicsExtractor graphics, int x, int y);
}
