package com.killer560.hub.mainmenu;

import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

/**
 * The four big main menu pictures ship as LOSSLESS WebP (jarsize-1104, 2026-11-04: 13.5 MB of PNG became 10.3 MB; every
 * pixel checked identical against the PNG with this same reader, killer560s-mod-logs/jarsize1104/webpcheck). Minecraft's
 * texture loader reads PNG only, so a {@code .webp} picture is registered here as a {@link ReloadableTexture} that
 * decodes it with the bundled TwelveMonkeys WebP reader (Image Preview's) and keeps the vanilla rest: the
 * {@code .webp.mcmeta} blur/clamp, the upload, and the reload on F3+T / a resource pack change.
 *
 * <p>Every draw of such a picture calls {@link #ensure} first (vanilla would make a {@code SimpleTexture} for an unknown
 * id and fail on the WebP). A 2560x2880 picture takes about a second to decode, so {@link #preload} starts the current
 * theme's picture on a background thread while the game is still loading, and the first title screen does not wait.
 */
public final class WebpPictures {
    private static final Logger LOGGER = ModLog.get("killer560smod-mainmenu");
    private static final Set<Identifier> REGISTERED = new HashSet<>();
    private static final Map<Identifier, CompletableFuture<NativeImage>> PRELOADS = new ConcurrentHashMap<>();

    private WebpPictures() {
    }

    /** The id of {@code textures/gui/<name>.webp} in this mod's namespace. */
    static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath("killer560smod", "textures/gui/" + name + ".webp");
    }

    /** Registers (and loads, once) the WebP picture {@code id}; any other id is left to vanilla. Render thread only. */
    static void ensure(Identifier id) {
        if (id == null || !id.getPath().endsWith(".webp") || !REGISTERED.add(id)) {
            return;
        }
        Minecraft.getInstance().getTextureManager().registerAndLoad(id, new Texture(id));
    }

    /** Starts decoding {@code id} from the mod jar on a background thread, so {@link #ensure} finds it ready. */
    static void preload(Identifier id) {
        if (id == null || !id.getPath().endsWith(".webp")) {
            return;
        }
        PRELOADS.computeIfAbsent(id, k -> CompletableFuture.supplyAsync(() -> {
            String path = "/assets/" + k.getNamespace() + "/" + k.getPath();
            try (InputStream in = WebpPictures.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IOException("missing " + path);
                }
                return decode(in);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }));
    }

    /** Decodes a WebP stream into a fresh RGBA NativeImage (caller owns it). */
    static NativeImage decode(InputStream stream) throws IOException {
        BufferedImage img;
        ImageReader reader = new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi().createReaderInstance(null);
        try (ImageInputStream in = ImageIO.createImageInputStream(stream)) {
            reader.setInput(in);
            img = reader.read(0);
        } finally {
            reader.dispose();
        }
        int w = img.getWidth();
        int h = img.getHeight();
        NativeImage out = new NativeImage(w, h, false);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int px = row[x];
                // ARGB -> ABGR: keep alpha and green, swap red and blue.
                out.setPixelABGR(x, y, (px & 0xFF00FF00) | ((px & 0xFF) << 16) | ((px >>> 16) & 0xFF));
            }
        }
        return out;
    }

    /** A WebP picture as a vanilla reloadable texture: decoded here, metadata and upload as for a PNG. */
    private static final class Texture extends ReloadableTexture {
        Texture(Identifier id) {
            super(id);
        }

        @Override
        public TextureContents loadContents(ResourceManager manager) throws IOException {
            Identifier id = resourceId();
            Resource resource = manager.getResourceOrThrow(id);
            TextureMetadataSection meta = resource.metadata().getSection(TextureMetadataSection.TYPE).orElse(null);
            CompletableFuture<NativeImage> early = PRELOADS.remove(id);
            if (early != null) {
                try {
                    return new TextureContents(early.join(), meta);
                } catch (RuntimeException e) {
                    LOGGER.warn("[MainMenu] preloading {} failed, decoding it again", id, e);
                }
            }
            long t0 = System.nanoTime();
            try (InputStream in = resource.open()) {
                NativeImage image = decode(in);
                LOGGER.info("[MainMenu] decoded {} ({}x{}) in {} ms", id, image.getWidth(), image.getHeight(),
                        (System.nanoTime() - t0) / 1_000_000);
                return new TextureContents(image, meta);
            }
        }
    }
}
