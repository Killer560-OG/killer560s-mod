package com.killer560.hub.window;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Borderless fullscreen: an undecorated window resized/positioned to cover the whole monitor,
 *  as opposed to Minecraft's own real-fullscreen (a dedicated exclusive video mode). Implemented
 *  with raw GLFW calls since {@link Window} has no borderless concept of its own - verified via
 *  javap against the real 26.1.2 jar (see project notes) rather than assumed. */
public final class WindowModeFeature {

    private static boolean appliedStartupState = false;

    private WindowModeFeature() {
    }

    /** Re-applies the saved borderless state once, on the first client tick after boot - the GLFW
     *  window isn't guaranteed to exist yet at {@code onInitializeClient()} time, but it always does
     *  by the first tick. */
    public static void tickApplyOnce(Minecraft client) {
        if (appliedStartupState) {
            return;
        }
        appliedStartupState = true;
        if (WindowModeConfig.getInstance().isBorderlessFullscreenEnabled()) {
            enable(client, true);
        }
    }

    /** Profile switch ({@code ProfileManager#applyProfile}): after {@link WindowModeConfig#load()} picked up
     *  a different borderless setting, puts the real window into that state so the next toggle doesn't go
     *  the wrong way. Before the first tick has applied the startup state this does nothing - that tick
     *  reads the freshly loaded config itself. */
    public static void applyConfigAfterReload(boolean wasEnabled) {
        if (!appliedStartupState) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        boolean nowEnabled = WindowModeConfig.getInstance().isBorderlessFullscreenEnabled();
        if (nowEnabled == wasEnabled) {
            return;
        }
        if (nowEnabled) {
            enable(client, false);
        } else {
            disable(client);
        }
    }

    public static void toggle() {
        Minecraft client = Minecraft.getInstance();
        WindowModeConfig cfg = WindowModeConfig.getInstance();
        if (cfg.isBorderlessFullscreenEnabled()) {
            cfg.setBorderlessFullscreenEnabled(false);
            cfg.save();
            disable(client);
        } else {
            cfg.setBorderlessFullscreenEnabled(true);
            cfg.save();
            enable(client, false);
        }
    }

    /** @param fromStartup true when re-applying the saved state on the first tick after boot. The window
     *  at that point is just Minecraft's default startup window, not a size the user chose, so it must not
     *  replace already-saved windowed bounds (2026-09-15 persistence audit: it did, so leaving borderless
     *  after every restart snapped back to the startup size instead of the user's real windowed bounds). */
    private static void enable(Minecraft client, boolean fromStartup) {
        Window window = client.getWindow();
        WindowModeConfig cfg = WindowModeConfig.getInstance();

        if (window.isFullscreen()) {
            window.setWindowed(cfg.getSavedWindowedWidth(), cfg.getSavedWindowedHeight());
        } else if (!fromStartup || !cfg.hasSavedWindowedBounds()) {
            cfg.saveWindowedBounds(window.getX(), window.getY(), window.getWidth(), window.getHeight());
            cfg.save();
        }

        Monitor monitor = window.findBestMonitor();
        if (monitor == null) {
            return;
        }
        VideoMode mode = monitor.getCurrentMode();

        GLFW.glfwSetWindowAttrib(window.handle(), GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        GLFW.glfwSetWindowMonitor(window.handle(), 0L, monitor.getX(), monitor.getY(),
                mode.getWidth(), mode.getHeight(), GLFW.GLFW_DONT_CARE);
    }

    private static void disable(Minecraft client) {
        Window window = client.getWindow();
        WindowModeConfig cfg = WindowModeConfig.getInstance();

        int x = cfg.hasSavedWindowedBounds() ? cfg.getSavedWindowedX() : 100;
        int y = cfg.hasSavedWindowedBounds() ? cfg.getSavedWindowedY() : 100;
        int width = cfg.hasSavedWindowedBounds() ? cfg.getSavedWindowedWidth() : 854;
        int height = cfg.hasSavedWindowedBounds() ? cfg.getSavedWindowedHeight() : 480;

        GLFW.glfwSetWindowAttrib(window.handle(), GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
        GLFW.glfwSetWindowMonitor(window.handle(), 0L, x, y, width, height, GLFW.GLFW_DONT_CARE);
    }
}
