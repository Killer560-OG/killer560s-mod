package com.killer560.hub.gui;

import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The mod menu's look ("Option A", killer560 2026-10-12): the palette and the drawing pieces every part of the menu shares -
 * bevelled panels (light top-left edge, dark bottom-right edge, black outline), recessed wells, drop shadows, the orange
 * glow, the switch, the value pill, the raised button and the slider track. Values come from the approved mockup
 * ({@code menu-option-a.html}, its "depth pass" block). Everything is plain fills and vertical gradients, batched through
 * {@link GuiRects} so a card or row is one render element (docs/LESSONS-GUI.md: every fill costs more than its pixels).
 */
public final class MenuStyle {

    private MenuStyle() {
    }

    // ---- palette (mockup :root and depth pass) ----------------------------------------------------------------------
    public static final int OR = 0xFFFF8A1F;
    public static final int OR2 = 0xFFC76A14;
    public static final int OR_LIGHT = 0xFFFFA040;
    public static final int OR_DARK = 0xFFC06010;
    public static final int TXT = 0xFFE9E2D8;
    public static final int DIM = 0xFF9A8F82;
    public static final int DIM2 = 0xFF6B6259;
    public static final int RED = 0xFFE05555;
    public static final int RED_TITLE = 0xFFFF5555;
    public static final int BLACK = 0xFF000000;
    public static final int WELL = 0xFF0B0907;
    public static final int WELL_DEEP = 0xFF070605;
    public static final int HILITE = 0x1CFFFFFF;
    public static final int HILITE_SOFT = 0x12FFFFFF;
    public static final int SHADE = 0x80000000;
    public static final int TRACK = 0xFF3B332B;
    public static final int KNOB_OFF = 0xFF8D8478;

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    /** A colour with its alpha replaced. */
    public static int alpha(int argb, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (argb & 0x00FFFFFF);
    }

    /** {@code argb} scaled toward black by {@code f} (0 = black, 1 = unchanged), alpha kept. */
    public static int shade(int argb, float f) {
        int r = Math.round(((argb >> 16) & 0xFF) * f);
        int g = Math.round(((argb >> 8) & 0xFF) * f);
        int b = Math.round((argb & 0xFF) * f);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Linear mix of two colours, alpha included. */
    public static int mix(int a, int b, float t) {
        int r = 0;
        for (int s = 0; s < 32; s += 8) {
            int ca = (a >>> s) & 0xFF;
            int cb = (b >>> s) & 0xFF;
            r |= (Math.round(ca + (cb - ca) * t) & 0xFF) << s;
        }
        return r;
    }

    // ---- shapes ---------------------------------------------------------------------------------------------------

    /** A raised bevelled box: vertical gradient, black outline, light top-left inner edge, dark bottom-right inner edge. */
    public static void bevel(GuiRects r, int x, int y, int w, int h, int top, int bottom) {
        if (w <= 2 || h <= 2) {
            r.fill(x, y, x + w, y + h, top);
            return;
        }
        r.gradient(x + 1, y + 1, x + w - 1, y + h - 1, top, bottom);
        r.outline(x, y, w, h, BLACK);
        r.fill(x + 1, y + 1, x + w - 1, y + 2, HILITE);
        r.fill(x + 1, y + 2, x + 2, y + h - 1, HILITE);
        r.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, SHADE);
        r.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, SHADE);
    }

    /** A recessed well: dark fill, black outline, a soft inner shadow along the top and left, a faint light bottom-right lip. */
    public static void well(GuiRects r, int x, int y, int w, int h, int fill) {
        r.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        r.outline(x, y, w, h, BLACK);
        // inset 3px 3px 8px #000 approximated by three fading strips
        int[] a = {0xB0, 0x60, 0x28};
        for (int i = 0; i < a.length && i * 2 + 4 < Math.min(w, h); i++) {
            int c = alpha(BLACK, a[i]);
            r.fill(x + 1, y + 1 + i, x + w - 1, y + 2 + i, c);
            r.fill(x + 1 + i, y + 2 + i, x + 2 + i, y + h - 1, c);
        }
        r.fill(x + 1, y + h - 1, x + w, y + h, 0x10FFFFFF);
        r.fill(x + w - 1, y + 1, x + w, y + h - 1, 0x10FFFFFF);
    }

    /** A drop shadow under/right of a box (0 3px 6px #000c), drawn BEFORE the box. */
    public static void dropShadow(GuiRects r, int x, int y, int w, int h, int depth) {
        int[] a = {0x90, 0x50, 0x24};
        for (int i = 0; i < Math.min(depth, a.length); i++) {
            int c = alpha(BLACK, a[i]);
            r.fill(x + 1 + i, y + h + i, x + w + i, y + h + i + 1, c);
            r.fill(x + w + i, y + 1 + i, x + w + i + 1, y + h + i, c);
        }
    }

    /** A soft glow of {@code rgb} around a box (0 0 14px #ff8a1f55), drawn BEFORE the box: layered translucent rings. */
    public static void glow(GuiRects r, int x, int y, int w, int h, int rgb, int strength) {
        int[] a = {strength, strength * 2 / 3, strength / 3, strength / 6};
        for (int i = 0; i < a.length; i++) {
            int d = i + 1;
            int c = alpha(rgb, a[i]);
            r.fill(x - d, y - d, x + w + d, y - d + 1, c);
            r.fill(x - d, y + h + d - 1, x + w + d, y + h + d, c);
            r.fill(x - d, y - d + 1, x - d + 1, y + h + d - 1, c);
            r.fill(x + w + d - 1, y - d + 1, x + w + d, y + h + d - 1, c);
        }
    }

    /** A horizontal gradient as vertical strips (GuiGraphicsExtractor only has vertical gradients). */
    public static void hGradient(GuiRects r, int x0, int y0, int x1, int y1, int left, int right, int steps) {
        int w = x1 - x0;
        if (w <= 0) {
            return;
        }
        steps = Math.max(1, Math.min(steps, w));
        for (int i = 0; i < steps; i++) {
            int a = x0 + w * i / steps;
            int b = x0 + w * (i + 1) / steps;
            r.fill(a, y0, b, y1, mix(left, right, steps == 1 ? 0f : i / (float) (steps - 1)));
        }
    }

    /** A 1px black separator with a faint light line under it. */
    public static void separator(GuiRects r, int x0, int x1, int y) {
        r.fill(x0, y, x1, y + 1, BLACK);
        r.fill(x0, y + 1, x1, y + 2, 0x12FFFFFF);
    }

    /** Width and height of {@link #toggle}. */
    public static final int SWITCH_W = 20;
    public static final int SWITCH_H = 10;

    /** The on/off switch: a recessed pill, orange gradient + glow when on, with a bevelled knob. */
    public static void toggle(GuiRects r, int x, int y, boolean on, boolean hover) {
        int w = SWITCH_W;
        int h = SWITCH_H;
        if (on) {
            glow(r, x, y, w, h, OR, hover ? 0x60 : 0x40);
        }
        // pill: square ends softened by clipping a pixel off each corner
        int top = on ? 0xFFC45F0A : 0xFF2E2721;
        int bottom = on ? 0xFFFF8A1F : 0xFF3B332B;
        r.fill(x + 1, y, x + w - 1, y + 1, BLACK);
        r.fill(x + 1, y + h - 1, x + w - 1, y + h, BLACK);
        r.fill(x, y + 1, x + 1, y + h - 1, BLACK);
        r.fill(x + w - 1, y + 1, x + w, y + h - 1, BLACK);
        r.gradient(x + 1, y + 1, x + w - 1, y + h - 1, top, bottom);
        r.fill(x + 1, y + 1, x + w - 1, y + 2, alpha(BLACK, 0x70));
        r.fill(x + 1, y + 2, x + 2, y + h - 1, alpha(BLACK, 0x50));
        int k = h - 2;
        int kx = on ? x + w - 1 - k : x + 1;
        int ky = y + 1;
        int knob = on ? 0xFFFFFFFF : (hover ? 0xFFA59C90 : KNOB_OFF);
        r.fill(kx + 1, ky, kx + k - 1, ky + k, knob);
        r.fill(kx, ky + 1, kx + k, ky + k - 1, knob);
        r.fill(kx + 1, ky, kx + k - 1, ky + 1, 0x60FFFFFF);
        r.fill(kx + 1, ky + k - 1, kx + k - 1, ky + k, 0x4D000000);
    }

    /** A recessed pill holding a value (the cycling buttons): dark well, an orange-gradient face when {@code lit}. */
    public static void pill(GuiRects r, int x, int y, int w, int h, boolean lit, boolean hover) {
        r.outline(x, y, w, h, BLACK);
        if (lit) {
            r.gradient(x + 1, y + 1, x + w - 1, y + h - 1, hover ? 0xFFFFB060 : OR_LIGHT, hover ? 0xFFE87A1A : 0xFFD86A0A);
            r.fill(x + 1, y + 1, x + w - 1, y + 2, 0x60FFFFFF);
            r.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0x50000000);
        } else {
            r.fill(x + 1, y + 1, x + w - 1, y + h - 1, hover ? 0xFF15110D : 0xFF0A0908);
            r.fill(x + 1, y + 1, x + w - 1, y + 2, 0xC0000000);
            r.fill(x + 1, y + 2, x + 2, y + h - 1, 0x80000000);
        }
    }

    /** A raised button with the orange outline ring (.btn): dark-orange gradient face, black edge, orange ring outside. */
    public static void raisedButton(GuiRects r, int x, int y, int w, int h, boolean hover, boolean pressedLook) {
        dropShadow(r, x, y, w, h, 2);
        r.outline(x - 1, y - 1, w + 2, h + 2, hover ? OR : 0xFF7A4A18);
        int top = pressedLook ? 0xFF4A2A08 : (hover ? 0xFF4A3016 : 0xFF3A2510);
        int bottom = pressedLook ? 0xFF63380B : (hover ? 0xFF2C1C0C : 0xFF22160A);
        r.gradient(x + 1, y + 1, x + w - 1, y + h - 1, top, bottom);
        r.outline(x, y, w, h, BLACK);
        r.fill(x + 1, y + 1, x + w - 1, y + 2, 0x28FFFFFF);
        r.fill(x + 1, y + 2, x + 2, y + h - 1, 0x28FFFFFF);
        r.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0x80000000);
        r.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, 0x80000000);
    }

    /** A red raised button (a destructive action asking for its confirming click). */
    public static void dangerButton(GuiRects r, int x, int y, int w, int h, boolean hover) {
        dropShadow(r, x, y, w, h, 2);
        r.outline(x - 1, y - 1, w + 2, h + 2, hover ? 0xFFFF4444 : 0xFFCC2222);
        r.gradient(x + 1, y + 1, x + w - 1, y + h - 1, hover ? 0xFF7A1A1A : 0xFF5A1010, hover ? 0xFF4A0E0E : 0xFF3A0A0A);
        r.outline(x, y, w, h, BLACK);
        r.fill(x + 1, y + 1, x + w - 1, y + 2, 0x28FFFFFF);
    }

    /** The orange scrollbar thumb (5 wide) in a dark track: {@code offset} of {@code max}, {@code view} of {@code view+max}. */
    public static void scrollbar(GuiRects r, int x, int top, int height, int offset, int max, int view) {
        if (max <= 0 || height <= 6) {
            return;
        }
        r.fill(x, top, x + 4, top + height, 0x80000000);
        int content = view + max;
        int thumb = Math.max(14, height * view / Math.max(1, content));
        int ty = top + (height - thumb) * Math.max(0, Math.min(offset, max)) / max;
        glow(r, x, ty, 4, thumb, OR, 0x30);
        hGradient(r, x, ty, x + 4, ty + thumb, OR_LIGHT, OR_DARK, 4);
        r.fill(x, ty, x + 1, ty + thumb, 0x50FFFFFF);
    }

    /** The section band (.sec): a fading orange (or red) gradient strip with a black top edge. */
    public static void sectionBand(GuiRects r, int x, int y, int w, int h, boolean red) {
        r.fill(x, y, x + w, y + 1, BLACK);
        r.fill(x, y + 1, x + w, y + 2, 0x0CFFFFFF);
        hGradient(r, x, y + 1, x + w, y + h, red ? 0xFF3A1414 : 0xFF2A1D10, red ? 0x003A1414 : 0x002A1D10, 12);
    }

    /** Slider track (recessed) with the filled part orange and a bevelled white knob. */
    public static void sliderTrack(GuiRects r, int x0, int x1, int cy, double t, boolean hover) {
        int h = 4;
        int y = cy - h / 2;
        r.outline(x0, y, x1 - x0, h, BLACK);
        r.fill(x0 + 1, y + 1, x1 - 1, y + h - 1, TRACK);
        r.fill(x0 + 1, y + 1, x1 - 1, y + 2, 0xA0000000);
        int kx = x0 + (int) Math.round(Math.max(0, Math.min(1, t)) * (x1 - x0));
        if (kx > x0 + 1) {
            r.gradient(x0 + 1, y + 1, Math.min(kx, x1 - 1), y + h - 1, OR_LIGHT, OR_DARK);
        }
        knob(r, kx - 4, cy - 4, hover);
    }

    /** An 8x8 rounded white knob with a black edge. */
    public static void knob(GuiRects r, int x, int y, boolean hover) {
        int s = 8;
        r.fill(x + 2, y, x + s - 2, y + s, BLACK);
        r.fill(x, y + 2, x + s, y + s - 2, BLACK);
        r.fill(x + 1, y + 1, x + s - 1, y + s - 1, BLACK);
        int face = hover ? 0xFFFFE0C0 : 0xFFFFFFFF;
        r.fill(x + 2, y + 1, x + s - 2, y + s - 1, face);
        r.fill(x + 1, y + 2, x + s - 1, y + s - 2, face);
        r.fill(x + 2, y + s - 2, x + s - 2, y + s - 1, 0xFFB8B0A8);
        r.fill(x + 1, y + s - 3, x + 2, y + s - 2, 0xFFB8B0A8);
        r.fill(x + s - 2, y + s - 3, x + s - 1, y + s - 2, 0xFFB8B0A8);
    }

    /** A tiny orange dot with a glow (the "changed from default" marker). */
    public static void dot(GuiRects r, int x, int y) {
        r.fill(x - 1, y - 1, x + 4, y + 4, 0x30FF8A1F);
        r.fill(x, y, x + 3, y + 3, OR);
    }

    // ---- text -----------------------------------------------------------------------------------------------------

    /** Text with the 1px black drop shadow (text-shadow: 1px 1px 0 #000c). */
    public static void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(font(), s, x, y, color, true);
    }

    public static void text(GuiGraphicsExtractor g, Component s, int x, int y, int color) {
        g.text(font(), s, x, y, color, true);
    }

    public static void text(GuiGraphicsExtractor g, FormattedCharSequence s, int x, int y, int color) {
        g.text(font(), s, x, y, color, true);
    }

    /** Text with no shadow. */
    public static void flat(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(font(), s, x, y, color, false);
    }

    /**
     * Text with a vertical gradient (the brand title: #ffb15a to #ff7a0a), drawn three times through horizontal scissor
     * bands. Shadowed once underneath. {@code scale} draws it larger about (x, y).
     */
    public static void gradientText(GuiGraphicsExtractor g, String s, int x, int y, float scale, int top, int bottom) {
        Font f = font();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(f, s, 1, 1, 0xFF000000, false);
        int w = f.width(s);
        int[] cuts = {0, 3, 6, 9};
        for (int i = 0; i < 3; i++) {
            int c = mix(top, bottom, i / 2f);
            g.enableScissor(0, cuts[i], w + 2, cuts[i + 1]);
            g.text(f, s, 0, 0, c, false);
            g.disableScissor();
        }
        g.pose().popMatrix();
    }

    /** {@code s} cut to {@code maxW} pixels with "..." when it does not fit (formatting codes kept). */
    public static String ellipsize(String s, int maxW) {
        Font f = font();
        if (f.width(s) <= maxW) {
            return s;
        }
        int dots = f.width("...");
        if (maxW <= dots) {
            return "";
        }
        String cut = f.plainSubstrByWidth(s, maxW - dots);
        return cut + "...";
    }

    /** Word-wrapped lines of plain {@code s} at {@code width}, at most {@code maxLines}; the last one ellipsized. */
    public static List<String> wrap(String s, int width, int maxLines) {
        Font f = font();
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank() || width < 8) {
            return out;
        }
        String[] words = s.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        int i = 0;
        while (i < words.length) {
            String w = words[i];
            String cand = line.isEmpty() ? w : line + " " + w;
            if (f.width(cand) <= width) {
                line.setLength(0);
                line.append(cand);
                i++;
                continue;
            }
            if (line.isEmpty()) {
                // a single word wider than the line: hard cut
                String cut = f.plainSubstrByWidth(w, width);
                if (cut.isEmpty()) {
                    break;
                }
                out.add(cut);
                words[i] = w.substring(cut.length());
            } else {
                out.add(line.toString());
                line.setLength(0);
            }
            if (out.size() == maxLines) {
                break;
            }
        }
        if (out.size() < maxLines && !line.isEmpty()) {
            out.add(line.toString());
            line.setLength(0);
        }
        boolean more = i < words.length || !line.isEmpty();
        if (more && !out.isEmpty()) {
            int last = out.size() - 1;
            out.set(last, ellipsize(out.get(last) + " " + String.join(" ", java.util.Arrays.copyOfRange(words,
                    Math.min(i, words.length), words.length)), width));
        }
        return out;
    }
}
