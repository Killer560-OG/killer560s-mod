package com.killer560.hub.dvd;

import com.killer560.hub.gifplayer.GifDecoder;
import com.killer560.hub.gifplayer.GifPlayerFeature;
import com.killer560.hub.gifplayer.GifTextureUtil;
import com.killer560.hub.gifplayer.Mp3Converter;
import com.killer560.hub.notify.ModOverlayMessage;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * An old-school bouncing "DVD screensaver" box, per killer560's request - several can be configured
 * and run at once ({@link DvdConfig}), each showing either typed text or one of the same gifs
 * {@code GifPlayerFeature} can show, bouncing around the screen and reversing direction off each
 * wall it hits. A "perfect corner hit" (bouncing off a vertical AND horizontal wall on the exact
 * same frame - the actual screensaver meme) can optionally play a sound and show a one-off on-screen
 * message (not sent to chat - this is a purely visual/audio gimmick, unlike Leap Message which is
 * genuinely about announcing to Party Chat), with {name} in that message replaced by the gif's
 * filename (or this DVD's own given name, for text content). Color can optionally change on any
 * wall hit and/or specifically on a perfect corner hit.
 */
public final class DvdFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-dvd");
    private static final float BASE_SPEED_PX_PER_SEC = 90f;
    /** Small margin added around the measured text size so a background box (if enabled) doesn't
     *  render literally flush against the glyphs. */
    private static final int TEXT_PADDING = 8;

    private static final int[] COLOR_PALETTE = {
            0xFFFFFFFF, 0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55, 0xFF55FF55,
            0xFF55FFFF, 0xFF5599FF, 0xFFAA55FF, 0xFFFF55FF, 0xFFFF8888
    };

    private static final ExecutorService SOUND_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "killer560smod-dvd-sound");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, Runtime> runtimes = new LinkedHashMap<>();
    private static boolean initialSyncDone = false;

    private static final class Runtime {
        DvdEntry entry;
        float x, y, vx, vy;
        long lastUpdateMs;
        int currentColor;

        String loadedGifFileName;
        GifDecoder.GifImage gif;
        DynamicTexture texture;
        int frameIndex;
        long lastFrameChangeAtMs;
    }

    public static void register() {
        // Nothing to do at mod-init time - same "GPU device not ready yet" hazard GifPlayerFeature
        // hit applies here too, so the first sync is deferred to the first render call, same fix.
    }

    /** Re-reads {@link DvdConfig} and syncs runtimes (add/remove/reload gif textures) to match. */
    public static void reload() {
        List<DvdEntry> entries = DvdConfig.getInstance().entries();
        Set<String> enabledIds = entries.stream().filter(e -> e.enabled).map(e -> e.id).collect(Collectors.toSet());

        for (String id : List.copyOf(runtimes.keySet())) {
            if (!enabledIds.contains(id)) {
                closeAndRemove(id);
            }
        }

        for (DvdEntry e : entries) {
            if (!e.enabled) {
                continue;
            }
            Runtime rt = runtimes.get(e.id);
            if (rt == null) {
                rt = createRuntime(e);
                runtimes.put(e.id, rt);
            }
            rt.entry = e;
            syncGifTexture(rt, e);
        }
    }

    private static Runtime createRuntime(DvdEntry e) {
        Runtime rt = new Runtime();
        rt.entry = e;
        rt.currentColor = e.textColor();
        rt.x = 40 + ThreadLocalRandom.current().nextInt(0, 200);
        rt.y = 40 + ThreadLocalRandom.current().nextInt(0, 150);
        double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
        rt.vx = (float) Math.cos(angle);
        rt.vy = (float) Math.sin(angle);
        return rt;
    }

    private static void closeAndRemove(String id) {
        Runtime rt = runtimes.remove(id);
        if (rt != null && rt.texture != null) {
            rt.texture.close();
        }
    }

    private static void syncGifTexture(Runtime rt, DvdEntry e) {
        if (e.contentType != DvdContentType.GIF) {
            if (rt.texture != null) {
                rt.texture.close();
                rt.texture = null;
                rt.gif = null;
                rt.loadedGifFileName = null;
            }
            return;
        }
        if (e.gifFileName.equals(rt.loadedGifFileName) && rt.texture != null) {
            return;
        }
        if (rt.texture != null) {
            rt.texture.close();
            rt.texture = null;
        }
        rt.gif = null;
        rt.loadedGifFileName = e.gifFileName;
        if (e.gifFileName.isBlank()) {
            return;
        }
        try {
            Path file = GifPlayerFeature.folder().resolve(e.gifFileName);
            GifDecoder.GifImage decoded = GifDecoder.decode(file);
            rt.gif = decoded;
            rt.frameIndex = 0;
            rt.lastFrameChangeAtMs = System.currentTimeMillis();
            // Default to the gif's real decoded size the moment it's picked, instead of the
            // generic Width/Height defaults - only happens here (a genuinely new file selection,
            // gated by the equals-check above), so it doesn't fight with dimensions killer560
            // deliberately changed afterward for that same file.
            e.boxWidth = decoded.width();
            e.boxHeight = decoded.height();
            DvdConfig.getInstance().save();
            NativeImage image = new NativeImage(decoded.width(), decoded.height(), false);
            GifTextureUtil.writeFrame(image, decoded.frames().get(0).argb());
            rt.texture = new DynamicTexture(() -> "killer560smod dvd: " + e.id, image);
            Minecraft.getInstance().getTextureManager().register(
                    Identifier.fromNamespaceAndPath("killer560smod", "dvd_" + Integer.toHexString(e.id.hashCode())),
                    rt.texture);
        } catch (Exception ex) {
            LOGGER.error("Failed to load DVD gif: {}", e.gifFileName, ex);
        }
    }

    /** Called every frame from {@link com.killer560.hub.dvd.mixin.DvdGuiMixin}. */
    public static void renderOverlay(GuiGraphicsExtractor graphics) {
        if (!initialSyncDone) {
            initialSyncDone = true;
            reload();
        }
        long now = System.currentTimeMillis();
        int screenW = graphics.guiWidth();
        int screenH = graphics.guiHeight();
        for (Runtime rt : runtimes.values()) {
            updateAndDraw(rt, graphics, screenW, screenH, now);
        }
    }

    private static void updateAndDraw(Runtime rt, GuiGraphicsExtractor graphics, int screenW, int screenH, long now) {
        DvdEntry e = rt.entry;
        if (rt.lastUpdateMs == 0) {
            rt.lastUpdateMs = now;
        }
        // Capped at 0.25s so a lag spike or loading-screen pause doesn't fling the box across the
        // whole screen in one jump once rendering resumes.
        float dt = Math.min(0.25f, (now - rt.lastUpdateMs) / 1000f);
        rt.lastUpdateMs = now;

        int w;
        int h;
        if (e.contentType == DvdContentType.TEXT) {
            // Sized to the actual text, not the configured Width/Height (those only matter for
            // GIF content) - field-tested (2026-09-05): the bounce box itself already correctly
            // reaches the true screen edge (proven by the gif fix using this exact same bounce
            // math), but text is centered WITHIN that box, so a box much wider than short text
            // left a visible gap at the edges even though the box itself was flush. Sizing the box
            // to the text means the visible glyphs are what actually reaches the edge.
            Font font = Minecraft.getInstance().font;
            String text = e.text.isBlank() ? "DVD" : e.text;
            w = Math.max(1, Math.round((font.width(text) + TEXT_PADDING) * e.scale));
            h = Math.max(1, Math.round((font.lineHeight + TEXT_PADDING) * e.scale));
        } else {
            w = Math.max(1, Math.round(e.boxWidth * e.scale));
            h = Math.max(1, Math.round(e.boxHeight * e.scale));
        }

        float speed = BASE_SPEED_PX_PER_SEC * Math.max(0.05f, e.speedMultiplier);
        float mag = (float) Math.sqrt(rt.vx * rt.vx + rt.vy * rt.vy);
        if (mag > 0.0001f) {
            rt.vx = rt.vx / mag * speed;
            rt.vy = rt.vy / mag * speed;
        }

        float newX = rt.x + rt.vx * dt;
        float newY = rt.y + rt.vy * dt;
        boolean bounceX = false;
        boolean bounceY = false;
        if (newX < 0) {
            newX = 0;
            rt.vx = -rt.vx;
            bounceX = true;
        } else if (newX + w > screenW) {
            newX = Math.max(0, screenW - w);
            rt.vx = -rt.vx;
            bounceX = true;
        }
        if (newY < 0) {
            newY = 0;
            rt.vy = -rt.vy;
            bounceY = true;
        } else if (newY + h > screenH) {
            newY = Math.max(0, screenH - h);
            rt.vy = -rt.vy;
            bounceY = true;
        }
        rt.x = newX;
        rt.y = newY;

        if (bounceX && bounceY) {
            onCornerHit(rt, e);
        } else if (bounceX || bounceY) {
            onWallHit(rt, e);
        }

        if (e.contentType == DvdContentType.GIF) {
            advanceGifFrame(rt);
        }
        draw(rt, e, graphics, Math.round(rt.x), Math.round(rt.y), w, h);
    }

    private static void onWallHit(Runtime rt, DvdEntry e) {
        if (e.changeColorOnWallHit) {
            rt.currentColor = nextColor(rt.currentColor);
        }
    }

    private static void onCornerHit(Runtime rt, DvdEntry e) {
        if (e.changeColorOnCornerHit || e.changeColorOnWallHit) {
            rt.currentColor = nextColor(rt.currentColor);
        }
        if (!e.cornerHitSoundFile.isBlank()) {
            String soundFile = e.cornerHitSoundFile;
            SOUND_EXECUTOR.submit(() -> playSound(soundFile));
        }
        if (!e.cornerHitText.isBlank()) {
            String nameValue = (e.contentType == DvdContentType.GIF && !e.gifFileName.isBlank())
                    ? stripExtension(e.gifFileName) : e.name;
            String message = e.cornerHitText.replace("{name}", nameValue);
            ModOverlayMessage.show("§d[DVD] §f" + message, 3000);
        }
    }

    private static int nextColor(int current) {
        int idx = ThreadLocalRandom.current().nextInt(COLOR_PALETTE.length);
        // Avoid landing on the exact same color twice in a row.
        if (COLOR_PALETTE[idx] == current) {
            idx = (idx + 1) % COLOR_PALETTE.length;
        }
        return COLOR_PALETTE[idx];
    }

    private static void advanceGifFrame(Runtime rt) {
        if (rt.gif == null || rt.texture == null) {
            return;
        }
        List<GifDecoder.Frame> frames = rt.gif.frames();
        if (frames.size() <= 1) {
            return;
        }
        long now = System.currentTimeMillis();
        int guard = 0;
        while (guard++ < frames.size()) {
            long delay = frames.get(rt.frameIndex).delayMs();
            if (now - rt.lastFrameChangeAtMs < delay) {
                break;
            }
            rt.lastFrameChangeAtMs += delay;
            rt.frameIndex = (rt.frameIndex + 1) % frames.size();
            GifTextureUtil.writeFrame(rt.texture.getPixels(), frames.get(rt.frameIndex).argb());
            rt.texture.upload();
        }
    }

    private static void draw(Runtime rt, DvdEntry e, GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        if (e.contentType == DvdContentType.GIF) {
            if (rt.texture != null) {
                GifTextureUtil.blit(graphics, rt.texture, x, y, w, h);
            }
            return;
        }
        Font font = Minecraft.getInstance().font;
        if (e.backgroundEnabled) {
            graphics.fill(x, y, x + w, y + h, 0x99000000);
        }
        String text = e.text.isBlank() ? "DVD" : e.text;
        graphics.centeredText(font, text, x + w / 2, y + (h - font.lineHeight) / 2, rt.currentColor);
    }

    private static void playSound(String filename) {
        try {
            Path folder = GifPlayerFeature.folder();
            Path file = folder.resolve(filename);
            Path playable = filename.toLowerCase(Locale.US).endsWith(".mp3")
                    ? Mp3Converter.convertToWav(file, folder.resolveSibling("killer560smod-gifs-cache"))
                    : file;
            try (AudioInputStream in = AudioSystem.getAudioInputStream(playable.toFile())) {
                Clip clip = AudioSystem.getClip();
                clip.open(in);
                clip.addLineListener(event -> {
                    if (event.getType() == LineEvent.Type.STOP) {
                        clip.close();
                    }
                });
                clip.start();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to play DVD corner-hit sound: {}", filename, e);
        }
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private DvdFeature() {
    }
}
