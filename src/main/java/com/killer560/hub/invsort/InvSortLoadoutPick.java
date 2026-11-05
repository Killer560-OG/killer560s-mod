package com.killer560.hub.invsort;

import com.killer560.hub.compat.McInput;
import com.killer560.hub.gui.ItemCell;
import com.killer560.hub.loadoutkeybinds.LoadoutKeybindsFeature;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Hypixel Loadout picker (killer560, 2026-11-04: "make the loadout selection in invsort open the loadout menu and have you
 * click an option"; 2026-11-05: "the loadout thing doesnt show the actual loadout"): a copy of /loadouts' first page as
 * {@link InvSortLoadoutCache} last saw it (the same items, Hypixel's tooltips on hover, each loadout's number on its
 * slot), plus a None button. A click on a loadout sets the layout's loadout, saves it and returns to the layouts menu.
 * Before the page has been seen it is 12 number buttons and a hint. Nothing is sent to the server.
 */
public final class InvSortLoadoutPick extends Screen implements MenuThemedScreen {

    private static final int COLS = 6;
    private static final int CELL = 28;
    private static final int PAD = 12;
    private static final int ICON = 24;
    private static final int PANEL_W = 9 * ICON + 2 * PAD;
    private static final int PANEL_H = ScreenChrome.HEADER_H + 4 + 6 * ICON + 28 + 2 * PAD;

    private final InventorySorterScreen parent;
    private final int current;
    private int panelX;
    private int panelY;

    InvSortLoadoutPick(InventorySorterScreen parent, int current) {
        super(Component.literal("Hypixel Loadout"));
        this.parent = parent;
        this.current = current;
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;
    }

    private int slotX(int i) {
        return panelX + PAD + (i % COLS) * CELL + (PANEL_W - 2 * PAD - COLS * CELL) / 2;
    }

    private int slotY(int i) {
        return panelY + ScreenChrome.HEADER_H + 4 + PAD + (i / COLS) * CELL;
    }

    private int noneY() {
        return panelY + PANEL_H - PAD - 18;
    }

    private int cellX(int s) {
        return panelX + PAD + (s % 9) * ICON;
    }

    private int cellY(int s) {
        return panelY + ScreenChrome.HEADER_H + 4 + PAD + (s / 9) * ICON;
    }

    /** The cached page's slot under the point, or -1. */
    private int cellAt(double mx, double my, List<ItemStack> page) {
        int cx = (int) Math.floor((mx - (panelX + PAD)) / ICON);
        int cy = (int) Math.floor((my - (panelY + ScreenChrome.HEADER_H + 4 + PAD)) / ICON);
        int s = cy * 9 + cx;
        return cx >= 0 && cx < 9 && cy >= 0 && s < Math.min(54, page.size()) ? s : -1;
    }

    /** The choice under the point: 1-12, 0 for None, -1 for nothing. */
    private int choiceAt(double mx, double my) {
        List<ItemStack> page = InvSortLoadoutCache.get();
        if (page != null) {
            int cell = cellAt(mx, my, page);
            int n = cell < 0 ? 0 : LoadoutKeybindsFeature.loadoutAt(cell);
            if (n > 0) {
                return n;
            }
        } else for (int i = 0; i < InventoryLayout.MAX_LOADOUT; i++) {
            if (mx >= slotX(i) && mx < slotX(i) + CELL - 2 && my >= slotY(i) && my < slotY(i) + CELL - 2) {
                return i + 1;
            }
        }
        return mx >= panelX + PAD && mx < panelX + PANEL_W - PAD && my >= noneY() && my < noneY() + 18 ? 0 : -1;
    }

    /** Test hook: the screen point in the middle of choice {@code n} (1-12, 0 for None) as drawn now. */
    public int[] centerOf(int n) {
        if (n == 0) {
            return new int[]{panelX + PANEL_W / 2, noneY() + 9};
        }
        List<ItemStack> page = InvSortLoadoutCache.get();
        if (page == null) {
            return new int[]{slotX(n - 1) + CELL / 2 - 1, slotY(n - 1) + CELL / 2 - 1};
        }
        int s = LoadoutKeybindsFeature.slotOf(n);
        return new int[]{cellX(s) + ICON / 2, cellY(s) + ICON / 2};
    }

    /** Sets the loadout and goes back to the layouts menu. Public for tests. */
    public void choose(int loadout) {
        parent.setLoadout(loadout);
        onClose();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int c = McInput.button(event) == 0 ? choiceAt(event.x(), event.y()) : -1;
        if (c >= 0) {
            choose(c);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(g, width, height);
        ScreenChrome.frame(g, panelX, panelY, PANEL_W, PANEL_H);
        ScreenChrome.header(g, panelX, panelY, PANEL_W, "Hypixel Loadout");
        ScreenChrome.headerRight(g, panelX, panelY, PANEL_W, "click a loadout", ScreenChrome.readable(0xFFAAAAAA));
        int hover = choiceAt(mouseX, mouseY);
        List<ItemStack> page = InvSortLoadoutCache.get();
        if (page == null) {
            for (int i = 0; i < InventoryLayout.MAX_LOADOUT; i++) {
                ScreenChrome.slot(g, slotX(i), slotY(i), CELL - 2, current == i + 1, hover == i + 1);
                ScreenChrome.centered(g, String.valueOf(i + 1), slotX(i) + (CELL - 2) / 2, slotY(i) + (CELL - 2) / 2 - 4,
                        ScreenChrome.readable(0xFFFFFFFF));
            }
            ScreenChrome.centered(g, "Open /loadouts once to see your loadouts here", panelX + PANEL_W / 2,
                    noneY() - 12, ScreenChrome.readable(0xFFAAAAAA));
        } else {
            ItemStack tip = ItemStack.EMPTY;
            for (int s = 0; s < Math.min(54, page.size()); s++) {
                int n = LoadoutKeybindsFeature.loadoutAt(s);
                ScreenChrome.slot(g, cellX(s), cellY(s), ICON - 1, n > 0 && n == current, n > 0 && n == hover);
                ItemCell.draw(g, font, page.get(s), cellX(s) + 1, cellY(s) + 1, ICON - 3);
                if (n > 0) {
                    ScreenChrome.text(g, String.valueOf(n), cellX(s) + 3, cellY(s) + 3, ScreenChrome.readable(0xFFFFFFFF));
                }
                if (mouseX >= cellX(s) && mouseX < cellX(s) + ICON && mouseY >= cellY(s) && mouseY < cellY(s) + ICON) {
                    tip = page.get(s);
                }
            }
            if (!tip.isEmpty()) {
                g.setTooltipForNextFrame(font, Screen.getTooltipFromItem(minecraft, tip), Optional.empty(), mouseX, mouseY);
            }
        }
        ScreenChrome.button(g, panelX + PAD, noneY(), PANEL_W - 2 * PAD, 18, "None", hover == 0, current == 0);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, PANEL_W, PANEL_H};
    }
}
