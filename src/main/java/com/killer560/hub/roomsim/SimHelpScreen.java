package com.killer560.hub.roomsim;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.GuiImages;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ModScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.mainmenu.WebpPictures;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Dungeon Sim Help" (simhelp-1117, simhelp-polish-1118): the sim menu's own frame ({@link SimMenuNav}): its raised column lists
 * the pages with Close at the bottom, the page sits in a recessed well with its pictures under the text, and Back / Next sit at
 * the bottom right with the page count centered on the panel. Pages come from {@link SimHelp#pages()} and are laid out when the
 * screen opens, resizes or changes page; the pictures are read off the render thread the first time a page shows them and freed
 * when the screen closes ({@link GuiImages}). Nothing runs per tick. Colors are the menu theme's ({@link MenuStyle}).
 */
public final class SimHelpScreen extends Screen implements MenuThemedScreen {

    private static final int LINE = 10;
    private static final int PAD = 8;
    private static final int GAP = 6;
    private static final int MIN_PIC_H = 90;
    private static final int STACK_H = 40;
    private static final String ASSETS = "/assets/killer560smod/textures/gui/simhelp/";

    /** A laid-out line: its top in the content, its height, its source line, and its wrapped text (command rows: command then description). */
    private record Row(int y, int h, SimHelp.Line line, List<FormattedCharSequence> seqs, int cmdLines) { }

    private final Screen parent;
    private final List<SimHelp.Page> pages = SimHelp.pages();
    private final GuiImages images = new GuiImages("simhelp/", true);
    private int page;
    private int scroll;
    private SimMenuNav nav;
    /** The well, and the text area inside it (content coordinates start at {@code ty}). */
    private int wx, wy, ww, wh, tx, ty, tw, th;
    private List<Row> rows = List.of();
    /** The Open Sim Settings button's top in the content (-1 = none), and each picture's box in the content. */
    private int settingsY = -1;
    private int[][] pics = new int[0][];
    private int contentH;
    private int[] themedBounds = new int[4];
    private int contentRight;
    private int picturesShown;
    private SettingsButtonWidget back, next;
    /** "Page N of M", built at layout. */
    private String label = "";

    public SimHelpScreen(Screen parent) {
        super(Component.literal("Dungeon Sim Help"));
        this.parent = parent;
    }

    @Override public int[] themedPanelBounds() { return themedBounds.clone(); }
    @Override public String themeId() { return SimItemsScreen.THEME_ID; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { McCompat.setScreen(this.minecraft, parent); }
    @Override public void removed() { images.close(); }

    public int pageCount() { return pages.size(); }
    public int page() { return page; }
    /** {rightmost page-list text pixel, the list's right edge}: the column's entries ellipsize, so the edge bounds them. */
    public int[] textExtent() { return new int[]{nav.sideX + nav.sideW - 4, nav.sideX + nav.sideW - 4}; }
    /** {rightmost content text pixel, the text area's right edge}. */
    public int[] contentExtent() { return new int[]{contentRight, tx + tw}; }
    /** Scroll range of the current page (0 = fits). */
    public int maxScroll() { return Math.max(0, contentH - th); }
    /** Pictures of this page drawn ready in the last frame. Read by the testkit. */
    public int picturesShown() { return picturesShown; }
    /** The page count label's center x (the panel's center). */
    public int pageLabelCenter() { return nav.panelX + nav.panelW / 2; }

    /** Every box on screen, x/y/w/h, by name ("panel", "well", "text", "label", "pic0".., "page0".., "close", "back", "next"). Read by the testkit. */
    public Map<String, int[]> layoutBoxes() {
        Map<String, int[]> out = new LinkedHashMap<>();
        out.put("panel", new int[]{nav.panelX, nav.panelY, nav.panelW, nav.panelH});
        out.put("well", new int[]{wx, wy, ww, wh});
        out.put("text", new int[]{tx, ty, tw, th});
        int lw = MenuStyle.font().width(label);
        out.put("label", new int[]{pageLabelCenter() - lw / 2, nav.backY() + 6, lw, 8});
        for (int i = 0; i < pics.length; i++) {
            out.put("pic" + i, new int[]{pics[i][0], ty + pics[i][1] - scroll, pics[i][2], pics[i][3]});
        }
        int i = 0;
        for (var w : this.children()) {
            if (!(w instanceof net.minecraft.client.gui.components.AbstractWidget a)) continue;
            String name = a == back ? "back" : a == next ? "next" : a.getMessage().getString().equals("Close") ? "close" : "page" + i++;
            out.put(name, new int[]{a.getX(), a.getY(), a.getWidth(), a.getHeight()});
        }
        return out;
    }

    public void showPage(int i) {
        page = Math.floorMod(i, pages.size());
        scroll = 0;
        rebuildWidgets();
    }


    @Override
    protected void init() {
        nav = new SimMenuNav(this.width, this.height);
        themedBounds = new int[]{nav.panelX, nav.panelY, nav.panelW, nav.panelH};
        // The page entries share the column above its Close; they shrink to fit nine in a short window.
        int top = nav.sideY + 6;
        int itemH = Math.max(12, Math.min(20, (nav.backY() - 12 - top + 3) / pages.size() - 3));
        for (int i = 0; i < pages.size(); i++) {
            int p = i;
            addRenderableWidget(new SimMenuNav.Item(pages.get(i).title(), i == page, nav.sideX + 4, top + i * (itemH + 3),
                    nav.sideW - 8, itemH, () -> showPage(p)));
        }
        nav.addBack(this::addRenderableWidget, "Close", this::onClose);
        int right = nav.contentX + nav.contentW;
        next = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Next"), b -> showPage(page + 1))
                .bounds(right - 50, nav.backY(), 50, 20).build());
        back = addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> showPage(page - 1))
                .bounds(right - 104, nav.backY(), 50, 20).build());
        wx = nav.contentX;
        wy = nav.contentY;
        ww = nav.contentW;
        wh = nav.backY() - 6 - wy;
        tx = wx + PAD;
        ty = wy + PAD - 2;
        tw = ww - 2 * PAD - 6;
        th = wh - 2 * (PAD - 2);
        layout();
    }

    private void layout() {
        var font = MenuStyle.font();
        SimHelp.Page p = pages.get(page);
        List<Row> out = new ArrayList<>();
        int y = 0;
        contentRight = 0;
        for (SimHelp.Line l : p.lines()) {
            boolean cmd = l.kind() == 'c';
            List<FormattedCharSequence> seqs = new ArrayList<>(font.split(Component.literal(l.text()), cmd ? tw - 8 : tw));
            int cmdLines = seqs.size();
            int h;
            if (cmd) {
                seqs.addAll(font.split(Component.literal(l.desc()), tw - 8));
                h = seqs.size() * LINE + 6;
            } else {
                h = seqs.size() * LINE + (l.kind() == 'h' ? 4 : 3);
            }
            for (FormattedCharSequence q : seqs) contentRight = Math.max(contentRight, (cmd ? tx + 4 : tx) + font.width(q));
            out.add(new Row(y, h, l, seqs, cmdLines));
            y += h + (cmd ? 2 : 0);
        }
        rows = out;
        label = "Page " + (page + 1) + " of " + pages.size();
        settingsY = -1;
        if (p.settingsButton()) {
            settingsY = y + 2;
            y = settingsY + 20;
        }
        // Pictures: one row under the text, equal height, as tall as the rest of the well allows (never under MIN_PIC_H
        // unless the width says so; then the page scrolls) and no wider than the text.
        List<SimHelp.Pic> list = p.pics();
        pics = new int[list.size()][];
        if (!list.isEmpty()) {
            y += GAP;
            double aspect = 0;
            for (SimHelp.Pic pic : list) aspect += (double) pic.w() / pic.h();
            int gaps = (list.size() - 1) * GAP;
            int byWidth = (int) ((tw - gaps) / aspect);
            int h = Math.min(byWidth, Math.max(th - y, MIN_PIC_H));
            if (h < STACK_H) {
                // Too narrow for one row (small window, high GUI scale): one picture per row, each as wide as the text.
                int cap = Math.max(th - y, MIN_PIC_H);
                for (int i = 0; i < list.size(); i++) {
                    SimHelp.Pic pic = list.get(i);
                    int ph = Math.max(1, (int) Math.round(tw * (double) pic.h() / pic.w()));
                    int pw = tw;
                    if (ph > cap) { ph = cap; pw = (int) Math.round(ph * (double) pic.w() / pic.h()); }
                    pics[i] = new int[]{tx + (tw - pw) / 2, y, pw, ph};
                    y += ph + GAP;
                }
                contentH = y - GAP;
                scroll = Math.min(scroll, maxScroll());
                return;
            }
            int rowW = gaps;
            int[] widths = new int[list.size()];
            for (int i = 0; i < list.size(); i++) rowW += widths[i] = (int) Math.round(h * (double) list.get(i).w() / list.get(i).h());
            int x = tx + Math.max(0, (tw - rowW) / 2);
            for (int i = 0; i < list.size(); i++) {
                pics[i] = new int[]{x, y, widths[i], h};
                x += widths[i] + GAP;
            }
            y += h;
        }
        contentH = y;
        scroll = Math.min(scroll, maxScroll());
    }

    /** The command row under the pointer, or -1. */
    private int rowAt(double mx, double my) {
        if (mx < tx || mx > tx + tw || my < ty || my > ty + th) return -1;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.line().kind() == 'c' && my >= ty + r.y() - scroll && my < ty + r.y() - scroll + r.h()) return i;
        }
        return -1;
    }

    private boolean onSettings(double mx, double my) {
        if (settingsY < 0 || my < ty || my > ty + th) return false;
        int y = ty + settingsY - scroll;
        return mx >= tx && mx < tx + settingsW() && my >= y && my < y + 20;
    }

    private int settingsW() { return MenuStyle.font().width("Open Sim Settings") + 20; }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (onSettings(mx, my)) {
            McCompat.setScreen(this.minecraft, ModScreen.atTab(this, "Sim Settings"));
            return true;
        }
        int i = rowAt(mx, my);
        if (i >= 0) {
            SimHelp.Line l = rows.get(i).line();
            String cmd = l.command();
            if (cmd.equals("/help")) return true;
            if (l.run()) {
                McCompat.setScreen(this.minecraft, parent);
                this.minecraft.player.connection.sendCommand(cmd.substring(1));
            } else {
                McCompat.setScreen(this.minecraft, new ChatScreen(cmd + " ", false));
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(sy) * 3 * LINE));
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int k = com.killer560.hub.compat.McInput.key(event);
        if (k == 262) { showPage(page + 1); return true; }
        if (k == 263) { showPage(page - 1); return true; }
        return super.keyPressed(event);
    }

    private GuiImages.Image picture(String file) {
        return images.get(file, () -> {
            try (InputStream in = SimHelpScreen.class.getResourceAsStream(ASSETS + file)) {
                return in == null ? null : WebpPictures.decode(in.readAllBytes());
            }
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        nav.drawFrame(g, this.width, this.height, "DUNGEON SIM HELP", false);
        ScreenChrome.well(g, wx, wy, ww, wh);
        int hovered = rowAt(mouseX, mouseY);
        g.enableScissor(wx + 1, wy + 1, wx + ww - 1, wy + wh - 1);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int y = ty + r.y() - scroll;
            if (y + r.h() < ty || y > ty + th) continue;
            boolean cmd = r.line().kind() == 'c';
            boolean hov = i == hovered;
            int x = tx;
            if (cmd) {
                GuiRects gr = GuiRects.begin(g);
                ScreenChrome.card(gr, tx - 2, y, tw + 2, r.h(), hov);
                gr.submit();
                x = tx + 4;
            }
            if (r.line().kind() == 'h') {
                ScreenChrome.section(g, r.line().text(), tx, y + 1, tx + tw, false);
                continue;
            }
            for (int j = 0; j < r.seqs().size(); j++) {
                int color = cmd ? (j < r.cmdLines() ? (hov ? MenuStyle.OR_HOVER : MenuStyle.OR) : MenuStyle.TXT_SOFT) : MenuStyle.TXT;
                ScreenChrome.text(g, r.seqs().get(j), x, y + (cmd ? 3 : 1) + j * LINE, color);
            }
        }
        if (settingsY >= 0) {
            ScreenChrome.button(g, tx, ty + settingsY - scroll, settingsW(), 20, "Open Sim Settings", onSettings(mouseX, mouseY), false);
        }
        int shown = 0;
        List<SimHelp.Pic> list = pages.get(page).pics();
        for (int i = 0; i < pics.length; i++) {
            int[] b = pics[i];
            int y = ty + b[1] - scroll;
            if (y + b[3] < wy || y > wy + wh) continue;
            GuiImages.Image img = picture(list.get(i).file());
            GuiRects gr = GuiRects.begin(g);
            gr.outline(b[0] - 1, y - 1, b[2] + 2, b[3] + 2, MenuStyle.ACCENT_EDGE);
            if (img == null) gr.fill(b[0], y, b[0] + b[2], y + b[3], MenuStyle.alpha(MenuStyle.TXT, 0x10));
            gr.submit();
            if (img != null) {
                GuiImages.draw(g, img, b[0], y, b[2], b[3]);
                shown++;
            }
        }
        picturesShown = shown;
        g.disableScissor();
        if (maxScroll() > 0) ScreenChrome.scrollbar(g, wx + ww - 6, wy + 3, wh - 6, scroll, maxScroll(), th);
        ScreenChrome.centered(g, label, pageLabelCenter(), nav.backY() + 6, MenuStyle.DIM);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
