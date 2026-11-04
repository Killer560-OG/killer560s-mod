package com.killer560.hub.armourdye;

import com.killer560.hub.gui.MenuThemedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared scrolling, close and theme plumbing of the armor dye pickers (Custom Items theme). */
abstract class ListScreenBase extends Screen implements MenuThemedScreen {

    protected int scroll;
    protected int panelX;
    protected int panelY;
    protected int panelW;
    protected int panelH;
    protected int rows;
    private final Screen back;

    protected ListScreenBase(Component title, Screen back) {
        super(title);
        this.back = back;
    }

    /** Number of entries the list scrolls through. */
    protected abstract int listSize();

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), Math.max(0, listSize() - rows)));
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(back);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, panelW, panelH};
    }

    @Override
    public String themeId() {
        return "custom_items";
    }
}
