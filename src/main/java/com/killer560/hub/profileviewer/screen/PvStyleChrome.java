package com.killer560.hub.profileviewer.screen;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.profileviewer.ProfileViewerConfig.Style;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The /pv Styles other than Default, drawn 1-1 with the originals' own sprites (pvthemes-1108, killer560 2026-11-08:
 * "make sure they are exactly 1-1 of the other mods for how they look"). The sprites under
 * {@code textures/gui/sprites/pv/} are NotEnoughUpdates' (LGPL-3.0: pv_bg, pv_elements, pv_dung, pv_dropdown),
 * SkyBlockPv's (non-code CC BY 4.0: its default, darkmode and neu theme sprites) and Olympus' (MIT: the dark button);
 * THIRD-PARTY-NOTICES.md credits them. Layout numbers are theirs:
 * NEU GuiProfileViewer.java:279-282 (431 x 202, centered), :690-708 (28 px tabs from guiLeft at guiTop - 28, the
 * pressed one 32 tall over the panel, the item at +6,+9), :331-357 (100 x 20 dropdowns 3 px under the panel);
 * SkyBlockPv BaseWindowedPvScreen.kt:85-86 (height 65% of the screen, 16:9), :138 + :309-329 (20 x 31 tabs from
 * x + 20 at y - 22, 2 px apart, drawn over the panel), :505 (the profile dropdown under the panel's left corner),
 * :161 (the tab's name under the panel). Every Identifier is built once; textures load with the GUI atlas.
 */
final class PvStyleChrome {

    private PvStyleChrome() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("killer560smod", "pv/" + path);
    }

    /** One style's sprites: panel, content box, tab normal / hovered / selected / selected+hovered, button, hovered,
     *  item slot (NEU: one cell of pv_invs.png; SkyBlockPv: one cell of its theme's inventory/mono.png). */
    private record Sprites(Identifier panel, Identifier box, Identifier tab, Identifier tabHover, Identifier tabSel,
                           Identifier tabSelHover, Identifier button, Identifier buttonHover, Identifier slot) {
    }

    private static final Identifier NEU_TAB_FIRST_SELECTED = id("neu/tab_first_selected");
    private static final Sprites NEU = new Sprites(id("neu/panel"), id("neu/box"), id("neu/tab"), id("neu/tab"),
            id("neu/tab_selected"), id("neu/tab_selected"), id("neu/button"), id("neu/button"), id("neu/slot"));
    private static final Sprites LIGHT = new Sprites(id("sbpv/light/panel"), id("sbpv/light/box"), id("sbpv/light/tab_normal"),
            id("sbpv/light/tab_hovered"), id("sbpv/light/tab_selected"), id("sbpv/light/tab_selected_hovered"),
            id("sbpv/button"), id("sbpv/button_hovered"), id("sbpv/light/slot"));
    // darkmode.json maps the normal and hovered tab to one sprite and keeps the default (green) selected one.
    private static final Sprites DARK = new Sprites(id("sbpv/dark/panel"), id("sbpv/dark/box"), id("sbpv/dark/tab_normal"),
            id("sbpv/dark/tab_normal"), LIGHT.tabSel, LIGHT.tabSelHover, LIGHT.button, LIGHT.buttonHover, id("sbpv/dark/slot"));
    private static final Sprites CLEAR = new Sprites(id("sbpv/clear/panel"), id("sbpv/clear/box"), id("sbpv/clear/tab_normal"),
            id("sbpv/clear/tab_hovered"), id("sbpv/clear/tab_selected"), id("sbpv/clear/tab_selected_hovered"),
            id("sbpv/clear/button"), id("sbpv/clear/button_hovered"), id("sbpv/clear/slot"));

    private static Sprites of(Style s) {
        return switch (s) {
            case NEU -> NEU;
            case SBPV_DARK -> DARK;
            case SBPV_CLEAR -> CLEAR;
            default -> LIGHT;
        };
    }

    /** NEU's page overlays (pv_basic.png, pv_dung.png), drawn over pv_bg at the panel's corner (GuiProfileViewer's
     *  per-page {@code Minecraft.getTextureManager().bindTexture(pv_*)} at guiLeft, guiTop). */
    static final Identifier NEU_BASIC = id("neu/pv_basic");
    static final Identifier NEU_DUNG = id("neu/pv_dung");
    /** pv_invs.png and pv_elements' 20 x 20 selector cell (the selected one is the same cell turned over). */
    static final Identifier NEU_INVS = id("neu/pv_invs"), NEU_INV_TAB = id("neu/inv_tab"), NEU_INV_TAB_ON = id("neu/inv_tab_on"),
            NEU_PET_SEL = id("neu/pet_sel"), NEU_PETS = id("neu/pv_pets"),
            NEU_COLS = id("neu/pv_cols"), NEU_CRIMSON = id("neu/pv_crimson"),
            NEU_MUSEUM = id("neu/pv_museum"), NEU_BESTIARY = id("neu/pv_bestiary"), NEU_RIFT = id("neu/pv_rift"), NEU_GARDEN = id("neu/pv_garden");
    /** NEU's boxes, {x, y, w, h} from the panel corner, measured off the two textures' painted areas. */
    static final int[] NEU_BASIC_MODEL = {23, 44, 81, 108}, NEU_BASIC_STATS = {128, 49, 89, 95}, NEU_BASIC_SKILLS = {232, 15, 181, 172};
    static final int[] NEU_DUNG_LEVEL = {16, 16, 124, 55}, NEU_DUNG_STATS = {16, 76, 124, 57}, NEU_DUNG_CLASS = {16, 138, 124, 48},
            NEU_DUNG_FLOORS = {154, 16, 124, 170}, NEU_DUNG_CLASSES = {292, 16, 124, 170};

    /** SkyBlockPV's category tab on the window's right edge (tabs/right, 31 x 20; darkmode has no selected one and
     *  keeps the default's, as its top tabs do). */
    static void sideTab(GuiGraphicsExtractor g, Style s, int x, int y, boolean on) {
        int i = s == Style.SBPV_DARK ? 1 : s == Style.SBPV_CLEAR ? 2 : 0;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, (on ? SIDE_ON : SIDE)[i], x, y, 31, 20);
    }
    private static final Identifier[] SIDE = {id("sbpv/light/side_tab"), id("sbpv/dark/side_tab"), id("sbpv/clear/side_tab")};
    private static final Identifier[] SIDE_ON = {id("sbpv/light/side_tab_on"), id("sbpv/light/side_tab_on"), id("sbpv/clear/side_tab_on")};

    /** The style's item slot sprite at {@code size} (SkyBlockPv's is nine_slice, NEU's is drawn 1:1 at 18). */
    static void slot(GuiGraphicsExtractor g, Style s, int x, int y, int size) {
        slot(g, s, x, y, size, -1);
    }

    /** The slot tinted by {@code color} (SkyBlockPV tints a selected one green, PvColors.GREEN 0x55FF55). */
    static void slot(GuiGraphicsExtractor g, Style s, int x, int y, int size, int color) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, of(s).slot, x, y, size, size, color);
    }

    /** SkyBlockPV's label title bar (box/title, nine-sliced). */
    static void title(GuiGraphicsExtractor g, Style s, int x, int y, int w, int h) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, TITLE[s == Style.SBPV_DARK ? 1 : s == Style.SBPV_CLEAR ? 2 : 0], x, y, w, h);
    }
    /** SkyBlockPV's 91 x 5 progress bar (progressbar/*, one set for every theme), filled to {@code done}. */
    static void bar(GuiGraphicsExtractor g, int x, int y, float done) {
        int w = (int) (91 * Math.min(1f, done));
        g.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_BG, x, y, 91, 5);
        if (w > 0) {
            g.enableScissor(x, y, x + w, y + 5);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, w >= 91 ? BAR_MAXED : BAR_FG, x, y, 91, 5);
            g.disableScissor();
        }
    }
    private static final Identifier BAR_BG = id("sbpv/bar_background"), BAR_FG = id("sbpv/bar_foreground"),
            BAR_MAXED = id("sbpv/bar_foreground_maxed");
    private static final Identifier[] TITLE = {id("sbpv/light/title"), id("sbpv/dark/title"), id("sbpv/clear/title")};

    /** SkyBlockPV's skill/slayer chip background (box/rounded_box_thin) and its player frame (buttons/disabled). */
    static void chip(GuiGraphicsExtractor g, Style s, int x, int y, int w, int h) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, CHIP[s == Style.SBPV_DARK ? 1 : s == Style.SBPV_CLEAR ? 2 : 0], x, y, w, h);
    }
    static void playerFrame(GuiGraphicsExtractor g, Style s, int x, int y, int w, int h) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PLAYER[s == Style.SBPV_DARK ? 1 : s == Style.SBPV_CLEAR ? 2 : 0], x, y, w, h);
    }
    private static final Identifier[] CHIP = {id("sbpv/light/chip"), id("sbpv/dark/chip"), id("sbpv/clear/chip")};
    private static final Identifier[] PLAYER = {id("sbpv/light/player_bg"), id("sbpv/dark/player_bg"), id("sbpv/clear/player_bg")};

    static final int NEU_W = 431;
    static final int NEU_H = 202;

    static int panelW(Style s, int screenW, int screenH) {
        return Math.min(screenW, s == Style.NEU ? NEU_W : (int) (panelH(s, screenH) * (16.0 / 9.0)));
    }

    static int panelH(Style s, int screenH) {
        return s == Style.NEU ? Math.min(screenH, NEU_H) : (int) (screenH * 0.65);
    }

    static int tabW(Style s) { return s == Style.NEU ? 28 : 20; }

    /** The tab sprite's height; NEU's pressed tab is one taller. */
    static int tabH(Style s) { return 31; }

    /** How far above the panel's top the tabs start. */
    static int tabRise(Style s) { return s == Style.NEU ? 28 : 22; }

    static int tabStep(Style s) { return s == Style.NEU ? 28 : 22; }

    /** The first tab's x inside the panel. */
    static int tabX0(Style s) { return s == Style.NEU ? 0 : 20; }

    /** Where the 16 px page icon sits inside a tab. */
    static int iconDx(Style s) { return s == Style.NEU ? 6 : 2; }

    static int iconDy(Style s, boolean selected) { return s == Style.NEU ? 9 : selected ? 4 : 5; }

    /** NEU draws its unpressed tabs behind the panel (only the pressed one in front); SkyBlockPv draws every tab over it. */
    static boolean tabBehindPanel(Style s, boolean selected) { return s == Style.NEU && !selected; }

    static void frame(GuiGraphicsExtractor g, Style s, int x, int y, int w, int h) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, of(s).panel, x, y, w, h);
    }

    /** A content box: NEU's page box (pv_dung), or SkyBlockPv's box/box. */
    static void box(GuiGraphicsExtractor g, Style s, int x, int y, int w, int h) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, of(s).box, x, y, w, h);
    }

    static void tab(GuiGraphicsExtractor g, Style s, int index, int x, int y, boolean selected, boolean hover) {
        Sprites sp = of(s);
        Identifier t = selected ? (s == Style.NEU && index == 0 ? NEU_TAB_FIRST_SELECTED : hover ? sp.tabSelHover : sp.tabSel)
                : hover ? sp.tabHover : sp.tab;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, t, x, y, tabW(s), tabH(s) + (s == Style.NEU && selected ? 1 : 0));
    }

    /** A button: NEU's dropdown face, or the dark button SkyBlockPv's profile dropdown uses; white text with shadow. */
    static void button(GuiGraphicsExtractor g, Style s, int x, int y, int w, int h, String label, boolean hover, boolean selected) {
        Sprites sp = of(s);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, hover || selected ? sp.buttonHover : sp.button, x, y, w, h);
        String t = MenuStyle.ellipsize(label, w - 6);
        g.text(MenuStyle.font(), t, x + (w - MenuStyle.font().width(t)) / 2, y + (h - 8) / 2, 0xFFFFFFFF, true);
    }

    /** SkyBlockPv's text color: dark_gray, which its Dark and NEU themes set to #B5B5B5 (themes/*.json "colors"). */
    static int sbpvText(Style s) { return s == Style.SBPV_LIGHT ? 0xFF555555 : 0xFFB5B5B5; }

    /** The tab's name under the panel (SkyBlockPv only), when the panel is wide enough (its 120 px side rule). */
    static void pageTitle(GuiGraphicsExtractor g, Style s, String title, int x, int y, int w, int h) {
        if (s == Style.NEU || w <= 240 + MenuStyle.font().width(title) + 20) return;
        ScreenChrome.text(g, title, x + (w - MenuStyle.font().width(title)) / 2, y + h + 2 + 6, sbpvText(s));
    }
}
