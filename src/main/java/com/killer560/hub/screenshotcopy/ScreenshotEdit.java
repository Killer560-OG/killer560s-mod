package com.killer560.hub.screenshotcopy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** The pixel side of the Screenshot Editor: the edit operations, drawing them onto a full-size copy of the picture, and
 *  reading and writing the PNG. No Minecraft screen code and no GPU, so all of it runs on a worker thread. The editor keeps
 *  its edits as a list of {@link Op} and only renders them here (the preview texture and Copy / Save alike), so what is
 *  saved is full size and exactly what the preview showed. */
public final class ScreenshotEdit {

    public enum Kind { CROP, PEN, LINE, ARROW, RECT, MARKER, TEXT, PIXELATE }

    /** One edit. {@code pts} is x,y pairs in picture pixels (PEN: the whole stroke; LINE, ARROW, RECT, MARKER, PIXELATE,
     *  CROP: two corners; TEXT: the top-left corner). {@code size} is a step 0..2, turned into pixels by {@link #px}.
     *  A CROP with no points puts the whole picture back. */
    public record Op(Kind kind, int color, int size, int[] pts, String text) {
    }

    /** A decoded picture: {@code argb} row by row. */
    public record Picture(int[] argb, int w, int h) {
    }

    private ScreenshotEdit() {
    }

    /** One screen-scaling unit of this picture: about a 640th of its long side (3 px on 1920x1080). */
    static int unit(int w, int h) {
        return Math.max(1, Math.max(w, h) / 640);
    }

    /** The pixel value of a size step for this picture: 1x, 2x, 4x the unit. */
    static int px(int size, int w, int h) {
        return unit(w, h) << Math.max(0, Math.min(2, size));
    }

    /** The newest CROP in {@code ops} as {x, y, w, h} clamped to the picture, or null when the whole picture stays. */
    public static int[] cropOf(List<Op> ops, int w, int h) {
        for (int i = ops.size() - 1; i >= 0; i--) {
            Op op = ops.get(i);
            if (op.kind() == Kind.CROP) {
                if (op.pts().length < 4) {
                    return null;
                }
                int x0 = clamp(Math.min(op.pts()[0], op.pts()[2]), 0, w - 1);
                int y0 = clamp(Math.min(op.pts()[1], op.pts()[3]), 0, h - 1);
                int x1 = clamp(Math.max(op.pts()[0], op.pts()[2]), x0 + 1, w);
                int y1 = clamp(Math.max(op.pts()[1], op.pts()[3]), y0 + 1, h);
                return new int[]{x0, y0, x1 - x0, y1 - y0};
            }
        }
        return null;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** {@code src} with every operation drawn on, same size, a new array ({@code src} is not touched). CROP is not applied
     *  here, see {@link #crop}. */
    public static int[] render(int[] src, int w, int h, List<Op> ops) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        System.arraycopy(src, 0, px, 0, src.length);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        for (Op op : ops) {
            int t = px(op.size(), w, h);
            int[] p = op.pts();
            g.setColor(new java.awt.Color(op.color(), true));
            switch (op.kind()) {
                case PEN -> {
                    g.setStroke(new BasicStroke(t, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    if (p.length == 2) {
                        g.fillOval(p[0] - t / 2, p[1] - t / 2, t, t);
                    }
                    for (int i = 2; i + 1 < p.length; i += 2) {
                        g.drawLine(p[i - 2], p[i - 1], p[i], p[i + 1]);
                    }
                }
                case LINE -> {
                    g.setStroke(new BasicStroke(t, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine(p[0], p[1], p[2], p[3]);
                }
                case ARROW -> arrow(g, p, t);
                case RECT -> {
                    g.setStroke(new BasicStroke(t, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
                    g.drawRect(Math.min(p[0], p[2]), Math.min(p[1], p[3]), Math.abs(p[2] - p[0]), Math.abs(p[3] - p[1]));
                }
                case MARKER -> {
                    // translucent, drawn over what is there, so text under it stays readable
                    g.setColor(new java.awt.Color((op.color() & 0xFFFFFF) | 0x66000000, true));
                    g.fillRect(Math.min(p[0], p[2]), Math.min(p[1], p[3]), Math.abs(p[2] - p[0]) + 1, Math.abs(p[3] - p[1]) + 1);
                }
                case TEXT -> text(g, op, t);
                case PIXELATE -> pixelate(px, w, h, p, t * 2);
                case CROP -> {
                }
            }
        }
        g.dispose();
        return px;
    }

    private static void arrow(Graphics2D g, int[] p, int t) {
        double dx = p[2] - p[0];
        double dy = p[3] - p[1];
        double len = Math.hypot(dx, dy);
        if (len < 1) {
            return;
        }
        double head = Math.min(len, Math.max(t * 4.0, 12));
        double ux = dx / len;
        double uy = dy / len;
        double bx = p[2] - ux * head * 0.8;
        double by = p[3] - uy * head * 0.8;
        g.setStroke(new BasicStroke(t, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(p[0], p[1], (int) Math.round(bx), (int) Math.round(by));
        double nx = -uy * head * 0.4;
        double ny = ux * head * 0.4;
        g.fillPolygon(new int[]{p[2], (int) Math.round(p[2] - ux * head + nx), (int) Math.round(p[2] - ux * head - nx)},
                new int[]{p[3], (int) Math.round(p[3] - uy * head + ny), (int) Math.round(p[3] - uy * head - ny)}, 3);
    }

    private static void text(Graphics2D g, Op op, int t) {
        String s = op.text();
        if (s == null || s.isEmpty()) {
            return;
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, t * 5)));
        int ascent = g.getFontMetrics().getAscent();
        int x = op.pts()[0];
        int y = op.pts()[1] + ascent;
        if ((op.color() & 0xFFFFFF) != 0) {
            g.setColor(new java.awt.Color(0xB0000000, true)); // a dark edge keeps light text readable on any picture
            int o = Math.max(1, t / 3);
            g.drawString(s, x + o, y + o);
        }
        g.setColor(new java.awt.Color(op.color(), true));
        g.drawString(s, x, y);
    }

    /** Replaces the box with blocks of their average color; blocks are {@code block} px. */
    private static void pixelate(int[] px, int w, int h, int[] p, int block) {
        int x0 = clamp(Math.min(p[0], p[2]), 0, w);
        int y0 = clamp(Math.min(p[1], p[3]), 0, h);
        int x1 = clamp(Math.max(p[0], p[2]), 0, w);
        int y1 = clamp(Math.max(p[1], p[3]), 0, h);
        for (int by = y0; by < y1; by += block) {
            for (int bx = x0; bx < x1; bx += block) {
                int ex = Math.min(bx + block, x1);
                int ey = Math.min(by + block, y1);
                long r = 0;
                long gr = 0;
                long b = 0;
                for (int y = by; y < ey; y++) {
                    for (int x = bx; x < ex; x++) {
                        int c = px[y * w + x];
                        r += (c >> 16) & 0xFF;
                        gr += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                    }
                }
                long n = (long) (ex - bx) * (ey - by);
                int avg = 0xFF000000 | ((int) (r / n) << 16) | ((int) (gr / n) << 8) | (int) (b / n);
                for (int y = by; y < ey; y++) {
                    for (int x = bx; x < ex; x++) {
                        px[y * w + x] = avg;
                    }
                }
            }
        }
    }

    /** The {@code rect} part of a picture ({@code rect} null: all of it). */
    public static Picture crop(int[] argb, int w, int h, int[] rect) {
        if (rect == null) {
            return new Picture(argb, w, h);
        }
        int[] out = new int[rect[2] * rect[3]];
        for (int y = 0; y < rect[3]; y++) {
            System.arraycopy(argb, (rect[1] + y) * w + rect[0], out, y * rect[2], rect[2]);
        }
        return new Picture(out, rect[2], rect[3]);
    }

    /** Reads a PNG (or any picture ImageIO knows) into ARGB. Blocking: call off the render thread. */
    public static Picture read(Path file) throws IOException {
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) {
            throw new IOException("not a picture: " + file.getFileName());
        }
        int w = img.getWidth();
        int h = img.getHeight();
        return new Picture(img.getRGB(0, 0, w, h, null, 0, w), w, h);
    }

    private static BufferedImage image(Picture pic) {
        BufferedImage img = new BufferedImage(pic.w(), pic.h(), BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, pic.w(), pic.h(), pic.argb(), 0, pic.w());
        return img;
    }

    /** Writes the picture over {@code file} (through a temp file beside it, so a failure leaves the old one). */
    public static void write(Picture pic, Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".editing");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            ImageIO.write(image(pic), "png", out);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Writes the picture beside {@code original} as {@code <name>-edited.png} (then -edited-2 ...), never over an existing
     *  file; returns the path written. */
    public static Path writeCopy(Picture pic, Path original) throws IOException {
        String base = original.getFileName().toString();
        base = base.endsWith(".png") ? base.substring(0, base.length() - 4) : base;
        for (int n = 1; n < 1000; n++) {
            Path target = original.resolveSibling(base + (n == 1 ? "-edited" : "-edited-" + n) + ".png");
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                ImageIO.write(image(pic), "png", out);
                return target;
            } catch (java.nio.file.FileAlreadyExistsException e) {
                // the next number
            }
        }
        throw new IOException("too many edited copies of " + base);
    }

    /** The game's screenshots folder. */
    public static Path folder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(Screenshot.SCREENSHOT_DIR);
    }

    /** A screenshot's file name (no folder, no ".."), as a real file in the screenshots folder, or null. */
    public static Path resolve(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")
                || name.contains(":") || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            return null;
        }
        Path dir = folder().toAbsolutePath().normalize();
        Path file = dir.resolve(name).normalize();
        return file.getParent() != null && file.getParent().equals(dir) && Files.isRegularFile(file) ? file : null;
    }
}
