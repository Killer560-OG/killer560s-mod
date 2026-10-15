package com.killer560.hub.roomsim;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.hud.GuiRects;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * The Dungeon Sim menu's frame and left column, shared by {@link SimMenuScreen}'s pages and the map designer
 * ({@link SimMapEditorScreen}), so the designer is one more page of the same menu rather than a screen of its own.
 *
 * <p>killer560 (2026-10-15): "Clicking Dungeon Sim opens Create a New Map by default. But if he was in the sim menu
 * earlier this game session, reopening takes him back to the page he had clicked last" - {@link #lastPage} is that
 * memory. It is held for the game session only: a restart opens on the designer again. The designer keeps this column
 * on screen, so Previous Runs, Load a Room and Route Practice are one click away from it.
 */
public final class SimMenuNav {

    /** The menu's pages, in the column's order. */
    public enum Page {
        DESIGNER(SimMenuScreen.NEW_MAP_LABEL),
        PREVIOUS("Previous Runs"),
        ROOM("Load a Room"),
        CYCLE("Route Practice");

        /** Its entry in the left column. */
        public final String nav;

        Page(String nav) {
            this.nav = nav;
        }
    }

    /** The page shown last this game session, or null before the menu was opened. Never saved to disk. */
    private static Page lastPage;

    /**
     * The designer this session, kept so a drawing survives a look at another page and back. Its parent is re-pointed
     * at whatever opened the menu this time.
     */
    private static SimMapEditorScreen designer;

    /** The page the menu shows when it is opened now: the last one this session, else the designer. For the testkit. */
    public static String openingPage() {
        return (lastPage == null ? Page.DESIGNER : lastPage).name();
    }

    /** Forgets the session's last page and drawing, as a fresh game start would. For the testkit. */
    public static void forgetSession() {
        lastPage = null;
        designer = null;
    }

    /** The page shown last this session, or null. */
    static Page lastPage() {
        return lastPage;
    }

    static void shown(Page page) {
        lastPage = page;
    }

    /** What the Dungeon Sim button (and /map) opens: the designer at first, then the page he was on last. */
    public static Screen open(Screen parent) {
        return screenFor(lastPage == null ? Page.DESIGNER : lastPage, parent);
    }

    /** The screen for {@code page}, closing back to {@code parent}. */
    static Screen screenFor(Page page, Screen parent) {
        if (page == Page.DESIGNER) {
            if (designer == null) {
                designer = new SimMapEditorScreen(parent);
            } else {
                designer.setParent(parent);
            }
            return designer;
        }
        return new SimMenuScreen(parent, page);
    }

    static void go(Minecraft mc, Page page, Screen parent) {
        McCompat.setScreen(mc, screenFor(page, parent));
    }

    // ------------------------------------------------------------------------------------------- geometry

    private static final int ITEM_H = 20;
    private static final int ITEM_GAP = 3;

    final int panelX;
    final int panelY;
    final int panelW;
    final int panelH;
    /** The left column's box. */
    final int sideX;
    final int sideY;
    final int sideW;
    final int sideH;
    /** The page area right of the column: x, top, width and the lowest unit it may use. */
    final int contentX;
    final int contentY;
    final int contentW;
    final int contentBottom;

    SimMenuNav(int width, int height) {
        // Wide: the old 560-unit cap left most of a real monitor empty around a list of names.
        panelW = Math.min(width - 16, Math.max(420, Math.min((int) (width * 0.92), 820)));
        panelH = Math.min(height - 16, Math.max(240, Math.min((int) (height * 0.9), 500)));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        sideX = panelX + 6;
        sideY = panelY + 4 + ScreenChrome.HEADER_H + 6;
        sideW = Math.max(112, Math.min(144, panelW / 5));
        sideH = panelY + panelH - 6 - sideY;
        contentX = sideX + sideW + 10;
        contentY = sideY + 2;
        contentW = panelX + panelW - 10 - contentX;
        contentBottom = panelY + panelH - 8;
    }

    /**
     * The column's entries and its Back, added through {@code add}. A page {@code local} can show itself (the sim menu's
     * own three) goes there; anything else opens its screen. Back closes the whole menu to {@code parent}.
     */
    void addItems(Consumer<AbstractWidget> add, Page current, Screen parent, Consumer<Page> local) {
        int x = sideX + 4;
        int w = sideW - 8;
        int y = sideY + 6;
        Minecraft mc = Minecraft.getInstance();
        for (Page p : Page.values()) {
            add.accept(new Item(p.nav, p == current, x, y, w, ITEM_H, () -> {
                if (p == current) {
                    return;
                }
                if (local != null && p != Page.DESIGNER) {
                    local.accept(p);
                } else {
                    go(mc, p, parent);
                }
            }));
            y += ITEM_H + ITEM_GAP;
        }
        add.accept(new Item("Back", false, x, backY(), w, ITEM_H, () -> McCompat.setScreen(mc, parent)));
    }

    private int backY() {
        return sideY + sideH - 6 - ITEM_H;
    }

    /** The panel, its header bar with the menu's name, and the column's raised box. Widgets draw over it. */
    void drawFrame(GuiGraphicsExtractor g, int screenW, int screenH) {
        ScreenChrome.backdrop(g, screenW, screenH);
        ScreenChrome.frame(g, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(g, panelX, panelY, panelW);
        ScreenChrome.title(g, "DUNGEON SIM", panelX + 10, panelY + 11, false);
        GuiRects r = GuiRects.begin(g);
        MenuStyle.dropShadow(r, sideX, sideY, sideW, sideH, 2);
        MenuStyle.bevel(r, sideX, sideY, sideW, sideH, MenuStyle.SIDE_TOP, MenuStyle.SIDE_BOTTOM);
        MenuStyle.separator(r, sideX + 6, sideX + sideW - 6, backY() - 6);
        r.submit();
    }

    /**
     * One entry of the column, drawn the way the mod menu's sidebar draws a category: the open page in the selected look
     * (accent gradient, an accent strip down its left, accent text), the rest dim text with a hover band. Its message is
     * the bare label, so a test finds it the way it finds a button.
     */
    public static final class Item extends AbstractWidget {
        private final boolean selected;
        private final Runnable press;

        Item(String label, boolean selected, int x, int y, int w, int h, Runnable press) {
            super(x, y, w, h, Component.literal(label));
            this.selected = selected;
            this.press = press;
        }

        /** Whether this entry is the page on screen. Read by the testkit. */
        public boolean selected() {
            return selected;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            boolean hover = isHovered && !selected;
            GuiRects r = GuiRects.begin(g);
            if (selected) {
                MenuStyle.dropShadow(r, x, y, w, h, 2);
                MenuStyle.glow(r, x, y, w, h, MenuStyle.OR, 0x22);
                MenuStyle.hGradient(r, x + 1, y + 1, x + w - 1, y + h - 1, MenuStyle.SEL_TOP, MenuStyle.SEL_BOTTOM, 6);
                r.outline(x, y, w, h, MenuStyle.ACCENT_EDGE);
                r.fill(x + 1, y + 1, x + w - 1, y + 2, MenuStyle.HILITE);
                r.fill(x + 1, y + 1, x + 3, y + h - 1, MenuStyle.OR);
            } else if (hover) {
                r.fill(x, y, x + w, y + h, MenuStyle.alpha(MenuStyle.TXT, 0x14));
                r.fill(x, y + 2, x + 2, y + h - 2, MenuStyle.OR2);
            }
            r.submit();
            String label = MenuStyle.ellipsize(getMessage().getString(), w - 14);
            int colour = selected ? MenuStyle.OR : hover ? MenuStyle.TXT_HOVER : MenuStyle.TXT_SOFT;
            ScreenChrome.text(g, label, x + 9, y + (h - 8) / 2, colour);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            press.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, Component.literal(getMessage().getString()
                    + (selected ? " (open)" : "")));
        }
    }
}
