package com.killer560.hub.menuthemes;

import com.killer560.hub.gifplayer.GifDecoder;
import com.killer560.hub.gifplayer.GifTextureUtil;
import com.killer560.hub.gui.MenuTheme;
import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Draws a theme's imported main menu background ({@link ThemeBackgrounds}) in place of its animated scene. Called by
 * {@code MainMenuScenes.draw} for every themed out-of-world background; returns false (the theme's own scene draws) while
 * the theme has none, or until the first frame is ready, or after a file failed to load.
 * <p>Nothing heavy runs on the render thread. A GIF is decoded whole on the decode thread by GIF Player's
 * {@link GifDecoder}, and every frame is converted there into the texture's own pixel order. A video's frames are JPEGs
 * read one at a time on the same thread, the one due next prefetched while the current one shows; the render thread only
 * copies a finished frame into the texture (one bulk memory copy) and uploads it, once per new frame. A slow disk shows the
 * last frame a little longer; nothing waits.
 * <p>The texture is released when the background changes and when a world is joined (the menu background is not drawn
 * in a world).
 */
public final class ThemeBackgroundPlayer {

    private static final Logger LOGGER = ModLog.get("killer560smod-themebg");

    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-themebg-decode");
        t.setDaemon(true);
        return t;
    });

    /** One loaded background: what it is, its frames as they become ready, and its texture. */
    private static final class Loaded {
        final String id;
        final Identifier textureId;
        volatile boolean failed;
        volatile String error;
        // GIF: every frame, converted; video: the frame files and the few decoded ahead.
        volatile int width;
        volatile int height;
        volatile int[][] gifFrames;
        volatile int[] gifDelays;
        volatile long gifTotalMs;
        volatile List<Path> videoFrames;
        volatile int fps;
        final Map<Integer, int[]> decoded = new ConcurrentHashMap<>();
        volatile boolean inFlight;
        DynamicTexture texture;
        int uploadedIndex = -1;
        long startNanos;

        Loaded(String id) {
            this.id = id;
            this.textureId = Identifier.fromNamespaceAndPath("killer560smod",
                    "theme_background_" + Integer.toHexString(id.hashCode()) + "_" + (++generation));
        }

        int frameCount() {
            int[][] g = gifFrames;
            if (g != null) {
                return g.length;
            }
            List<Path> v = videoFrames;
            return v == null ? 0 : v.size();
        }
    }

    private static int generation;
    private static Loaded current;
    private static boolean registered;

    // testkit evidence
    private static volatile int uploads;
    private static volatile int draws;
    private static volatile String lastError;

    private ThemeBackgroundPlayer() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != null && current != null) {
                release();
            }
        });
    }

    /**
     * Draws {@code theme}'s imported background over the whole {@code w} x {@code h} screen, cropped to fill it. Render
     * thread. Returns false when there is nothing (yet) to draw, so the caller draws the theme's own scene.
     */
    public static boolean draw(GuiGraphicsExtractor g, int w, int h, MenuTheme theme) {
        String id = ThemeExtrasConfig.getInstance().getBackground(theme);
        if (id == null || ThemeBackgrounds.NONE.equals(id)) {
            if (current != null) {
                release();
            }
            return false;
        }
        Loaded l = current;
        if (l == null || !l.id.equals(id)) {
            release();
            l = start(id);
            current = l;
        }
        if (l.failed) {
            return false;
        }
        int n = l.frameCount();
        if (n == 0) {
            return false;
        }
        int want = frameAt(l, n);
        int[] px = l.gifFrames != null ? l.gifFrames[want] : l.decoded.get(want);
        if (px != null && want != l.uploadedIndex) {
            upload(l, px);
            l.uploadedIndex = want;
        }
        if (l.videoFrames != null) {
            prefetch(l, want, n);
        }
        if (l.texture == null) {
            return false;
        }
        blitCover(g, l, w, h);
        draws++;
        return true;
    }

    /** The frame due now: GIF by its own delays, video by its frame rate, both looping. */
    private static int frameAt(Loaded l, int n) {
        long ms = (System.nanoTime() - l.startNanos) / 1_000_000L;
        int[] delays = l.gifDelays;
        if (delays != null) {
            long t = l.gifTotalMs <= 0 ? 0 : ms % l.gifTotalMs;
            for (int i = 0; i < delays.length; i++) {
                t -= delays[i];
                if (t < 0) {
                    return i;
                }
            }
            return delays.length - 1;
        }
        return (int) ((ms * Math.max(1, l.fps) / 1000L) % n);
    }

    private static Loaded start(String id) {
        Loaded l = new Loaded(id);
        l.startNanos = System.nanoTime();
        Path file = ThemeBackgrounds.fileOf(id);
        if (file == null) {
            fail(l, "background missing: " + id);
            return l;
        }
        DECODER.execute(() -> {
            try {
                if (id.startsWith(ThemeBackgrounds.GIF_PREFIX)) {
                    loadGif(l, file);
                } else {
                    loadVideo(l, file);
                }
            } catch (Exception | OutOfMemoryError e) {
                fail(l, "could not load " + id + ": " + e.getMessage());
            }
        });
        return l;
    }

    private static void loadGif(Loaded l, Path file) throws IOException {
        GifDecoder.GifImage gif = GifDecoder.decode(file);
        int w = gif.width();
        int h = gif.height();
        int[][] frames = new int[gif.frames().size()][];
        int[] delays = new int[frames.length];
        long total = 0;
        for (int i = 0; i < frames.length; i++) {
            frames[i] = toTexture(gif.frames().get(i).argb(), w, h);
            delays[i] = gif.frames().get(i).delayMs();
            total += delays[i];
        }
        l.width = w;
        l.height = h;
        l.gifDelays = delays;
        l.gifTotalMs = total;
        l.gifFrames = frames; // last: frameCount() reads it
        LOGGER.info("[ThemeBackground] loaded {} ({} frames, {}x{})", l.id, frames.length, w, h);
    }

    private static void loadVideo(Loaded l, Path dir) throws IOException {
        int fps = ThemeBackgrounds.FPS;
        for (String line : Files.readAllLines(dir.resolve(ThemeBackgrounds.INFO_FILE), StandardCharsets.UTF_8)) {
            if (line.startsWith("fps=")) {
                try {
                    fps = Math.max(1, Math.min(60, Integer.parseInt(line.substring(4).trim())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        List<Path> frames;
        try (Stream<Path> s = Files.list(dir)) {
            frames = new ArrayList<>(s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jpg")).sorted().toList());
        }
        if (frames.isEmpty()) {
            throw new IOException("no frames in " + dir.getFileName());
        }
        // The first frame now, so the size is known and the texture can be made as soon as it is drawn.
        int[] first = readFrame(l, frames.get(0));
        l.decoded.put(0, first);
        l.fps = fps;
        l.videoFrames = List.copyOf(frames);
        LOGGER.info("[ThemeBackground] loaded {} ({} frames at {} fps, {}x{})", l.id, frames.size(), fps, l.width, l.height);
    }

    /** Reads one JPEG frame into texture order; the first one read sets the size, a frame of another size is refused. */
    private static int[] readFrame(Loaded l, Path file) throws IOException {
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) {
            throw new IOException("unreadable frame " + file.getFileName());
        }
        int w = img.getWidth();
        int h = img.getHeight();
        if (l.width == 0) {
            l.width = w;
            l.height = h;
        } else if (l.width != w || l.height != h) {
            throw new IOException("frame " + file.getFileName() + " is " + w + "x" + h + ", not " + l.width + "x" + l.height);
        }
        return toTexture(img.getRGB(0, 0, w, h, null, 0, w), w, h);
    }

    /** Asks the decode thread for the frame after {@code want} (or {@code want} itself, if it is not ready). One at a time. */
    private static void prefetch(Loaded l, int want, int n) {
        if (l.inFlight) {
            return;
        }
        int next = l.decoded.containsKey(want) ? (want + 1) % n : want;
        if (l.decoded.containsKey(next)) {
            return;
        }
        // Keep the shown frame, the one being fetched and nothing else: a long video is never held whole.
        l.decoded.keySet().removeIf(k -> k != want && k != l.uploadedIndex);
        l.inFlight = true;
        List<Path> frames = l.videoFrames;
        DECODER.execute(() -> {
            try {
                l.decoded.put(next, readFrame(l, frames.get(next)));
            } catch (Exception | OutOfMemoryError e) {
                fail(l, "could not read a frame of " + l.id + ": " + e.getMessage());
            } finally {
                l.inFlight = false;
            }
        });
    }

    /** ARGB rows top-down into the texture's ABGR, rows bottom-up: exactly what {@link GifTextureUtil#writeFrame} writes. */
    static int[] toTexture(int[] argb, int w, int h) {
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int src = y * w;
            int dst = (h - 1 - y) * w;
            for (int x = 0; x < w; x++) {
                int px = argb[src + x];
                out[dst + x] = (px & 0xFF00FF00) | ((px & 0xFF) << 16) | ((px >>> 16) & 0xFF);
            }
        }
        return out;
    }

    /** Render thread: one bulk copy into the texture's pixels and one upload. */
    private static void upload(Loaded l, int[] px) {
        if (l.texture == null) {
            NativeImage image = new NativeImage(l.width, l.height, false);
            MemoryUtil.memIntBuffer(image.getPointer(), px.length).put(px);
            l.texture = new DynamicTexture(() -> "killer560smod theme background " + l.id, image); // uploads it
            Minecraft.getInstance().getTextureManager().register(l.textureId, l.texture);
        } else {
            NativeImage image = l.texture.getPixels();
            if (image == null || image.getWidth() * image.getHeight() != px.length) {
                return;
            }
            MemoryUtil.memIntBuffer(image.getPointer(), px.length).put(px);
            l.texture.upload();
        }
        uploads++;
    }

    /** Fills the screen keeping the picture's shape: the longer side is cropped, centred. */
    private static void blitCover(GuiGraphicsExtractor g, Loaded l, int w, int h) {
        float pictureAspect = l.width / (float) Math.max(1, l.height);
        float screenAspect = w / (float) Math.max(1, h);
        float u0 = 0f;
        float u1 = 1f;
        float top = 0f;
        float bottom = 1f;
        if (pictureAspect > screenAspect) {
            float keep = screenAspect / pictureAspect;
            u0 = (1f - keep) / 2f;
            u1 = 1f - u0;
        } else if (pictureAspect < screenAspect) {
            float keep = pictureAspect / screenAspect;
            top = (1f - keep) / 2f;
            bottom = 1f - top;
        }
        GifTextureUtil.blit(g, l.texture, 0, 0, w, h, u0, u1, top, bottom);
    }

    private static void fail(Loaded l, String why) {
        l.failed = true;
        l.error = why;
        lastError = why;
        LOGGER.warn("[ThemeBackground] {}", why);
    }

    /** Frees the texture and forgets the loaded background (render thread). */
    public static void release() {
        Loaded l = current;
        current = null;
        if (l != null) {
            l.failed = true; // a decode still running for it is dropped when it lands
            l.decoded.clear();
            if (l.texture != null) {
                Minecraft.getInstance().getTextureManager().release(l.textureId);
                l.texture = null;
            }
        }
    }

    // ---------------------------------------------------------------- testkit evidence

    /** The background loaded now, or null. */
    public static String loadedId() {
        Loaded l = current;
        return l == null ? null : l.id;
    }

    /** "WxH frames" of the loaded background, or "" before it is decoded. */
    public static String loadedShape() {
        Loaded l = current;
        return l == null || l.frameCount() == 0 ? "" : l.width + "x" + l.height + " " + l.frameCount();
    }

    /** The frame index last uploaded, or -1. */
    public static int shownFrame() {
        Loaded l = current;
        return l == null ? -1 : l.uploadedIndex;
    }

    public static int uploads() {
        return uploads;
    }

    public static int draws() {
        return draws;
    }

    public static String lastError() {
        return lastError;
    }
}
