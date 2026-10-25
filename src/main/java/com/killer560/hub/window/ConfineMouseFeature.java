package com.killer560.hub.window;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.platform.Window;
import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;

import java.util.Arrays;

/**
 * Confine Mouse In Menus (killer560, 2026-10-24): "for borderless fullscreen add an option for the mouse to not go
 * outside of the window in a menu unless you alt tab or have chat open. But like in a chest for instance your mouse
 * must stay within the window."
 *
 * <p>Each client tick, while the setting is on, the window is in this mod's borderless fullscreen (and not Minecraft's
 * exclusive fullscreen), the game window has focus and a screen other than chat is open, the cursor is held to the
 * window's client rectangle with user32 {@code ClipCursor}. The rectangle is read fresh every tick (GetClientRect +
 * ClientToScreen), so a moved or resized window is followed. Any other state releases it with {@code ClipCursor(NULL)},
 * as do CLIENT_STOPPING and a JVM shutdown hook. The cursor mode is never touched; only the clip rectangle.
 *
 * <p>One exception on release: with no screen open and the game's mouse grabbed, GLFW's own disabled-cursor mode has
 * already clipped the cursor to the same rectangle (it does that on grab and undoes it on release), so clearing it
 * then would undo GLFW's clip, not ours. The release is skipped in that one case; GLFW clears its clip itself.
 *
 * <p>Bindings are JNA core with RECT as {@code int[4]} and POINT as {@code int[2]}, like {@code shorts.Win32}. If a
 * call fails, it is logged once and the feature does nothing for the rest of the session.
 */
public final class ConfineMouseFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-window");

    interface User32 extends StdCallLibrary {
        boolean ClipCursor(int[] rect);

        boolean GetClientRect(long hwnd, int[] rect);

        boolean ClientToScreen(long hwnd, int[] point);
    }

    private static volatile User32 user32;
    private static boolean loadAttempted;
    private static volatile boolean broken;
    /** Whether the current clip is ours (so a release is ours to make). */
    private static volatile boolean clipped;
    private static int[] lastRect;

    private ConfineMouseFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("ConfineMouseFeature.tick", ConfineMouseFeature::tick));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> release());
        Runtime.getRuntime().addShutdownHook(new Thread(ConfineMouseFeature::release, "killer560smod-confinemouse-exit"));
    }

    /** Whether the game window is in this mod's borderless fullscreen right now (exclusive fullscreen does not count). */
    static boolean isBorderless(Minecraft client) {
        return WindowModeConfig.getInstance().isBorderlessFullscreenEnabled() && !client.getWindow().isFullscreen();
    }

    /** The confinement rule: setting on, borderless, window focused, and a screen other than chat open. */
    static boolean shouldConfine(Minecraft client) {
        if (!ConfineMouseConfig.getInstance().isEnabled() || !isBorderless(client) || !client.isWindowActive()) {
            return false;
        }
        Screen screen = McCompat.screen(client);
        return screen != null && !(screen instanceof ChatScreen);
    }

    private static void tick(Minecraft client) {
        if (broken || Util.getPlatform() != Util.OS.WINDOWS) {
            return;
        }
        try {
            if (!shouldConfine(client)) {
                if (clipped) {
                    boolean glfwOwnsClip = McCompat.screen(client) == null && client.mouseHandler.isMouseGrabbed();
                    if (glfwOwnsClip) {
                        clipped = false;
                        lastRect = null;
                    } else {
                        release();
                    }
                }
                return;
            }
            if (!ensureLoaded()) {
                return;
            }
            int[] rect = clientRectOnScreen(client.getWindow());
            if (rect == null) {
                fail("could not read the window's client rectangle");
                return;
            }
            // Reapplied every tick, not only on a change: GLFW clears the clip whenever the cursor mode changes, and
            // Windows can drop it on its own (another app's ClipCursor, a display change).
            if (!user32.ClipCursor(rect)) {
                fail("ClipCursor failed (error " + Native.getLastError() + ")");
                return;
            }
            if (!clipped || !Arrays.equals(rect, lastRect)) {
                LOGGER.info("[ConfineMouse] cursor held to {},{} - {},{}", rect[0], rect[1], rect[2], rect[3]);
            }
            clipped = true;
            lastRect = rect;
        } catch (Throwable t) {
            fail("unexpected " + t);
        }
    }

    /** {left, top, right, bottom} of the window's client area in screen coordinates, or null. */
    private static int[] clientRectOnScreen(Window window) {
        long hwnd = GLFWNativeWin32.glfwGetWin32Window(window.handle());
        if (hwnd == 0L) {
            return null;
        }
        int[] client = new int[4];
        int[] origin = new int[2];
        if (!user32.GetClientRect(hwnd, client) || !user32.ClientToScreen(hwnd, origin)) {
            return null;
        }
        int w = client[2] - client[0];
        int h = client[3] - client[1];
        if (w <= 0 || h <= 0) {
            return null;
        }
        return new int[]{origin[0], origin[1], origin[0] + w, origin[1] + h};
    }

    /** Clears the clip if it is ours. Safe from any thread and safe to call twice; never throws. */
    static void release() {
        if (!clipped) {
            return;
        }
        clipped = false;
        lastRect = null;
        User32 u = user32;
        if (u == null) {
            return;
        }
        try {
            if (!u.ClipCursor(null)) {
                LOGGER.warn("[ConfineMouse] ClipCursor(NULL) failed (error {})", Native.getLastError());
            } else {
                LOGGER.info("[ConfineMouse] cursor released");
            }
        } catch (Throwable t) {
            LOGGER.warn("[ConfineMouse] could not release the cursor", t);
        }
    }

    private static synchronized boolean ensureLoaded() {
        if (!loadAttempted) {
            loadAttempted = true;
            try {
                if (Native.POINTER_SIZE != 8) {
                    fail("needs a 64-bit Java");
                    return false;
                }
                user32 = Native.load("user32", User32.class);
            } catch (Throwable t) {
                user32 = null;
                fail("could not load user32: " + t);
            }
        }
        return user32 != null;
    }

    /** Logs once, releases anything held, and turns the feature off for this session. */
    private static void fail(String why) {
        if (!broken) {
            broken = true;
            LOGGER.warn("[ConfineMouse] {} - Confine Mouse In Menus is off for this session", why);
        }
        release();
    }
}
