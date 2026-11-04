package com.killer560.hub.util;

import net.minecraft.client.Minecraft;

/**
 * Which mouse buttons the game has seen go down and not yet come up, from {@code MouseHandler.onButton}
 * ({@code macrostop/mixin/MacroStopMouseMixin} reports every press and release here). {@link KeyUtil#isKeyDown} reads
 * this beside the platform's own mouse state (SDL on 26.3, GLFW on 26.2), so a mouse bind sees every button event the
 * game itself sees - the same press a Controls binding would get - and the testkit's real input reaches it too.
 *
 * <p>Stored (GLFW, 0-based) button numbers. Everything is forgotten while the window is not focused, so a release the
 * window never got (alt-tab with a button held) cannot leave a button "held" forever.
 */
public final class MouseButtons {

    private static final boolean[] DOWN = new boolean[KeyUtil.MAX_MOUSE_BUTTON + 1];

    private MouseButtons() {
    }

    /** A press ({@code action} 1) or release (0) of stored button {@code button}. Render thread. */
    public static void onButton(int button, int action) {
        if (button < 0 || button >= DOWN.length) return;
        if (action == 1) {
            DOWN[button] = true;
        } else if (action == 0) {
            DOWN[button] = false;
        }
    }

    /** Whether stored button {@code button} went down and has not come up (false while the window is unfocused). */
    public static boolean isDown(int button) {
        if (button < 0 || button >= DOWN.length) return false;
        if (!Minecraft.getInstance().isWindowActive()) {
            java.util.Arrays.fill(DOWN, false);
            return false;
        }
        return DOWN[button];
    }
}
