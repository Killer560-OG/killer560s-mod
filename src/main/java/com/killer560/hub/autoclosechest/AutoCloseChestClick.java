package com.killer560.hub.autoclosechest;

import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/** Click mode of Auto Close Chest (Nebulune / Devonian CloseChestOnKey, featE-1108), in every build: a dungeon secret
 *  chest stays open so the loot can be read, and the first key or mouse press closes it. The press is eaten, so it
 *  does not also take an item or act as a hotbar key. */
public final class AutoCloseChestClick {

    /** For the testkit: chests closed by a press. */
    private static volatile int closed;

    private AutoCloseChestClick() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen<?> container && wanted(container)) {
                ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> !close(container));
                ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !close(container));
            }
        });
    }

    private static boolean wanted(AbstractContainerScreen<?> screen) {
        AutoCloseChestConfig cfg = AutoCloseChestConfig.getInstance();
        return cfg.isEnabled() && !cfg.isAuto() && DungeonState.isInDungeon()
                && AutoCloseChestFeature.SECRET_CHEST_TITLES.contains(screen.getTitle().getString().trim())
                && AutoCloseChestFeature.SECRET_CHEST_MENU_TYPES.contains(screen.getMenu().getType());
    }

    /** Closes the chest the way Escape does. @return true (the press is consumed). */
    private static boolean close(AbstractContainerScreen<?> screen) {
        closed++;
        screen.onClose();
        return true;
    }

    public static int closedCount() {
        return closed;
    }
}
