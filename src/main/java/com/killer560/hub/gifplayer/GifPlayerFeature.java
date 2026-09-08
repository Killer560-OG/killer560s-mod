package com.killer560.hub.gifplayer;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-gifplayer");
    private static final int MAX_DISPLAY_SIZE = 256;

    private static final Path GIF_FOLDER =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-gifs");

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
            if (!foundNames.contains(loadedName)) {
                unload(loadedName);
            }
        }

        GifPlayerConfig cfg = GifPlayerConfig.getInstance();
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
        HudElementRegistry.register(new HudElement() {
            @Override
            public String id() {
                return entry.elementId;
            }

            @Override
            public String displayName() {
                return "GIF: " + name;
            }

            @Override
            public int defaultX() {
                return 20 + (index * 20);
            }

            @Override
            public int defaultY() {
                return 20 + (index * 20);
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
            HudElement element = HudElementRegistry.all().stream()
                    .filter(e -> e.id().equals(entry.elementId)).findFirst().orElse(null);
            if (element == null) {
                continue;
            }
            int[] pos = HudElementRegistry.resolvePosition(element);
            float scale = HudElementRegistry.resolveScale(element);
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

    public static List<String> playingFileNames() {
        return List.copyOf(loaded.keySet());
    }

    private GifPlayerFeature() {
    }
}
