package com.killer560.hub.imagepreview;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.copychat.mixin.ChatComponentAccessor;
import com.killer560.hub.gifplayer.GifTextureUtil;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Image Preview: with the chat open, hovering a link to a picture (imgur in all its forms, Discord's CDN, any direct
 * .png/.jpg/.gif/.webp/.bmp, and with Any Host a page's og:image) shows the picture beside the cursor. An imgur album
 * shows "1/N" and the mouse wheel steps through it; GIFs play. A middle click opens a bigger viewer
 * ({@link ImageViewerScreen}). A left click is untouched, so vanilla's open-link prompt still runs.
 *
 * <p>The link under the cursor is vanilla's own clickable style when the line carries an open-URL click event, and
 * otherwise a URL written as plain text (Hypixel sends players' links unclickable), found in the whole message so a
 * link wrapped over two chat rows still counts. Fetching and decoding run on two worker threads ({@link ImageFetcher},
 * {@link ImageDecoding}); the render thread only uploads a finished picture and draws it. Textures not drawn for
 * {@link #TEXTURE_IDLE_MS} are released; decoded pictures are kept up to {@link #MAX_KEPT_BYTES}, then dropped
 * oldest first (the disk cache has them).
 */
public final class ImagePreviewFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-imagepreview");

    static final long TEXTURE_IDLE_MS = 3000;
    static final long MAX_KEPT_BYTES = 160L * 1024 * 1024;
    private static final int MAX_ENTRIES = 64;
    /** The link under a still mouse is looked up again this often (new chat lines, scrolling). */
    private static final long REHIT_MS = 250;

    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "killer560smod-imagepreview");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final AtomicInteger TEXTURE_SERIAL = new AtomicInteger();

    enum State { RESOLVING, READY, IGNORED, FAILED }

    /** One picture of a link (an album has several). Written by a worker, read by the render thread. */
    static final class Pic {
        final String url;
        volatile ImageDecoding.Decoded decoded;
        volatile String error;
        volatile boolean ignored;
        volatile boolean started;
        DynamicTexture texture;
        Identifier textureId;
        int frame;
        long frameAt;
        long lastShownMs;

        Pic(String url) {
            this.url = url;
        }
    }

    /** One hovered link. */
    static final class Entry {
        final String link;
        volatile State state = State.RESOLVING;
        volatile String error;
        volatile List<Pic> pics = List.of();
        int index;
        long lastUsedMs;

        Entry(String link) {
            this.link = link;
        }

        Pic current() {
            List<Pic> p = pics;
            return p.isEmpty() ? null : p.get(Math.floorMod(index, p.size()));
        }
    }

    /** Access-ordered: the eldest is the least recently hovered. Render thread only. */
    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>(16, 0.75f, true);

    // Hover state (render thread).
    private static String hoveredLink;
    private static int lastMouseX = Integer.MIN_VALUE;
    private static int lastMouseY = Integer.MIN_VALUE;
    private static long lastHitMs;

    // Test hooks and measurements.
    /** What the last drawn frame showed: link, box and picture rectangle, frame, caption. Null when nothing drew. */
    public record Drawn(String link, int x, int y, int w, int h, int frame, int frames, int index, int count,
                        int textureW, int textureH, String caption, long atMs) {
    }

    private static volatile Drawn lastDrawn;
    private static volatile long framesDrawn;
    private static volatile long workNanos;
    private static volatile long workFrames;

    private ImagePreviewFeature() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) {
                return;
            }
            ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> {
                try {
                    onExtract(g, mx, my);
                } catch (RuntimeException e) {
                    LOGGER.warn("[ImagePreview] draw failed", e);
                }
            });
            ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, sx, sy) -> !onScroll(sy));
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !onClick(s, event.button()));
            ScreenEvents.remove(screen).register(s -> hoveredLink = null);
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("ImagePreviewFeature.tick", c -> tick()));
    }

    // ------------------------------------------------------------------------------------------------- hovering

    private static void onExtract(GuiGraphicsExtractor g, int mx, int my) {
        ImagePreviewConfig cfg = ImagePreviewConfig.getInstance();
        if (!cfg.isEnabled()) {
            hoveredLink = null;
            return;
        }
        long t0 = System.nanoTime();
        long now = System.currentTimeMillis();
        if (mx != lastMouseX || my != lastMouseY || now - lastHitMs >= REHIT_MS) {
            lastMouseX = mx;
            lastMouseY = my;
            lastHitMs = now;
            hoveredLink = linkAt(mx, my);
        }
        String link = hoveredLink;
        if (link != null) {
            draw(g, link, mx, my, cfg, now);
        }
        workNanos += System.nanoTime() - t0;
        workFrames++;
    }

    /** The link under (mx, my) in the open chat, or null. */
    static String linkAt(int mx, int my) {
        Minecraft client = Minecraft.getInstance();
        ChatComponent chat = McCompat.chat(client);
        int guiHeight = client.getWindow().getGuiScaledHeight();
        // 1. A real link: vanilla's own finder, as ChatScreen.mouseClicked uses it (javap 26.1.2 and 26.2).
        ActiveTextCollector.ClickableStyleFinder finder = new ActiveTextCollector.ClickableStyleFinder(client.font, mx, my);
        chat.captureClickableText(finder, guiHeight, 0, ChatComponent.DisplayMode.FOREGROUND);
        Style style = finder.result();
        if (style != null && style.getClickEvent() instanceof ClickEvent.OpenUrl open) {
            String u = open.uri().toString();
            String lower = u.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return u;
            }
        }
        // 2. A link written as plain text.
        return plainLinkAt(client, chat, guiHeight, mx, my);
    }

    private record Placed(Matrix3x2f pose, int x, int y, FormattedCharSequence text) {
    }

    private static String plainLinkAt(Minecraft client, ChatComponent chat, int guiHeight, int mx, int my) {
        List<GuiMessage.Line> trimmed = ((ChatComponentAccessor) chat).killer560smod$getTrimmedMessages();
        if (trimmed.isEmpty()) {
            return null;
        }
        List<Placed> placed = new ArrayList<>();
        ActiveTextCollector.ClickableStyleFinder recorder =
                new ActiveTextCollector.ClickableStyleFinder(client.font, mx, my) {
                    @Override
                    public void accept(TextAlignment alignment, int x, int y, ActiveTextCollector.Parameters parameters,
                                       FormattedCharSequence text) {
                        placed.add(new Placed(new Matrix3x2f(parameters.pose()), x, y, text));
                    }
                };
        chat.captureClickableText(recorder, guiHeight, 0, ChatComponent.DisplayMode.FOREGROUND);
        Font font = client.font;
        for (Placed p : placed) {
            Vector2f local = new Matrix3x2f(p.pose()).invert().transformPosition(new Vector2f(mx, my));
            if (local.y < p.y() - 1 || local.y >= p.y() + font.lineHeight || local.x < p.x()) {
                continue;
            }
            GuiMessage.Line line = null;
            for (GuiMessage.Line l : trimmed) {
                if (l.content() == p.text()) {
                    line = l;
                    break;
                }
            }
            if (line == null) {
                return null;
            }
            // The character under the cursor: widths summed per code point (the font has no kerning).
            StringBuilder sb = new StringBuilder();
            int[] hitChar = {-1};
            float[] cursor = {p.x()};
            float target = local.x;
            p.text().accept((index, st, cp) -> {
                float w = font.width(FormattedCharSequence.codepoint(cp, st));
                if (hitChar[0] < 0 && target >= cursor[0] && target < cursor[0] + w) {
                    hitChar[0] = sb.length();
                }
                cursor[0] += w;
                sb.appendCodePoint(cp);
                return true;
            });
            if (hitChar[0] < 0) {
                return null;
            }
            String lineText = sb.toString();
            // The whole message, so a link wrapped over two rows is found whole.
            String full = lineText;
            int offset = 0;
            try {
                GuiMessage parent = line.parent();
                List<GuiMessage.Line> rows = new ArrayList<>();
                for (GuiMessage.Line l : trimmed) {
                    if (l.parent() == parent) {
                        rows.add(0, l); // newest first in the list, so this puts the message's rows top to bottom
                    }
                }
                String whole = ChatFormatting.stripFormatting(parent.content().getString());
                if (rows.size() > 1 && whole != null) {
                    int from = 0;
                    int found = -1;
                    boolean ok = true;
                    for (GuiMessage.Line r : rows) {
                        String rt = plain(r.content());
                        String key = rt.strip();
                        int at = key.isEmpty() ? from : whole.indexOf(key, from);
                        if (at < 0) {
                            ok = false;
                            break;
                        }
                        if (r == line) {
                            found = at - (rt.length() - rt.stripLeading().length());
                        }
                        from = at + key.length();
                    }
                    if (ok && found >= 0) {
                        full = whole;
                        offset = found;
                    }
                }
            } catch (RuntimeException ignored) {
                // the single row is still searched
            }
            int at = offset + hitChar[0];
            for (ImageLinks.Found f : ImageLinks.find(full)) {
                if (at >= f.start() && at < f.end()) {
                    return f.url();
                }
            }
            return null;
        }
        return null;
    }

    private static String plain(FormattedCharSequence seq) {
        StringBuilder sb = new StringBuilder();
        seq.accept((i, s, cp) -> {
            sb.appendCodePoint(cp);
            return true;
        });
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------- loading

    /** The entry for {@code link}, starting its resolve the first time it is hovered. Render thread. */
    static Entry entry(String link) {
        Entry e = ENTRIES.get(link);
        if (e == null) {
            e = new Entry(link);
            ENTRIES.put(link, e);
            Entry started = e;
            boolean anyHost = ImagePreviewConfig.getInstance().isAnyHost();
            WORKERS.execute(() -> resolve(started, anyHost));
            trimEntries();
        }
        e.lastUsedMs = System.currentTimeMillis();
        return e;
    }

    private static void resolve(Entry e, boolean anyHost) {
        try {
            ImageLinks.Target t = ImageLinks.classify(e.link, anyHost);
            if (t == null) {
                if (!anyHost && ImageLinks.hasImageExtension(e.link) && ImageLinks.allowedHost(e.link, true)) {
                    // A picture on another site: say why nothing is fetched instead of looking broken.
                    e.error = "Any Host is off (imgur and Discord only)";
                    e.state = State.FAILED;
                } else {
                    e.state = State.IGNORED;
                }
                return;
            }
            List<String> urls = switch (t.kind()) {
                case DIRECT -> List.of(t.url());
                case IMGUR_MEDIA, IMGUR_ALBUM -> ImageFetcher.imgur(t);
                case PAGE -> {
                    String og = ImageFetcher.ogImage(t.url(), anyHost);
                    yield og == null ? List.of() : List.of(og);
                }
            };
            if (urls.isEmpty()) {
                e.state = State.IGNORED;
                return;
            }
            List<Pic> pics = new ArrayList<>(urls.size());
            for (String u : urls) {
                pics.add(new Pic(u));
            }
            pics.get(0).started = true; // loaded just below: the render thread must not start it a second time
            e.pics = List.copyOf(pics);
            e.state = State.READY;
            load(pics.get(0), anyHost);
        } catch (ImageFetcher.NotAnImage n) {
            e.state = State.IGNORED;
        } catch (Exception ex) {
            e.error = ex instanceof ImageFetcher.FetchException ? ex.getMessage() : "Couldn't load";
            e.state = State.FAILED;
            LOGGER.info("[ImagePreview] {}: {}", e.link, ex.toString());
        }
    }

    private static void startLoad(Pic p) {
        if (p.started) {
            return;
        }
        p.started = true;
        boolean anyHost = ImagePreviewConfig.getInstance().isAnyHost();
        WORKERS.execute(() -> load(p, anyHost));
    }

    private static void load(Pic p, boolean anyHost) {
        p.started = true;
        try {
            byte[] bytes = ImageFetcher.picture(p.url, anyHost);
            p.decoded = ImageDecoding.decode(bytes);
        } catch (ImageFetcher.NotAnImage n) {
            p.ignored = true;
        } catch (ImageFetcher.FetchException f) {
            p.error = f.getMessage();
            LOGGER.info("[ImagePreview] {}: {}", p.url, f.getMessage());
        } catch (Throwable t) {
            p.error = "Couldn't load";
            LOGGER.info("[ImagePreview] {}: {}", p.url, t.toString());
        }
    }

    // ------------------------------------------------------------------------------------------------- drawing

    private static final int PAD = 3;
    private static final int BG = 0xE0101010;
    private static final int BORDER = 0xFFFF8C1A;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DIM = 0xFFAAAAAA;

    private static void draw(GuiGraphicsExtractor g, String link, int mx, int my, ImagePreviewConfig cfg, long now) {
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        Entry e = entry(link);
        if (e.state == State.IGNORED) {
            return;
        }
        if (e.state == State.FAILED) {
            box(g, font, mx, my, "Image Preview: " + e.error, link, now);
            return;
        }
        Pic p = e.current();
        if (e.state == State.RESOLVING || p == null) {
            box(g, font, mx, my, "Loading...", link, now);
            return;
        }
        startLoad(p);
        if (p.ignored) {
            return;
        }
        if (p.error != null) {
            box(g, font, mx, my, "Image Preview: " + p.error + caption(e, ""), link, now);
            return;
        }
        ImageDecoding.Decoded d = p.decoded;
        if (d == null) {
            box(g, font, mx, my, "Loading..." + caption(e, ""), link, now);
            return;
        }
        if (p.texture == null && !upload(p, d)) {
            box(g, font, mx, my, "Image Preview: couldn't show it", link, now);
            return;
        }
        p.lastShownMs = now;
        advance(p, d, cfg, now);

        int max = cfg.getMaxPreviewSize();
        int[] size = displaySize(d.sourceWidth(), d.sourceHeight(), max, client.getWindow().getGuiScale());
        String cap = caption(e, "").trim();
        int capH = cap.isEmpty() ? 0 : font.lineHeight + 2;
        int boxW = Math.max(size[0], cap.isEmpty() ? 0 : font.width(cap)) + PAD * 2;
        int boxH = size[1] + capH + PAD * 2;
        int[] at = place(mx, my, boxW, boxH);
        frame(g, at[0], at[1], boxW, boxH);
        int ix = at[0] + (boxW - size[0]) / 2;
        int iy = at[1] + PAD;
        GifTextureUtil.blit(g, p.texture, ix, iy, size[0], size[1]);
        if (!cap.isEmpty()) {
            g.text(font, cap, at[0] + (boxW - font.width(cap)) / 2, iy + size[1] + 2, DIM, true);
        }
        framesDrawn++;
        lastDrawn = new Drawn(link, ix, iy, size[0], size[1], p.frame, d.frames().size(), Math.floorMod(e.index,
                Math.max(1, e.pics.size())), e.pics.size(), d.width(), d.height(), cap, now);
    }

    /** " 1/5" for an album, "" otherwise. */
    private static String caption(Entry e, String prefix) {
        int n = e.pics.size();
        return n > 1 ? prefix + " " + (Math.floorMod(e.index, n) + 1) + "/" + n : "";
    }

    /** The picture's size on screen in GUI pixels: one picture pixel per screen pixel, shrunk to fit {@code max}, and a
     *  small picture (an emoji) grown to at least 48 GUI pixels. */
    static int[] displaySize(int w, int h, int max, double guiScale) {
        double gs = Math.max(1.0, guiScale);
        double dw = w / gs;
        double dh = h / gs;
        double longest = Math.max(dw, dh);
        double f = Math.min(1.0, max / longest);
        if (longest * f < 48) {
            f = Math.min(48 / longest, max / longest);
        }
        return new int[]{Math.max(1, (int) Math.round(dw * f)), Math.max(1, (int) Math.round(dh * f))};
    }

    /** Above and to the right of the cursor, flipped or pushed to stay on screen. */
    private static int[] place(int mx, int my, int w, int h) {
        Minecraft client = Minecraft.getInstance();
        int sw = client.getWindow().getGuiScaledWidth();
        int sh = client.getWindow().getGuiScaledHeight();
        int x = mx + 12;
        if (x + w > sw - 2) {
            x = mx - 12 - w;
        }
        x = Math.max(2, Math.min(x, sw - w - 2));
        int y = my - 12 - h;
        if (y < 2) {
            y = my + 12;
        }
        y = Math.max(2, Math.min(y, sh - h - 2));
        return new int[]{x, y};
    }

    private static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, BG);
        g.fill(x, y, x + w, y + 1, BORDER);
        g.fill(x, y + h - 1, x + w, y + h, BORDER);
        g.fill(x, y + 1, x + 1, y + h - 1, BORDER);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, BORDER);
    }

    private static void box(GuiGraphicsExtractor g, Font font, int mx, int my, String text, String link, long now) {
        int w = font.width(text) + PAD * 2 + 2;
        int h = font.lineHeight + PAD * 2;
        int[] at = place(mx, my, w, h);
        frame(g, at[0], at[1], w, h);
        g.text(font, text, at[0] + PAD + 1, at[1] + PAD + 1, text.startsWith("Loading") ? DIM : TEXT, true);
        lastDrawn = new Drawn(link, at[0], at[1], w, h, -1, 0, 0, 0, 0, 0, text, now);
    }

    private static boolean upload(Pic p, ImageDecoding.Decoded d) {
        try {
            NativeImage image = new NativeImage(d.width(), d.height(), false);
            GifTextureUtil.writeFrame(image, d.frames().get(0));
            p.textureId = Identifier.fromNamespaceAndPath("killer560smod",
                    "imagepreview/" + Integer.toHexString(TEXTURE_SERIAL.incrementAndGet()));
            // The (Supplier, NativeImage) constructor creates and uploads the GPU texture (see GifPlayerFeature).
            p.texture = new DynamicTexture(() -> "killer560smod image preview", image);
            Minecraft.getInstance().getTextureManager().register(p.textureId, p.texture);
            p.frame = 0;
            p.frameAt = System.currentTimeMillis();
            return true;
        } catch (RuntimeException ex) {
            LOGGER.warn("[ImagePreview] texture failed for {}", p.url, ex);
            p.error = "Couldn't show it";
            return false;
        }
    }

    private static void advance(Pic p, ImageDecoding.Decoded d, ImagePreviewConfig cfg, long now) {
        int n = d.frames().size();
        if (n < 2 || !cfg.isPlayGifs()) {
            return;
        }
        int index = p.frame;
        int steps = 0;
        while (now - p.frameAt >= d.delaysMs()[index] && steps < n) {
            p.frameAt += d.delaysMs()[index];
            index = (index + 1) % n;
            steps++;
        }
        if (steps == 0) {
            return;
        }
        if (steps == n) {
            p.frameAt = now; // a whole loop behind (not shown for a while): restart the clock
        }
        p.frame = index;
        GifTextureUtil.writeFrame(p.texture.getPixels(), d.frames().get(index));
        p.texture.upload();
    }

    private static void releaseTexture(Pic p) {
        if (p.texture != null) {
            // release closes it and frees the id (see GifPlayerFeature.dropLoaded).
            Minecraft.getInstance().getTextureManager().release(p.textureId);
            p.texture = null;
            p.textureId = null;
        }
    }

    // ------------------------------------------------------------------------------------------------- input

    /** Mouse wheel over an album: the next or previous picture. True when consumed. */
    private static boolean onScroll(double amount) {
        if (!ImagePreviewConfig.getInstance().isEnabled() || hoveredLink == null || amount == 0) {
            return false;
        }
        Entry e = ENTRIES.get(hoveredLink);
        if (e == null || e.pics.size() < 2) {
            return false;
        }
        step(e, amount > 0 ? -1 : 1);
        return true;
    }

    static void step(Entry e, int dir) {
        e.index = Math.floorMod(e.index + dir, e.pics.size());
    }

    /** A middle click on a previewed link opens the viewer. True when consumed. */
    private static boolean onClick(Screen screen, int button) {
        if (button != 2 || !ImagePreviewConfig.getInstance().isEnabled() || hoveredLink == null) {
            return false;
        }
        Entry e = ENTRIES.get(hoveredLink);
        if (e == null || e.state != State.READY) {
            return false;
        }
        McCompat.setScreen(Minecraft.getInstance(), new ImageViewerScreen(screen, e));
        return true;
    }

    // ------------------------------------------------------------------------------------------------- upkeep

    private static void tick() {
        long now = System.currentTimeMillis();
        for (Entry e : ENTRIES.values()) {
            for (Pic p : e.pics) {
                if (p.texture != null && now - p.lastShownMs > TEXTURE_IDLE_MS) {
                    releaseTexture(p);
                }
            }
        }
    }

    /** Drops the least recently hovered links past {@link #MAX_ENTRIES} or {@link #MAX_KEPT_BYTES}. */
    private static void trimEntries() {
        long kept = 0;
        for (Entry e : ENTRIES.values()) {
            for (Pic p : e.pics) {
                ImageDecoding.Decoded d = p.decoded;
                if (d != null) {
                    kept += d.bytes();
                }
            }
        }
        Iterator<Map.Entry<String, Entry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext() && (ENTRIES.size() > MAX_ENTRIES || kept > MAX_KEPT_BYTES)) {
            Entry e = it.next().getValue();
            if (e.link.equals(hoveredLink)) {
                continue;
            }
            for (Pic p : e.pics) {
                ImageDecoding.Decoded d = p.decoded;
                if (d != null) {
                    kept -= d.bytes();
                }
                releaseTexture(p);
            }
            it.remove();
        }
    }

    // ------------------------------------------------------------------------------------------------- test hooks

    /** The last frame's preview (null when none drew since {@link #resetForTest}). */
    public static Drawn lastDrawnForTest() {
        return lastDrawn;
    }

    public static void clearLastDrawnForTest() {
        lastDrawn = null;
    }

    public static long framesDrawnForTest() {
        return framesDrawn;
    }

    /** HTTP requests actually sent since start (a cache hit leaves it alone). */
    public static int requestsForTest() {
        return ImageFetcher.REQUESTS.get();
    }

    /** Average nanoseconds of Image Preview work per chat frame, and how many frames that covers. */
    public static long[] workForTest() {
        return new long[]{workFrames == 0 ? 0 : workNanos / workFrames, workFrames};
    }

    public static void resetWorkForTest() {
        workNanos = 0;
        workFrames = 0;
    }

    /** The link the last hover check found, or null. */
    public static String hoveredForTest() {
        return hoveredLink;
    }

    /** The current picture's ARGB at (fx, fy), each 0..1 of the picture, or 0 when none is decoded. */
    public static int sampleForTest(String link, double fx, double fy) {
        Entry e = ENTRIES.get(link);
        Pic p = e == null ? null : e.current();
        ImageDecoding.Decoded d = p == null ? null : p.decoded;
        if (d == null) {
            return 0;
        }
        int x = Math.min(d.width() - 1, (int) (fx * d.width()));
        int y = Math.min(d.height() - 1, (int) (fy * d.height()));
        return d.frames().get(p.frame)[y * d.width() + x];
    }

    /** "state|error|pics|loaded" for {@code link}, or null when it was never hovered. */
    public static String entryStateForTest(String link) {
        Entry e = ENTRIES.get(link);
        if (e == null) {
            return null;
        }
        Pic p = e.current();
        return e.state + "|" + e.error + "|" + e.pics.size() + "|"
                + (p == null ? "-" : p.decoded != null ? "decoded" : p.ignored ? "ignored" : p.error != null ? "error:" + p.error : "loading");
    }

    /** A setting changed (the host rule, above all): forget every link so each is looked at again. Render thread. */
    public static void onSettingsChanged() {
        resetForTest();
    }

    /** Forgets every link and texture (the disk cache stays), and the last-drawn record. Render thread. */
    public static void resetForTest() {
        for (Entry e : ENTRIES.values()) {
            for (Pic p : e.pics) {
                releaseTexture(p);
            }
        }
        ENTRIES.clear();
        hoveredLink = null;
        lastDrawn = null;
        framesDrawn = 0;
        workNanos = 0;
        workFrames = 0;
        lastMouseX = Integer.MIN_VALUE;
    }

    /** Live textures held right now. */
    public static int texturesForTest() {
        int n = 0;
        for (Entry e : ENTRIES.values()) {
            for (Pic p : e.pics) {
                if (p.texture != null) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Empties the disk cache (worker-safe). */
    public static void clearDiskCacheForTest() {
        ImageFetcher.trimCache(0);
    }

    static Pic currentPic(Entry e) {
        return e.current();
    }

    static boolean ensureTexture(Pic p) {
        ImageDecoding.Decoded d = p.decoded;
        return d != null && (p.texture != null || upload(p, d));
    }

    static void tickAnimation(Pic p, long now) {
        ImageDecoding.Decoded d = p.decoded;
        if (d != null && p.texture != null) {
            p.lastShownMs = now;
            advance(p, d, ImagePreviewConfig.getInstance(), now);
        }
    }

    static void requestLoad(Pic p) {
        startLoad(p);
    }
}
