package com.killer560.hub.imagepreview;

import com.killer560.hub.gifplayer.GifDecoder;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Bytes to pixels for Image Preview, with ImageIO's own readers (PNG, JPEG, GIF, BMP), the GIF Player's
 * {@link GifDecoder} for animation, and TwelveMonkeys' pure-Java WebP reader (BSD, bundled). Nothing else is ever
 * run: the format is decided from the file's first bytes, never from the address or the server's word.
 *
 * <p>The size is read from the header BEFORE anything is decoded, so a small file that claims a huge picture (a
 * "decompression bomb") is refused without allocating it. What is kept is scaled down to texture size.
 */
final class ImageDecoding {

    /** Refused above either: a side over 8192 px or 40 million pixels in one frame. */
    static final int MAX_SIDE = 8192;
    static final long MAX_PIXELS = 40_000_000L;
    /** A GIF is animated only while all its frames together stay under this (else its first frame is shown). */
    static final long MAX_ANIMATED_PIXELS = 32_000_000L;
    static final int MAX_FRAMES = 1000;
    /** Largest texture kept: a still picture, and each frame of an animation (whose frames also share a budget). */
    static final int STILL_TEXTURE_SIDE = 1024;
    static final int ANIMATED_TEXTURE_SIDE = 512;
    static final long ANIMATED_KEPT_PIXELS = 16_000_000L;

    record Decoded(int width, int height, int sourceWidth, int sourceHeight, List<int[]> frames, int[] delaysMs,
                   String format) {
        long bytes() {
            return (long) width * height * 4L * frames.size();
        }
    }

    private ImageDecoding() {
    }

    /** The picture format from the magic bytes: "png", "jpeg", "gif", "bmp", "webp", or null when it is none of them. */
    static String sniff(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "gif";
        }
        if (b[0] == 'B' && b[1] == 'M') {
            return "bmp";
        }
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B'
                && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static ImageReader readerFor(String format) throws IOException {
        if (format.equals("webp")) {
            try {
                return new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi().createReaderInstance(null);
            } catch (Throwable t) {
                throw new ImageFetcher.FetchException("WebP not supported");
            }
        }
        Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName(format);
        if (!it.hasNext()) {
            throw new ImageFetcher.FetchException(format.toUpperCase(java.util.Locale.ROOT) + " not supported");
        }
        return it.next();
    }

    static Decoded decode(byte[] bytes) throws IOException {
        String format = sniff(bytes);
        if (format == null) {
            throw new ImageFetcher.NotAnImage("unknown format");
        }
        ImageReader reader = readerFor(format);
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(in, false, true);
            int w = reader.getWidth(0);
            int h = reader.getHeight(0);
            if (w <= 0 || h <= 0) {
                throw new ImageFetcher.FetchException("Broken picture");
            }
            if (w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS) {
                throw new ImageFetcher.FetchException("Too many pixels (" + w + "x" + h + ")");
            }
            if (format.equals("gif")) {
                int n = reader.getNumImages(true);
                if (n > 1 && n <= MAX_FRAMES && (long) w * h * n <= MAX_ANIMATED_PIXELS) {
                    return animated(bytes);
                }
            }
            BufferedImage img = reader.read(0);
            if (img == null) {
                throw new ImageFetcher.FetchException("Broken picture");
            }
            int[] size = fit(img.getWidth(), img.getHeight(), STILL_TEXTURE_SIDE);
            int[] argb = scale(toArgb(img), size[0], size[1]);
            List<int[]> frames = new ArrayList<>(1);
            frames.add(argb);
            return new Decoded(size[0], size[1], img.getWidth(), img.getHeight(), frames, new int[]{0}, format);
        } catch (ImageFetcher.FetchException | ImageFetcher.NotAnImage e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ImageFetcher.FetchException("Broken picture");
        } finally {
            reader.dispose();
        }
    }

    private static Decoded animated(byte[] bytes) throws IOException {
        GifDecoder.GifImage gif = GifDecoder.decode(new ByteArrayInputStream(bytes), "image preview");
        int sw = gif.width();
        int sh = gif.height();
        int n = gif.frames().size();
        int side = ANIMATED_TEXTURE_SIDE;
        int[] size = fit(sw, sh, side);
        while ((long) size[0] * size[1] * n > ANIMATED_KEPT_PIXELS && side > 32) {
            side = side * 3 / 4;
            size = fit(sw, sh, side);
        }
        List<int[]> frames = new ArrayList<>(n);
        int[] delays = new int[n];
        BufferedImage canvas = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < n; i++) {
            GifDecoder.Frame f = gif.frames().get(i);
            canvas.setRGB(0, 0, sw, sh, f.argb(), 0, sw);
            frames.add(scale(canvas, size[0], size[1]));
            delays[i] = Math.max(20, f.delayMs());
        }
        return new Decoded(size[0], size[1], sw, sh, frames, delays, "gif");
    }

    /** {@code w x h} scaled down (never up) to fit in {@code side} on its longest side. */
    static int[] fit(int w, int h, int side) {
        double s = Math.min(1.0, side / (double) Math.max(w, h));
        return new int[]{Math.max(1, (int) Math.round(w * s)), Math.max(1, (int) Math.round(h * s))};
    }

    private static BufferedImage toArgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_ARGB) {
            return img;
        }
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    /** ARGB pixels of {@code src} at {@code w x h}: halved bilinearly until close, then one last bilinear step. */
    private static int[] scale(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        if (cur.getWidth() != w || cur.getHeight() != h) {
            int cw = cur.getWidth();
            int ch = cur.getHeight();
            while (cw / 2 >= w && ch / 2 >= h) {
                cw /= 2;
                ch /= 2;
                cur = draw(cur, cw, ch);
            }
            if (cw != w || ch != h) {
                cur = draw(cur, w, h);
            }
        }
        return cur.getRGB(0, 0, w, h, null, 0, w);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
