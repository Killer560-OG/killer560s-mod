package com.killer560.hub.scoreboard;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * "Custom Background Image" - SkyHanni's {@code useCustomBackgroundImage} / SkyBlock Custom Scoreboard's
 * {@code CustomScoreboardBackground}: a PNG dropped into {@code config/killer560smod-customscoreboard/background.png}
 * is loaded into a dynamic texture and stretched over the board. The file's modification time is re-checked every
 * 2 seconds, so replacing the image applies without a restart. Must only be used from the render thread.
 */
final class ScoreboardBackgroundImage {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-customscoreboard");
    static final Path FILE = CustomScoreboardConfig.DATA_DIR.resolve("background.png");
    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("killer560smod", "customscoreboard/background");

    private static boolean registered = false;
    private static long loadedModified = Long.MIN_VALUE;
    private static long lastCheckMs = 0L;
    private static int width = 0;
    private static int height = 0;

    private ScoreboardBackgroundImage() {
    }

    /** @return the texture id if a usable image is loaded, else null. */
    static Identifier texture() {
        long now = System.currentTimeMillis();
        if (now - lastCheckMs > 2000L) {
            lastCheckMs = now;
            refresh();
        }
        return registered ? TEXTURE_ID : null;
    }

    /** Forces a re-read on the next frame (the "Reload Image" button). */
    static void reload() {
        loadedModified = Long.MIN_VALUE;
        lastCheckMs = 0L;
    }

    private static void refresh() {
        long modified;
        try {
            if (!Files.isRegularFile(FILE)) {
                release();
                return;
            }
            modified = Files.getLastModifiedTime(FILE).toMillis();
        } catch (Exception e) {
            release();
            return;
        }
        if (modified == loadedModified && registered) {
            return;
        }
        loadedModified = modified;
        try (InputStream in = Files.newInputStream(FILE)) {
            NativeImage image = NativeImage.read(in);
            width = image.getWidth();
            height = image.getHeight();
            DynamicTexture texture = new DynamicTexture(() -> "Custom Scoreboard Background", image);
            texture.upload();
            Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture);
            registered = true;
        } catch (Exception e) {
            LOGGER.warn("[CustomScoreboard] Couldn't load {}: {}", FILE, e.toString());
            release();
            loadedModified = modified; // don't retry a broken file every 2s until it changes
        }
    }

    private static void release() {
        if (registered) {
            try {
                Minecraft.getInstance().getTextureManager().release(TEXTURE_ID);
            } catch (Exception ignored) {
            }
        }
        registered = false;
        loadedModified = Long.MIN_VALUE;
        width = 0;
        height = 0;
    }

    static boolean fileExists() {
        return Files.isRegularFile(FILE);
    }
}
