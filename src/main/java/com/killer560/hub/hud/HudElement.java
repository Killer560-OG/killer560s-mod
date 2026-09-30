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
     * Whether this element's own setting is switched on - the feature toggle and nothing else. No location,
     * floor, boss-phase or "is there anything to show" test belongs here: that half of the question is
     * answered by {@link HudSeen}, from the element's real draw call.
     * <p>
     * killer560 (2026-09-30): "if a setting is disabled i shouldn't be able to edit its gui." So a false
     * here keeps the element out of the HUD editor entirely, however recently it drew. Must never throw -
     * an exception counts as enabled, so a broken check can't make an element uneditable.
     */
    /**
     * Whether this element's own setting is switched on.
     *
     * <p>Only the setting. Where he has to be standing for it to appear is NOT asked here - that half is the
     * {@link HudSeen} draw stamp, taken at the point the element really draws. One predicate answering both
     * questions drifted from the render path it was meant to mirror: Split Timers' copy tested
     * {@code isInDungeon()} while its own {@code render()} never did.
     */
    boolean isEnabledInSettings();

    /** Draws the element's real content at the given top-left position (already resolved from config). */
    void render(GuiGraphicsExtractor graphics, int x, int y);
}
