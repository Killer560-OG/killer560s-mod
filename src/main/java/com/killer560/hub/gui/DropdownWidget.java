package com.killer560.hub.gui;

import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A drop-down you pick from (killer560, 2026-10-22: the Custom Party Finder's buttons should be "drop-downs you select
 * from, not buttons you toggle between"). The closed button shows the current choice and a ▼; a click opens the list of
 * choices under it (above it when there is no room below); a click on a choice selects it and closes the list; a click
 * anywhere else, or Escape, closes it and changes nothing. Opening and closing happen on the click itself - nothing here
 * waits or animates.
 * <p>
 * The list has to draw over every other widget and take every click while it is open, which a widget cannot do from its
 * own {@code extractWidgetRenderState} (widgets added after it draw over it, and the screen hands clicks to whichever
 * child is under the mouse). So at most one drop-down is open at a time ({@link #openOne()}), and a screen holding any
 * forwards four things to the static helpers: {@link #clickOpen} first in {@code mouseClicked}, {@link #keyOpen} first in
 * {@code keyPressed}, {@link #scrollOpen} first in {@code mouseScrolled}, and {@link #extractOpen} after
 * {@code super.extractRenderState} (it starts a new stratum, so nothing drawn before it covers the list). Call
 * {@link #closeAll()} in the screen's {@code init}, so a rebuilt screen never keeps a stale list open. {@code LfgScreen}
 * is the worked example.
 */
public final class DropdownWidget<T> extends AbstractWidget {

    /** One choice: its value, the text the list shows, and the shorter text the closed button shows. */
    public record Option<T>(T value, String label, String buttonLabel) {
        public static <T> Option<T> of(T value, String label) {
            return new Option<>(value, label, label);
        }
    }

    private static final int ROW_H = 12;
    private static final String ARROW = "▼";

    /** The one open drop-down, or null. */
    private static DropdownWidget<?> open;

    private final List<Option<T>> options;
    private final Consumer<T> onSelect;
    private int selected;
    /** The screen's size the list must stay inside (0: the window's GUI-scaled size). */
    private int limitW;
    private int limitH;

    // The open list's layout, worked out when it opens.
    private int listX;
    private int listY;
    private int listW;
    private int visibleRows;
    private int scroll;

    private DropdownWidget(int x, int y, int w, int h, List<Option<T>> options, int selected, Consumer<T> onSelect) {
        super(x, y, w, h, Component.empty());
        if (options.isEmpty()) {
            throw new IllegalArgumentException("a drop-down needs at least one choice");
        }
        this.options = List.copyOf(options);
        this.onSelect = onSelect;
        this.selected = selected;
        updateMessage();
    }

    /**
     * A drop-down at {@code x, y, w, h} over {@code options}, showing {@code current} (the first choice when it is none of
     * them). {@code onSelect} runs once when he picks a different choice; picking the one already shown runs nothing.
     */
    public static <T> DropdownWidget<T> of(int x, int y, int w, int h, List<Option<T>> options, T current,
            Consumer<T> onSelect) {
        int at = 0;
        for (int i = 0; i < options.size(); i++) {
            if (Objects.equals(options.get(i).value(), current)) {
                at = i;
                break;
            }
        }
        return new DropdownWidget<>(x, y, w, h, options, at, onSelect);
    }

    /** Keeps the open list inside a screen of this size (call with the screen's {@code width, height}). */
    public DropdownWidget<T> within(int width, int height) {
        this.limitW = width;
        this.limitH = height;
        return this;
    }

    // ---- state -----------------------------------------------------------------------------------------------------

    public T selected() {
        return options.get(selected).value();
    }

    public int selectedIndex() {
        return selected;
    }

    /** The list's texts, in order (read by the testkit). */
    public List<String> optionLabels() {
        List<String> out = new ArrayList<>();
        for (Option<T> o : options) {
            out.add(o.label());
        }
        return out;
    }

    public boolean isOpen() {
        return open == this;
    }

    /** The open drop-down, or null. */
    public static DropdownWidget<?> openOne() {
        return open;
    }

    public void openList() {
        if (open != null && open != this) {
            open.closeList();
        }
        layout();
        open = this;
    }

    public void closeList() {
        if (open == this) {
            open = null;
        }
    }

    /** Closes whichever drop-down is open, changing nothing. */
    public static void closeAll() {
        open = null;
    }

    /** Selects choice {@code index} and closes the list; {@code onSelect} runs when it is a different choice. */
    public void choose(int index) {
        closeList();
        if (index < 0 || index >= options.size() || index == selected) {
            return;
        }
        selected = index;
        updateMessage();
        onSelect.accept(options.get(index).value());
    }

    private void updateMessage() {
        setMessage(Component.literal(options.get(selected).buttonLabel()));
    }

    // ---- layout ----------------------------------------------------------------------------------------------------

    private int screenW() {
        return limitW > 0 ? limitW : Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    private int screenH() {
        return limitH > 0 ? limitH : Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }

    private void layout() {
        Font font = Minecraft.getInstance().font;
        int widest = 0;
        for (Option<T> o : options) {
            widest = Math.max(widest, font.width(o.label()));
        }
        int sw = screenW();
        int sh = screenH();
        listW = Math.min(Math.max(getWidth(), widest + 14), Math.max(getWidth(), sw - 4));
        listX = Math.max(2, Math.min(getX(), sw - 2 - listW));
        int n = options.size();
        int below = (sh - 2 - (getY() + getHeight())) / ROW_H;
        int above = (getY() - 2) / ROW_H;
        boolean down = below >= n || below >= above;
        visibleRows = Math.max(1, Math.min(n, down ? below : above));
        int h = visibleRows * ROW_H + 2;
        listY = down ? getY() + getHeight() : getY() - h;
        // The shown choice in view: as near the top of the list as the scroll allows.
        scroll = Math.max(0, Math.min(selected, n - visibleRows));
    }

    /** The open list's box {x, y, w, h}, or null when it is closed (read by the testkit). */
    public int[] listBounds() {
        return isOpen() ? new int[]{listX, listY, listW, visibleRows * ROW_H + 2} : null;
    }

    /** Where choice {@code index} sits in the open list {x, y, w, h}, or null when closed or scrolled out of view. */
    public int[] optionBounds(int index) {
        if (!isOpen() || index < scroll || index >= scroll + visibleRows) {
            return null;
        }
        return new int[]{listX + 1, listY + 1 + (index - scroll) * ROW_H, listW - 2, ROW_H};
    }

    private boolean overList(double mx, double my) {
        return mx >= listX && mx < listX + listW && my >= listY && my < listY + visibleRows * ROW_H + 2;
    }

    private int rowAt(double mx, double my) {
        if (!overList(mx, my)) {
            return -1;
        }
        int row = (int) Math.floor((my - listY - 1) / ROW_H);
        if (row < 0 || row >= visibleRows) {
            return -1;
        }
        return scroll + row;
    }

    // ---- the screen's forwards -------------------------------------------------------------------------------------

    /** True while a list is open and the mouse is over it (the screen then draws its widgets as un-hovered). */
    public static boolean coversMouse(double mx, double my) {
        return open != null && open.overList(mx, my);
    }

    /**
     * First thing in a screen's {@code mouseClicked}. With a list open it takes the click, whatever is under it: a choice
     * selects and closes, the drop-down's own button or anywhere else closes without a change. Returns false (the screen
     * carries on) only when no list is open.
     */
    public static boolean clickOpen(MouseButtonEvent event) {
        DropdownWidget<?> d = open;
        if (d == null) {
            return false;
        }
        int row = event.button() == 0 ? d.rowAt(event.x(), event.y()) : -1;
        if (row >= 0) {
            d.playDownSound(Minecraft.getInstance().getSoundManager());
            d.choose(row);
        } else {
            d.closeList();
        }
        return true;
    }

    /** First thing in a screen's {@code keyPressed}: Escape closes an open list (and not the screen). */
    public static boolean keyOpen(KeyEvent event) {
        if (open != null && event.key() == 256) { // GLFW_KEY_ESCAPE
            open.closeList();
            return true;
        }
        return false;
    }

    /** First thing in a screen's {@code mouseScrolled}: the wheel scrolls an open list that is longer than its box. */
    public static boolean scrollOpen(double mx, double my, double dy) {
        DropdownWidget<?> d = open;
        if (d == null || !d.overList(mx, my)) {
            return open != null; // a wheel elsewhere does not scroll the page under an open list
        }
        int max = Math.max(0, d.options.size() - d.visibleRows);
        d.scroll = Math.max(0, Math.min(max, d.scroll - (int) Math.signum(dy)));
        return true;
    }

    /** After {@code super.extractRenderState}: draws the open list, if any, above everything drawn before it. */
    public static void extractOpen(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        DropdownWidget<?> d = open;
        if (d == null || !d.visible) {
            return;
        }
        graphics.nextStratum();
        d.drawList(graphics, mouseX, mouseY);
    }

    // ---- drawing ---------------------------------------------------------------------------------------------------

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        MenuStyle.use();
        int x0 = getX();
        int y0 = getY();
        int w = getWidth();
        int h = getHeight();
        boolean hover = isHovered && active;
        boolean isOpen = isOpen();
        GuiRects r = GuiRects.begin(graphics);
        MenuStyle.raisedButton(r, x0 + 1, y0 + 1, w - 2, h - 2, hover || isOpen ? 1f : 0f, isOpen ? 1f : 0f);
        r.submit();
        Font font = Minecraft.getInstance().font;
        int arrowW = font.width(ARROW);
        String raw = getMessage().getString();
        String label = MenuStyle.ellipsize(MenuStyle.LIGHT ? MenuStyle.lightReadable(raw) : raw, w - 14 - arrowW);
        int ty = y0 + (h - 8) / 2;
        int color = !active ? MenuStyle.DIM2 : (hover || isOpen ? MenuStyle.OR_HOVER : MenuStyle.OR);
        MenuStyle.text(graphics, label, x0 + 6, ty, faded(color));
        MenuStyle.text(graphics, ARROW, x0 + w - 6 - arrowW, ty, faded(isOpen ? MenuStyle.OR_HOVER : MenuStyle.DIM));
    }

    private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        MenuStyle.use();
        int h = visibleRows * ROW_H + 2;
        int hoverRow = rowAt(mouseX, mouseY);
        GuiRects r = GuiRects.begin(graphics);
        MenuStyle.dropShadow(r, listX, listY, listW, h, 3);
        r.gradient(listX + 1, listY + 1, listX + listW - 1, listY + h - 1, MenuStyle.DRAWER_TOP, MenuStyle.DRAWER_BOTTOM);
        r.outline(listX, listY, listW, h, MenuStyle.OR2);
        for (int i = 0; i < visibleRows; i++) {
            int idx = scroll + i;
            int ry = listY + 1 + i * ROW_H;
            if (idx == hoverRow) {
                r.fill(listX + 1, ry, listX + listW - 1, ry + ROW_H, MenuStyle.alpha(MenuStyle.OR, 0x38));
            } else if (idx == selected) {
                r.fill(listX + 1, ry, listX + listW - 1, ry + ROW_H, MenuStyle.alpha(MenuStyle.OR, 0x18));
            }
            if (idx == selected) {
                r.fill(listX + 1, ry + 1, listX + 3, ry + ROW_H - 1, MenuStyle.OR);
            }
        }
        int max = options.size() - visibleRows;
        if (max > 0) {
            MenuStyle.scrollbar(r, listX + listW - 6, listY + 1, h - 2, scroll, max, visibleRows);
        }
        r.submit();
        Font font = Minecraft.getInstance().font;
        int textW = listW - (max > 0 ? 16 : 10);
        for (int i = 0; i < visibleRows; i++) {
            int idx = scroll + i;
            int ry = listY + 1 + i * ROW_H;
            String raw = options.get(idx).label();
            String label = MenuStyle.ellipsize(MenuStyle.LIGHT ? MenuStyle.lightReadable(raw) : raw, textW);
            int color = idx == hoverRow ? MenuStyle.TXT_HOVER : idx == selected ? MenuStyle.OR_HOVER : MenuStyle.TXT;
            MenuStyle.text(graphics, label, listX + 6, ry + (ROW_H - 8) / 2, color);
        }
    }

    private int faded(int argb) {
        float a = Math.max(0f, Math.min(1f, this.alpha));
        if (a >= 1f) {
            return argb;
        }
        return (Math.round(((argb >>> 24) & 0xFF) * a) << 24) | (argb & 0x00FFFFFF);
    }

    // ---- input -----------------------------------------------------------------------------------------------------

    /** A left click on the closed button opens the list (an open one is closed by {@link #clickOpen}, or here when the
     *  screen does not forward clicks). */
    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (isOpen()) {
            closeList();
        } else {
            openList();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
