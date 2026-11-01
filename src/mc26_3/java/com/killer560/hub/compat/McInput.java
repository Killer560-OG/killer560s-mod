package com.killer560.hub.compat;

import com.killer560.hub.util.Keys;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLScancode;

import java.util.Arrays;

/**
 * Keyboard and mouse codes across Minecraft versions (26.3 copy: SDL3).
 *
 * <p>The mod STORES GLFW numbering everywhere ({@code util/Keys}: GLFW key tokens, GLFW mouse buttons 0 left,
 * 1 right, 2 middle), because every config file, profile and shared setting written before 26.3 holds those numbers
 * and must keep meaning the same physical key. 26.3 replaced GLFW with SDL3: {@code KeyEvent.key()} (= {@code input()}),
 * {@code KeyMapping} keyboard values and {@code InputConstants.KEY_*} are SDL SCANCODES (A=4, Escape=41, Return=40,
 * LShift=225; {@code KeyEvent.keycode()} is the layout-dependent SDL keycode and is only used for Ctrl+C/V/X/A), and
 * mouse buttons are SDL's 1-based numbers (left 1, middle 2, right 3, X1 4, X2 5). GLFW key tokens are named after
 * the US-layout physical key, the same thing an SDL scancode identifies, so the table below pairs them by name.
 * Built from LWJGL's own {@link SDLScancode} constants (javap lwjgl-sdl 3.4.3, 2026-11-01). Public signatures must
 * match the 26.2 copy exactly.
 */
public final class McInput {

    private static final int[] GLFW_TO_SDL = new int[Keys.LAST + 1];
    private static final int[] SDL_TO_GLFW = new int[SDLScancode.SDL_SCANCODE_COUNT];

    static {
        Arrays.fill(GLFW_TO_SDL, -1);
        Arrays.fill(SDL_TO_GLFW, -1);
        map(Keys.SPACE, SDLScancode.SDL_SCANCODE_SPACE);
        map(Keys.APOSTROPHE, SDLScancode.SDL_SCANCODE_APOSTROPHE);
        map(Keys.COMMA, SDLScancode.SDL_SCANCODE_COMMA);
        map(Keys.MINUS, SDLScancode.SDL_SCANCODE_MINUS);
        map(Keys.PERIOD, SDLScancode.SDL_SCANCODE_PERIOD);
        map(Keys.SLASH, SDLScancode.SDL_SCANCODE_SLASH);
        map(Keys.NUM_0, SDLScancode.SDL_SCANCODE_0);
        map(Keys.NUM_1, SDLScancode.SDL_SCANCODE_1);
        map(Keys.NUM_2, SDLScancode.SDL_SCANCODE_2);
        map(Keys.NUM_3, SDLScancode.SDL_SCANCODE_3);
        map(Keys.NUM_4, SDLScancode.SDL_SCANCODE_4);
        map(Keys.NUM_5, SDLScancode.SDL_SCANCODE_5);
        map(Keys.NUM_6, SDLScancode.SDL_SCANCODE_6);
        map(Keys.NUM_7, SDLScancode.SDL_SCANCODE_7);
        map(Keys.NUM_8, SDLScancode.SDL_SCANCODE_8);
        map(Keys.NUM_9, SDLScancode.SDL_SCANCODE_9);
        map(Keys.SEMICOLON, SDLScancode.SDL_SCANCODE_SEMICOLON);
        map(Keys.EQUAL, SDLScancode.SDL_SCANCODE_EQUALS);
        map(Keys.A, SDLScancode.SDL_SCANCODE_A);
        map(Keys.B, SDLScancode.SDL_SCANCODE_B);
        map(Keys.C, SDLScancode.SDL_SCANCODE_C);
        map(Keys.D, SDLScancode.SDL_SCANCODE_D);
        map(Keys.E, SDLScancode.SDL_SCANCODE_E);
        map(Keys.F, SDLScancode.SDL_SCANCODE_F);
        map(Keys.G, SDLScancode.SDL_SCANCODE_G);
        map(Keys.H, SDLScancode.SDL_SCANCODE_H);
        map(Keys.I, SDLScancode.SDL_SCANCODE_I);
        map(Keys.J, SDLScancode.SDL_SCANCODE_J);
        map(Keys.K, SDLScancode.SDL_SCANCODE_K);
        map(Keys.L, SDLScancode.SDL_SCANCODE_L);
        map(Keys.M, SDLScancode.SDL_SCANCODE_M);
        map(Keys.N, SDLScancode.SDL_SCANCODE_N);
        map(Keys.O, SDLScancode.SDL_SCANCODE_O);
        map(Keys.P, SDLScancode.SDL_SCANCODE_P);
        map(Keys.Q, SDLScancode.SDL_SCANCODE_Q);
        map(Keys.R, SDLScancode.SDL_SCANCODE_R);
        map(Keys.S, SDLScancode.SDL_SCANCODE_S);
        map(Keys.T, SDLScancode.SDL_SCANCODE_T);
        map(Keys.U, SDLScancode.SDL_SCANCODE_U);
        map(Keys.V, SDLScancode.SDL_SCANCODE_V);
        map(Keys.W, SDLScancode.SDL_SCANCODE_W);
        map(Keys.X, SDLScancode.SDL_SCANCODE_X);
        map(Keys.Y, SDLScancode.SDL_SCANCODE_Y);
        map(Keys.Z, SDLScancode.SDL_SCANCODE_Z);
        map(Keys.LEFT_BRACKET, SDLScancode.SDL_SCANCODE_LEFTBRACKET);
        map(Keys.BACKSLASH, SDLScancode.SDL_SCANCODE_BACKSLASH);
        map(Keys.RIGHT_BRACKET, SDLScancode.SDL_SCANCODE_RIGHTBRACKET);
        map(Keys.GRAVE_ACCENT, SDLScancode.SDL_SCANCODE_GRAVE);
        // GLFW's WORLD_1 is the extra ISO key beside left shift (Windows scancode 0x56), SDL's NONUSBACKSLASH.
        map(Keys.WORLD_1, SDLScancode.SDL_SCANCODE_NONUSBACKSLASH);
        map(Keys.ESCAPE, SDLScancode.SDL_SCANCODE_ESCAPE);
        map(Keys.ENTER, SDLScancode.SDL_SCANCODE_RETURN);
        map(Keys.TAB, SDLScancode.SDL_SCANCODE_TAB);
        map(Keys.BACKSPACE, SDLScancode.SDL_SCANCODE_BACKSPACE);
        map(Keys.INSERT, SDLScancode.SDL_SCANCODE_INSERT);
        map(Keys.DELETE, SDLScancode.SDL_SCANCODE_DELETE);
        map(Keys.RIGHT, SDLScancode.SDL_SCANCODE_RIGHT);
        map(Keys.LEFT, SDLScancode.SDL_SCANCODE_LEFT);
        map(Keys.DOWN, SDLScancode.SDL_SCANCODE_DOWN);
        map(Keys.UP, SDLScancode.SDL_SCANCODE_UP);
        map(Keys.PAGE_UP, SDLScancode.SDL_SCANCODE_PAGEUP);
        map(Keys.PAGE_DOWN, SDLScancode.SDL_SCANCODE_PAGEDOWN);
        map(Keys.HOME, SDLScancode.SDL_SCANCODE_HOME);
        map(Keys.END, SDLScancode.SDL_SCANCODE_END);
        map(Keys.CAPS_LOCK, SDLScancode.SDL_SCANCODE_CAPSLOCK);
        map(Keys.SCROLL_LOCK, SDLScancode.SDL_SCANCODE_SCROLLLOCK);
        map(Keys.NUM_LOCK, SDLScancode.SDL_SCANCODE_NUMLOCKCLEAR);
        map(Keys.PRINT_SCREEN, SDLScancode.SDL_SCANCODE_PRINTSCREEN);
        map(Keys.PAUSE, SDLScancode.SDL_SCANCODE_PAUSE);
        map(Keys.F1, SDLScancode.SDL_SCANCODE_F1);
        map(Keys.F2, SDLScancode.SDL_SCANCODE_F2);
        map(Keys.F3, SDLScancode.SDL_SCANCODE_F3);
        map(Keys.F4, SDLScancode.SDL_SCANCODE_F4);
        map(Keys.F5, SDLScancode.SDL_SCANCODE_F5);
        map(Keys.F6, SDLScancode.SDL_SCANCODE_F6);
        map(Keys.F7, SDLScancode.SDL_SCANCODE_F7);
        map(Keys.F8, SDLScancode.SDL_SCANCODE_F8);
        map(Keys.F9, SDLScancode.SDL_SCANCODE_F9);
        map(Keys.F10, SDLScancode.SDL_SCANCODE_F10);
        map(Keys.F11, SDLScancode.SDL_SCANCODE_F11);
        map(Keys.F12, SDLScancode.SDL_SCANCODE_F12);
        map(Keys.F13, SDLScancode.SDL_SCANCODE_F13);
        map(Keys.F14, SDLScancode.SDL_SCANCODE_F14);
        map(Keys.F15, SDLScancode.SDL_SCANCODE_F15);
        map(Keys.F16, SDLScancode.SDL_SCANCODE_F16);
        map(Keys.F17, SDLScancode.SDL_SCANCODE_F17);
        map(Keys.F18, SDLScancode.SDL_SCANCODE_F18);
        map(Keys.F19, SDLScancode.SDL_SCANCODE_F19);
        map(Keys.F20, SDLScancode.SDL_SCANCODE_F20);
        map(Keys.F21, SDLScancode.SDL_SCANCODE_F21);
        map(Keys.F22, SDLScancode.SDL_SCANCODE_F22);
        map(Keys.F23, SDLScancode.SDL_SCANCODE_F23);
        map(Keys.F24, SDLScancode.SDL_SCANCODE_F24);
        map(Keys.KP_0, SDLScancode.SDL_SCANCODE_KP_0);
        map(Keys.KP_1, SDLScancode.SDL_SCANCODE_KP_1);
        map(Keys.KP_2, SDLScancode.SDL_SCANCODE_KP_2);
        map(Keys.KP_3, SDLScancode.SDL_SCANCODE_KP_3);
        map(Keys.KP_4, SDLScancode.SDL_SCANCODE_KP_4);
        map(Keys.KP_5, SDLScancode.SDL_SCANCODE_KP_5);
        map(Keys.KP_6, SDLScancode.SDL_SCANCODE_KP_6);
        map(Keys.KP_7, SDLScancode.SDL_SCANCODE_KP_7);
        map(Keys.KP_8, SDLScancode.SDL_SCANCODE_KP_8);
        map(Keys.KP_9, SDLScancode.SDL_SCANCODE_KP_9);
        // The numpad '.' key reports SDL KP_PERIOD on Windows; KP_DECIMAL (which 26.3 names KEY_NUMPADCOMMA) maps back too.
        map(Keys.KP_DECIMAL, SDLScancode.SDL_SCANCODE_KP_PERIOD);
        SDL_TO_GLFW[SDLScancode.SDL_SCANCODE_KP_DECIMAL] = Keys.KP_DECIMAL;
        map(Keys.KP_DIVIDE, SDLScancode.SDL_SCANCODE_KP_DIVIDE);
        map(Keys.KP_MULTIPLY, SDLScancode.SDL_SCANCODE_KP_MULTIPLY);
        map(Keys.KP_SUBTRACT, SDLScancode.SDL_SCANCODE_KP_MINUS);
        map(Keys.KP_ADD, SDLScancode.SDL_SCANCODE_KP_PLUS);
        map(Keys.KP_ENTER, SDLScancode.SDL_SCANCODE_KP_ENTER);
        map(Keys.KP_EQUAL, SDLScancode.SDL_SCANCODE_KP_EQUALS);
        map(Keys.LEFT_SHIFT, SDLScancode.SDL_SCANCODE_LSHIFT);
        map(Keys.LEFT_CONTROL, SDLScancode.SDL_SCANCODE_LCTRL);
        map(Keys.LEFT_ALT, SDLScancode.SDL_SCANCODE_LALT);
        map(Keys.LEFT_SUPER, SDLScancode.SDL_SCANCODE_LGUI);
        map(Keys.RIGHT_SHIFT, SDLScancode.SDL_SCANCODE_RSHIFT);
        map(Keys.RIGHT_CONTROL, SDLScancode.SDL_SCANCODE_RCTRL);
        map(Keys.RIGHT_ALT, SDLScancode.SDL_SCANCODE_RALT);
        map(Keys.RIGHT_SUPER, SDLScancode.SDL_SCANCODE_RGUI);
        // The Windows context-menu key arrives as SDL APPLICATION.
        map(Keys.MENU, SDLScancode.SDL_SCANCODE_APPLICATION);
        // No SDL scancode for GLFW WORLD_2 or F25: those stored codes poll as never-down on 26.3.
    }

    private static void map(int glfw, int sdl) {
        GLFW_TO_SDL[glfw] = sdl;
        SDL_TO_GLFW[sdl] = glfw;
    }

    private McInput() {
    }

    /** Stored (GLFW) key for a native key code (SDL scancode), or -1 when it has none. */
    public static int toStoredKey(int nativeKey) {
        return nativeKey >= 0 && nativeKey < SDL_TO_GLFW.length ? SDL_TO_GLFW[nativeKey] : -1;
    }

    /** Native key code (SDL scancode) for a stored (GLFW) key, or -1 when this version has no such key. */
    public static int toNativeKey(int storedKey) {
        return storedKey >= 0 && storedKey < GLFW_TO_SDL.length ? GLFW_TO_SDL[storedKey] : -1;
    }

    /** Stored (GLFW, 0-based) mouse button for a native SDL button: 1 left -> 0, 3 right -> 1, 2 middle -> 2,
     *  X1 4 -> 3, X2 5 -> 4 (vanilla's own {@code AbstractContainerScreen.getContainerClickButton} swaps 1 and 3 the same way). */
    public static int toStoredButton(int nativeButton) {
        return switch (nativeButton) {
            case 1 -> 0;
            case 2 -> 2;
            case 3 -> 1;
            default -> nativeButton >= 4 ? nativeButton - 1 : -1;
        };
    }

    /** Native SDL mouse button for a stored (GLFW, 0-based) one. */
    public static int toNativeButton(int storedButton) {
        return switch (storedButton) {
            case 0 -> 1;
            case 1 -> 3;
            case 2 -> 2;
            default -> storedButton >= 3 ? storedButton + 1 : -1;
        };
    }

    /** The stored (GLFW) key of a key event (its SDL scancode, translated). */
    public static int key(KeyEvent event) {
        return toStoredKey(event.key());
    }

    /** The stored (GLFW, 0 left / 1 right / 2 middle) button of a mouse event. */
    public static int button(MouseButtonEvent event) {
        return toStoredButton(event.button());
    }

    /** The stored (GLFW, 0 left / 1 right / 2 middle) button of a raw mouse input. */
    public static int button(MouseButtonInfo info) {
        return toStoredButton(info.button());
    }

    /** Polls a stored (GLFW) keyboard key. */
    public static boolean isStoredKeyDown(Window window, int storedKey) {
        return isNativeKeyDown(window, toNativeKey(storedKey));
    }

    /** Polls a native keyboard key (SDL scancode: a {@code KeyMapping} value or {@code InputConstants.KEY_*}). */
    public static boolean isNativeKeyDown(Window window, int nativeKey) {
        return window != null && nativeKey > 0 && nativeKey < SDLScancode.SDL_SCANCODE_COUNT
                && InputConstants.isKeyDown(nativeKey);
    }

    /** Polls a stored (GLFW, 0-based) mouse button. */
    public static boolean isStoredButtonDown(Window window, int storedButton) {
        return isNativeButtonDown(window, toNativeButton(storedButton));
    }

    /** Polls a native SDL mouse button (a mouse {@code KeyMapping} value). SDL keeps one mouse state for the
     *  focused window, so {@code window} only guards against a missing window. */
    public static boolean isNativeButtonDown(Window window, int nativeButton) {
        if (window == null || nativeButton < 1 || nativeButton > 32) {
            return false;
        }
        return (SDLMouse.nSDL_GetMouseState(0L, 0L) & (1 << (nativeButton - 1))) != 0; // SDL_BUTTON_MASK(X), private in LWJGL
    }

    /** The keyboard input type ({@code KEYSYM} on 26.2, {@code KEYBOARD} on 26.3). */
    public static InputConstants.Type keyboardType() {
        return InputConstants.Type.KEYBOARD;
    }

    /** Whether a bound key is a keyboard key (as opposed to a mouse button). */
    public static boolean isKeyboard(InputConstants.Key key) {
        return key != null && key.getType() == InputConstants.Type.KEYBOARD;
    }

    /** The input for a stored (GLFW) key, for display names and key mappings. */
    public static InputConstants.Key storedKeyInput(int storedKey) {
        int nativeKey = toNativeKey(storedKey);
        return nativeKey < 0 ? InputConstants.UNKNOWN : InputConstants.Type.KEYBOARD.getOrCreate(nativeKey);
    }

    /** The input for a stored (GLFW, 0-based) mouse button. */
    public static InputConstants.Key storedButtonInput(int storedButton) {
        int nativeButton = toNativeButton(storedButton);
        return nativeButton < 0 ? InputConstants.UNKNOWN : InputConstants.Type.MOUSE.getOrCreate(nativeButton);
    }
}
