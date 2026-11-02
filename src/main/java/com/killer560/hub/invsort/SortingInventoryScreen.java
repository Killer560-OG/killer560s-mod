package com.killer560.hub.invsort;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Player;

/**
 * The player's own inventory while the sorter works in it (killer560, 2026-11-01: "Make it so it blocks all clicks while
 * the thing is happening and you cannot override it as a setting"). Every mouse press, release, drag and scroll is
 * swallowed, and so is every key but Escape and the inventory key, which close it (and so stop the sort). There is no
 * setting for it.
 * <p>
 * The block is registered from {@link #init()}, so it is this screen's FIRST Fabric allow-listener: Fabric stops at the
 * first listener that says no, so no other feature's click handler (Slot Binds, Item Protect, Auto Sell's key...) sees
 * the click either, and the screen's own handlers below never pass it to the vanilla inventory.
 */
public class SortingInventoryScreen extends InventoryScreen {

    public SortingInventoryScreen(Player player) {
        super(player);
    }

    @Override
    protected void init() {
        super.init();
        ScreenMouseEvents.allowMouseClick(this).register((s, event) -> false);
        ScreenMouseEvents.allowMouseRelease(this).register((s, event) -> false);
        ScreenMouseEvents.allowMouseScroll(this).register((s, mx, my, sx, sy) -> false);
        ScreenKeyboardEvents.allowKeyPress(this).register((s, event) -> closes(event));
    }

    private boolean closes(KeyEvent event) {
        return event.key() == InputConstants.KEY_ESCAPE
                || (this.minecraft != null && this.minecraft.options.keyInventory.matches(event));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return closes(event) ? super.keyPressed(event) : true;
    }

    /** For the testkit: whether a click would reach the inventory (always false). */
    public static boolean blocksClicks() {
        return true;
    }
}
