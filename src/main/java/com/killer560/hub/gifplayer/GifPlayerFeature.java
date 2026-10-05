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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
     * frames were downscaled from 500px to 256px / 128 colours to keep the jar small (871 KB).
     */
    public static final String BUILTIN_NAME = "cute-anime-cat-girl-dancing-qb6oo9cgljjixtqf.gif";
    private static final String BUILTIN_RESOURCE = "/assets/killer560smod/gifs/dancing-catgirl.gif";
    /** His own HUD scale for it in the Dungeons instance (0.4 of 256px, about 102 GUI px). */
    private static final float BUILTIN_DEFAULT_SCALE = 0.4f;
    /** His own position: 18px down, about 11px in from the right edge. */
    private static final int BUILTIN_DEFAULT_Y = 18;
    private static final int BUILTIN_RIGHT_MARGIN = 11;
    private static GifDecoder.GifImage builtinDecoded;

    /** Filename -> its loaded/playing state. Only present here while enabled and successfully decoded. */
    private static final Map<String, LoadedGif> loaded = new LinkedHashMap<>();

    // register() runs during Fabric client init, before RenderSystem's GPU device exists yet - a
    // real crash seen in the field ("Can't getDevice() before it was initialized"). The first load
    // is deferred to the first actual HUD render call instead, by which point it's always ready.
    private static boolean initialLoadDone = false;

    private static final class LoadedGif {
        final String elementId;
        GifDecoder.GifImage gif;
        DynamicTexture texture;
        int frameIndex;
        long lastFrameChangeAtMs;
        int displayWidth = 128;
        int displayHeight = 128;

        LoadedGif(String elementId) {
            this.elementId = elementId;
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

    /** Re-scans the folder for every .gif and syncs {@link #loaded} to match which ones are
     *  currently enabled, plus every companion audio file (see {@link GifAudioFeature}). */
    public static void reload() {
        GifAudioFeature.reload();

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
        for (String loadedName : List.copyOf(loaded.keySet())) {
            if (!foundNames.contains(loadedName) && !BUILTIN_NAME.equals(loadedName)) {
                unload(loadedName);
            }
        }

        GifPlayerConfig cfg = GifPlayerConfig.getInstance();
        // The built-in only plays when no folder file of the same name exists - that one is loaded below instead.
        if (!foundNames.contains(BUILTIN_NAME)) {
            if (cfg.isGifFileEnabled(BUILTIN_NAME)) {
                try {
                    if (builtinDecoded == null) {
                        try (java.io.InputStream in = GifPlayerFeature.class.getResourceAsStream(BUILTIN_RESOURCE)) {
                            if (in == null) {
                                throw new IOException("missing resource " + BUILTIN_RESOURCE);
                            }
                            builtinDecoded = GifDecoder.decode(in, BUILTIN_RESOURCE);
                        }
                    }
                    applyGif(BUILTIN_NAME, builtinDecoded);
                } catch (Exception e) {
                    LOGGER.error("Failed to load the built-in GIF", e);
                    unload(BUILTIN_NAME);
                }
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
            try {
                GifDecoder.GifImage decoded = GifDecoder.decode(file);
                applyGif(name, decoded);
                LOGGER.info("Loaded GIF: {} ({} frames, {}x{})", name, decoded.frames().size(),
                        decoded.width(), decoded.height());
            } catch (Exception e) {
                LOGGER.error("Failed to load GIF: {}", name, e);
                unload(name);
            }
        }
    }

    private static void unload(String name) {
        LoadedGif existing = loaded.remove(name);
        if (existing != null) {
            if (existing.texture != null) {
                existing.texture.close();
            }
            HudElementRegistry.unregister(existing.elementId);
        }
    }

    private static void applyGif(String name, GifDecoder.GifImage decoded) {
        unload(name);

        LoadedGif entry = new LoadedGif(elementId(name));
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
        Minecraft.getInstance().getTextureManager().register(
                Identifier.fromNamespaceAndPath("killer560smod", "gif_player_" + Integer.toHexString(name.hashCode())),
                entry.texture);

        loaded.put(name, entry);
        registerHudElement(name, entry);
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

    /** Called every frame from {@link com.killer560.hub.gifplayer.mixin.GifPlayerGuiMixin}. */
    public static void renderOverlay(GuiGraphicsExtractor graphics) {
        if (!initialLoadDone) {
            initialLoadDone = true;
            reload();
        }
        if (!GifPlayerConfig.getInstance().isEnabled()) {
            return;
        }
        for (Map.Entry<String, LoadedGif> mapEntry : loaded.entrySet()) {
            LoadedGif entry = mapEntry.getValue();
            advanceFrame(entry);
            // byId, not a Stream: this loop runs for every loaded GIF every frame (2026-09-20, FPS pass).
            HudElement element = HudElementRegistry.byId(entry.elementId);
            if (element == null) {
                continue;
            }
            int[] pos = HudElementRegistry.resolvePosition(element);
            float scale = HudElementRegistry.resolveScale(element);
            // The element's render() just forwards to drawFrame, so it is also the HUD editor's preview -
            // this in-game loop is the call that means the GIF was really on screen.
            HudSeen.markDrawn(entry.elementId);
            graphics.pose().pushMatrix();
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            drawFrame(entry, graphics, 0, 0);
            graphics.pose().popMatrix();
        }
    }

    private static void advanceFrame(LoadedGif entry) {
        List<GifDecoder.Frame> frames = entry.gif.frames();
        if (frames.size() <= 1) {
            return;
        }
        float speed = Math.max(0.1f, GifPlayerConfig.getInstance().getSpeedMultiplier());
        long now = System.currentTimeMillis();
        int guard = 0;
        while (guard++ < frames.size()) {
            long effectiveDelay = Math.round(frames.get(entry.frameIndex).delayMs() / speed);
            if (now - entry.lastFrameChangeAtMs < effectiveDelay) {
                break;
            }
            entry.lastFrameChangeAtMs += effectiveDelay;
            entry.frameIndex = (entry.frameIndex + 1) % frames.size();
            GifTextureUtil.writeFrame(entry.texture.getPixels(), frames.get(entry.frameIndex).argb());
            entry.texture.upload();
        }
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
        return isBuiltinLoaded(name) ? "Dancing Cat Girl (built-in)" : name;
    }

    public static List<String> playingFileNames() {
        return List.copyOf(loaded.keySet());
    }

    private GifPlayerFeature() {
    }
}
