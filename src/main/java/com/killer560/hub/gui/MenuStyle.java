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

    // ---- palette ----------------------------------------------------------------------------------------------------
    // Every colour the menu draws. Derived by use() from the active MenuTheme's five base colours; the initial values
    // are Amber's (the mockup's :root and depth pass). Mutable on purpose: ModScreen calls use() before it draws.
    public static int OR = 0xFFFF8A1F;
    public static int OR2 = 0xFFC76A14;
    public static int OR_LIGHT = 0xFFFFA040;
    public static int OR_DARK = 0xFFC06010;
    /** A hovered accent (brighter on dark themes, deeper on light ones). */
    public static int OR_HOVER = 0xFFFFB060;
    /** A dim accent line: the raised button's ring, a hovered card's outline, the selected row's border. */
    public static int ACCENT_EDGE = 0xFF7A4A18;
    public static int TXT = 0xFFE9E2D8;
    public static int TXT_HOVER = 0xFFFFF4E8;
    public static int TXT_SOFT = 0xFFD8CFC4;
    public static int DIM = 0xFF9A8F82;
    public static int DIM2 = 0xFF6B6259;
    public static int RED = 0xFFE05555;
    public static int RED_TITLE = 0xFFFF5555;
    public static int RED_BAND = 0xFF3A1414;
    /** The outline colour (black on dark themes, a mid grey on light ones). */
    public static int BLACK = 0xFF000000;
    public static int WELL = 0xFF0B0907;
    public static int WELL_DEEP = 0xFF070605;
    public static int HILITE = 0x1CFFFFFF;
    public static int HILITE_SOFT = 0x12FFFFFF;
    public static int SHADE = 0x80000000;
    /** How strong drop shadows and inner shadows are (1 dark, ~0.35 light). */
    public static float SHADOW = 1f;
    /** Whether text gets the 1px drop shadow (dark themes) - dark text on a light panel does not. */
    public static boolean TEXT_SHADOW = true;
    public static boolean LIGHT = false;
    public static int TRACK = 0xFF3B332B;
    public static int KNOB_OFF = 0xFF8D8478;
    public static int SW_OFF_TOP = 0xFF2E2721;
    public static int SW_OFF_BOTTOM = 0xFF3B332B;
    public static int SW_ON_TOP = 0xFFC45F0A;
    public static int SW_ON_BOTTOM = 0xFFFF8A1F;
    public static int PANEL_TOP = 0xFF1C1712;
    public static int PANEL_BOTTOM = 0xFF120F0C;
    public static int FRAME_RING = 0xFF4A3420;
    public static int HEADER_TOP = 0xFF2A2119;
    public static int HEADER_BOTTOM = 0xFF1A1510;
    public static int SIDE_TOP = 0xFF19140F;
    public static int SIDE_BOTTOM = 0xFF16120E;
    public static int CARD_TOP = 0xFF251E17;
    public static int CARD_BOTTOM = 0xFF1A1510;
    public static int CARD_HOVER_TOP = 0xFF2D241B;
    public static int CARD_HOVER_BOTTOM = 0xFF201912;
    public static int DRAWER_TOP = 0xFF1D1813;
    public static int DRAWER_BOTTOM = 0xFF13100C;
    public static int TITLE_TOP = 0xFF2C2219;
    public static int TITLE_BOTTOM = 0xFF1D1712;
    public static int BOX_TOP = 0xFF211A14;
    public static int BOX_BOTTOM = 0xFF16120E;
    public static int SEL_TOP = 0xFF3A220C;
    public static int SEL_BOTTOM = 0xFF24160A;
    public static int BTN_TOP = 0xFF3A2510;
    public static int BTN_BOTTOM = 0xFF22160A;
    public static int BTN_HOVER_TOP = 0xFF4A3016;
    public static int BTN_HOVER_BOTTOM = 0xFF2C1C0C;
    public static int BTN_PRESSED_TOP = 0xFF4A2A08;
    public static int BTN_PRESSED_BOTTOM = 0xFF63380B;
    public static int BAND = 0xFF2A1D10;
    public static int PILL_BG = 0xFF0A0908;
    public static int PILL_BG_HOVER = 0xFF15110D;
    public static int GEAR = 0xFF4A423A;
    /** The veil over a switched-off card (alpha included), and its lighter hovered version. */
    public static int OFF_VEIL = 0x800B0907;
    public static int OFF_VEIL_HOVER = 0x500B0907;
    /** The dim behind the whole menu. */
    public static int BACKDROP = 0xB0000000;
    public static int BRAND_TOP = 0xFFFFB15A;
    public static int BRAND_BOTTOM = 0xFFFF7A0A;
    public static int TOOLTIP_TOP = 0xF51D1813;
    public static int TOOLTIP_BOTTOM = 0xF513100C;
    public static int KEY_CHIP_BG = 0xFF0D0B09;
    public static int KEY_CHIP_EDGE = 0xFF3A2A1A;
    public static int KNOB = 0xFFFFFFFF;
    public static int KNOB_HOVER = 0xFFFFE0C0;
    public static int KNOB_EDGE = 0xFFB8B0A8;

    private static int[] usedColours;

    /**
     * Derives the palette from the active theme (cheap when nothing changed). Call before drawing. While a
     * {@link MenuThemedScreen} with its own Theme setting is open that screen's colours are used
     * ({@link MenuTheme#forScreen}; "Theme Default" is the menu's), otherwise the menu's own.
     */
    public static void use() {
        int[] c = null;
        if (!ModScreen.drawingClosed // a closed menu fading out over another screen keeps the menu's colours
                && com.killer560.hub.compat.McCompat.screen(Minecraft.getInstance()) instanceof MenuThemedScreen t
                && t.themeId() != null) {
            c = MenuTheme.forScreen(t.themeId());
        }
        if (c == null) {
            c = MenuConfig.getInstance().activeColours();
        }
        if (java.util.Arrays.equals(c, usedColours)) {
            return;
        }
        usedColours = c.clone();
        derive(c[0], c[1], c[2], c[3], c[4]);
    }

    /** Derives the palette from five base colours given directly: the Bazaar and Auction House, whose reskins draw over
     *  a vanilla container (not a {@link MenuThemedScreen}), pass their own Theme's {@link MenuTheme#forScreen}. The next
     *  {@link #use()} re-derives the menu's (or its screen's) own. */
    public static void use(int[] c) {
        if (c == null || java.util.Arrays.equals(c, usedColours)) {
            return;
        }
        usedColours = c.clone();
        derive(c[0], c[1], c[2], c[3], c[4]);
    }

    /**
     * The palette five base colours give, as a snapshot, WITHOUT leaving {@link MenuStyle}'s statics changed (the menu or
     * screen being drawn keeps its own colours): an overlay that is not itself a themed screen reads its colours from
     * this ({@link OverlayThemes}).
     */
    static OverlayPalette snapshot(int[] c) {
        int[] prev = usedColours == null ? null : usedColours.clone();
        derive(c[0], c[1], c[2], c[3], c[4]);
        OverlayPalette p = new OverlayPalette(LIGHT, c.clone(), BACKDROP, PANEL_TOP, PANEL_BOTTOM, CARD_TOP, CARD_HOVER_TOP,
                WELL, ACCENT_EDGE, BLACK, OR, OR_HOVER, OR_DARK, BTN_TOP, BTN_HOVER_TOP, SEL_TOP, TXT, TXT_HOVER, DIM, DIM2,
                SW_ON_BOTTOM);
        if (prev != null) {
            derive(prev[0], prev[1], prev[2], prev[3], prev[4]);
        }
        usedColours = prev;
        return p;
    }

    private static int lift(int c, float t) {
        return mix(c, 0xFFFFFFFF, t);
    }

    private static int sink(int c, float t) {
        return mix(c, 0xFF000000, t);
    }

    /** 0 (black) .. 1 (white). */
    public static float luminance(int c) {
        return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
    }

    /** Every palette colour from the five base colours (accent, panel, card, text, on-switch). */
    public static void derive(int accent, int panel, int card, int text, int switchOn) {
        accent |= 0xFF000000;
        panel |= 0xFF000000;
        card |= 0xFF000000;
        text |= 0xFF000000;
        switchOn |= 0xFF000000;
        boolean light = luminance(panel) > 0.5f;
        LIGHT = light;
        TEXT_SHADOW = !light;
        SHADOW = light ? 0.35f : 1f;
        OR = accent;
        OR_LIGHT = light ? accent : lift(accent, 0.25f);
        OR_DARK = sink(accent, 0.25f);
        OR2 = light ? sink(accent, 0.15f) : mix(accent, panel, 0.22f);
        OR_HOVER = light ? sink(accent, 0.25f) : lift(accent, 0.4f);
        ACCENT_EDGE = mix(panel, accent, light ? 0.55f : 0.42f);
        TXT = text;
        TXT_HOVER = light ? sink(text, 0.4f) : lift(text, 0.5f);
        TXT_SOFT = mix(text, panel, 0.12f);
        DIM = mix(text, panel, 0.4f);
        DIM2 = mix(text, panel, 0.58f);
        RED = light ? 0xFFB02A2A : 0xFFE05555;
        RED_TITLE = light ? 0xFFC62828 : 0xFFFF5555;
        RED_BAND = mix(panel, 0xFFC02020, light ? 0.18f : 0.22f);
        BLACK = light ? sink(panel, 0.42f) : 0xFF000000;
        HILITE = light ? 0x90FFFFFF : 0x1CFFFFFF;
        HILITE_SOFT = light ? 0x60FFFFFF : 0x12FFFFFF;
        SHADE = light ? 0x28000000 : 0x80000000;
        WELL = light ? sink(panel, 0.07f) : sink(panel, 0.6f);
        WELL_DEEP = light ? lift(panel, 0.7f) : sink(panel, 0.72f);
        TRACK = light ? sink(panel, 0.18f) : mix(panel, text, 0.15f);
        KNOB_OFF = mix(panel, text, light ? 0.35f : 0.5f);
        SW_OFF_TOP = light ? sink(panel, 0.2f) : mix(panel, text, 0.08f);
        SW_OFF_BOTTOM = light ? sink(panel, 0.12f) : mix(panel, text, 0.15f);
        SW_ON_TOP = sink(switchOn, 0.25f);
        SW_ON_BOTTOM = switchOn;
        PANEL_TOP = light ? lift(panel, 0.25f) : lift(panel, 0.02f);
        PANEL_BOTTOM = light ? sink(panel, 0.04f) : sink(panel, 0.3f);
        FRAME_RING = mix(panel, accent, light ? 0.45f : 0.25f);
        HEADER_TOP = light ? lift(panel, 0.55f) : mix(lift(panel, 0.06f), accent, 0.04f);
        HEADER_BOTTOM = light ? lift(panel, 0.15f) : sink(panel, 0.05f);
        SIDE_TOP = light ? lift(panel, 0.35f) : sink(panel, 0.1f);
        SIDE_BOTTOM = light ? lift(panel, 0.1f) : sink(panel, 0.2f);
        CARD_TOP = card;
        CARD_BOTTOM = light ? sink(card, 0.06f) : sink(card, 0.3f);
        CARD_HOVER_TOP = light ? lift(card, 0.5f) : lift(card, 0.05f);
        CARD_HOVER_BOTTOM = light ? sink(card, 0.02f) : sink(card, 0.14f);
        DRAWER_TOP = light ? lift(panel, 0.45f) : lift(panel, 0.02f);
        DRAWER_BOTTOM = light ? lift(panel, 0.1f) : sink(panel, 0.32f);
        TITLE_TOP = light ? lift(card, 0.4f) : mix(lift(card, 0.05f), accent, 0.03f);
        TITLE_BOTTOM = light ? sink(card, 0.04f) : sink(card, 0.2f);
        BOX_TOP = light ? card : mix(panel, card, 0.6f);
        BOX_BOTTOM = light ? sink(card, 0.05f) : sink(panel, 0.2f);
        SEL_TOP = mix(panel, accent, light ? 0.3f : 0.22f);
        SEL_BOTTOM = mix(panel, accent, light ? 0.14f : 0.1f);
        BTN_TOP = light ? lift(mix(panel, accent, 0.12f), 0.4f) : mix(panel, accent, 0.15f);
        BTN_BOTTOM = light ? mix(panel, accent, 0.1f) : mix(sink(panel, 0.2f), accent, 0.07f);
        BTN_HOVER_TOP = light ? lift(mix(panel, accent, 0.2f), 0.4f) : mix(panel, accent, 0.22f);
        BTN_HOVER_BOTTOM = light ? mix(panel, accent, 0.18f) : mix(panel, accent, 0.1f);
        BTN_PRESSED_TOP = mix(panel, accent, light ? 0.35f : 0.28f);
        BTN_PRESSED_BOTTOM = mix(panel, accent, light ? 0.45f : 0.38f);
        BAND = mix(panel, accent, light ? 0.16f : 0.1f);
        PILL_BG = light ? sink(panel, 0.1f) : sink(panel, 0.62f);
        PILL_BG_HOVER = light ? sink(panel, 0.16f) : sink(panel, 0.45f);
        GEAR = mix(text, panel, 0.72f);
        OFF_VEIL = alpha(light ? panel : sink(panel, 0.55f), light ? 0x99 : 0x80);
        OFF_VEIL_HOVER = alpha(light ? panel : sink(panel, 0.55f), light ? 0x60 : 0x50);
        BACKDROP = light ? 0x90000000 : 0xB0000000;
        BRAND_TOP = light ? lift(accent, 0.1f) : lift(accent, 0.3f);
        BRAND_BOTTOM = light ? sink(accent, 0.2f) : sink(accent, 0.05f);
        TOOLTIP_TOP = alpha(light ? lift(card, 0.3f) : lift(panel, 0.02f), 0xF5);
        TOOLTIP_BOTTOM = alpha(light ? card : sink(panel, 0.3f), 0xF5);
        KEY_CHIP_BG = light ? lift(panel, 0.5f) : sink(panel, 0.5f);
        KEY_CHIP_EDGE = mix(panel, accent, light ? 0.3f : 0.15f);
        KNOB = 0xFFFFFFFF;
        KNOB_HOVER = lift(accent, 0.75f);
        KNOB_EDGE = light ? 0xFFC8C4BE : 0xFFB8B0A8;
    }

    /** A shadow colour at {@code a} (0..255), scaled by the theme's shadow strength. */
    public static int shadow(int a) {
        return alpha(0xFF000000, Math.round(a * SHADOW));
    }

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

    /** {@code a} at 0, {@code b} at 1, mixed between (exactly a / b at the ends, so a settled animation draws the same
     *  pixels as no animation). */
    public static int ease(int a, int b, float t) {
        return t <= 0f ? a : t >= 1f ? b : mix(a, b, t);
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
            int c = shadow(a[i]);
            r.fill(x + 1, y + 1 + i, x + w - 1, y + 2 + i, c);
            r.fill(x + 1 + i, y + 2 + i, x + 2 + i, y + h - 1, c);
        }
        r.fill(x + 1, y + h - 1, x + w, y + h, HILITE_SOFT);
        r.fill(x + w - 1, y + 1, x + w, y + h - 1, HILITE_SOFT);
    }

    /** A drop shadow under/right of a box (0 3px 6px #000c), drawn BEFORE the box. */
    public static void dropShadow(GuiRects r, int x, int y, int w, int h, int depth) {
        int[] a = {0x90, 0x50, 0x24};
        for (int i = 0; i < Math.min(depth, a.length); i++) {
            int c = shadow(a[i]);
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
        r.fill(x0, y + 1, x1, y + 2, HILITE_SOFT);
    }

    /** Width and height of {@link #toggle}. */
    public static final int SWITCH_W = 20;
    public static final int SWITCH_H = 10;

    /** The on/off switch: a recessed pill, orange gradient + glow when on, with a bevelled knob. */
    public static void toggle(GuiRects r, int x, int y, boolean on, boolean hover) {
        toggle(r, x, y, on ? 1f : 0f, on ? 1f : 0f, hover ? 1f : 0f);
    }

    /**
     * The switch part way between off and on (gui/MenuAnim): {@code knob} places the knob (0 left .. 1 right),
     * {@code colour} blends the track, glow and knob colours, {@code hover} the hover shade. At 0/1 it draws exactly
     * the boolean switch's pixels.
     */
    public static void toggle(GuiRects r, int x, int y, float knob, float colour, float hover) {
        int w = SWITCH_W;
        int h = SWITCH_H;
        if (colour > 0f) {
            glow(r, x, y, w, h, SW_ON_BOTTOM, Math.round((0x40 + 0x20 * hover) * colour));
        }
        // pill: square ends softened by clipping a pixel off each corner
        int top = colour <= 0f ? SW_OFF_TOP : colour >= 1f ? SW_ON_TOP : mix(SW_OFF_TOP, SW_ON_TOP, colour);
        int bottom = colour <= 0f ? SW_OFF_BOTTOM : colour >= 1f ? SW_ON_BOTTOM : mix(SW_OFF_BOTTOM, SW_ON_BOTTOM, colour);
        r.fill(x + 1, y, x + w - 1, y + 1, BLACK);
        r.fill(x + 1, y + h - 1, x + w - 1, y + h, BLACK);
        r.fill(x, y + 1, x + 1, y + h - 1, BLACK);
        r.fill(x + w - 1, y + 1, x + w, y + h - 1, BLACK);
        r.gradient(x + 1, y + 1, x + w - 1, y + h - 1, top, bottom);
        r.fill(x + 1, y + 1, x + w - 1, y + 2, shadow(0x70));
        r.fill(x + 1, y + 2, x + 2, y + h - 1, shadow(0x50));
        int k = h - 2;
        int kx = x + 1 + Math.round(Math.max(0f, Math.min(1f, knob)) * (w - 2 - k));
        int ky = y + 1;
        int offKnob = hover <= 0f ? KNOB_OFF : mix(KNOB_OFF, mix(KNOB_OFF, TXT, 0.3f), hover);
        int knobColour = colour <= 0f ? offKnob : colour >= 1f ? KNOB : mix(offKnob, KNOB, colour);
        r.fill(kx + 1, ky, kx + k - 1, ky + k, knobColour);
        r.fill(kx, ky + 1, kx + k, ky + k - 1, knobColour);
        r.fill(kx + 1, ky, kx + k - 1, ky + 1, 0x60FFFFFF);
        r.fill(kx + 1, ky + k - 1, kx + k - 1, ky + k, shadow(0x4D));
    }

    /** A recessed pill holding a value (the cycling buttons): dark well, an orange-gradient face when {@code lit}. */
    public static void pill(GuiRects r, int x, int y, int w, int h, boolean lit, boolean hover) {
        pill(r, x, y, w, h, lit, hover ? 1f : 0f);
    }

    /** {@link #pill} with an eased hover (0..1). */
    public static void pill(GuiRects r, int x, int y, int w, int h, boolean lit, float hover) {
        r.outline(x, y, w, h, BLACK);
        if (lit) {
            r.gradient(x + 1, y + 1, x + w - 1, y + h - 1, ease(OR_LIGHT, OR_HOVER, hover), ease(OR_DARK, OR, hover));
            r.fill(x + 1, y + 1, x + w - 1, y + 2, 0x60FFFFFF);
            r.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, shadow(0x50));
        } else {
            r.fill(x + 1, y + 1, x + w - 1, y + h - 1, ease(PILL_BG, PILL_BG_HOVER, hover));
            r.fill(x + 1, y + 1, x + w - 1, y + 2, shadow(0xC0));
            r.fill(x + 1, y + 2, x + 2, y + h - 1, shadow(0x80));
        }
    }

    /** A raised button with the orange outline ring (.btn): dark-orange gradient face, black edge, orange ring outside. */
    public static void raisedButton(GuiRects r, int x, int y, int w, int h, boolean hover, boolean pressedLook) {
        raisedButton(r, x, y, w, h, hover ? 1f : 0f, pressedLook ? 1f : 0f);
    }

    /** {@link #raisedButton} with an eased hover and press (0..1 each; press 1 is the pressed look). */
    public static void raisedButton(GuiRects r, int x, int y, int w, int h, float hover, float press) {
        dropShadow(r, x, y, w, h, 2);
        r.outline(x - 1, y - 1, w + 2, h + 2, ease(ACCENT_EDGE, OR, hover));
        int top = ease(ease(BTN_TOP, BTN_HOVER_TOP, hover), BTN_PRESSED_TOP, press);
        int bottom = ease(ease(BTN_BOTTOM, BTN_HOVER_BOTTOM, hover), BTN_PRESSED_BOTTOM, press);
        r.gradient(x + 1, y + 1, x + w - 1, y + h - 1, top, bottom);
        r.outline(x, y, w, h, BLACK);
        r.fill(x + 1, y + 1, x + w - 1, y + 2, HILITE);
        r.fill(x + 1, y + 2, x + 2, y + h - 1, HILITE);
        r.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, SHADE);
        r.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, SHADE);
    }

    /** A red raised button (a destructive action asking for its confirming click). */
    public static void dangerButton(GuiRects r, int x, int y, int w, int h, boolean hover) {
        dropShadow(r, x, y, w, h, 2);
        r.outline(x - 1, y - 1, w + 2, h + 2, hover ? 0xFFFF4444 : 0xFFCC2222);
        r.gradient(x + 1, y + 1, x + w - 1, y + h - 1, hover ? 0xFF7A1A1A : 0xFF5A1010, hover ? 0xFF4A0E0E : 0xFF3A0A0A);
        r.outline(x, y, w, h, BLACK);
        r.fill(x + 1, y + 1, x + w - 1, y + 2, HILITE);
    }

    /** The orange scrollbar thumb (5 wide) in a dark track: {@code offset} of {@code max}, {@code view} of {@code view+max}. */
    public static void scrollbar(GuiRects r, int x, int top, int height, int offset, int max, int view) {
        if (max <= 0 || height <= 6) {
            return;
        }
        r.fill(x, top, x + 4, top + height, shadow(0x80));
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
        r.fill(x, y + 1, x + w, y + 2, HILITE_SOFT);
        int c = red ? RED_BAND : BAND;
        hGradient(r, x, y + 1, x + w, y + h, c, alpha(c, 0), 12);
    }

    /** Slider track (recessed) with the filled part orange and a bevelled white knob. */
    public static void sliderTrack(GuiRects r, int x0, int x1, int cy, double t, boolean hover) {
        sliderTrack(r, x0, x1, cy, t, hover ? 1f : 0f);
    }

    /** {@link #sliderTrack} with an eased knob hover. */
    public static void sliderTrack(GuiRects r, int x0, int x1, int cy, double t, float hover) {
        int h = 4;
        int y = cy - h / 2;
        r.outline(x0, y, x1 - x0, h, BLACK);
        r.fill(x0 + 1, y + 1, x1 - 1, y + h - 1, TRACK);
        r.fill(x0 + 1, y + 1, x1 - 1, y + 2, shadow(0xA0));
        int kx = x0 + (int) Math.round(Math.max(0, Math.min(1, t)) * (x1 - x0));
        if (kx > x0 + 1) {
            r.gradient(x0 + 1, y + 1, Math.min(kx, x1 - 1), y + h - 1, OR_LIGHT, OR_DARK);
        }
        knob(r, kx - 4, cy - 4, hover);
    }

    /** An 8x8 rounded white knob with a black edge. */
    public static void knob(GuiRects r, int x, int y, boolean hover) {
        knob(r, x, y, hover ? 1f : 0f);
    }

    /** {@link #knob} with an eased hover. */
    public static void knob(GuiRects r, int x, int y, float hover) {
        int s = 8;
        r.fill(x + 2, y, x + s - 2, y + s, BLACK);
        r.fill(x, y + 2, x + s, y + s - 2, BLACK);
        r.fill(x + 1, y + 1, x + s - 1, y + s - 1, BLACK);
        int face = ease(KNOB, KNOB_HOVER, hover);
        r.fill(x + 2, y + 1, x + s - 2, y + s - 1, face);
        r.fill(x + 1, y + 2, x + s - 1, y + s - 2, face);
        r.fill(x + 2, y + s - 2, x + s - 2, y + s - 1, KNOB_EDGE);
        r.fill(x + 1, y + s - 3, x + 2, y + s - 2, KNOB_EDGE);
        r.fill(x + s - 2, y + s - 3, x + s - 1, y + s - 2, KNOB_EDGE);
    }

    /** A tiny orange dot with a glow (the "changed from default" marker). */
    public static void dot(GuiRects r, int x, int y) {
        r.fill(x - 1, y - 1, x + 4, y + 4, alpha(OR, 0x30));
        r.fill(x, y, x + 3, y + 3, OR);
    }

    // ---- text -----------------------------------------------------------------------------------------------------

    /** Text with the 1px black drop shadow (text-shadow: 1px 1px 0 #000c). */
    public static void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(font(), s, x, y, color, TEXT_SHADOW);
    }

    public static void text(GuiGraphicsExtractor g, Component s, int x, int y, int color) {
        g.text(font(), s, x, y, color, TEXT_SHADOW);
    }

    public static void text(GuiGraphicsExtractor g, FormattedCharSequence s, int x, int y, int color) {
        g.text(font(), s, x, y, color, TEXT_SHADOW);
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
        if (TEXT_SHADOW) {
            g.text(f, s, 1, 1, 0xFF000000, false);
        }
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

    /** Legacy colour codes that wash out on a light panel swapped for their darker partners (white text becomes black). */
    public static String lightReadable(String raw) {
        if (raw.indexOf('§') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            out.append(ch);
            if (ch == '§' && i + 1 < raw.length()) {
                char code = Character.toLowerCase(raw.charAt(i + 1));
                char to = switch (code) {
                    case 'a' -> '2';
                    case 'b' -> '3';
                    case 'e' -> '6';
                    case 'f' -> '0';
                    case '7' -> '8';
                    case 'd' -> '5';
                    default -> code;
                };
                out.append(to);
                i++;
            }
        }
        return out.toString();
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

    /** True when {@code s} word-wraps into at most {@code maxLines} lines of {@code width} with nothing cut or ellipsized. */
    public static boolean fits(String s, int width, int maxLines) {
        if (s == null || s.isBlank()) {
            return true;
        }
        Font f = font();
        if (maxLines <= 0 || width < 8) {
            return false;
        }
        int lines = 1;
        int lineW = 0;
        int space = f.width(" ");
        for (String word : s.trim().split("\\s+")) {
            int ww = f.width(word);
            if (ww > width) {
                return false;
            }
            if (lineW == 0) {
                lineW = ww;
            } else if (lineW + space + ww <= width) {
                lineW += space + ww;
            } else {
                lines++;
                lineW = ww;
            }
        }
        return lines <= maxLines;
    }

    /** Word-wrapped lines of plain {@code s} at {@code width}, at most {@code maxLines}; the last one ellipsized. */
    public static List<String> wrap(String s, int width, int maxLines) {
        Font f = font();
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank() || width < 8 || maxLines <= 0) {
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
