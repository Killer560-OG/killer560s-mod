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

    /** This element's own scale when the player has never resized it (multiplied by the global HUD scale like any
     *  saved scale). 1.0 for everything except the built-in GIF, which ships at the size killer560 uses it at. */
    default float defaultScale() {
        return 1.0f;
    }

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

    /**
     * Where a layout places this element right now, in GUI pixels, or null when its own saved position applies (every
     * element but the Health and Mana Bars readouts under their Predefined layout, 2026-10-07). When non-null,
     * {@link HudElementRegistry#resolvePosition} returns it as it is: the layout keeps it on screen itself, and the
     * element's saved position is left untouched for when the layout is switched off. The returned array is shared;
     * callers must not change it.
     */
    default int[] layoutPosition() {
        return null;
    }

    /**
     * The own scale a layout draws this element at, in place of its saved HUD-editor scale, or 0 (or less) when the
     * saved scale applies. Like {@link #layoutPosition()}: the saved value is left untouched for when the layout is off.
     * Health and Mana Bars' Predefined layout uses it so every readout in it shares one scale (2026-10-07). Multiplied
     * by the global HUD scale like any own scale ({@link HudElementRegistry#resolveScale}).
     */
    default float layoutScale() {
        return 0f;
    }
}
