package com.killer560.hub.storagesearch;

import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;

/**
 * One open-bind for Storage Item Search - killer560 (2026-09-21): "allow for me to have multiple keybinds in the
 * sense of ctrl + f to open it". {@link KeyUtil} only stores a single bare key (or a mouse button) per setting and
 * has no notion of a modifier combination, so the combination half lives here rather than being bolted onto every
 * other keybind in the mod; see this feature's staging notes for the KeyUtil generalisation that would let the rest
 * of the mod share it.
 * <p>
 * {@code code} is exactly what {@link KeyUtil} already understands - a GLFW key code, or a negative mouse code
 * ({@link KeyUtil#codeForMouseButton}) - so mouse binds keep working. {@code mods} is a bit mask of the modifiers
 * that must be held. The match is strict in both directions: a modifier that is not in the mask must NOT be held,
 * so a plain "F" bind never fires while Ctrl is down (otherwise Ctrl+F would also trigger the plain F bind).
 */
public record StorageSearchBind(int mods, int code) {

    public static final int MOD_CTRL = 1;
    public static final int MOD_SHIFT = 2;
    public static final int MOD_ALT = 4;

    /** @return true when {@code key} is itself a modifier key, which can only be part of a combination. */
    public static boolean isModifierKey(int key) {
        return modBitFor(key) != 0;
    }

    /** @return the {@code mods} bit this key contributes, or 0 when it is not a modifier key. */
    public static int modBitFor(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> MOD_CTRL;
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> MOD_SHIFT;
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> MOD_ALT;
            default -> 0;
        };
    }

    public boolean isSet() {
        return code != KeyUtil.NONE;
    }

    /** Polls the whole combination. Cheap - at most four {@code glfwGetKey} calls, and only for binds that are set. */
    public boolean isDown(Window window) {
        if (window == null || !isSet()) {
            return false;
        }
        if (heldMods(window) != mods) {
            return false;
        }
        return KeyUtil.isBindDown(window, code);
    }

    private static int heldMods(Window window) {
        int held = 0;
        if (KeyUtil.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) || KeyUtil.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
            held |= MOD_CTRL;
        }
        if (KeyUtil.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) || KeyUtil.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            held |= MOD_SHIFT;
        }
        if (KeyUtil.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT) || KeyUtil.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT)) {
            held |= MOD_ALT;
        }
        return held;
    }

    /** "Ctrl + F", "Shift + Left Button", "Not Set". */
    public String display() {
        if (!isSet()) {
            return "Not Set";
        }
        StringBuilder sb = new StringBuilder();
        if ((mods & MOD_CTRL) != 0) {
            sb.append("Ctrl + ");
        }
        if ((mods & MOD_SHIFT) != 0) {
            sb.append("Shift + ");
        }
        if ((mods & MOD_ALT) != 0) {
            sb.append("Alt + ");
        }
        return sb.append(KeyUtil.bindDisplayName(code)).toString();
    }

    /** Config form: {@code "<mods>:<code>"}. */
    public String serialize() {
        return mods + ":" + code;
    }

    /** @return the bind this string encodes, or null when it is unusable (so a hand-edited config can't break the
     *  feature - same spirit as {@link KeyUtil#sanitizeBind}). */
    public static StorageSearchBind parse(String raw) {
        if (raw == null) {
            return null;
        }
        int colon = raw.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            int mods = Integer.parseInt(raw.substring(0, colon).trim());
            int code = KeyUtil.sanitizeBind(Integer.parseInt(raw.substring(colon + 1).trim()));
            if (code == KeyUtil.NONE) {
                return null;
            }
            return new StorageSearchBind(mods & (MOD_CTRL | MOD_SHIFT | MOD_ALT), code);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
