package com.killer560.hub.roomsim;

import com.killer560.hub.gui.profit.ProfitPanels;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The sim's toolkit, as a grid you click.
 *
 * <p>killer560 (2026-09-28): "make /simitem open a menu like the skyblock stoage system types where i can click
 * on the items. All of it should be through a gui."
 *
 * <p>Laid out the way Skyblock's own storage menus are - a grid of real item icons in a bordered panel, hover
 * for the name, click to take one - rather than as a list of labels. That matters more than looks: the point of
 * practising in here is muscle memory, and picking a Hyperion out of a grid by its icon is the same action as
 * picking it out of a Skyblock menu, while reading "hyperion" off a list of words is not.
 *
 * <p>The items themselves still come from {@link SimItems}, which owns the one table of what exists and what
 * each one does. This is a way of asking, not a second list to keep in step.
 */
public final class SimItemsScreen extends Screen {

    /** Nine across, the width of a hotbar - the row he will be filling. */
    private static final int COLUMNS = 9;

    /**
     * Cell size and spacing.
     *
     * <p>killer560 (2026-09-28): "this menu is a bit cluttered but works just make it look better." It was
     * drawing a 20px grid and then writing the hovered item's name straight across it, so the text sat on top
     * of the icons. Cells are now the 18px an inventory slot is, with the 4px gutter a Skyblock menu uses, and
     * every piece of text has a row of its own.
     */
    private static final int CELL = 26;
    private static final int PAD = 4;

    /** Room for the title bar above the grid. */
    private static final int HEADER = 34;

    /** Room under the grid for the hovered name and the button, each on its own line. */
    private static final int FOOTER = 56;

    private final Screen parent;
    private final List<SimItems.Entry> entries = new ArrayList<>();
    private final List<ItemStack> icons = new ArrayList<>();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int gridX;
    private int gridY;
    private int hovered = -1;

    public SimItemsScreen(Screen parent) {
        super(Component.literal("Sim Items"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        entries.clear();
        icons.clear();
        for (SimItems.Entry e : SimItems.entries()) {
            entries.add(e);
            icons.add(SimItems.build(e.skyblockId()));
        }
        int rows = Math.max(1, (entries.size() + COLUMNS - 1) / COLUMNS);
        int gridW = COLUMNS * CELL + (COLUMNS - 1) * PAD;
        int gridH = rows * CELL + (rows - 1) * PAD;
        panelW = gridW + 24;
        panelH = HEADER + gridH + FOOTER;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        gridX = panelX + 12;
        gridY = panelY + HEADER;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,
                                boolean doubleClick) {
        int idx = cellAt(event.x(), event.y());
        if (idx >= 0 && idx < entries.size()) {
            // Gives one and stays open. Kitting out a hotbar is nine picks, and a menu that closed after each
            // would make the common case nine round trips through the inventory key.
            SimItems.give(this.minecraft, entries.get(idx).skyblockId());
            return true;
        }
        if (isOverGiveAll(event.x(), event.y())) {
            SimItems.giveEverything(this.minecraft);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xCC000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, ProfitPanels.PANEL_BG);
        g.outline(panelX, panelY, panelW, panelH, ProfitPanels.BORDER);
        g.fill(panelX, panelY, panelX + panelW, panelY + 30, 0xFF000000);
        g.fill(panelX, panelY + 29, panelX + panelW, panelY + 30, ProfitPanels.ACCENT);
        g.text(this.font, "SIM ITEMS", panelX + 10, panelY + 11, ProfitPanels.ACCENT, false);

        hovered = cellAt(mouseX, mouseY);
        for (int i = 0; i < entries.size(); i++) {
            int cx = gridX + (i % COLUMNS) * (CELL + PAD);
            int cy = gridY + (i / COLUMNS) * (CELL + PAD);
            g.fill(cx, cy, cx + CELL, cy + CELL, i == hovered ? 0xFF3A2A12 : 0xFF141414);
            if (i == hovered) {
                g.outline(cx, cy, CELL, CELL, ProfitPanels.ACCENT);
            }
            // Centred in the cell rather than pinned to a corner - an item icon is 16px.
            g.item(icons.get(i), cx + (CELL - 16) / 2, cy + (CELL - 16) / 2);
        }

        int allY = panelY + panelH - 28;
        boolean overAll = isOverGiveAll(mouseX, mouseY);
        g.fill(panelX + 10, allY, panelX + panelW - 10, allY + 18, overAll ? 0xFF3A2A12 : 0xFF141414);
        g.outline(panelX + 10, allY, panelW - 20, 18, overAll ? ProfitPanels.ACCENT : ProfitPanels.BORDER);
        g.centeredText(this.font, "Give me everything", panelX + panelW / 2, allY + 5,
                overAll ? ProfitPanels.ACCENT : ProfitPanels.TEXT);

        // The name under the grid rather than in a floating tooltip: the grid is small and a tooltip would
        // cover the neighbouring items you are choosing between.
        String label = hovered >= 0 && hovered < entries.size()
                ? entries.get(hovered).displayName()
                : "Click an item to take one";
        // Its own line, clear of both the grid above and the button below.
        g.centeredText(this.font, label, panelX + panelW / 2, panelY + panelH - FOOTER + 6,
                hovered >= 0 ? ProfitPanels.TEXT : ProfitPanels.DIM);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private boolean isOverGiveAll(double mx, double my) {
        int allY = panelY + panelH - 28;
        return mx >= panelX + 10 && mx <= panelX + panelW - 10 && my >= allY && my <= allY + 18;
    }

    /** Which cell a point is over, or -1. */
    private int cellAt(double mx, double my) {
        if (mx < gridX || my < gridY) {
            return -1;
        }
        int col = (int) ((mx - gridX) / (double) (CELL + PAD));
        int row = (int) ((my - gridY) / (double) (CELL + PAD));
        if (col < 0 || col >= COLUMNS || row < 0) {
            return -1;
        }
        // Reject the gaps between cells, so a click on the seam does nothing rather than the wrong thing.
        double inX = (mx - gridX) % (CELL + PAD);
        double inY = (my - gridY) % (CELL + PAD);
        if (inX > CELL || inY > CELL) {
            return -1;
        }
        int idx = row * COLUMNS + col;
        return idx < entries.size() ? idx : -1;
    }
}
