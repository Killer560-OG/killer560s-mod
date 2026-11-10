package com.killer560.hub.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;

import java.lang.reflect.Field;
import java.nio.IntBuffer;

/**
 * Window, display and cursor management across Minecraft versions (26.3 copy: SDL3).
 *
 * <p>26.3's {@code Window.handle()} is the {@code SDL_Window*}; Minecraft's own Window code drives it with
 * {@code SDLVideo} (javap 26.3: {@code SDL_GetWindowFlags}, {@code SDL_SetWindowPosition/Size}...), and so does this
 * class. Coordinates are SDL's screen coordinates; a window's position and size are its CONTENT (client) area, as
 * with GLFW. A display is an SDL display id (Minecraft's {@code Monitor.id()}). Public signatures must match the
 * 26.2 copy exactly.
 */
public final class McWindow {

    private McWindow() {
    }

    // ------------------------------------------------------------------------------------------------ window

    private static Field fullscreenField;
    private static boolean fullscreenFieldTried;

    /** Whether vanilla (F11 / Video Settings) fullscreen is on. 26.3's Window keeps it in a private
     *  {@code fullscreen} field with no getter (javap 26.3; the game is not obfuscated, so the name holds at
     *  runtime); the fullscreen option is the fallback. */
    public static boolean isFullscreen(Window window) {
        if (!fullscreenFieldTried) {
            fullscreenFieldTried = true;
            try {
                Field f = Window.class.getDeclaredField("fullscreen");
                f.setAccessible(true);
                fullscreenField = f;
            } catch (Throwable ignored) {
                fullscreenField = null;
            }
        }
        if (fullscreenField != null) {
            try {
                return fullscreenField.getBoolean(window);
            } catch (Throwable ignored) {
            }
        }
        Minecraft client = Minecraft.getInstance();
        return client != null && client.options != null && client.options.fullscreen().get();
    }

    /** Turns vanilla fullscreen on and records it in options (the caller saves options). On 26.3 the option's own
     *  listener calls {@code Window.setFullscreen}, and {@code updateFullscreenIfChanged} applies it. */
    public static void enterFullscreen(Minecraft client, Window window) {
        window.setFullscreen(true);
        client.options.fullscreen().set(true);
    }

    /** Shows or hides the title bar and borders. */
    public static void setDecorated(Window window, boolean decorated) {
        SDLVideo.SDL_SetWindowBordered(window.handle(), decorated);
    }

    /** Leaves any fullscreen and places the content area at {@code (x, y)} with size {@code w x h}. */
    public static void setWindowedBounds(Window window, int x, int y, int width, int height) {
        long h = window.handle();
        if ((SDLVideo.SDL_GetWindowFlags(h) & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0) {
            SDLVideo.SDL_SetWindowFullscreen(h, false);
        }
        SDLVideo.SDL_SetWindowSize(h, width, height);
        SDLVideo.SDL_SetWindowPosition(h, x, y);
    }

    /** Moves the content area's top-left corner to {@code (x, y)}. */
    public static void setPosition(Window window, int x, int y) {
        SDLVideo.SDL_SetWindowPosition(window.handle(), x, y);
    }

    /** @return {x, y} of the content area. */
    public static int[] position(Window window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.callocInt(1);
            IntBuffer y = stack.callocInt(1);
            SDLVideo.SDL_GetWindowPosition(window.handle(), x, y);
            return new int[]{x.get(0), y.get(0)};
        }
    }

    /** @return {w, h} of the content area in screen coordinates. */
    public static int[] size(Window window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.callocInt(1);
            IntBuffer h = stack.callocInt(1);
            SDLVideo.SDL_GetWindowSize(window.handle(), w, h);
            return new int[]{w.get(0), h.get(0)};
        }
    }

    /** @return {left, top, right, bottom} decoration sizes (title bar and borders), zeros when undecorated. */
    public static int[] frameSize(Window window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer t = stack.callocInt(1);
            IntBuffer l = stack.callocInt(1);
            IntBuffer b = stack.callocInt(1);
            IntBuffer r = stack.callocInt(1);
            // SDL's order is top, left, bottom, right.
            if (!SDLVideo.SDL_GetWindowBordersSize(window.handle(), t, l, b, r)) {
                return new int[]{0, 0, 0, 0};
            }
            return new int[]{Math.max(0, l.get(0)), Math.max(0, t.get(0)), Math.max(0, r.get(0)), Math.max(0, b.get(0))};
        }
    }

    /** Whether the window is minimized or maximized (both block an exact placement until restored). */
    public static boolean isMinimizedOrMaximized(Window window) {
        long flags = SDLVideo.SDL_GetWindowFlags(window.handle());
        return (flags & (SDLVideo.SDL_WINDOW_MINIMIZED | SDLVideo.SDL_WINDOW_MAXIMIZED)) != 0;
    }

    /** Restores a minimized or maximized window. */
    public static void restore(Window window) {
        SDLVideo.SDL_RestoreWindow(window.handle());
    }

    /** Brings the window to the front and gives it input focus. */
    public static void focus(Window window) {
        SDLVideo.SDL_RaiseWindow(window.handle());
    }

    /** The display's content (DPI) scale for this window, 1 when unknown. */
    public static float contentScale(Window window) {
        float v = SDLVideo.SDL_GetWindowDisplayScale(window.handle());
        return v > 0f ? v : 1f;
    }

    /** The Win32 HWND of the window (SDL window property {@code SDL.window.win32.hwnd}), or 0. */
    public static long win32Handle(Window window) {
        try {
            long h = window.handle();
            if (h == 0L) {
                return 0L;
            }
            int props = SDLVideo.SDL_GetWindowProperties(h);
            return props == 0 ? 0L
                    : SDLProperties.SDL_GetPointerProperty(props, SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------------------------------------ cursor

    /** Hides the real pointer, or puts back what the game wants: captured (in-world) or a normal arrow. SDL hides
     *  the pointer itself in relative (grabbed) mode, so un-hiding a grabbed window re-asserts relative mode the way
     *  {@code InputConstants.grabMouse} does. */
    public static void setCursorHidden(Window window, boolean hidden, boolean grabbed) {
        if (hidden) {
            SDLMouse.SDL_HideCursor();
            return;
        }
        SDLMouse.SDL_ShowCursor();
        if (grabbed) {
            SDLMouse.SDL_SetWindowRelativeMouseMode(window.handle(), true);
        }
    }

    /** Re-issues the game's grab or release of the mouse at {@code (x, y)}. */
    public static void reapplyMouseGrab(Window window, boolean grabbed, double x, double y) {
        if (grabbed) {
            InputConstants.grabMouse(window, x, y);
        } else {
            InputConstants.releaseMouse(window, x, y);
        }
    }

    /** Standard-cursor id for {@code CursorType.createStandardCursor}: the NW-SE diagonal resize arrow. */
    public static int resizeNwseCursorId() {
        return SDLMouse.SDL_SYSTEM_CURSOR_NWSE_RESIZE;
    }

    /** Standard-cursor id for {@code CursorType.createStandardCursor}: the NE-SW diagonal resize arrow. */
    public static int resizeNeswCursorId() {
        return SDLMouse.SDL_SYSTEM_CURSOR_NESW_RESIZE;
    }

    // ------------------------------------------------------------------------------------------------ displays

    /** Every connected display, in the windowing system's order. */
    public static long[] displays() {
        IntBuffer ids = SDLVideo.SDL_GetDisplays();
        if (ids == null) {
            return new long[0];
        }
        try {
            long[] out = new long[ids.remaining()];
            for (int i = 0; i < out.length; i++) {
                out[i] = ids.get(ids.position() + i) & 0xFFFFFFFFL;
            }
            return out;
        } finally {
            SDLStdinc.SDL_free(ids);
        }
    }

    /** The primary display, or 0. */
    public static long primaryDisplay() {
        return SDLVideo.SDL_GetPrimaryDisplay() & 0xFFFFFFFFL;
    }

    /** The display id of one of Minecraft's {@link Monitor}s (comparable with {@link #displays()}). */
    public static long displayOf(Monitor monitor) {
        return monitor.id() & 0xFFFFFFFFL;
    }

    /** @return {x, y, w, h} of the display in screen coordinates, or null. */
    public static int[] displayBounds(long display) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Rect r = SDL_Rect.calloc(stack);
            if (!SDLVideo.SDL_GetDisplayBounds((int) display, r) || r.w() <= 0 || r.h() <= 0) {
                SDL_DisplayMode mode = SDLVideo.SDL_GetCurrentDisplayMode((int) display);
                return mode == null ? null : new int[]{0, 0, mode.w(), mode.h()};
            }
            return new int[]{r.x(), r.y(), r.w(), r.h()};
        }
    }

    /** @return {x, y, w, h} of the display's work area (without the taskbar), or null. */
    public static int[] displayWorkArea(long display) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Rect r = SDL_Rect.calloc(stack);
            if (!SDLVideo.SDL_GetDisplayUsableBounds((int) display, r) || r.w() <= 0 || r.h() <= 0) {
                return null;
            }
            return new int[]{r.x(), r.y(), r.w(), r.h()};
        }
    }

    /** The display's human-readable name, or null. */
    public static String displayName(long display) {
        return SDLVideo.SDL_GetDisplayName((int) display);
    }

    /** The Win32 display device name (e.g. {@code \\.\DISPLAY1}), or "" when unavailable. SDL3 does not expose it,
     *  so 26.3 always answers "" and saved monitors are found by name and index instead. */
    public static String displayWin32Device(long display) {
        return "";
    }

    // ------------------------------------------------------------------------------------------------ OS opens

    /** Opens a link in the default browser (26.2: {@code Util.getPlatform().openUri}; 26.3: {@code Blaze3D.openUri}). */
    public static void openUri(String uri) {
        try {
            com.mojang.blaze3d.Blaze3D.openUri(new java.net.URI(uri));
        } catch (java.net.URISyntaxException e) {
            com.killer560.hub.util.ModLog.get("killer560smod-externalopen").warn("Not a valid link: {}", uri);
        }
    }

    /** Opens a file or folder with the OS (26.2: {@code Util.getPlatform().openPath}; 26.3: {@code Blaze3D.openPath}). */
    public static void openPath(java.nio.file.Path path) {
        com.mojang.blaze3d.Blaze3D.openPath(path);
    }

}
