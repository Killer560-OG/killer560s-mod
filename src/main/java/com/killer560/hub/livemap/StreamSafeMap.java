package com.killer560.hub.livemap;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.compat.McRender;
import com.killer560.hub.compat.McWindow;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.Win32;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;
import org.slf4j.Logger;

import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.List;

/**
 * Stream Safe Funny Map (cheat build, killer560 2026-11-18: "if I am screen sharing or recording my game then you don't see
 * my funny map style instead you would see the normal map style however I can still see the funny map").
 *
 * <p>While it is on, every draw of the Dungeon Map HUD into the game's frame is the NORMAL map
 * ({@link #hidingFunny()} turns {@link LiveMapConfig#isFunnyMap()} off for it, and the mimic tint with it), so anything
 * that captures the game's own picture sees only that. The Funny Map is drawn by a second {@link GuiRenderer} into an
 * offscreen target at most {@link #REFRESH_MS} apart, read back, and pushed with {@code UpdateLayeredWindow} into a
 * borderless, click-through window owned by the game window and placed exactly over the map, which
 * {@code SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)} keeps out of screen capture. A failure of any Win32 or GPU step
 * shows nothing more: the game window keeps the normal map, and chat says so once.
 *
 * <p>Render thread only. Nothing runs while the switch is off and no overlay exists.
 */
public final class StreamSafeMap {

    private static final Logger LOGGER = ModLog.get("killer560smod-streamsafe");
    public static final boolean SUPPORTED = net.minecraft.util.Util.getPlatform() == net.minecraft.util.Util.OS.WINDOWS;

    private static final int WDA_EXCLUDEFROMCAPTURE = 0x11;
    private static final int WS_POPUP = 0x80000000;
    private static final int WS_EX_TRANSPARENT = 0x20;
    private static final int WS_EX_NOACTIVATE = 0x08000000;
    static final int EX_STYLE = (int) Win32.WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE
            | (int) Win32.WS_EX_TOOLWINDOW;
    private static final int ULW_ALPHA = 2;
    /** BLENDFUNCTION {AC_SRC_OVER, 0, 255, AC_SRC_ALPHA}: per-pixel premultiplied alpha. */
    private static final int[] BLEND = {0x01FF0000};
    /** Fastest refresh (teammate markers move every tick; rooms change a few times a second at most). */
    static final long REFRESH_MS = 100L;
    /** The map has not drawn this long (world left, boss, map off): the window and its buffers are freed. */
    private static final long IDLE_FREE_MS = 5000L;

    private static boolean inHud;
    private static boolean overlayPass;
    private static boolean rendering;
    private static HudElement element;
    private static int mapPx;
    private static final Matrix3x2f ENTRY_POSE = new Matrix3x2f();
    private static final Matrix3x2f DRAWN_POSE = new Matrix3x2f();
    private static long frame;
    private static long drawnFrame = -1L;
    private static long lastPushMs;
    private static long lastDrawnMs;

    private static GuiRenderer renderer;
    private static GuiRenderState state;
    private static TextureTarget target;
    private static GpuBuffer readBuffer;
    private static long readBufferSize;
    private static boolean readPending;

    private static long hwnd;
    private static long memDc;
    private static long dib;
    private static long dibBits;
    private static int dibW;
    private static int dibH;
    private static boolean shown;
    private static boolean broken;
    private static boolean saidFullscreen;
    private static int[] lastPixels;
    /** Reused between pushes (one map square, about 300x300). */
    private static int[] pixels;
    private static int[] scaledPixels;
    private static final int[] RECT = new int[4];
    /** The map square in framebuffer pixels {x, y} (its size is RECT's). */
    private static final int[] FB = new int[4];
    private static long pushes;

    private StreamSafeMap() {
    }

    /** Whether the Dungeon Map HUD being drawn right now must be the normal map: Stream Safe is on and this draw is
     *  not the overlay's. Read by {@link LiveMapConfig#isFunnyMap()}, so it is false outside the HUD's own draw. */
    static boolean hidingFunny() {
        return inHud && !overlayPass && LiveMapConfig.getInstance().isStreamSafe();
    }

    /** The Dungeon Map HUD starts drawing (past its own hide checks); {@code graphics}' pose is the one to replay. */
    static void hudBegin(HudElement hud, GuiGraphicsExtractor graphics, int px) {
        inHud = true;
        if (!overlayPass && LiveMapConfig.getInstance().isStreamSafe()) {
            element = hud;
            mapPx = px;
            ENTRY_POSE.set(graphics.pose());
            DRAWN_POSE.set(graphics.pose());
            drawnFrame = frame;
        }
    }

    /** The pose the map square is really drawn at (after the HUD peek's enlarge and shift). */
    static void hudPose(GuiGraphicsExtractor graphics) {
        if (!overlayPass && drawnFrame == frame) {
            DRAWN_POSE.set(graphics.pose());
        }
    }

    static void hudEnd() {
        inHud = false;
    }

    /** GuiRenderer.draw's target: ours while the overlay's renderer draws, else the game's. */
    public static RenderTarget drawTarget(RenderTarget main) {
        return rendering && target != null ? target : main;
    }

    /** After the game's GUI is drawn each frame (GameRenderer.render, after GuiRenderer.endFrame). */
    public static void afterGui() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED || !SUPPORTED) {
            return;
        }
        boolean drawn = drawnFrame == frame;
        frame++;
        if (!drawn && hwnd == 0L && renderer == null) {
            return;   // off, or nothing to show and nothing to free
        }
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        boolean want = drawn && !broken && LiveMapConfig.getInstance().isStreamSafe() && screenAllows(mc);
        if (want && McWindow.isFullscreen(mc.getWindow())) {
            if (!saidFullscreen) {
                saidFullscreen = true;
                say("Stream Safe cannot draw the Funny Map over exclusive fullscreen; only the normal map shows."
                        + " Use windowed or borderless to see it.");
            }
            want = false;
        }
        if (!want) {
            hide();
            if (now - lastDrawnMs > IDLE_FREE_MS || !LiveMapConfig.getInstance().isStreamSafe()) {
                free();
            }
            return;
        }
        lastDrawnMs = now;
        if (readPending || now - lastPushMs < REFRESH_MS) {
            return;
        }
        lastPushMs = now;
        try {
            renderAndRead(mc);
        } catch (Throwable t) {
            fail("draw failed", t);
        }
    }

    /** No screen, or chat (which leaves the map uncovered); any other screen may cover it. */
    private static boolean screenAllows(Minecraft mc) {
        var screen = McCompat.screen(mc);
        return screen == null || screen instanceof ChatScreen;
    }

    private static void renderAndRead(Minecraft mc) {
        RenderTarget main = McRender.mainRenderTarget(mc);
        int w = main.width;
        int h = main.height;
        if (target == null || target.width != w || target.height != h) {
            if (target != null) {
                target.destroyBuffers();
            }
            target = McRender.newTextureTarget("killer560smod stream safe map", w, h, true);
        }
        if (renderer == null) {
            state = new GuiRenderState();
            renderer = new GuiRenderer(state, mc.gameRenderer.featureRenderDispatcher(), List.of());
        }
        McRender.clearToZero(target.getColorTexture());
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        g.pose().set(ENTRY_POSE);
        overlayPass = true;
        try {
            element.render(g, 0, 0);
        } finally {
            overlayPass = false;
        }
        rendering = true;
        try {
            renderer.render();
            renderer.endFrame();
        } finally {
            rendering = false;
        }

        // The map square's framebuffer rectangle.
        int gs = Math.max(1, (int) Math.ceil(mc.getWindow().getGuiScale()));
        Vector2f a = DRAWN_POSE.transformPosition(0f, 0f, new Vector2f());
        Vector2f b = DRAWN_POSE.transformPosition(mapPx, mapPx, new Vector2f());
        int x0 = Math.max(0, (int) Math.floor(Math.min(a.x, b.x) * gs));
        int y0 = Math.max(0, (int) Math.floor(Math.min(a.y, b.y) * gs));
        int x1 = Math.min(w, (int) Math.ceil(Math.max(a.x, b.x) * gs));
        int y1 = Math.min(h, (int) Math.ceil(Math.max(a.y, b.y) * gs));
        int rw = x1 - x0;
        int rh = y1 - y0;
        if (rw <= 0 || rh <= 0) {
            hide();
            return;
        }
        long size = (long) rw * rh * 4L;
        if (readBuffer == null || readBufferSize != size) {
            if (readBuffer != null) {
                readBuffer.close();
            }
            readBuffer = RenderSystem.getDevice().createBuffer(() -> "killer560smod stream safe map readback",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size);
            readBufferSize = size;
        }
        GpuBuffer buf = readBuffer;
        readPending = true;
        // Textures are bottom-up: the region's texture row is counted from the bottom, and its rows come back bottom first.
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(target.getColorTexture(), buf, 0L, () -> {
            try {
                if (buf != readBuffer || buf.isClosed()) {
                    return;
                }
                int[] px = pixels == null || pixels.length != rw * rh ? (pixels = new int[rw * rh]) : pixels;
                try (GpuBufferSlice.MappedView view = buf.map(true, false)) {
                    IntBuffer in = view.data().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                    for (int row = 0; row < rh; row++) {
                        int src = row * rw;
                        int dst = (rh - 1 - row) * rw;
                        for (int col = 0; col < rw; col++) {
                            int p = in.get(src + col);   // R,G,B,A bytes, already premultiplied (GUI blend over 0)
                            px[dst + col] = (p & 0xFF00FF00) | ((p & 0xFF) << 16) | ((p >>> 16) & 0xFF);
                        }
                    }
                }
                push(mc, px, x0, y0, rw, rh, w, h);
            } catch (Throwable t) {
                fail("readback failed", t);
            } finally {
                readPending = false;
            }
        }, 0, x0, h - y1, rw, rh);
    }

    private static void push(Minecraft mc, int[] px, int x, int y, int rw, int rh, int fbWidth, int fbHeight) {
        if (broken || !LiveMapConfig.getInstance().isStreamSafe()) {
            return;
        }
        Throwable err = Win32.ensureLoaded();
        if (err != null || Win32.user32() == null || Win32.gdi32() == null) {
            fail("Win32 unavailable", err);
            return;
        }
        Win32.User32 u = Win32.user32();
        long game = McWindow.win32Handle(mc.getWindow());
        int[] client = game == 0L ? null : Win32.clientRectOnScreen(game);
        // A hidden or minimized game window (a hidden test client too) shows no overlay on the desktop.
        if (client == null || client[2] <= 0 || !u.IsWindowVisible(game) || u.IsIconic(game)) {
            hide();
            return;
        }
        // The framebuffer is stretched over the client area when their sizes differ (a fixed-size framebuffer), so
        // the overlay is placed and sized in client pixels and the read pixels are scaled to it.
        double sx = client[2] / (double) fbWidth;
        double sy = client[3] / (double) fbHeight;
        int cx0 = (int) Math.round(x * sx);
        int cy0 = (int) Math.round(y * sy);
        int dw = Math.max(1, (int) Math.round((x + rw) * sx) - cx0);
        int dh = Math.max(1, (int) Math.round((y + rh) * sy) - cy0);
        int sxPos = client[0] + cx0;
        int syPos = client[1] + cy0;
        int[] out = px;
        if (dw != rw || dh != rh) {
            out = scaledPixels == null || scaledPixels.length != dw * dh ? (scaledPixels = new int[dw * dh]) : scaledPixels;
            for (int r = 0; r < dh; r++) {
                int srcRow = Math.min(rh - 1, r * rh / dh) * rw;
                for (int c = 0; c < dw; c++) {
                    out[r * dw + c] = px[srcRow + Math.min(rw - 1, c * rw / dw)];
                }
            }
        }
        if (hwnd == 0L) {
            hwnd = u.CreateWindowExW(EX_STYLE, new com.sun.jna.WString("STATIC"), new com.sun.jna.WString(""), WS_POPUP,
                    sxPos, syPos, dw, dh, game, 0L, 0L, 0L);
            if (hwnd == 0L) {
                fail("CreateWindowExW returned 0", null);
                return;
            }
            // Before Windows 10 2004 the call "behaves as if WDA_MONITOR is applied" (a black box in captures, per
            // Microsoft's docs), so the affinity is read back too.
            int[] aff = new int[1];
            if (!u.SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE) || !u.GetWindowDisplayAffinity(hwnd, aff)
                    || aff[0] != WDA_EXCLUDEFROMCAPTURE) {
                fail("SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE) failed (needs Windows 10 2004 or newer)", null);
                return;
            }
            LOGGER.info("[StreamSafe] overlay window created, excluded from capture");
        }
        Win32.Gdi32 gdi = Win32.gdi32();
        if (dib == 0L || dibW != dw || dibH != dh) {
            if (memDc == 0L) {
                memDc = gdi.CreateCompatibleDC(0L);
            }
            long[] bits = new long[1];
            int[] bmi = {40, dw, -dh, 1 | (32 << 16), 0, 0, 0, 0, 0, 0, 0};
            long next = gdi.CreateDIBSection(memDc, bmi, 0, bits, 0L, 0);
            if (next == 0L || bits[0] == 0L) {
                fail("CreateDIBSection failed", null);
                return;
            }
            gdi.SelectObject(memDc, next);   // select the new one first: a bitmap still selected cannot be deleted
            if (dib != 0L) {
                gdi.DeleteObject(dib);
            }
            dib = next;
            dibBits = bits[0];
            dibW = dw;
            dibH = dh;
        }
        new com.sun.jna.Pointer(dibBits).write(0L, out, 0, dw * dh);
        if (!u.UpdateLayeredWindow(hwnd, 0L, new int[]{sxPos, syPos}, new int[]{dw, dh}, memDc, new int[]{0, 0}, 0,
                BLEND, ULW_ALPHA)) {
            fail("UpdateLayeredWindow failed", null);
            return;
        }
        if (!shown) {
            u.ShowWindow(hwnd, Win32.SW_SHOWNOACTIVATE);
            shown = true;
        }
        RECT[0] = sxPos;
        RECT[1] = syPos;
        RECT[2] = dw;
        RECT[3] = dh;
        FB[0] = x;
        FB[1] = y;
        FB[2] = rw;
        FB[3] = rh;
        lastPixels = px;
        pushes++;
    }

    private static void hide() {
        if (shown && hwnd != 0L && Win32.user32() != null) {
            Win32.user32().ShowWindow(hwnd, Win32.SW_HIDE);
        }
        shown = false;
    }

    /** Frees the window, the bitmap and the GPU objects (world left, switch off, a failure). */
    private static void free() {
        hide();
        Win32.User32 u = Win32.user32();
        Win32.Gdi32 gdi = Win32.gdi32();
        if (hwnd != 0L && u != null) {
            u.DestroyWindow(hwnd);
        }
        hwnd = 0L;
        if (gdi != null) {
            if (dib != 0L) {
                gdi.DeleteObject(dib);
            }
            if (memDc != 0L) {
                gdi.DeleteDC(memDc);
            }
        }
        dib = 0L;
        memDc = 0L;
        dibBits = 0L;
        dibW = 0;
        dibH = 0;
        if (readBuffer != null && !readPending) {
            readBuffer.close();
        }
        readBuffer = null;
        readBufferSize = 0L;
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
        if (renderer != null) {
            renderer.close();
            renderer = null;
            state = null;
        }
        lastPixels = null;
        pixels = null;
        scaledPixels = null;
    }

    private static void fail(String what, Throwable t) {
        if (broken) {
            return;
        }
        broken = true;
        if (t != null) {
            LOGGER.error("[StreamSafe] {}; showing the normal map only", what, t);
        } else {
            LOGGER.error("[StreamSafe] {}; showing the normal map only", what);
        }
        try {
            free();
        } catch (Throwable ignored) {
            // freeing after a failure must never throw into the frame
        }
        say("Stream Safe could not draw the Funny Map window, so only the normal map shows (viewers still see nothing"
                + " more).");
    }

    private static void say(String line) {
        try {
            ModChat.send("Dungeon Map", ModChat.bad(line));
        } catch (Throwable ignored) {
            // chat not ready
        }
    }

    // ---- test hooks ----------------------------------------------------------------------------------------------

    /** {hwnd, x, y, w, h, extended style, display affinity, shown 0/1, pushes, framebuffer x, framebuffer y, owner} or null
     *  while there is no window. */
    public static long[] overlayInfo() {
        Win32.User32 u = Win32.user32();
        if (hwnd == 0L || u == null) {
            return null;
        }
        int[] aff = new int[1];
        u.GetWindowDisplayAffinity(hwnd, aff);
        int[] wr = Win32.windowRect(hwnd);
        long ex = u.GetWindowLongPtrW(hwnd, Win32.GWL_EXSTYLE);
        return new long[]{hwnd, wr == null ? -1 : wr[0], wr == null ? -1 : wr[1], wr == null ? -1 : wr[2],
                wr == null ? -1 : wr[3], ex, aff[0], shown ? 1 : 0, pushes, FB[0], FB[1],
                u.GetWindow(hwnd, Win32.GW_OWNER)};
    }

    /** The last pixels pushed (BGRA premultiplied, top row first) and their {x, y, w, h} in the framebuffer, or null. */
    public static Object[] lastPush() {
        return lastPixels == null ? null : new Object[]{lastPixels.clone(), FB.clone()};
    }

    /** Test: sets the overlay's display affinity (0 = capturable, 0x11 = excluded) to prove what capture sees. */
    public static boolean setAffinityForTest(int affinity) {
        return hwnd != 0L && Win32.user32() != null && Win32.user32().SetWindowDisplayAffinity(hwnd, affinity);
    }

    /** Test: whether a failure switched the overlay off for this session, and clears it. */
    public static boolean resetBrokenForTest() {
        boolean was = broken;
        broken = false;
        saidFullscreen = false;
        return was;
    }
}
