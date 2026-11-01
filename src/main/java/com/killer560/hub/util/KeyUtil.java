package com.killer560.hub.util;

import com.killer560.hub.compat.McInput;
import com.mojang.blaze3d.platform.Window;

/**
 * Shared keybind-code validation for every raw-polled keybind in the mod.
 *
 * <p>Every code here is a STORED code: a GLFW key token ({@link Keys}) or a mouse bind, on every Minecraft version
 * (26.3's SDL scancodes are translated by {@link McInput}; see {@link Keys}). A key code from a key event goes through
 * {@code McInput.key(event)} before it is stored or compared. On 26.2 an out-of-range code made {@code glfwGetKey}
 * log GLFW_INVALID_ENUM on every call, so a hand-edited bad code in a config file (e.g. {@code 5} or {@code 9999})
 * spammed the log; {@link #sanitize} still drops those. A mouse button is encoded into the same field as a negative
 * code (see {@link #MOUSE_CODE_BASE}), with {@code -1} meaning "Not Set".
 */
public final class KeyUtil {

    /** "Not Set" sentinel used by every keybind config in the mod. */
    public static final int NONE = -1;

    private KeyUtil() {
    }

    /** True for stored keyboard codes (GLFW range {@code GLFW_KEY_SPACE..GLFW_KEY_LAST}). */
    public static boolean isValidKey(int code) {
        return code >= Keys.FIRST && code <= Keys.LAST;
    }

    /** Returns {@code code} if valid, otherwise {@link #NONE} - use on config load. */
    public static int sanitize(int code) {
        return isValidKey(code) ? code : NONE;
    }

    /** Polls a STORED keyboard code (never with an invalid one). For a code from Minecraft (a {@code KeyMapping}
     *  value, {@code InputConstants.KEY_*}) use {@link McInput#isNativeKeyDown} instead. */
    public static boolean isKeyDown(Window window, int code) {
        return window != null && isValidKey(code) && McInput.isStoredKeyDown(window, code);
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
                ? McInput.isStoredButtonDown(window, mouseButton(code))
                : isKeyDown(window, code);
    }

    /** Compact {@link #bindDisplayName} for a tight readout like the Interactive Map legend, where "Left Button"
     *  runs straight into the column beside it: "LMB"/"RMB"/"MMB"/"M4"... for a mouse bind, the key's own name
     *  otherwise. */
    public static String bindShortName(int code) {
        if (code == NONE || !isMouseCode(code)) {
            return bindDisplayName(code);
        }
        int button = mouseButton(code);
        return switch (button) {
            case 0 -> "LMB";
            case 1 -> "RMB";
            case 2 -> "MMB";
            default -> "M" + (button + 1);
        };
    }

    /** Display name for either kind of bind ("Left Button", "Middle Button", "R", "Not Set"). */
    public static String bindDisplayName(int code) {
        if (code == NONE) {
            return "Not Set";
        }
        return isMouseCode(code)
                ? McInput.storedButtonInput(mouseButton(code)).getDisplayName().getString()
                : keyName(code);
    }

    /** Display name of a STORED keyboard code ("R", "Left Shift"), whatever Minecraft version is running. */
    public static String keyName(int code) {
        return McInput.storedKeyInput(code).getDisplayName().getString();
    }
}
