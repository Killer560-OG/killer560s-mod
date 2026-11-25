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
 * :279-282 (431 x 202, centered),:690-708 (28 px tabs from guiLeft at guiTop - 28, the
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

    /** The page overlays (pv_basic.png, pv_dung.png), drawn over pv_bg at the panel's corner (GuiProfileViewer's
     *  per-page {@code Minecraft.getTextureManager().bindTexture(pv_*)} at guiLeft, guiTop). */
    static final Identifier NEU_BASIC = id("neu/pv_basic");
    static final Identifier NEU_DUNG = id("neu/pv_dung");
    /** pv_invs.png and pv_elements' 20 x 20 selector cell (the selected one is the same cell turned over). */
    static final Identifier NEU_INVS = id("neu/pv_invs"), NEU_INV_TAB = id("neu/inv_tab"), NEU_INV_TAB_ON = id("neu/inv_tab_on"),
            NEU_PET_SEL = id("neu/pet_sel"), NEU_PETS = id("neu/pv_pets"),
            NEU_COLS = id("neu/pv_cols"), NEU_CRIMSON = id("neu/pv_crimson"),
            NEU_MUSEUM = id("neu/pv_museum"), NEU_BESTIARY = id("neu/pv_bestiary"), NEU_RIFT = id("neu/pv_rift"), NEU_GARDEN = id("neu/pv_garden"),
            NEU_TROPHY = id("neu/pv_trophy"), NEU_BINGO_TAB = id("neu/pv_bingo_tab"), NEU_LEVELS = id("neu/pv_levels"), NEU_DROPDOWN_LIST = id("neu/dropdown_list"),
            NEU_SIDE = id("neu/side"), NEU_SIDE_TOP_ON = id("neu/side_top_on"), NEU_SIDE_ON = id("neu/side_on");
    /** The boxes, {x, y, w, h} from the panel corner, measured off the two textures' painted areas. */
    static final int[] NEU_BASIC_MODEL = {23, 44, 81, 108};
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

    /** The tab sprite's height; the pressed tab is one taller. */
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

    /** A button: NEU's dropdown face with its teal text, or the dark button SkyBlockPv's profile dropdown uses with
     *  white text; both with shadow. */
    static void button(GuiGraphicsExtractor g, Style s, int x, int y, int w, int h, String label, boolean hover, boolean selected) {
        Sprites sp = of(s);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, hover || selected ? sp.buttonHover : sp.button, x, y, w, h);
        if (s == Style.NEU) {
            neuCenteredScaled(g, label, x + w / 2f, y + h / 2f, w - 10, NEU_BUTTON_TEXT);
            return;
        }
        String t = MenuStyle.ellipsize(label, w - 6);
        g.text(MenuStyle.font(), t, x + (w - MenuStyle.font().width(t)) / 2, y + (h - 8) / 2, 0xFFFFFFFF, true);
    }

    /** NEU's drawStringCenteredScaledMaxWidth: centered on cx, cy with shadow, shrunk to maxW when wider. */
    static void neuCenteredScaled(GuiGraphicsExtractor g, String text, float cx, float cy, int maxW, int color) {
        int w = MenuStyle.font().width(text);
        float k = w > maxW ? maxW / (float) w : 1f;
        g.pose().pushMatrix();
        g.pose().translate(cx - w * k / 2f, cy - 4 * k);
        g.pose().scale(k, k);
        g.text(MenuStyle.font(), text, 0, 0, color, true);
        g.pose().popMatrix();
    }

    /** NEU's skill bar (GuiProfileViewer.renderBar): vanilla's 182 x 5 boss bar, the left half of the width from the
     *  sprite's left end and the right half from its right end, filled in 5% steps; a maxed one is gold all through
     *  (NEU tints it gold with a shader; vanilla's yellow bar is the same shape). Both sprites are vanilla's own. */
    static void neuBar(GuiGraphicsExtractor g, int x, int y, int w, float done, boolean maxed) {
        int half = w / 2;
        int fill = maxed ? w : Math.round(Math.round(Math.max(0f, Math.min(1f, done)) * 20f) / 20f * w);
        neuBarPart(g, BOSS_BG, x, y, half, w, w);
        if (maxed) {
            neuMaxedBar(g, x, y, w);
        } else {
            neuBarPart(g, BOSS_PINK, x, y, half, w, fill);
        }
    }

    /** NEU's maxed bar (renderGoldBar + its make_gold shader): the boss bar's rows with the hue turned by
     *  {@code amount + clipX * 4} and the saturation at 0.7, so a rainbow drifts across it once every 10 s. Drawn from one
     *  4-cycle hue strip built once (1024 x 5, no disk), its start and stretch set from the bar's screen x. */
    private static void neuMaxedBar(GuiGraphicsExtractor g, int x, int y, int w) {
        Identifier strip = rainbowStrip();
        int gw = Math.max(1, g.guiWidth());
        float amount = (float) ((-net.minecraft.util.Util.getMillis() / 10000.0) % 1.0);
        float start = (amount + 8f * (x + 1) / gw) % 1f;
        if (start < 0) start += 1f;
        int span = Math.max(1, Math.round((w - 2) * 8f / gw * 256f));
        g.blit(RenderPipelines.GUI_TEXTURED, strip, x + 1, y, start * 256f, 0f, w - 2, 5, Math.min(span, 768), 5, 1024, 5);
    }

    private static Identifier rainbow;
    /** The pink boss bar's five row brightnesses (vanilla boss_bar/pink_progress, hue 0.87, full saturation). */
    private static final float[] ROW_V = {0.290f, 0.925f, 0.725f, 0.549f, 0.290f};

    private static Identifier rainbowStrip() {
        if (rainbow == null) {
            com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(1024, 5, false);
            for (int u = 0; u < 1024; u++) {
                float hue = (0.87f + u / 256f) % 1f;
                for (int r = 0; r < 5; r++) {
                    int rgb = java.awt.Color.HSBtoRGB(hue, 0.7f, ROW_V[r]);
                    img.setPixel(u, r, 0xFF000000 | rgb);
                }
            }
            rainbow = id("neu_maxed_bar");
            net.minecraft.client.renderer.texture.DynamicTexture tex =
                    new net.minecraft.client.renderer.texture.DynamicTexture(() -> "killer560smod neu maxed bar", img);
            tex.upload();
            net.minecraft.client.Minecraft.getInstance().getTextureManager().register(rainbow, tex);
        }
        return rainbow;
    }

    private static void neuBarPart(GuiGraphicsExtractor g, Identifier sprite, int x, int y, int half, int w, int upTo) {
        int left = Math.min(upTo, half);
        if (left > 0) g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, 182, 5, 0, 0, x, y, left, 5);
        int right = upTo - half;
        if (right > 0) g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, 182, 5, 182 - (w - half), 0, x + half, y, right, 5);
    }

    private static final Identifier BOSS_BG = Identifier.withDefaultNamespace("boss_bar/pink_background"),
            BOSS_PINK = Identifier.withDefaultNamespace("boss_bar/pink_progress");

    /** NEU's soft text outline (Utils.renderAlignedString / renderShadowedString): the text in black at the twelve
     *  half-pixel offsets around it (alpha 200 one step out, 100 two steps out), then the text itself, no shadow. */
    static void neuOutlined(GuiGraphicsExtractor g, String text, float x, float y, int color) {
        for (int i = 0; i < OUTLINE.length; i += 3) {
            g.pose().pushMatrix();
            g.pose().translate(x + OUTLINE[i], y + OUTLINE[i + 1]);
            g.text(MenuStyle.font(), text, 0, 0, (int) OUTLINE[i + 2] << 24, false);
            g.pose().popMatrix();
        }
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.text(MenuStyle.font(), text, 0, 0, color, false);
        g.pose().popMatrix();
    }

    /** dx, dy, alpha for {@link #neuOutlined}: offsets -2..2 halved where |dx| != |dy|. */
    private static final float[] OUTLINE = {
            -1f, 0f, 100, 1f, 0f, 100, 0f, -1f, 100, 0f, 1f, 100,
            -0.5f, 0f, 200, 0.5f, 0f, 200, 0f, -0.5f, 200, 0f, 0.5f, 200,
            -1f, -0.5f, 100, -1f, 0.5f, 100, 1f, -0.5f, 100, 1f, 0.5f, 100,
            -0.5f, -1f, 100, 0.5f, -1f, 100, -0.5f, 1f, 100, 0.5f, 1f, 100};

    /** NEU's renderAlignedString: name at x and value right-aligned to x + len, or both joined and centered when they
     *  do not fit. */
    static void neuAligned(GuiGraphicsExtractor g, String name, int nameColor, String value, int valueColor, int x, int y, int len) {
        var f = MenuStyle.font();
        if (f.width(name) + f.width(" ") + f.width(value) >= len) {
            String both = name + " " + value;
            neuOutlined(g, both, x + len / 2f - f.width(both) / 2f, y, nameColor);
            return;
        }
        neuOutlined(g, name, x, y, nameColor);
        neuOutlined(g, value, x + len - f.width(value), y, valueColor);
    }

    /** Hypixel's SkyBlock level colors, one per 40 levels (gray, white, yellow, green, dark green, aqua, cyan, blue,
     *  pink, purple, gold, red, dark red), as NEU's sblevel_colours. */
    static int sbLevelColor(int level) {
        return SB_LEVEL[Math.max(0, Math.min(SB_LEVEL.length - 1, level / 40))];
    }
    private static final int[] SB_LEVEL = {0xFFAAAAAA, 0xFFFFFFFF, 0xFFFFFF55, 0xFF55FF55, 0xFF00AA00, 0xFF55FFFF, 0xFF00AAAA,
            0xFF5555FF, 0xFFFF55FF, 0xFFAA00AA, 0xFFFFAA00, 0xFFFF5555, 0xFFAA0000};

    /** NEU's short numbers: whole under 1000, else floored to tenths with k/m/b/t, the tenth dropped from 10 up or
     *  when it is 0 (1500 -> 1.5k, 12345 -> 12k). */
    static String neuShort(double n) {
        if (n < 0) return "-" + neuShort(-n);
        if (n < 1000) return n % 1 == 0 ? Long.toString((long) n) : String.format(java.util.Locale.ROOT, "%.2f", n);
        int suffix = 0;
        double d = Math.floor(n / 100) / 10;
        while (d >= 1000 && suffix < 3) {
            suffix++;
            d = Math.floor(d / 100) / 10;
        }
        boolean whole = d % 1 == 0 || d > 9.99;
        return (whole ? Long.toString((long) d) : Double.toString(d)) + "kmbt".charAt(suffix);
    }

    /** NEU's "#.#": one decimal at most, none when it is 0. */
    static String neuTenths(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r % 1 == 0 ? Long.toString((long) r) : Double.toString(r);
    }

    /** NEU's profile and second button text color (GuiProfileViewer :333-340, Color(63, 224, 208)). */
    static final int NEU_BUTTON_TEXT = 0xFF3FE0D0;

    /** NEU's gamemode icons beside the profile button (pv_ironman, pv_bingo, pv_stranded; 16 x 16); any other game mode
     *  (one newer than these) shows its pv_unknown icon, a normal profile none. */
    static Identifier neuGameMode(String mode) {
        return switch (mode == null ? "" : mode) {
            case "ironman" -> NEU_IRONMAN;
            case "bingo" -> NEU_BINGO;
            case "island" -> NEU_STRANDED;
            case "", "normal" -> null;
            default -> NEU_UNKNOWN;
        };
    }
    private static final Identifier NEU_IRONMAN = id("neu/pv_ironman"), NEU_BINGO = id("neu/pv_bingo"), NEU_STRANDED = id("neu/pv_stranded"),
            NEU_UNKNOWN = id("neu/pv_unknown");

    /** NEU's recent-player tab on the panel's right edge (pv_elements u 196-256, v 144-200: the top one, a lower one,
     *  each selected or not), 32 x 28 drawn 3 px left of the edge. */
    static void neuRecentTab(GuiGraphicsExtractor g, int x, int y, boolean top, boolean selected) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, top ? (selected ? NEU_RT_TOP_ON : NEU_RT_TOP) : (selected ? NEU_RT_ON : NEU_RT), x - 3, y, 32, 28);
    }
    private static final Identifier NEU_RT_TOP = id("neu/recent_top"), NEU_RT_TOP_ON = id("neu/recent_top_on"),
            NEU_RT = id("neu/recent"), NEU_RT_ON = id("neu/recent_on");

    /** SkyBlockPv's text color: dark_gray, which its Dark and NEU themes set to #B5B5B5 (themes/*.json "colors"). */
    static int sbpvText(Style s) { return s == Style.SBPV_LIGHT ? 0xFF555555 : 0xFFB5B5B5; }

    /** The tab's name under the panel (SkyBlockPv only), when the panel is wide enough (its 120 px side rule). */
    static void pageTitle(GuiGraphicsExtractor g, Style s, String title, int x, int y, int w, int h) {
        if (s == Style.NEU || w <= 240 + MenuStyle.font().width(title) + 20) return;
        ScreenChrome.text(g, title, x + (w - MenuStyle.font().width(title)) / 2, y + h + 2 + 6, sbpvText(s));
    }
}
