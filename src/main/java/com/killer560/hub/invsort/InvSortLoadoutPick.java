package com.killer560.hub.invsort;

import com.killer560.hub.compat.McInput;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Hypixel Loadout picker (killer560, 2026-11-04: "make the loadout selection in invsort open the loadout menu and have you
 * click an option"): a dummy of /loadouts' first page, 12 slots in two rows plus a None button. A click on one sets the
 * layout's loadout, saves it and returns to the layouts menu. Nothing is sent to the server.
 */
public final class InvSortLoadoutPick extends Screen implements MenuThemedScreen {

    private static final int COLS = 6;
    private static final int CELL = 28;
    private static final int PAD = 12;
    private static final int PANEL_W = COLS * CELL + 2 * PAD;
    private static final int PANEL_H = ScreenChrome.HEADER_H + 4 + 2 * CELL + 28 + 2 * PAD;

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
        return panelX + PAD + (i % COLS) * CELL;
    }

    private int slotY(int i) {
        return panelY + ScreenChrome.HEADER_H + 4 + PAD + (i / COLS) * CELL;
    }

    private int noneY() {
        return slotY(COLS * 2) + 4;
    }

    /** The choice under the point: 1-12, 0 for None, -1 for nothing. */
    private int choiceAt(double mx, double my) {
        for (int i = 0; i < InventoryLayout.MAX_LOADOUT; i++) {
            if (mx >= slotX(i) && mx < slotX(i) + CELL - 2 && my >= slotY(i) && my < slotY(i) + CELL - 2) {
                return i + 1;
            }
        }
        return mx >= panelX + PAD && mx < panelX + PANEL_W - PAD && my >= noneY() && my < noneY() + 18 ? 0 : -1;
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
        ScreenChrome.headerRight(g, panelX, panelY, PANEL_W, "click one", ScreenChrome.readable(0xFFAAAAAA));
        int hover = choiceAt(mouseX, mouseY);
        for (int i = 0; i < InventoryLayout.MAX_LOADOUT; i++) {
            ScreenChrome.slot(g, slotX(i), slotY(i), CELL - 2, current == i + 1, hover == i + 1);
            ScreenChrome.centered(g, String.valueOf(i + 1), slotX(i) + (CELL - 2) / 2, slotY(i) + (CELL - 2) / 2 - 4,
                    ScreenChrome.readable(0xFFFFFFFF));
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
