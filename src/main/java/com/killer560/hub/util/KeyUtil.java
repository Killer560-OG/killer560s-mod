package com.killer560.hub.util;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;

/**
 * Shared keybind-code validation for every raw-polled keybind in the mod.
 *
 * <p>{@link InputConstants#isKeyDown} is a bare {@code glfwGetKey}, and GLFW rejects any code outside
 * {@code GLFW_KEY_SPACE..GLFW_KEY_LAST} with a GLFW_INVALID_ENUM error - which Minecraft logs on every call.
 * Since the polls run every tick, a hand-edited bad code in a config file (e.g. {@code 5} or {@code 9999})
 * used to spam the log constantly. Keyboard codes come from {@code keyPressed}; a mouse button is encoded
 * into the same field as a negative code (see {@link #MOUSE_CODE_BASE}), with {@code -1} meaning "Not Set".
 */
public final class KeyUtil {

    /** "Not Set" sentinel used by every keybind config in the mod. */
    public static final int NONE = -1;

    private KeyUtil() {
    }

    /** True for codes {@code glfwGetKey} accepts (keyboard range {@code GLFW_KEY_SPACE..GLFW_KEY_LAST}). */
    public static boolean isValidKey(int code) {
        return code >= GLFW.GLFW_KEY_SPACE && code <= GLFW.GLFW_KEY_LAST;
    }

    /** Returns {@code code} if valid, otherwise {@link #NONE} - use on config load. */
    public static int sanitize(int code) {
        return isValidKey(code) ? code : NONE;
    }

    /** Drop-in replacement for {@link InputConstants#isKeyDown} that never polls GLFW with an invalid code. */
    public static boolean isKeyDown(Window window, int code) {
        return window != null && isValidKey(code) && InputConstants.isKeyDown(window, code);
    }

    /** Mouse buttons are stored in the same int field as a key, as {@code MOUSE_CODE_BASE - button}
     *  (left -100, right -101, middle -102, ...). {@code -1} is still "Not Set". */
    public static final int MOUSE_CODE_BASE = -100;
    public static final int MAX_MOUSE_BUTTON = 7;

    public static boolean isMouseCode(int code) {
        return code <= MOUSE_CODE_BASE && code >= MOUSE_CODE_BASE - MAX_MOUSE_BUTTON;
    }

    public static int mouseButton(int code) {
        return MOUSE_CODE_BASE - code;
    }

    public static int codeForMouseButton(int button) {
        return MOUSE_CODE_BASE - button;
    }

    /** Like {@link #sanitize}, but keeps mouse-button codes - use on config load for any bind that
     *  accepts a mouse button. */
    public static int sanitizeBind(int code) {
        return isMouseCode(code) ? code : sanitize(code);
    }

    /** Polls a key or a mouse button, whichever the code is. */
    public static boolean isBindDown(Window window, int code) {
        if (window == null || code == NONE) {
            return false;
        }
        return isMouseCode(code)
                ? GLFW.glfwGetMouseButton(window.handle(), mouseButton(code)) == GLFW.GLFW_PRESS
                : isKeyDown(window, code);
    }

    /** Display name for either kind of bind ("Left Button", "Middle Button", "R", "Not Set"). */
    public static String bindDisplayName(int code) {
        if (code == NONE) {
            return "Not Set";
        }
        return isMouseCode(code)
                ? InputConstants.Type.MOUSE.getOrCreate(mouseButton(code)).getDisplayName().getString()
                : InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
    }
}
