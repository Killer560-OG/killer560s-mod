package com.killer560.hub.fonts;

/**
 * "Mod Menu Only": when the chosen font applies. Text is measured while a screen lays out and draws, but GUI text is
 * PREPARED later, after every screen and the HUD have submitted (GuiTextRenderState.ensurePrepared, javap 26.1.2 and
 * 26.2), so a flag held only while the mod's screen draws would measure in one font and render in the other. Each
 * GuiTextRenderState therefore remembers, as it is made, whether it was made inside one of the mod's screens, and
 * {@code fonts.mixin.GuiTextRenderStateFontMixin} replays that answer around its own preparation ({@link #override}).
 * <p>
 * Render thread only (screens and GUI render states live there); a lookup from another thread just sees "not in a menu".
 */
public final class FontScope {

    /** Depth of mod-screen layout/draw calls in progress. */
    private static int menuDepth;
    /** -1: no override; 0: force Minecraft's font; 1: force the menu font (a render state being prepared). */
    private static int override = -1;

    private FontScope() {
    }

    public static boolean menuNow() {
        int o = override;
        if (o >= 0) {
            return o == 1;
        }
        return menuDepth > 0;
    }

    public static void enterMenu() {
        menuDepth++;
    }

    public static void exitMenu() {
        if (menuDepth > 0) {
            menuDepth--;
        }
    }

    /** Sets the override for one render state's preparation; returns the previous value to restore. */
    public static int override(boolean menu) {
        int prev = override;
        override = menu ? 1 : 0;
        return prev;
    }

    public static void restore(int prev) {
        override = prev;
    }

    /** Each tick, between frames: nothing can be mid-draw, so a depth left by an exception is cleared. */
    static void resetDepth() {
        menuDepth = 0;
        override = -1;
    }
}
