package com.killer560.hub.loadoutkeybinds;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real "Loadout" GUI keybind navigation, ported from Odin's own {@code LoadoutKeybinds.kt}. The real
 * Skyblock Loadout screen (accessed via the wardrobe/equipment menu) shows a real "(N/M) Loadout" title
 * and up to 12 real loadout slots per page at fixed real slot indices; this lets number-row keys (and
 * left/right arrows for paging) click them directly instead of needing the mouse, using the exact real
 * container click ({@code MultiPlayerGameMode#handleContainerInput} with real
 * {@code ContainerInput.PICKUP}) - the same real technique {@code AutoLeapFeature}/{@code SlotBinds}
 * already use. Built on the same real Fabric Screen API this session already verified via {@code javap}.
 */
public final class LoadoutKeybindsFeature {

    private static final Pattern LOADOUT_TITLE = Pattern.compile("\\((\\d)/(\\d)\\) Loadout");
    private static final int[] LOADOUT_SLOTS = {
            14, 15, 16,
            23, 24, 25,
            32, 33, 34,
            41, 42, 43
    };

    private LoadoutKeybindsFeature() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(LoadoutKeybindsFeature::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!LoadoutKeybindsConfig.getInstance().isEnabled()
                || !(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
            Matcher matcher = LOADOUT_TITLE.matcher(containerScreen.getTitle().getString());
            if (!matcher.find()) {
                return true;
            }
            int current = Integer.parseInt(matcher.group(1));
            int total = Integer.parseInt(matcher.group(2));
            LoadoutKeybindsConfig cfg = LoadoutKeybindsConfig.getInstance();

            int slot;
            if (event.key() == cfg.getNextPageKey()) {
                if (current >= total) {
                    return true;
                }
                slot = 44;
            } else if (event.key() == cfg.getPreviousPageKey()) {
                if (current <= 1) {
                    return true;
                }
                slot = 17;
            } else {
                int keyIndex = -1;
                for (int i = 0; i < LOADOUT_SLOTS.length; i++) {
                    if (cfg.getSlotKey(i) == event.key()) {
                        keyIndex = i;
                        break;
                    }
                }
                if (keyIndex == -1) {
                    return true;
                }
                slot = LOADOUT_SLOTS[keyIndex];
            }

            client.gameMode.handleContainerInput(containerScreen.getMenu().containerId, slot, 0,
                    ContainerInput.PICKUP, client.player);
            return false;
        });
    }
}
