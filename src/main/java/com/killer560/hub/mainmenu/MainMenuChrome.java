package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.namechanger.NameReplacer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.lang.ref.WeakReference;

/**
 * The redesigned title screen's own pieces (killer560, 2026-10-15: "Just make it look more professional in every
 * aspect just like how you redid our mod menu"), drawn in the main menu's theme ({@link MainMenuLook}) through the mod
 * menu's palette ({@link MenuStyle#use(int[])}): the frosted panel behind the button column, the buttons themselves, the
 * "Playing as" chip and the footer band. The layout ({@link MainMenuTitleLayout}) says where the panel goes.
 */
public final class MainMenuChrome {

    private MainMenuChrome() {
    }

    private static WeakReference<Screen> cardScreen = new WeakReference<>(null);
    private static WeakReference<AbstractWidget> fadeSource = new WeakReference<>(null);
    private static int cardX;
    private static int cardY;
    private static int cardW;
    private static int cardH;

    /** Where the panel goes on {@code screen} (the column and the Options/Quit row plus padding), and a widget whose
     *  alpha is the screen's fade-in. */
    static void setCard(Screen screen, int x, int y, int w, int h, AbstractWidget fade) {
        cardScreen = new WeakReference<>(screen);
        fadeSource = new WeakReference<>(fade);
        cardX = x;
        cardY = y;
        cardW = w;
        cardH = h;
    }

    static void clearCard() {
        cardScreen = new WeakReference<>(null);
    }

    /** The panel's box on {@code screen}, or null when the layout did not place one (testkit reads it). */
    public static int[] cardBox(Screen screen) {
        return screen != null && cardScreen.get() == screen ? new int[]{cardX, cardY, cardW, cardH} : null;
    }

    private static float fade() {
        AbstractWidget w = fadeSource.get();
        return w == null ? 1f : Math.max(0f, Math.min(1f, w.getAlpha()));
    }

    private static int f(int argb, float a) {
        return MainMenuTheme.fade(argb, a);
    }

    /** Applies the main menu's palette to {@link MenuStyle}'s statics; the next {@code MenuStyle.use()} restores the
     *  mod menu's. */
    public static MainMenuLook usePalette() {
        MainMenuLook look = MainMenuLook.current();
        MenuStyle.use(look.colours());
        return look;
    }

    /** The panel behind the button column, and the footer band - drawn right after the background, under everything. */
    public static void drawUnderlay(GuiGraphicsExtractor g, Screen screen) {
        MainMenuLook look = usePalette();
        float a = fade();
        GuiRects r = GuiRects.begin(g);
        // The thin accent line along the very bottom edge (killer560, 2026-10-15: "I would like that moved all the way to
        // the bottom"); the version, mod name and copyright texts that sat on the old footer band are gone.
        accentLine(r, 0, screen.width, screen.height - 1, a * 0.6f);

        if (look.panel() && cardScreen.get() == screen && cardW > 0) {
            int x = cardX;
            int y = cardY;
            int w = cardW;
            int h = cardH;
            int shadowA = (int) (0x90 * MenuStyle.SHADOW * a);
            for (int i = 0; i < 4; i++) {
                int c = MenuStyle.alpha(0xFF000000, shadowA >> i);
                r.fill(x + 2 + i, y + h + i, x + w + 2 + i, y + h + i + 1, c);
                r.fill(x + w + i, y + 2 + i, x + w + i + 1, y + h + i, c);
            }
            // Fully opaque (killer560, 2026-10-15: "do not make that dark portion surrounding the settings translucent, it
            // makes it hard to look at"): nothing of the animated background shows behind the buttons.
            int fill = 0xFF;
            r.gradient(x + 1, y + 1, x + w - 1, y + h - 1, f(MenuStyle.alpha(MenuStyle.PANEL_TOP, fill), a),
                    f(MenuStyle.alpha(MenuStyle.PANEL_BOTTOM, fill), a));
            r.outline(x, y, w, h, f(MenuStyle.alpha(MenuStyle.BLACK, 0xE0), a));
            r.fill(x + 1, y + 1, x + w - 1, y + 2, f(MenuStyle.HILITE, a));
            r.fill(x + 1, y + 2, x + 2, y + h - 1, f(MenuStyle.HILITE_SOFT, a));
            r.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, f(MenuStyle.SHADE, a));
            // Accent hairline across the top, brightest in the middle.
            accentLine(r, x + 1, x + w - 1, y + 1, a);
            // A thin rule between the column and the Options/Quit row.
            int ruleY = MainMenuTitleLayout.ruleY(screen);
            if (ruleY > y && ruleY < y + h) {
                r.fill(x + 10, ruleY, x + w - 10, ruleY + 1, f(MenuStyle.alpha(MenuStyle.BLACK, 0x90), a));
                r.fill(x + 10, ruleY + 1, x + w - 10, ruleY + 2, f(MenuStyle.HILITE_SOFT, a));
            }
        }
        r.submit();
    }

    private static void accentLine(GuiRects r, int x0, int x1, int y, float a) {
        int w = x1 - x0;
        if (w <= 2) {
            return;
        }
        int half = w / 2;
        int steps = 12;
        for (int i = 0; i < steps; i++) {
            int s0 = half * i / steps;
            int s1 = half * (i + 1) / steps;
            int alpha = (int) (0xD0 * (1.0 - (double) i / steps) * a);
            int c = MenuStyle.alpha(MenuStyle.OR, alpha);
            r.fill(x0 + half + s0, y, x0 + half + s1, y + 1, c);
            r.fill(x0 + half - s1, y, x0 + half - s0, y + 1, c);
        }
    }

    /** A title-screen button: a raised face in the theme's card color, an accent ring, glow and side bar on hover. */
    public static void drawButton(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean active, boolean hover,
                                  float alpha) {
        MenuWidgets.sync();
        GuiRects r = GuiRects.begin(g);
        float a = Math.max(0f, Math.min(1f, alpha)) * (active ? 1f : 0.55f);
        boolean lit = active && hover;
        if (lit) {
            int[] glow = {0x48, 0x28, 0x12};
            for (int i = 0; i < glow.length; i++) {
                int d = i + 1;
                int c = f(MenuStyle.alpha(MenuStyle.OR, glow[i]), a);
                r.fill(x - d, y - d, x + w + d, y - d + 1, c);
                r.fill(x - d, y + h + d - 1, x + w + d, y + h + d, c);
                r.fill(x - d, y - d + 1, x - d + 1, y + h + d - 1, c);
                r.fill(x + w + d - 1, y - d + 1, x + w + d, y + h + d - 1, c);
            }
        } else {
            r.fill(x + 1, y + h, x + w + 1, y + h + 1, f(MenuStyle.shadow(0x70), a));
        }
        int top = lit ? MenuStyle.CARD_HOVER_TOP : MenuStyle.CARD_TOP;
        int bottom = lit ? MenuStyle.mix(MenuStyle.CARD_HOVER_BOTTOM, MenuStyle.OR, 0.12f) : MenuStyle.CARD_BOTTOM;
        r.gradient(x + 1, y + 1, x + w - 1, y + h - 1, f(top, a), f(bottom, a));
        r.outline(x, y, w, h, f(lit ? MenuStyle.OR : MenuStyle.FRAME_RING, a));
        r.fill(x + 1, y + 1, x + w - 1, y + 2, f(MenuStyle.HILITE, a));
        r.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, f(MenuStyle.SHADE, a));
        if (lit) {
            r.fill(x + 1, y + 2, x + 3, y + h - 2, f(MenuStyle.OR, a));
            r.fill(x + w - 3, y + 2, x + w - 1, y + h - 2, f(MenuStyle.alpha(MenuStyle.OR, 0x60), a));
        }
        r.submit();
    }

    /** Label colors on a title-screen button. */
    public static int labelColour(boolean active, boolean hover) {
        MenuWidgets.sync();
        if (!active) {
            return MenuStyle.DIM2 & 0xFFFFFF;
        }
        return (hover ? (MenuStyle.LIGHT ? MenuStyle.OR_DARK : MenuStyle.OR_HOVER) : MenuStyle.TXT) & 0xFFFFFF;
    }

    /**
     * "● Playing as Name" as a chip centered under the menu. The name is the account's REAL name, wrapped so Name Changer
     * leaves it alone (killer560, 2026-10-15: "Do not have the custom ign color work on this page").
     */
    public static void drawPlayingAs(GuiGraphicsExtractor g, int cx, int y, String name, float alpha) {
        usePalette();
        Font font = Minecraft.getInstance().font;
        String prefix = "Playing as ";
        FormattedCharSequence nameSeq = new NameReplacer.Skip(FormattedCharSequence.forward(name, Style.EMPTY));
        int prefixW = font.width(prefix);
        int nameW = font.width(nameSeq);
        int padX = 7;
        int dot = 5;
        int w = padX + dot + 5 + prefixW + nameW + padX;
        int h = 15;
        int x = cx - w / 2;
        GuiRects r = GuiRects.begin(g);
        r.fill(x + 1, y, x + w - 1, y + h, f(MenuStyle.alpha(MenuStyle.PILL_BG, 0xFF), alpha));
        r.fill(x, y + 1, x + w, y + h - 1, f(MenuStyle.alpha(MenuStyle.PILL_BG, 0xFF), alpha));
        r.fill(x + 1, y, x + w - 1, y + 1, f(MenuStyle.ACCENT_EDGE, alpha));
        r.fill(x + 1, y + h - 1, x + w - 1, y + h, f(MenuStyle.ACCENT_EDGE, alpha));
        r.fill(x, y + 1, x + 1, y + h - 1, f(MenuStyle.ACCENT_EDGE, alpha));
        r.fill(x + w - 1, y + 1, x + w, y + h - 1, f(MenuStyle.ACCENT_EDGE, alpha));
        int dx = x + padX;
        int dy = y + (h - dot) / 2;
        int online = 0xFF4ED16A;
        r.fill(dx - 1, dy - 1, dx + dot + 1, dy + dot + 1, f(MenuStyle.alpha(online, 0x40), alpha));
        r.fill(dx + 1, dy, dx + dot - 1, dy + dot, f(online, alpha));
        r.fill(dx, dy + 1, dx + dot, dy + dot - 1, f(online, alpha));
        r.submit();
        int tx = dx + dot + 5;
        int ty = y + (h - 8) / 2;
        int textA = Math.round(alpha * 255f);
        g.text(font, prefix, tx, ty, MainMenuTheme.argb(MenuStyle.DIM, textA), MenuStyle.TEXT_SHADOW);
        g.text(font, nameSeq, tx + prefixW, ty, MainMenuTheme.argb(MenuStyle.LIGHT ? MenuStyle.OR_DARK : MenuStyle.OR_LIGHT,
                textA), MenuStyle.TEXT_SHADOW);
    }

    public static int dimColour() {
        usePalette();
        return MenuStyle.DIM & 0xFFFFFF;
    }

    public static int accentLightColour() {
        usePalette();
        return (MenuStyle.LIGHT ? MenuStyle.OR_DARK : MenuStyle.OR_LIGHT) & 0xFFFFFF;
    }
}
