package com.killer560.hub.roomsim;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ModScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.hud.GuiRects;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * "Dungeon Sim Help" (simhelp-1117): a page list on the left, the page's lines on the right, Back / Next at the bottom. Pages come
 * from {@link SimHelp#pages()} and are laid out when the screen opens or resizes (word-wrapped to the content width, scrolled with
 * the wheel when a page is taller than the box); nothing runs per tick. Colors are the menu theme's ({@link MenuStyle}).
 */
public final class SimHelpScreen extends Screen implements MenuThemedScreen {

    private static final int HEAD = 28;
    private static final int LINE = 10;

    /** A laid-out line: its top in the content, its height, its source line, and its wrapped text (command rows: command then description). */
    private record Row(int y, int h, SimHelp.Line line, List<FormattedCharSequence> seqs, int cmdLines) { }

    private final Screen parent;
    private final List<SimHelp.Page> pages = SimHelp.pages();
    private int page;
    private int scroll;
    private int panelX, panelY, panelW, panelH, listW, cx, cy, cw, ch;
    private List<Row> rows = List.of();
    private int contentH;
    private int[] themedBounds = new int[4];
    /** Rightmost text pixel of the page list and of the current page, from the layout (read by the testkit; no render needed). */
    private int listRight, contentRight;

    public SimHelpScreen(Screen parent) {
        super(Component.literal("Dungeon Sim Help"));
        this.parent = parent;
    }

    @Override public int[] themedPanelBounds() { return themedBounds.clone(); }
    @Override public String themeId() { return SimItemsScreen.THEME_ID; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { McCompat.setScreen(this.minecraft, parent); }

    public int pageCount() { return pages.size(); }
    public int page() { return page; }
    /** {rightmost page-list text pixel, the list's right edge}. */
    public int[] textExtent() { return new int[]{listRight, panelX + 8 + listW}; }
    /** {rightmost content text pixel, the content box's right edge}. */
    public int[] contentExtent() { return new int[]{contentRight, cx + cw}; }
    /** Scroll range of the current page (0 = fits). */
    public int maxScroll() { return Math.max(0, contentH - ch); }

    public void showPage(int i) {
        page = Math.floorMod(i, pages.size());
        scroll = 0;
        layout();
    }

    @Override
    protected void init() {
        var font = MenuStyle.font();
        listW = 0;
        for (SimHelp.Page p : pages) listW = Math.max(listW, font.width(p.title()));
        listW += 16;
        panelW = Math.min(this.width - 16, 520);
        panelH = Math.min(this.height - 16, 330);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        themedBounds = new int[]{panelX, panelY, panelW, panelH};
        listRight = panelX + 14 + listW - 16;
        cx = panelX + 8 + listW + 10;
        cy = panelY + HEAD + 8;
        cw = panelX + panelW - 14 - cx;
        ch = panelY + panelH - 32 - cy;
        int by = panelY + panelH - 24;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> showPage(page - 1))
                .bounds(panelX + 8, by, 50, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Next"), b -> showPage(page + 1))
                .bounds(panelX + 62, by, 50, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Open Sim Settings"), b ->
                McCompat.setScreen(this.minecraft, ModScreen.atTab(this, "Sim Settings")))
                .bounds(panelX + 120, by, 118, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Close"), b -> onClose())
                .bounds(panelX + panelW - 58, by, 50, 18).build());
        layout();
    }

    private void layout() {
        var font = MenuStyle.font();
        List<Row> out = new ArrayList<>();
        int y = 0;
        contentRight = 0;
        for (SimHelp.Line l : pages.get(page).lines()) {
            List<FormattedCharSequence> seqs = new ArrayList<>(font.split(Component.literal(l.text()), l.kind() == 'c' ? cw - 8 : cw));
            int cmdLines = seqs.size();
            int h;
            if (l.kind() == 'c') {
                seqs.addAll(font.split(Component.literal(l.desc()), cw - 8));
                h = seqs.size() * LINE + 6;
            } else {
                h = seqs.size() * LINE + (l.kind() == 'h' ? 4 : 3);
            }
            for (FormattedCharSequence q : seqs) contentRight = Math.max(contentRight, (l.kind() == 'c' ? cx + 4 : cx) + font.width(q));
            out.add(new Row(y, h, l, seqs, cmdLines));
            y += h + (l.kind() == 'c' ? 2 : 0);
        }
        rows = out;
        contentH = y;
        scroll = Math.min(scroll, maxScroll());
        // The Open Sim Settings button belongs to pages that name settings.
        for (var w : this.children()) {
            if (w instanceof SettingsButtonWidget b && b.getMessage().getString().startsWith("Open Sim")) b.visible = pages.get(page).settingsButton();
        }
    }

    private int rowAt(double mx, double my) {
        if (mx < cx || mx > cx + cw || my < cy || my > cy + ch) return -1;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.line().kind() == 'c' && my >= cy + r.y() - scroll && my < cy + r.y() - scroll + r.h()) return i;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        for (int i = 0; i < pages.size(); i++) {
            int ry = panelY + HEAD + 8 + i * 14;
            if (mx >= panelX + 8 && mx < panelX + 8 + listW && my >= ry && my < ry + 14) {
                showPage(i);
                return true;
            }
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

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(g, this.width, this.height);
        ScreenChrome.frame(g, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(g, panelX, panelY, panelW);
        ScreenChrome.title(g, "DUNGEON SIM HELP", panelX + 10, panelY + 11, false);
        var font = MenuStyle.font();
        for (int i = 0; i < pages.size(); i++) {
            int ry = panelY + HEAD + 8 + i * 14;
            boolean hov = mouseX >= panelX + 8 && mouseX < panelX + 8 + listW && mouseY >= ry && mouseY < ry + 14;
            ScreenChrome.row(g, panelX + 8, ry, listW, 13, hov, i == page, i % 2 == 1);
            String t = pages.get(i).title();
            ScreenChrome.text(g, t, panelX + 14, ry + 3, i == page ? MenuStyle.OR_HOVER : hov ? MenuStyle.TXT_HOVER : MenuStyle.TXT);
        }
        g.enableScissor(cx - 2, cy, cx + cw + 4, cy + ch);
        for (Row r : rows) {
            int y = cy + r.y() - scroll;
            if (y + r.h() < cy || y > cy + ch) continue;
            boolean cmd = r.line().kind() == 'c';
            boolean hov = cmd && rowAt(mouseX, mouseY) >= 0 && rows.get(rowAt(mouseX, mouseY)) == r;
            int tx = cx;
            if (cmd) {
                GuiRects gr = GuiRects.begin(g);
                ScreenChrome.card(gr, cx - 2, y, cw + 2, r.h(), hov);
                gr.submit();
                tx = cx + 4;
            }
            if (r.line().kind() == 'h') ScreenChrome.section(g, r.line().text(), cx, y + 1, cx + cw, false);
            for (int i = 0; i < r.seqs().size(); i++) {
                if (r.line().kind() == 'h') break;
                int ly = y + (cmd ? 3 : 1) + i * LINE;
                int color = cmd ? (i < r.cmdLines() ? (hov ? MenuStyle.OR_HOVER : MenuStyle.OR) : MenuStyle.TXT_SOFT) : MenuStyle.TXT;
                ScreenChrome.text(g, r.seqs().get(i), tx, ly, color);
            }
        }
        g.disableScissor();
        if (maxScroll() > 0) ScreenChrome.scrollbar(g, cx + cw + 6, cy, ch, scroll, maxScroll(), ch);
        String count = "Page " + (page + 1) + " of " + pages.size();
        ScreenChrome.text(g, count, panelX + 250 + (panelW - 330 - font.width(count)) / 2, panelY + panelH - 19, MenuStyle.DIM);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
