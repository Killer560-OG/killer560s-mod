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
 * used to spam the log constantly. This mod only ever stores keyboard codes (key capture goes through
 * {@code keyPressed}; mouse buttons are never encoded), with {@code -1} meaning "Not Set".
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
}
