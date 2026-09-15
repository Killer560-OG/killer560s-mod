package com.killer560.hub.mainmenu;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Black+orange restyle of the vanilla title screen (2026-09-15, killer560: "redo the main menu to fit our
 * theme more. Make the Minecraft buttons our theme and the background our theme and whatnot").
 * <p>
 * All drawing lives here; the mixins in {@code mainmenu.mixin} only decide WHEN to call it. Everything is
 * scoped to {@link TitleScreen} (other menus keep vanilla visuals) and guarded: the first exception thrown
 * by any themed draw is logged once and permanently flips {@link #active()} off for the session, so the
 * menu silently falls back to vanilla instead of crashing or spamming the log every frame.
 */
public final class MainMenuTheme {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-mainmenu");

    // Palette - same values as SettingsButtonWidget / ModScreen / ModChat so the title screen matches the
    // Swap Accounts button and the mod menu exactly.
    public static final int ORANGE = 0xCC6600;
    public static final int LIGHT_ORANGE = 0xFFA040;
    public static final int TEXT = 0xF0E6DC;
    public static final int DIM = 0x9A8C80;

    private static final int BTN_BG = 0xFF1A1A1A;
    private static final int BTN_BG_HOVER = 0xFF262626;
    private static final int BTN_BORDER = 0xFF663D1A;
    private static final int BTN_BORDER_HOVER = 0xFFCC6600;
    private static final int BTN_BG_DISABLED = 0xFF121212;
    private static final int BTN_BORDER_DISABLED = 0xFF3A2C22;

    private static volatile boolean failed;
    private static String modTag;

    private MainMenuTheme() {
    }

    /** Toggle on and nothing has failed this session. */
    public static boolean active() {
        return !failed && MainMenuThemeConfig.getInstance().isEnabled();
    }

    /** {@link #active()} and the screen currently shown is the title screen - used by the mixins on
     *  shared widget classes (AbstractButton, PlainTextButton) so other screens stay vanilla. */
    public static boolean activeOnTitleScreen() {
        return active() && Minecraft.getInstance().screen instanceof TitleScreen;
    }

    /** Widget theming scope (buttons, text fields, sliders, checkboxes, lists...): the title screen, plus -
     *  while "Themed Menus" is on - every other open screen except container screens (chests, inventories,
     *  terminals keep vanilla/feature visuals). Applies in and out of a world. */
    public static boolean activeOnMenus() {
        if (!active()) {
            return false;
        }
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null) {
            return false;
        }
        if (screen instanceof TitleScreen) {
            return true;
        }
        // Chat (and InBedChatScreen's Leave Bed button / mod-added chat widgets) stays vanilla, like the HUD.
        return MainMenuThemeConfig.getInstance().isOtherMenus() && !(screen instanceof AbstractContainerScreen<?>)
                && !(screen instanceof ChatScreen);
    }

    /** Background scope: {@link #activeOnMenus()} and no world loaded, so in-game menus keep the blurred
     *  world behind them and only out-of-world menus swap the panorama for the themed background. */
    public static boolean activeOnMenuBackground() {
        return activeOnMenus() && Minecraft.getInstance().level == null;
    }

    public static void fail(String where, Throwable t) {
        if (!failed) {
            failed = true;
            LOGGER.error("[MainMenu] Themed main menu disabled for this session after an error in {}", where, t);
        }
    }

    // ---------------------------------------------------------------- colour helpers

    /** rgb (0xRRGGBB) with an explicit 0..255 alpha. */
    public static int argb(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    /** Multiplies an ARGB colour's own alpha by {@code factor} (0..1). */
    public static int fade(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        return argb(argb, Math.round(a * Math.max(0f, Math.min(1f, factor))));
    }

    // ---------------------------------------------------------------- buttons

    public static void drawButtonBox(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                     boolean active, boolean highlighted, float alpha) {
        int bg;
        int border;
        if (!active) {
            bg = BTN_BG_DISABLED;
            border = BTN_BORDER_DISABLED;
        } else if (highlighted) {
            bg = BTN_BG_HOVER;
            border = BTN_BORDER_HOVER;
        } else {
            bg = BTN_BG;
            border = BTN_BORDER;
        }
        g.fill(x, y, x + w, y + h, fade(bg, alpha));
        g.outline(x, y, w, h, fade(border, alpha));
    }

    /** Wraps a label so its un-coloured parts render in the theme colour; explicit colours inside the
     *  component (another mod's coloured label, vanilla's grey inactive message) still win because
     *  children only inherit the parent colour when they don't set their own. */
    public static Component recolorLabel(Component message, boolean active, boolean highlighted) {
        if (message == null) {
            return null;
        }
        int rgb = !active ? DIM : (highlighted ? LIGHT_ORANGE : TEXT);
        return Component.empty().withColor(rgb).append(message);
    }

    // ---------------------------------------------------------------- footer

    public static String modTag() {
        String tag = modTag;
        if (tag == null) {
            tag = "Killer560's Mod";
            try {
                tag = FabricLoader.getInstance().getModContainer("killer560smod")
                        .map(c -> "Killer560's Mod " + c.getMetadata().getVersion().getFriendlyString())
                        .orElse(tag);
            } catch (Throwable ignored) {
            }
            modTag = tag;
        }
        return tag;
    }

    // ---------------------------------------------------------------- background

    /** Replaces the rotating panorama: dark warm gradient, two slowly drifting orange glows (one behind
     *  the logo, one rising from the bottom edge), optional drifting embers, and an edge vignette. All
     *  positions derive from the clock and the screen size - no per-frame state, so resizing / GUI scale
     *  changes are free and nothing accumulates. Roughly 250-400 flat quads per frame. */
    public static void drawBackground(GuiGraphicsExtractor g, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        double t = (Util.getMillis() % 3_600_000L) / 1000.0;

        // 1. Base: near-black with a faint warm tint at the top.
        g.fillGradient(0, 0, w, h, 0xFF140D08, 0xFF050404);

        // 2. Glow behind the logo, drifting gently side to side.
        int logoCx = w / 2 + (int) Math.round(Math.sin(t * 0.21) * Math.min(40, w * 0.06));
        int logoCy = 55 + (int) Math.round(Math.cos(t * 0.17) * 6);
        double breathe = 0.85 + 0.15 * Math.sin(t * 0.6);
        glow(g, logoCx, logoCy, Math.min(w * 0.55, 300), Math.min(h * 0.40, 110), ORANGE, (int) (0x2C * breathe), 5);

        // 3. Wide ember glow rising from the bottom edge, drifting the opposite way.
        int lowCx = w / 2 - (int) Math.round(Math.sin(t * 0.13) * w * 0.12);
        glow(g, lowCx, h + 10, w * 0.75, h * 0.42, ORANGE, 0x26, 5);

        // 4. Embers.
        if (MainMenuThemeConfig.getInstance().isParticles()) {
            embers(g, w, h, t);
        }

        // 5. Vignette: top + bottom gradients, stepped side strips.
        g.fillGradient(0, 0, w, Math.max(1, h / 5), 0x80000000, 0x00000000);
        g.fillGradient(0, h - Math.max(1, h / 4), w, h, 0x00000000, 0x99000000);
        int strips = 10;
        int stripW = Math.max(1, w / 60);
        for (int i = 0; i < strips; i++) {
            int a = (int) (0x70 * (1.0 - (double) i / strips));
            int c = argb(0x000000, a);
            g.fill(i * stripW, 0, (i + 1) * stripW, h, c);
            g.fill(w - (i + 1) * stripW, 0, w - i * stripW, h, c);
        }

        // 6. Hairline orange accents along the top and bottom edges, fading out toward the sides.
        accentLine(g, w, 0, 0x90);
        accentLine(g, w, h - 1, 0x70);
    }

    /** Cheap soft elliptical glow: {@code layers} nested ellipses, each drawn as horizontal bands of
     *  low alpha, so the centre accumulates to ~maxAlpha and the edge fades to nothing. */
    private static void glow(GuiGraphicsExtractor g, int cx, int cy, double rx, double ry, int rgb, int maxAlpha, int layers) {
        if (rx < 1 || ry < 1 || maxAlpha <= 0) {
            return;
        }
        int perLayer = Math.max(1, maxAlpha / layers);
        int color = argb(rgb, perLayer);
        for (int k = layers; k >= 1; k--) {
            double s = (double) k / layers;
            double lrx = rx * s;
            double lry = ry * s;
            int step = Math.max(2, (int) Math.ceil(lry / 14.0));
            int top = (int) Math.floor(cy - lry);
            int bottom = (int) Math.ceil(cy + lry);
            for (int y = top; y < bottom; y += step) {
                double dy = ((y + step * 0.5) - cy) / lry;
                double q = 1.0 - dy * dy;
                if (q <= 0) {
                    continue;
                }
                int half = (int) Math.round(lrx * Math.sqrt(q));
                if (half <= 0) {
                    continue;
                }
                g.fill(cx - half, y, cx + half, y + step, color);
            }
        }
    }

    private static void embers(GuiGraphicsExtractor g, int w, int h, double t) {
        int count = Math.max(24, Math.min(70, (w * h) / 3500));
        double travel = h + 24.0;
        for (int i = 0; i < count; i++) {
            double r1 = hash(i, 1);
            double r2 = hash(i, 2);
            double r3 = hash(i, 3);
            double speed = 5.0 + r2 * 13.0; // GUI px per second
            double rise = (t * speed + r1 * travel * 11.0) % travel;
            double y = h + 12.0 - rise;
            double x = r3 * w + Math.sin(t * (0.25 + r1 * 0.45) + i * 1.3) * (5.0 + r2 * 12.0);
            double progress = rise / travel; // 0 at bottom, 1 at top
            double life = Math.min(1.0, progress * 8.0) * (1.0 - progress);
            double flicker = 0.7 + 0.3 * Math.sin(t * (2.0 + r3 * 2.5) + i * 1.7);
            int a = (int) (215 * life * flicker);
            if (a <= 4) {
                continue;
            }
            int px = (int) Math.round(x);
            int py = (int) Math.round(y);
            int size = r2 > 0.82 ? 2 : 1;
            g.fill(px - 1, py - 1, px + size + 1, py + size + 1, argb(ORANGE, a / 4));
            g.fill(px, py, px + size, py + size, argb(r1 > 0.5 ? LIGHT_ORANGE : ORANGE, a));
        }
    }

    private static void accentLine(GuiGraphicsExtractor g, int w, int y, int maxAlpha) {
        int segments = 16;
        int segW = Math.max(1, w / (segments * 2));
        int cx = w / 2;
        for (int i = 0; i < segments; i++) {
            int a = (int) (maxAlpha * (1.0 - (double) i / segments));
            int c = argb(ORANGE, a);
            int inner = i * segW;
            int outer = (i == segments - 1) ? cx : (i + 1) * segW;
            g.fill(cx + inner, y, cx + outer, y + 1, c);
            g.fill(cx - outer, y, cx - inner, y + 1, c);
        }
    }

    private static double hash(int i, int salt) {
        double v = Math.sin(i * 12.9898 + salt * 78.233) * 43758.5453;
        return v - Math.floor(v);
    }
}
