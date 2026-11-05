package com.killer560.hub.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/**
 * Keyboard and mouse codes across Minecraft versions (26.2 copy: GLFW).
 *
 * <p>The mod STORES GLFW numbering everywhere ({@code util/Keys}: GLFW key tokens, GLFW mouse buttons 0 left,
 * 1 right, 2 middle). A NATIVE code is whatever this Minecraft version hands out ({@code KeyEvent.key()},
 * {@code KeyMapping}/{@code InputConstants.Key} values, {@code InputConstants.KEY_*}, {@code MouseButtonEvent.button()}).
 * On 26.2 the two are the same numbers, so every translation here is the identity; the 26.3 copy (SDL3) is where the
 * tables live. Public signatures must match the 26.3 copy exactly.
 */
public final class McInput {

    private McInput() {
    }

    /** Stored (GLFW) key for a native key code, or -1 when it has none. */
    public static int toStoredKey(int nativeKey) {
        return nativeKey;
    }

    /** Native key code for a stored (GLFW) key, or -1 when this version has no such key. */
    public static int toNativeKey(int storedKey) {
        return storedKey;
    }

    /** Stored (GLFW, 0-based) mouse button for a native button number. */
    public static int toStoredButton(int nativeButton) {
        return nativeButton;
    }

    /** Native mouse button number for a stored (GLFW, 0-based) one. */
    public static int toNativeButton(int storedButton) {
        return storedButton;
    }

    /** The stored (GLFW) key of a key event. */
    public static int key(KeyEvent event) {
        return event.key();
    }

    /** The stored (GLFW, 0 left / 1 right / 2 middle) button of a mouse event. */
    public static int button(MouseButtonEvent event) {
        return event.button();
    }

    /** The stored (GLFW, 0 left / 1 right / 2 middle) button of a raw mouse input. */
    public static int button(MouseButtonInfo info) {
        return info.button();
    }

    /** No-op on 26.2: GLFW delivers typed characters at all times (26.3's SDL needs text input started). */
    public static void textInput(Object owner, boolean on) {
    }

    /** No-op on 26.2, as {@link #textInput}. */
    public static void textInputWhileOpen(net.minecraft.client.gui.screens.Screen screen) {
    }

    /** Polls a stored (GLFW) keyboard key. The caller checks the code is in the keyboard range. */
    public static boolean isStoredKeyDown(Window window, int storedKey) {
        return window != null && InputConstants.isKeyDown(window, storedKey);
    }

    /** Polls a native keyboard key (a {@code KeyMapping} value or {@code InputConstants.KEY_*}). */
    public static boolean isNativeKeyDown(Window window, int nativeKey) {
        return window != null && nativeKey >= GLFW.GLFW_KEY_SPACE && nativeKey <= GLFW.GLFW_KEY_LAST
                && InputConstants.isKeyDown(window, nativeKey);
    }

    /** Polls a stored (GLFW, 0-based) mouse button. */
    public static boolean isStoredButtonDown(Window window, int storedButton) {
        return window != null && storedButton >= 0 && storedButton <= GLFW.GLFW_MOUSE_BUTTON_LAST
                && GLFW.glfwGetMouseButton(window.handle(), storedButton) == GLFW.GLFW_PRESS;
    }

    /** Polls a native mouse button (a mouse {@code KeyMapping} value). */
    public static boolean isNativeButtonDown(Window window, int nativeButton) {
        return isStoredButtonDown(window, nativeButton);
    }

    /** The keyboard input type ({@code KEYSYM} on 26.2, {@code KEYBOARD} on 26.3). */
    public static InputConstants.Type keyboardType() {
        return InputConstants.Type.KEYSYM;
    }

    /** Whether a bound key is a keyboard key (as opposed to a mouse button). */
    public static boolean isKeyboard(InputConstants.Key key) {
        return key != null && key.getType() == InputConstants.Type.KEYSYM;
    }

    /** The input for a stored (GLFW) key, for display names and key mappings. */
    public static InputConstants.Key storedKeyInput(int storedKey) {
        return InputConstants.Type.KEYSYM.getOrCreate(storedKey);
    }

    /** The input for a stored (GLFW, 0-based) mouse button. */
    public static InputConstants.Key storedButtonInput(int storedButton) {
        return InputConstants.Type.MOUSE.getOrCreate(storedButton);
    }
}
