package com.killer560.hub.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * An ordered list editor in the shape SkyHanni's config uses for its Custom Scoreboard (MoulConfig's
 * {@code GuiOptionEditorDraggableList}): every row can be dragged to a new place, each row has a trash can that takes it
 * off the list, and the list's owner offers an Add control for what is not on it. Built for the Custom Scoreboard
 * (killer560, 2026-10-07) but knows nothing about it - the rows come from a {@link Model}.
 *
 * <ul>
 *   <li>DRAG: press anywhere on a row except its trash can and move more than {@value #DRAG_SLOP} units; the row
 *       lifts out and follows the cursor, the rest close up, and an orange line shows where it will land. Release
 *       drops it there ({@link Model#move}). Near the top or bottom edge the list scrolls by itself, faster the
 *       closer to (or further past) the edge the cursor is.</li>
 *   <li>CLICK: a press and release without that movement is {@link Model#click} (the scoreboard uses it to select a
 *       row and show that line's own options).</li>
 *   <li>TRASH: the can at a row's right end calls {@link Model#trash} on press, like a button.</li>
 *   <li>WHEEL: scrolls the list when it is longer than its box; at either end the turn goes to the page instead.</li>
 * </ul>
 *
 * <p>One widget for the whole list rather than a widget per row: a drag must keep receiving the mouse while it crosses
 * rows, and the mod menu routes a drag only to the widget that took the press. Scroll lives in a {@link ScrollState}
 * the owner keeps, because the mod menu rebuilds every widget after each change.
 */
public final class DragListWidget extends AbstractWidget implements WheelTarget {

    /** What the list shows and what its gestures do. Indices are positions in the list, top first. */
    public interface Model {
        int size();

        String label(int index);

        boolean selected(int index);

        void click(int index);

        /** Move the row at {@code from} so it ends up at {@code to}, counted with that row already lifted out. */
        void move(int from, int to);

        void trash(int index);
    }

    /** The list's scroll offset, kept by its owner across rebuilds. */
    public static final class ScrollState {
        public int scroll;
    }

    public static final int ROW_H = 16;
    static final int DRAG_SLOP = 3;
    private static final int ACCENT = 0xFFCC6600;
    private static final int ACCENT_BRIGHT = 0xFFFFAA33;
    private static final int BORDER = 0xFF553311;
    private static final int TRASH_W = 14;
    /** Auto-scroll: band at each edge, and speed range in units per second. */
    private static final int EDGE = ROW_H;
    private static final double MIN_SPEED = 60;
    private static final double MAX_SPEED = 320;

    private final Model model;
    private final ScrollState state;
    private final String emptyText;
    private final int visibleRows;

    private int pressIndex = -1;
    private double pressY;
    private double grabOffset;
    private boolean dragging;
    private double mouseY;
    private int insertSlot;
    private long lastAutoScrollNanos;

    public DragListWidget(int x, int y, int width, int visibleRows, Model model, ScrollState state, Component message,
                          String emptyText) {
        super(x, y, width, Math.max(1, visibleRows) * ROW_H + 2, message);
        this.model = model;
        this.state = state;
        this.emptyText = emptyText;
        this.visibleRows = Math.max(1, visibleRows);
        state.scroll = clampScroll(state.scroll);
    }

    /** Rows that fit before the list scrolls. */
    public static int heightFor(int rows) {
        return Math.max(1, rows) * ROW_H + 2;
    }

    // ---- geometry (public so tests can aim real mouse input at a row) -----------------------------------------------

    public int innerTop() {
        return getY() + 1;
    }

    public int innerBottom() {
        return getY() + 1 + visibleRows * ROW_H;
    }

    /** Screen y of row {@code index}'s top edge at the current scroll (may be outside the box). */
    public int rowY(int index) {
        return innerTop() + index * ROW_H - state.scroll;
    }

    /** Centre x of the drag handle (the three bars at the row's left). */
    public int handleX() {
        return getX() + 7;
    }

    /** Centre x of the trash can. */
    public int trashX() {
        return trashLeft() + TRASH_W / 2;
    }

    public int scroll() {
        return state.scroll;
    }

    public int maxScroll() {
        return Math.max(0, model.size() * ROW_H - visibleRows * ROW_H);
    }

    public boolean isDraggingRow() {
        return dragging;
    }

    private int trashLeft() {
        return getX() + getWidth() - TRASH_W - (maxScroll() > 0 ? 5 : 1);
    }

    private int clampScroll(int v) {
        return Math.max(0, Math.min(maxScroll(), v));
    }

    private int rowAt(double my) {
        if (my < innerTop() || my >= innerBottom()) {
            return -1;
        }
        int i = (int) Math.floor((my - innerTop() + state.scroll) / ROW_H);
        return i >= 0 && i < model.size() ? i : -1;
    }

    private boolean onTrash(double mx) {
        return mx >= trashLeft() && mx < trashLeft() + TRASH_W;
    }

    // ---- input ------------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!this.active || !this.visible || event.button() != 0 || !isMouseOver(event.x(), event.y())) {
            return false;
        }
        int i = rowAt(event.y());
        if (i < 0) {
            return true; // the list's empty space: taken, so the press does not fall through to a row behind it
        }
        if (onTrash(event.x())) {
            AbstractWidget.playButtonClickSound(Minecraft.getInstance().getSoundManager());
            model.trash(i);
            return true;
        }
        pressIndex = i;
        pressY = event.y();
        mouseY = event.y();
        grabOffset = event.y() - rowY(i);
        dragging = false;
        insertSlot = i;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (pressIndex < 0 || event.button() != 0) {
            return false;
        }
        mouseY = event.y();
        if (!dragging && Math.abs(event.y() - pressY) < DRAG_SLOP) {
            return true;
        }
        if (!dragging) {
            dragging = true;
            lastAutoScrollNanos = System.nanoTime();
        }
        autoScroll();
        updateSlot();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pressIndex < 0 || event.button() != 0) {
            return false;
        }
        int from = pressIndex;
        boolean wasDrag = dragging;
        pressIndex = -1;
        dragging = false;
        if (wasDrag) {
            mouseY = event.y();
            updateSlot();
            if (insertSlot != from) {
                model.move(from, insertSlot);
            }
        } else {
            model.click(from);
        }
        return true;
    }

    @Override
    public boolean wheel(double mouseX, double mouseY, double scrollY) {
        if (!isMouseOver(mouseX, mouseY) || maxScroll() <= 0) {
            return false;
        }
        int next = clampScroll(state.scroll - (int) Math.round(scrollY * ROW_H * 2));
        if (next == state.scroll) {
            return false;
        }
        state.scroll = next;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return wheel(mouseX, mouseY, scrollY);
    }

    /** Where the lifted row would land: rows counted with it lifted out, so 0..size-1. */
    private void updateSlot() {
        double floatTop = mouseY - grabOffset;
        int slot = (int) Math.round((floatTop - innerTop() + state.scroll) / ROW_H);
        insertSlot = Math.max(0, Math.min(model.size() - 1, slot));
    }

    /** Scrolls while a dragged row is held in the top or bottom edge band (or beyond it). Time-based, so the speed
     *  does not depend on the frame rate or on how often the mouse moves. */
    private void autoScroll() {
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastAutoScrollNanos) / 1e9);
        lastAutoScrollNanos = now;
        if (maxScroll() <= 0) {
            return;
        }
        double depth = 0;
        int dir = 0;
        if (mouseY < innerTop() + EDGE) {
            depth = (innerTop() + EDGE - mouseY) / EDGE;
            dir = -1;
        } else if (mouseY > innerBottom() - EDGE) {
            depth = (mouseY - (innerBottom() - EDGE)) / EDGE;
            dir = 1;
        }
        if (dir == 0) {
            return;
        }
        double speed = MIN_SPEED + (MAX_SPEED - MIN_SPEED) * Math.min(1.0, depth / 2.0);
        int step = (int) Math.max(1, Math.round(speed * dt));
        state.scroll = clampScroll(state.scroll + dir * step);
    }

    // ---- drawing ----------------------------------------------------------------------------------------------------

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        if (dragging) {
            autoScroll();
            updateSlot();
        }
        Font font = Minecraft.getInstance().font;
        int x = getX();
        int w = getWidth();
        g.fill(x, getY(), x + w, getY() + getHeight(), 0xFF080808);
        g.outline(x, getY(), w, getHeight(), BORDER);
        int top = innerTop();
        int bottom = innerBottom();
        if (model.size() == 0) {
            g.text(font, "§7" + emptyText, x + 6, top + 4, 0xFFFFFFFF, false);
            return;
        }
        g.enableScissor(x + 1, top, x + w - 1, bottom);
        try {
            int shown = 0;
            for (int i = 0; i < model.size(); i++) {
                if (dragging && i == pressIndex) {
                    continue;
                }
                int slot = shown++;
                if (dragging && slot >= insertSlot) {
                    slot++; // leave the gap where the lifted row will land
                }
                int rowY = top + slot * ROW_H - state.scroll;
                if (rowY + ROW_H <= top || rowY >= bottom) {
                    continue;
                }
                boolean hovered = !dragging && mx >= x && mx < x + w && my >= Math.max(top, rowY)
                        && my < Math.min(bottom, rowY + ROW_H);
                drawRow(g, font, i, x + 1, rowY, w - 2, hovered, hovered && onTrash(mx), model.selected(i), false);
            }
            if (dragging) {
                int lineY = top + insertSlot * ROW_H - state.scroll;
                g.fill(x + 1, lineY - 1, x + w - 1, lineY + 1, ACCENT_BRIGHT);
            }
        } finally {
            g.disableScissor();
        }
        drawScrollbar(g);
        if (dragging && pressIndex >= 0 && pressIndex < model.size()) {
            int fy = (int) Math.round(mouseY - grabOffset);
            fy = Math.max(top - ROW_H / 2, Math.min(bottom - ROW_H / 2, fy));
            drawRow(g, font, pressIndex, x + 1, fy, w - 2, true, false, true, true);
        }
    }

    private void drawRow(GuiGraphicsExtractor g, Font font, int index, int x, int y, int w, boolean hovered,
                         boolean trashHovered, boolean selected, boolean lifted) {
        int bg = lifted ? 0xEE2A1A08 : selected ? 0xFF24170A : hovered ? 0xFF262626 : (index % 2 == 0 ? 0xFF121212 : 0xFF0D0D0D);
        g.fill(x, y, x + w, y + ROW_H, bg);
        if (selected || lifted) {
            g.outline(x, y, w, ROW_H, lifted ? ACCENT_BRIGHT : ACCENT);
        }
        // Handle: three short bars.
        int hc = hovered || lifted ? ACCENT_BRIGHT : 0xFF777777;
        for (int b = 0; b < 3; b++) {
            g.fill(x + 3, y + 5 + b * 3, x + 10, y + 6 + b * 3, hc);
        }
        int textX = x + 14;
        int textW = trashLeft() - 4 - textX;
        String text = "§7" + (index + 1) + ". §f" + model.label(index);
        g.text(font, font.plainSubstrByWidth(text, Math.max(10, textW)), textX, y + 4, 0xFFFFFFFF, false);
        if (!lifted) {
            drawTrash(g, trashLeft() + 3, y + 2, trashHovered ? 0xFFFF5555 : (hovered ? 0xFFBBBBBB : 0xFF777777));
        }
    }

    /** A small trash can, 8x12: handle, lid, body with two slats. */
    private static void drawTrash(GuiGraphicsExtractor g, int x, int y, int c) {
        g.fill(x + 3, y + 1, x + 5, y + 2, c);
        g.fill(x - 1, y + 2, x + 9, y + 3, c);
        g.outline(x, y + 4, 8, 8, c);
        g.fill(x + 2, y + 6, x + 3, y + 10, c);
        g.fill(x + 5, y + 6, x + 6, y + 10, c);
    }

    private void drawScrollbar(GuiGraphicsExtractor g) {
        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int h = visibleRows * ROW_H;
        int trackX = getX() + getWidth() - 4;
        g.fill(trackX, innerTop(), trackX + 3, innerBottom(), 0xFF1A1A1A);
        int contentH = Math.max(1, model.size() * ROW_H);
        int thumbH = Math.max(10, h * h / contentH);
        int thumbY = innerTop() + (h - thumbH) * state.scroll / max;
        g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, ACCENT);
    }

    /** Empty while a row is being dragged, so the mod menu's hover description does not cover the drop line. */
    @Override
    public Component getMessage() {
        return dragging ? Component.empty() : super.getMessage();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
