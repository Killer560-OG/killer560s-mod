package com.killer560.hub.gui;

import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The pictures one screen shows (mod icons, the sim help's pictures): each is read and decoded on the IO pool the first
 * time it is asked for, uploaded on the render thread, drawn, and released by {@link #close} when the screen
 * goes. Holds only what its screen asked for; a read that lands after {@link #close} is dropped.
 */
public final class GuiImages {

    private static final Logger LOGGER = ModLog.get("killer560smod-gui");

    /** A ready picture: its texture and its pixel size. */
    public record Image(Identifier id, int w, int h) {
    }

    /** "Asked for, not ready (or unreadable)". */
    private static final Image PENDING = new Image(null, 0, 0);

    private final String path;
    private final boolean smooth;
    private final Map<String, Image> images = new HashMap<>();
    /** Moved by {@link #close}, so a read started before it is thrown away. */
    private int generation;

    /** {@code path}: the texture path prefix under killer560smod, e.g. "modlist/"; {@code smooth}: linear filtering (pictures drawn scaled), else nearest (pixel art). */
    public GuiImages(String path, boolean smooth) {
        this.path = path;
        this.smooth = smooth;
    }

    /** The picture for {@code key} once uploaded, else null; the first call starts {@code read} (off the render thread; may return null). */
    public Image get(String key, Callable<NativeImage> read) {
        Image i = images.get(key);
        if (i != null) {
            return i.id() == null ? null : i;
        }
        images.put(key, PENDING);
        int gen = generation;
        Util.ioPool().execute(() -> {
            NativeImage img = null;
            try {
                img = read.call();
            } catch (Exception e) {
                LOGGER.warn("[GuiImages] {}{} unreadable: {}", path, key, e.toString());
            }
            NativeImage done = img;
            if (done != null) {
                Minecraft.getInstance().execute(() -> upload(key, done, gen));
            }
        });
        return null;
    }

    private void upload(String key, NativeImage image, int gen) {
        if (gen != generation) {
            image.close();
            return;
        }
        Identifier id = Identifier.fromNamespaceAndPath("killer560smod",
                path + key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_"));
        int w = image.getWidth();
        int h = image.getHeight();
        DynamicTexture texture = new DynamicTexture(() -> "killer560smod " + path + key, image) {
            {
                if (smooth) sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            }
        };
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(id, texture);
        images.put(key, new Image(id, w, h));
    }

    /** Releases every texture; later reads of this screen's open are dropped. Call from {@code removed()}. */
    public void close() {
        generation++;
        for (Image i : images.values()) {
            if (i.id() != null) {
                Minecraft.getInstance().getTextureManager().release(i.id());
            }
        }
        images.clear();
    }

    /** Draws {@code i} scaled into the box. */
    public static void draw(GuiGraphicsExtractor g, Image i, int x, int y, int w, int h) {
        g.blit(RenderPipelines.GUI_TEXTURED, i.id(), x, y, 0f, 0f, w, h, i.w(), i.h(), i.w(), i.h());
    }
}
