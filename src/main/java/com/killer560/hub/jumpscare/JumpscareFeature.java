package com.killer560.hub.jumpscare;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Super Scary Jumpscare: on average once every {@link #AVERAGE_INTERVAL_SECONDS} of actual play time
 * (checked once per real second while the client is ticking, so time with the game closed doesn't
 * count), plays a sound and flashes a full-screen image at the same time, the image lasting half as
 * long as the sound does. killer560 supplies his own image/sound by dropping them into {@link #folder()}
 * - this class ships with neither bundled, and does nothing until both are present.
 * <p>
 * Reuses the same texture (via {@code com.killer560.hub.gifplayer.GifTextureUtil}) and MP3-to-WAV
 * (via {@code com.killer560.hub.gifplayer.Mp3Converter}) pipelines the GIF Player feature already
 * uses, rather than duplicating that logic - a plain static image just skips GifDecoder's GIF-specific
 * frame compositing and decodes straight from {@link ImageIO} instead.
 */
public final class JumpscareFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-jumpscare");

    // Field-tested successfully at 10s (2026-09-06) - reverted to the real 24-hour average. Rolled
    // once per real second the client is ticking, with probability 1/AVERAGE_INTERVAL_SECONDS each
    // roll, giving a geometric distribution whose mean interval is exactly this many seconds of
    // actual play time.
    private static final int AVERAGE_INTERVAL_SECONDS = 86400;

    private static final Path FOLDER =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-jumpscare");
    private static final Path CACHE_FOLDER =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-jumpscare-cache");

    // Field-tested (2026-09-06): requiring an exact filename ("image.png") silently failed the very
    // first real test because killer560's file was named "attachment.gif" - not wrong in spirit, just a
    // different name/extension than the one hardcoded name it checked for. Matching by EXTENSION
    // instead of exact filename means any file with a supported extension works regardless of its
    // base name, so this whole class of mismatch can't happen again.
    private static final List<String> IMAGE_EXTENSIONS = List.of(".png", ".jpg", ".jpeg", ".gif", ".bmp");
    private static final List<String> SOUND_EXTENSIONS = List.of(".mp3", ".wav");

    private static final Random RANDOM = new Random();

    private static long lastRollAtMs = 0L;
    private static boolean active = false;
    private static long activeUntilMs = 0L;
    private static DynamicTexture activeTexture = null;
    private static Clip activeClip = null;

    public static void register() {
        try {
            Files.createDirectories(FOLDER);
        } catch (IOException e) {
            LOGGER.error("Failed to create jumpscare folder", e);
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    public static Path folder() {
        return FOLDER;
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        if (lastRollAtMs == 0L) {
            lastRollAtMs = now;
            return;
        }
        if (now - lastRollAtMs < 1000) {
            return;
        }
        lastRollAtMs += 1000;
        if (RANDOM.nextDouble() < 1.0 / AVERAGE_INTERVAL_SECONDS) {
            trigger(now);
        }
    }

    private static void trigger(long now) {
        if (active) {
            return;
        }
        Path imageFile = findFileByExtension(IMAGE_EXTENSIONS);
        Path soundFile = findFileByExtension(SOUND_EXTENSIONS);
        if (imageFile == null || soundFile == null) {
            LOGGER.warn("Jumpscare due but missing files in {} (image present={}, sound present={})",
                    FOLDER, imageFile != null, soundFile != null);
            return;
        }

        long durationMs;
        try {
            durationMs = playSound(soundFile);
        } catch (Exception e) {
            LOGGER.error("Failed to play jumpscare sound: {}", soundFile.getFileName(), e);
            return;
        }

        try {
            loadImage(imageFile);
        } catch (Exception e) {
            LOGGER.error("Failed to load jumpscare image: {}", imageFile.getFileName(), e);
            if (activeClip != null) {
                activeClip.stop();
                activeClip.close();
                activeClip = null;
            }
            return;
        }

        active = true;
        long imageDurationMs = durationMs / 2;
        activeUntilMs = now + imageDurationMs;
        LOGGER.info("Jumpscare triggered - sound {}ms, image {}ms", durationMs, imageDurationMs);
    }

    private static long playSound(Path soundFile) throws Exception {
        Path playablePath = soundFile.getFileName().toString().toLowerCase(Locale.US).endsWith(".mp3")
                ? com.killer560.hub.gifplayer.Mp3Converter.convertToWav(soundFile, CACHE_FOLDER)
                : soundFile;
        try (AudioInputStream in = AudioSystem.getAudioInputStream(playablePath.toFile())) {
            Clip clip = AudioSystem.getClip();
            clip.open(in);
            if (activeClip != null) {
                activeClip.stop();
                activeClip.close();
            }
            activeClip = clip;
            clip.start();
            return Math.max(1L, clip.getMicrosecondLength() / 1000L);
        }
    }

    private static void loadImage(Path imageFile) throws IOException {
        BufferedImage buffered = ImageIO.read(imageFile.toFile());
        if (buffered == null) {
            throw new IOException("Not a readable image: " + imageFile);
        }
        int width = buffered.getWidth();
        int height = buffered.getHeight();
        int[] argb = buffered.getRGB(0, 0, width, height, null, 0, width);

        NativeImage nativeImage = new NativeImage(width, height, false);
        com.killer560.hub.gifplayer.GifTextureUtil.writeFrame(nativeImage, argb);

        if (activeTexture != null) {
            activeTexture.close();
        }
        activeTexture = new DynamicTexture(() -> "killer560smod jumpscare", nativeImage);
        Minecraft.getInstance().getTextureManager().register(
                Identifier.fromNamespaceAndPath("killer560smod", "jumpscare"), activeTexture);
    }

    private static Path findFileByExtension(List<String> extensions) {
        try (Stream<Path> stream = Files.list(FOLDER)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.US);
                        return extensions.stream().anyMatch(name::endsWith);
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Called every frame from {@link com.killer560.hub.jumpscare.mixin.JumpscareGuiMixin}. */
    public static void renderOverlay(GuiGraphicsExtractor graphics) {
        if (!active) {
            return;
        }
        if (System.currentTimeMillis() >= activeUntilMs) {
            active = false;
            return;
        }
        if (activeTexture == null) {
            return;
        }
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        com.killer560.hub.gifplayer.GifTextureUtil.blit(graphics, activeTexture, 0, 0, screenWidth, screenHeight);
    }

    private JumpscareFeature() {
    }
}
