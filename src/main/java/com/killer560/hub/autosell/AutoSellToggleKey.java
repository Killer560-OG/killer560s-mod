package com.killer560.hub.autosell;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

/**
 * The Auto Sell toggle key (killer560, 2026-10-18: "a keybind that i press to add or remove items"). While any container
 * screen is open, pressing the bound key over an item adds that item's identity to the sell list, or removes it if it is
 * already there. The press is swallowed only when it actually acted on an item, so the key still works as normal anywhere
 * else. Adding or removing from the list is not a click on the item, so nothing is sold by it.
 */
public final class AutoSellToggleKey {

    private static final String FEATURE = "AutoSell";

    private AutoSellToggleKey() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) {
                return;
            }
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> !handle(s, event.key()));
        });
    }

    /** @return true if the key acted on a hovered item and must not reach the screen. */
    static boolean handle(Screen screen, int key) {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return false;
        }
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        if (key == KeyUtil.NONE || cfg.getToggleKeyCode() != key || !(screen instanceof AbstractContainerScreen<?> container)) {
            return false;
        }
        var hovered = ((AbstractContainerScreenAccessor) container).killer560smod$getHoveredSlot();
        if (hovered == null) {
            return false;
        }
        ItemStack stack = hovered.getItem();
        if (stack.isEmpty()) {
            return false;
        }
        String identity = ItemIdentity.of(stack);
        if (identity == null) {
            return false;
        }
        if (cfg.getSellIdentities().stream().anyMatch(identity::equalsIgnoreCase)) {
            cfg.removeSellIdentity(identity);
            cfg.save();
            ModChat.send(FEATURE, ModChat.text("Removed "), ModChat.value(identity), ModChat.text(" from the sell list."));
        } else {
            cfg.addSellIdentity(identity);
            cfg.save();
            ModChat.send(FEATURE, ModChat.good("Added "), ModChat.value(identity), ModChat.text(" to the sell list."));
        }
        return true;
    }
}
