package com.killer560.hub.mainmenu;

import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>Every draw of such a picture asks {@link #ensure} first (vanilla would make a {@code SimpleTexture} for an unknown
 * id and fail on the WebP). A 2560x2880 picture takes about a second to decode (far longer on a loaded PC: 35 s was seen with
 * nine test clients running), so the decode never runs on the render thread: {@link #preload} starts the current theme's
 * picture at client init, {@link #ensure} starts any other on its first draw, and the picture is drawn once it is ready.
 *
 * <p>Since themedl-1104 the jar holds only a small preview of each picture under the same id (Amber's at medium quality);
 * the full one is a {@link ThemeAssets} download. The preview is drawn (upscaled: every scene draws in the picture's own
 * units and samples by UV) until the download is ready, then the full picture is decoded in the background and replaces it
 * under the same id; with no download (offline, no custom title screen) the preview stays.
 */
public final class WebpPictures {
    private static final Logger LOGGER = ModLog.get("killer560smod-mainmenu");
    private static final Set<Identifier> REGISTERED = new HashSet<>();
    /** Registered with the downloaded full picture (or given up on it for this session after a failed decode). */
    private static final Set<Identifier> FULL = new HashSet<>();
    private static final Map<Identifier, String> NAMES = new ConcurrentHashMap<>();
    private static final Map<Identifier, CompletableFuture<Decoded>> PRELOADS = new ConcurrentHashMap<>();

    /** A decoded picture and whether it is the downloaded full one (else the jar's preview). */
    private record Decoded(NativeImage image, boolean full) {
    }

    private WebpPictures() {
    }

    /** The id of {@code textures/gui/<name>.webp} in this mod's namespace. */
    static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath("killer560smod", "textures/gui/" + name + ".webp");
    }

    /**
     * Whether the WebP picture {@code id} is ready to draw; any other id is always ready (left to vanilla). Never blocks:
     * the first call starts the decode on a background thread and answers false until it is done, then registers the
     * decoded picture (an upload only) and answers true from then on. A caller draws nothing of the picture while it is
     * false (the title screen's own background shows). Render thread only.
     */
    static boolean ensure(Identifier id) {
        if (id == null || !id.getPath().endsWith(".webp")) {
            return true;
        }
        if (REGISTERED.contains(id)) {
            if (!FULL.contains(id)) {
                upgrade(id);
            }
            return true;
        }
        preload(id);
        CompletableFuture<Decoded> f = PRELOADS.get(id);
        if (f != null && !f.isDone()) {
            return false;
        }
        register(id, f);
        return true;
    }

    /** Registers the decoded picture {@code f} under {@code id} (an upload only; replaces a registered preview). */
    private static void register(Identifier id, CompletableFuture<Decoded> f) {
        REGISTERED.add(id);
        Decoded d = f == null ? null : f.getNow(null);
        if (d != null && d.full()) {
            FULL.add(id);
        }
        Minecraft.getInstance().getTextureManager().registerAndLoad(id, new Texture(id));
    }

    /** While the preview is registered: asks for the download, then decodes it in the background and swaps it in. */
    private static void upgrade(Identifier id) {
        CompletableFuture<Decoded> f = PRELOADS.get(id);
        if (f == null) {
            String name = NAMES.computeIfAbsent(id, ThemeAssets::pictureName); // no allocation a frame
            ThemeAssets.request(name);
            java.nio.file.Path full = ThemeAssets.ready(name);
            if (full != null) {
                PRELOADS.put(id, CompletableFuture.supplyAsync(() -> decodeFull(id, full)));
            }
        } else if (f.isDone()) {
            FULL.add(id); // one try a session: a file that will not decode leaves the preview up
            register(id, f);
        }
    }

    /** Starts decoding {@code id} on a background thread (the downloaded picture when it is cached, else the jar's
     *  preview), so {@link #ensure} finds it ready; asks for the download too. */
    static void preload(Identifier id) {
        if (id == null || !id.getPath().endsWith(".webp")) {
            return;
        }
        if (REGISTERED.contains(id)) {
            return;
        }
        PRELOADS.computeIfAbsent(id, k -> {
            String name = ThemeAssets.pictureName(k);
            ThemeAssets.request(name);
            return CompletableFuture.supplyAsync(() -> {
                java.nio.file.Path full = ThemeAssets.cachedNow(name); // a stat, on this background thread
                return full != null ? decodeFull(k, full) : decodeJar(k);
            });
        });
    }

    private static Decoded decodeJar(Identifier k) {
        String path = "/assets/" + k.getNamespace() + "/" + k.getPath();
        long t0 = System.nanoTime();
        try (InputStream in = WebpPictures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing " + path);
            }
            NativeImage image = decode(in.readAllBytes());
            LOGGER.info("[MainMenu] decoded the preview of {} ({}x{}) in {} ms", k, image.getWidth(), image.getHeight(),
                    (System.nanoTime() - t0) / 1_000_000);
            return new Decoded(image, false);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Decoded decodeFull(Identifier k, java.nio.file.Path file) {
        long t0 = System.nanoTime();
        try {
            NativeImage image = decode(java.nio.file.Files.readAllBytes(file));
            LOGGER.info("[MainMenu] decoded the downloaded {} ({}x{}) in {} ms", k, image.getWidth(), image.getHeight(),
                    (System.nanoTime() - t0) / 1_000_000);
            return new Decoded(image, true);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Test hook: whether {@code name}'s registered picture is the downloaded one. */
    public static boolean fullForTest(String name) { return FULL.contains(id(name)) && REGISTERED.contains(id(name)); }

    /** Decodes WebP bytes into a fresh RGBA NativeImage (caller owns it). */
    static NativeImage decode(byte[] bytes) throws IOException {
        BufferedImage img;
        ImageReader reader = new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi().createReaderInstance(null);
        try (ImageInputStream in = new com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream(bytes)) {
            reader.setInput(in);
            img = reader.read(0);
        } finally {
            reader.dispose();
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int[] argb;
        if (img.getType() == BufferedImage.TYPE_INT_ARGB
                && img.getRaster().getDataBuffer() instanceof DataBufferInt db && db.getNumBanks() == 1
                && db.getOffset() == 0 && db.getData().length == w * h) {
            argb = db.getData(); // the reader's own array: no per-pixel colour model calls
        } else {
            argb = img.getRGB(0, 0, w, h, null, 0, w);
        }
        NativeImage out = new NativeImage(w, h, false);
        try {
            int i = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++, i++) {
                    int px = argb[i];
                    // ARGB -> ABGR: keep alpha and green, swap red and blue.
                    out.setPixelABGR(x, y, (px & 0xFF00FF00) | ((px & 0xFF) << 16) | ((px >>> 16) & 0xFF));
                }
            }
        } catch (RuntimeException e) {
            out.close();
            throw e;
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
            CompletableFuture<Decoded> early = PRELOADS.remove(id);
            if (early != null) {
                try {
                    return new TextureContents(early.join().image(), meta);
                } catch (RuntimeException e) {
                    LOGGER.warn("[MainMenu] decoding {} failed, decoding the jar's preview again", id, e);
                }
            }
            // A resource reload (F3+T, a pack change): the downloaded picture when it is cached and decodes, else the jar's
            // (not after a failed early decode, which was that file).
            java.nio.file.Path full = ThemeAssets.ready(ThemeAssets.pictureName(id));
            if (full != null && early == null) {
                try {
                    return new TextureContents(decodeFull(id, full).image(), meta);
                } catch (RuntimeException e) {
                    LOGGER.warn("[MainMenu] the downloaded {} does not decode; drawing the preview", id, e);
                }
            }
            long t0 = System.nanoTime();
            try (InputStream in = resource.open()) {
                NativeImage image = decode(in.readAllBytes());
                LOGGER.info("[MainMenu] decoded {} ({}x{}) in {} ms", id, image.getWidth(), image.getHeight(),
                        (System.nanoTime() - t0) / 1_000_000);
                return new TextureContents(image, meta);
            }
        }
    }
}
