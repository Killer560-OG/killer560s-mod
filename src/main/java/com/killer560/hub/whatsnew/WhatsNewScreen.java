package com.killer560.hub.whatsnew;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What's New (killer560, 2026-11-11: "a What's New button on the main menu"): two columns in the menu theme. Left, every
 * update newest first (date and one line, the open one lit) and Back; right, the open update's sections as cards (title,
 * blurb, a chevron that opens the bullet list). Reached from the Themed Main Menu's What's New button and from Home's
 * What's New row (so it works with the custom title screen off). Data: {@link WhatsNewData}.
 */
public final class WhatsNewScreen extends Screen implements MenuThemedScreen {

    private static final int PAD = 8;
    private static final int LIST_ROW_H = 28;
    private static final int CARD_H = 30;
    private static final int CARD_GAP = 4;
    private static final int LINE_H = 10;
    private static final int BAR_W = 6;

    private final Screen parent;
    private List<WhatsNewData.Update> updates;
    private int selected;
    /** Indexes of the open update's sections whose bullet list is showing. */
    private final Set<Integer> open = new HashSet<>();
    /** Wrapped bullet lines per section of the open update, built when it is picked or the window resizes. */
    private final List<List<String>> lines = new ArrayList<>();
    private int listScroll;
    private int cardScroll;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int cardX;
    private int cardY;
    private int cardW;
    private int cardH;

    public WhatsNewScreen(Screen parent) {
        super(Component.literal("What's New"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(540, this.width - 8);
        panelH = Math.min(320, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(2, (this.height - panelH) / 2);
        listW = Math.max(120, panelW / 3);
        listX = panelX + PAD;
        listY = panelY + ScreenChrome.HEADER_H + 10;
        listH = panelY + panelH - PAD - 24 - listY;
        cardX = listX + listW + PAD + BAR_W;
        cardW = panelX + panelW - PAD - cardX;
        cardY = listY + 28;
        cardH = panelY + panelH - PAD - cardY;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose()).primary()
                .bounds(listX, panelY + panelH - PAD - 20, listW, 20).build());
        updates = WhatsNewData.get();
        if (updates != null) {
            select(Math.min(selected, Math.max(0, updates.size() - 1)));
        }
    }

    /** Opens update {@code i}: its sections closed, the cards back at the top. */
    void select(int i) {
        selected = i;
        open.clear();
        cardScroll = 0;
        lines.clear();
        if (updates == null || updates.isEmpty()) {
            return;
        }
        for (WhatsNewData.Section s : updates.get(i).sections()) {
            List<String> wrapped = new ArrayList<>();
            for (String item : s.items()) {
                List<String> parts = MenuStyle.wrap(item, cardW - 34, 4);
                for (int k = 0; k < parts.size(); k++) {
                    wrapped.add((k == 0 ? "- " : "  ") + parts.get(k));
                }
            }
            lines.add(wrapped);
        }
    }

    void toggle(int card) {
        if (!open.remove(card)) {
            open.add(card);
        }
    }

    private int heightOf(int card) {
        return CARD_H + (open.contains(card) ? lines.get(card).size() * LINE_H + 8 : 0);
    }

    private int totalCardsH() {
        int h = 0;
        for (int i = 0; i < lines.size(); i++) {
            h += heightOf(i) + CARD_GAP;
        }
        return h;
    }

    private void clampCardScroll() {
        cardScroll = Math.max(0, Math.min(cardScroll, totalCardsH() - cardH));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (updates == null) {
            updates = WhatsNewData.get();
            if (updates != null) {
                select(0);
            }
        }
        ScreenChrome.backdrop(g, this.width, this.height);
        ScreenChrome.frame(g, panelX, panelY, panelW, panelH);
        ScreenChrome.header(g, panelX, panelY, panelW, "Updates");
        ScreenChrome.well(g, listX, listY, listW, listH);
        if (updates == null || updates.isEmpty()) {
            ScreenChrome.text(g, updates == null ? "Loading..." : "No updates listed.", listX + 6, listY + 6, MenuStyle.DIM);
        } else {
            ScreenChrome.headerRight(g, panelX, panelY, panelW, updates.size() + " updates", MenuStyle.DIM);
            drawList(g, mouseX, mouseY);
            drawCards(g, mouseX, mouseY);
        }
        ScreenChrome.text(g, "What's New", cardX, listY + 2, MenuStyle.OR);
        ScreenChrome.text(g, "every update, newest first", cardX, listY + 14, MenuStyle.DIM);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private int listRows() {
        return Math.max(1, listH / LIST_ROW_H);
    }

    private void drawList(GuiGraphicsExtractor g, int mx, int my) {
        for (int i = 0; i < listRows() && listScroll + i < updates.size(); i++) {
            WhatsNewData.Update u = updates.get(listScroll + i);
            int ry = listY + i * LIST_ROW_H;
            boolean hover = mx >= listX && mx < listX + listW && my >= ry && my < ry + LIST_ROW_H;
            ScreenChrome.row(g, listX, ry, listW, LIST_ROW_H, hover, listScroll + i == selected, i % 2 == 1);
            ScreenChrome.text(g, u.date(), listX + 6, ry + 4, MenuStyle.OR);
            ScreenChrome.text(g, MenuStyle.ellipsize(u.summary(), listW - 12), listX + 6, ry + 15, MenuStyle.TXT);
        }
        if (updates.size() > listRows()) {
            ScreenChrome.scrollbar(g, listX + listW + 2, listY, listRows() * LIST_ROW_H, listScroll,
                    updates.size() - listRows(), listRows());
        }
    }

    private void drawCards(GuiGraphicsExtractor g, int mx, int my) {
        clampCardScroll();
        g.enableScissor(cardX, cardY, cardX + cardW, cardY + cardH);
        int y = cardY - cardScroll;
        List<WhatsNewData.Section> secs = updates.get(selected).sections();
        for (int i = 0; i < secs.size(); i++) {
            int h = heightOf(i);
            if (y + h > cardY && y < cardY + cardH) {
                boolean hover = mx >= cardX && mx < cardX + cardW && my >= Math.max(y, cardY)
                        && my < Math.min(y + CARD_H, cardY + cardH);
                ScreenChrome.card(g, cardX, y, cardW, h, hover);
                ScreenChrome.text(g, secs.get(i).title(), cardX + 8, y + 5, MenuStyle.OR);
                ScreenChrome.text(g, MenuStyle.ellipsize(secs.get(i).blurb(), cardW - 36), cardX + 8, y + 17, MenuStyle.DIM);
                ScreenChrome.text(g, open.contains(i) ? "v" : ">", cardX + cardW - 14, y + 11, MenuStyle.OR);
                if (open.contains(i)) {
                    List<String> ls = lines.get(i);
                    for (int k = 0; k < ls.size(); k++) {
                        ScreenChrome.text(g, ls.get(k), cardX + 12, y + CARD_H + k * LINE_H, MenuStyle.TXT);
                    }
                }
            }
            y += h + CARD_GAP;
        }
        g.disableScissor();
        int total = totalCardsH();
        if (total > cardH) {
            ScreenChrome.scrollbar(g, cardX - BAR_W - 2, cardY, cardH, cardScroll, total - cardH, cardH);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        if (updates != null && !updates.isEmpty()) {
            if (x >= listX && x < listX + listW && y >= listY && y < listY + listRows() * LIST_ROW_H) {
                int idx = listScroll + (int) ((y - listY) / LIST_ROW_H);
                if (idx < updates.size()) {
                    select(idx);
                    return true;
                }
            }
            if (x >= cardX && x < cardX + cardW && y >= cardY && y < cardY + cardH) {
                int top = cardY - cardScroll;
                for (int i = 0; i < lines.size(); i++) {
                    if (y >= top && y < top + CARD_H) {
                        toggle(i);
                        return true;
                    }
                    top += heightOf(i) + CARD_GAP;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = (int) Math.signum(scrollY);
        if (mouseX < cardX - BAR_W - 4 && updates != null) {
            listScroll = Math.max(0, Math.min(listScroll - step, updates.size() - listRows()));
        } else {
            cardScroll -= step * 14;
            clampCardScroll();
        }
        return true;
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return parent != null && parent.isPauseScreen();
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, panelW, panelH};
    }
}
