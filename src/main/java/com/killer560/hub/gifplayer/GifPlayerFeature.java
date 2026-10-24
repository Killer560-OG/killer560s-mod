package com.killer560.hub.gifplayer;

import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudSeen;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads every .gif in the killer560smod-gifs folder that's toggled on and loops each of them on
 * screen at once, independently, as its own draggable/resizable HUD overlay (reusing the same
 * HudElement system the RNG Meter overlay uses for position/scale - each gif gets its own element id
 * so they can each be positioned separately in the HUD editor). Each loaded gif keeps one
 * {@link DynamicTexture} for its whole animation - a frame advance overwrites its
 * {@link NativeImage}'s pixels in place (via the ABGR-packed setPixelABGR, verified against the real
 * 26.1.2 jar) and re-uploads, rather than allocating a new texture per frame.
 */
public final class GifPlayerFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-gifplayer");
    private static final int MAX_DISPLAY_SIZE = 256;

    private static final Path GIF_FOLDER =
            ModPaths.config("killer560smod-gifs");

    /**
     * The GIF every install ships with (killer560, 2026-10-04: "take my gif for the dancing catgirl on my dungeons
     * instance and make that show by default on every mod as a gif they have preinstalled"). It is read from the jar,
     * not copied into the folder, so nothing on disk is ever written or overwritten. Its key is the filename of his
     * own copy, so the HUD position and scale he already saved for that file apply to it, and a folder file of the
     * same name takes its place rather than showing twice. It is toggled like any folder file (absent = on), and its
     * frames were downscaled from 500px to 256px / 128 colors to keep the jar small (871 KB).
     */
    public static final String BUILTIN_NAME = "cute-anime-cat-girl-dancing-qb6oo9cgljjixtqf.gif";
    private static final String BUILTIN_RESOURCE = "/assets/killer560smod/gifs/dancing-catgirl.gif";
    /** His own HUD scale for it in the Dungeons instance (0.4 of 256px, about 102 GUI px). */
    private static final float BUILTIN_DEFAULT_SCALE = 0.4f;
    /** His own position: 18px down, about 11px in from the right edge. */
    private static final int BUILTIN_DEFAULT_Y = 18;
    private static final int BUILTIN_RIGHT_MARGIN = 11;
    private static volatile GifDecoder.GifImage builtinDecoded;

    /** Filename -> its loaded/playing state. Only present here while enabled and successfully decoded. */
    private static final Map<String, LoadedGif> loaded = new LinkedHashMap<>();

    // register() runs during Fabric client init, before RenderSystem's GPU device exists yet - a
    // real crash seen in the field ("Can't getDevice() before it was initialized"). The first load
    // is deferred to the first actual HUD render call instead, by which point it's always ready.
    private static boolean audioInitDone = false;
    /** The GIFs are not decoded until the feature first draws (switch on, Skyblock gate open) or reload() is called. */
    private static boolean gifsRequested = false;

    /**
     * Decoding runs here, never on the render thread (a long GIF took a second or more per toggle click). The result
     * is a plain {@link GifDecoder.GifImage}; the texture and HUD element are made in {@link #drainReady} on the
     * render thread.
     */
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-gif-decode");
        t.setDaemon(true);
        return t;
    });

    private record Decoded(String name, long stamp, GifDecoder.GifImage gif) {
    }

    private static final ConcurrentLinkedQueue<Decoded> ready = new ConcurrentLinkedQueue<>();
    /** Name -> stamp of the decode in flight (render thread only). A result that does not match is stale and dropped. */
    private static final Map<String, Long> pending = new HashMap<>();

    private static final class LoadedGif {
        final String elementId;
        final Identifier textureId;
        final long stamp;
        GifDecoder.GifImage gif;
        DynamicTexture texture;
        int frameIndex;
        long lastFrameChangeAtMs;
        int displayWidth = 128;
        int displayHeight = 128;

        LoadedGif(String elementId, Identifier textureId, long stamp) {
            this.elementId = elementId;
            this.textureId = textureId;
            this.stamp = stamp;
        }
    }

    public static void register() {
        try {
            Files.createDirectories(GIF_FOLDER);
        } catch (IOException e) {
            LOGGER.error("Failed to create GIF folder", e);
        }
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("GifPlayerFeature",
                client -> GifAudioFeature.tickSkyblockGate()));
    }

    private static String elementId(String filename) {
        return "gif_player_" + filename;
    }

    /** Size and modification time of a file, so an unchanged one is not decoded or converted again. */
    static long fileStamp(Path file) {
        try {
            return Files.size(file) * 31L + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    /** Re-scans the folder for every .gif and syncs {@link #loaded} to match which ones are
     *  currently enabled, plus every companion audio file (see {@link GifAudioFeature}). A file that is already
     *  loaded and unchanged is left alone; the rest decode on a background thread and appear when done. */
    public static void reload() {
        GifAudioFeature.reload();
        loadGifs();
    }

    private static void loadGifs() {
        gifsRequested = true;
        List<Path> found;
        try (Stream<Path> stream = Files.list(GIF_FOLDER)) {
            found = stream.filter(Files::isRegularFile)
                    .filter(GifDecoder::isGif)
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOGGER.error("Failed to list GIF folder", e);
            return;
        }

        Set<String> foundNames = found.stream().map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        Set<String> known = new HashSet<>(loaded.keySet());
        known.addAll(pending.keySet());
        for (String knownName : known) {
            if (!foundNames.contains(knownName) && !BUILTIN_NAME.equals(knownName)) {
                unload(knownName);
            }
        }

        GifPlayerConfig cfg = GifPlayerConfig.getInstance();
        // The built-in only plays when no folder file of the same name exists - that one is loaded below instead.
        if (!foundNames.contains(BUILTIN_NAME)) {
            if (cfg.isGifFileEnabled(BUILTIN_NAME)) {
                request(BUILTIN_NAME, 0L, null);
            } else {
                unload(BUILTIN_NAME);
            }
        }
        for (Path file : found) {
            String name = file.getFileName().toString();
            if (!cfg.isGifFileEnabled(name)) {
                unload(name);
                continue;
            }
            request(name, fileStamp(file), file);
        }
    }

    /** Queues a decode unless {@code name} is already loaded (or being decoded) from the same file. {@code file} null = the built-in. */
    private static void request(String name, long stamp, Path file) {
        LoadedGif current = loaded.get(name);
        if (current != null && current.stamp == stamp) {
            pending.remove(name);
            return;
        }
        Long inFlight = pending.get(name);
        if (inFlight != null && inFlight == stamp) {
            return;
        }
        pending.put(name, stamp);
        DECODER.execute(() -> {
            GifDecoder.GifImage image = null;
            try {
                image = file == null ? decodeBuiltin() : GifDecoder.decode(file);
            } catch (Exception | OutOfMemoryError e) {
                LOGGER.error("Failed to load GIF: {}", name, e);
            }
            ready.add(new Decoded(name, stamp, image));
        });
    }

    /** Render thread: turns finished decodes into textures and HUD elements. */
    private static void drainReady() {
        Decoded d;
        while ((d = ready.poll()) != null) {
            Long inFlight = pending.get(d.name());
            if (inFlight == null || inFlight != d.stamp()) {
                continue;
            }
            pending.remove(d.name());
            if (d.gif() == null) {
                unload(d.name());
                continue;
            }
            try {
                applyGif(d.name(), d.gif(), d.stamp());
                LOGGER.info("Loaded GIF: {} ({} frames, {}x{})", d.name(), d.gif().frames().size(),
                        d.gif().width(), d.gif().height());
            } catch (Exception e) {
                LOGGER.error("Failed to load GIF: {}", d.name(), e);
                unload(d.name());
            }
        }
    }

    private static void unload(String name) {
        pending.remove(name);
        dropLoaded(name);
    }

    private static void dropLoaded(String name) {
        LoadedGif existing = loaded.remove(name);
        if (existing != null) {
            if (existing.texture != null) {
                // release closes it and frees the id; closing it alone left a dead texture registered under the id.
                Minecraft.getInstance().getTextureManager().release(existing.textureId);
            }
            HudElementRegistry.unregister(existing.elementId);
        }
    }

    private static void applyGif(String name, GifDecoder.GifImage decoded, long stamp) {
        dropLoaded(name);

        LoadedGif entry = new LoadedGif(elementId(name),
                Identifier.fromNamespaceAndPath("killer560smod", "gif_player_" + Integer.toHexString(name.hashCode())), stamp);
        entry.gif = decoded;
        entry.frameIndex = 0;
        entry.lastFrameChangeAtMs = System.currentTimeMillis();

        double scale = Math.min(1.0, MAX_DISPLAY_SIZE / (double) Math.max(decoded.width(), decoded.height()));
        entry.displayWidth = Math.max(1, (int) Math.round(decoded.width() * scale));
        entry.displayHeight = Math.max(1, (int) Math.round(decoded.height() * scale));

        NativeImage image = new NativeImage(decoded.width(), decoded.height(), false);
        GifTextureUtil.writeFrame(image, decoded.frames().get(0).argb());
        // DynamicTexture's own (Supplier, NativeImage) constructor already creates the GPU texture
        // and calls upload() internally (confirmed via javap) - no need to upload again here.
        entry.texture = new DynamicTexture(() -> "killer560smod gif player: " + name, image);
        Minecraft.getInstance().getTextureManager().register(entry.textureId, entry.texture);

        loaded.put(name, entry);
        registerHudElement(name, entry);
    }

    /** The built-in GIF from the jar, decoded once and kept (DVD shares it through {@link #decodeListed}). */
    private static synchronized GifDecoder.GifImage decodeBuiltin() throws IOException {
        if (builtinDecoded == null) {
            try (java.io.InputStream in = GifPlayerFeature.class.getResourceAsStream(BUILTIN_RESOURCE)) {
                if (in == null) {
                    throw new IOException("missing resource " + BUILTIN_RESOURCE);
                }
                builtinDecoded = GifDecoder.decode(in, BUILTIN_RESOURCE);
            }
        }
        return builtinDecoded;
    }

    private static void registerHudElement(String name, LoadedGif entry) {
        // Staggered default so multiple gifs don't all spawn stacked in the exact same corner.
        int index = loaded.size();
        boolean builtin = isBuiltinLoaded(name);
        HudElementRegistry.register(new HudElement() {
            @Override
            public String id() {
                return entry.elementId;
            }

            @Override
            public String displayName() {
                return "GIF: " + GifPlayerFeature.displayName(name);
            }

            @Override
            public int defaultX() {
                if (builtin) {
                    var window = Minecraft.getInstance().getWindow();
                    if (window != null) {
                        int scaledW = Math.round(width() * HudElementRegistry.resolveScale(this));
                        return window.getGuiScaledWidth() - scaledW - BUILTIN_RIGHT_MARGIN;
                    }
                }
                return 20 + (index * 20);
            }

            @Override
            public int defaultY() {
                return builtin ? BUILTIN_DEFAULT_Y : 20 + (index * 20);
            }

            @Override
            public float defaultScale() {
                return builtin ? BUILTIN_DEFAULT_SCALE : 1.0f;
            }

            @Override
            public int width() {
                return entry.displayWidth;
            }

            @Override
            public int height() {
                return entry.displayHeight;
            }

            @Override
            public boolean isEnabledInSettings() {
                return GifPlayerConfig.getInstance().isEnabled();
            }

            @Override
            public void render(GuiGraphicsExtractor graphics, int x, int y) {
                drawFrame(entry, graphics, x, y);
            }
        });
    }

    /** Called every frame from {@link com.killer560.hub.hud.GuiOverlays}. */
    public static void renderOverlay(GuiGraphicsExtractor graphics) {
        if (!audioInitDone) {
            audioInitDone = true;
            GifAudioFeature.reload();
        }
        if (!ready.isEmpty()) {
            drainReady();
        }
        // killer560, 2026-10-11: "make it so gifs and dvds and whatnot dont show in the storage or any inventory".
        // The switch first: it is off for nearly everyone, and the container test reads the screen (2026-10-15).
        if (!GifPlayerConfig.getInstance().isEnabled() || com.killer560.hub.hud.GuiOverlays.containerOpen()) {
            return;
        }
        if (!gifsRequested) {
            loadGifs();
        }
        if (loaded.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        float speed = Math.max(0.1f, GifPlayerConfig.getInstance().getSpeedMultiplier());
        for (Map.Entry<String, LoadedGif> mapEntry : loaded.entrySet()) {
            LoadedGif entry = mapEntry.getValue();
            advanceFrame(entry, now, speed);
            // byId, not a Stream: this loop runs for every loaded GIF every frame (2026-09-20, FPS pass).
            HudElement element = HudElementRegistry.byId(entry.elementId);
            if (element == null) {
                continue;
            }
            int[] pos = HudElementRegistry.resolvePosition(element);
            float scale = HudElementRegistry.resolveScale(element);
            // The element's render() just forward to drawFrame, so it is also the HUD editor's preview -
            // this in-game loop is the call that means the GIF was really on screen.
            HudSeen.markDrawn(entry.elementId);
            graphics.pose().pushMatrix();
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            drawFrame(entry, graphics, 0, 0);
            graphics.pose().popMatrix();
        }
    }

    /** Steps to the frame that is due now and uploads it once, however many frames were skipped. */
    private static void advanceFrame(LoadedGif entry, long now, float speed) {
        List<GifDecoder.Frame> frames = entry.gif.frames();
        int count = frames.size();
        if (count <= 1) {
            return;
        }
        int index = entry.frameIndex;
        int steps = 0;
        while (steps < count) {
            long effectiveDelay = Math.round(frames.get(index).delayMs() / speed);
            if (now - entry.lastFrameChangeAtMs < effectiveDelay) {
                break;
            }
            entry.lastFrameChangeAtMs += effectiveDelay;
            index = (index + 1) % count;
            steps++;
        }
        if (steps == 0) {
            return;
        }
        if (steps == count) {
            // A whole loop behind (the GIF was not drawn while a container was open): restart the clock instead of
            // racing through the next frames to catch up.
            entry.lastFrameChangeAtMs = now;
        }
        entry.frameIndex = index;
        // Every skipped frame used to be written and uploaded in turn - up to a full loop's worth after closing a chest.
        GifTextureUtil.writeFrame(entry.texture.getPixels(), frames.get(index).argb());
        entry.texture.upload();
    }

    private static void drawFrame(LoadedGif entry, GuiGraphicsExtractor graphics, int x, int y) {
        if (entry.texture == null) {
            return;
        }
        GifTextureUtil.blit(graphics, entry.texture, x, y, entry.displayWidth, entry.displayHeight);
    }

    public static Path folder() {
        return GIF_FOLDER;
    }

    /** Every .gif filename currently in the folder, regardless of its enabled state - for the tab's
     *  per-file toggle list. */
    public static List<String> discoverFileNames() {
        try (Stream<Path> stream = Files.list(GIF_FOLDER)) {
            return stream.filter(Files::isRegularFile)
                    .filter(GifDecoder::isGif)
                    .map(p -> p.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    /** What the GIF tab lists: every folder file, plus the built-in when no folder file has its name. */
    public static List<String> listedFileNames() {
        List<String> names = new java.util.ArrayList<>(discoverFileNames());
        if (!names.contains(BUILTIN_NAME)) {
            names.add(0, BUILTIN_NAME);
        }
        return names;
    }

    /** True when {@code name} is (or would be) served from the jar rather than the folder. */
    public static boolean isBuiltinLoaded(String name) {
        return BUILTIN_NAME.equals(name) && !Files.isRegularFile(GIF_FOLDER.resolve(name));
    }

    /** Label for a GIF in the tab and HUD editor. */
    public static String displayName(String name) {
        return isBuiltinLoaded(name) ? "Dancing Cat Girl" : name;
    }

    /**
     * Decodes a GIF by the name {@link #listedFileNames()} gives it: the built-in from the jar when no folder file has
     * its name, otherwise the folder file. DVD uses this so the built-in Dancing Cat Girl can bounce too (killer560,
     * 2026-10-11: "it doesn't show up for dvd").
     */
    public static GifDecoder.GifImage decodeListed(String name) throws IOException {
        if (isBuiltinLoaded(name)) {
            return decodeBuiltin();
        }
        return GifDecoder.decode(GIF_FOLDER.resolve(name));
    }

    public static List<String> playingFileNames() {
        return List.copyOf(loaded.keySet());
    }

    private GifPlayerFeature() {
    }
}
