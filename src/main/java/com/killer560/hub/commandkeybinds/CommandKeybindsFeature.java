package com.killer560.hub.commandkeybinds;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Real Skyblock menu-command keybinds, ported from Odin's own {@code CommandKeybinds.kt} - "various
 * keybinds for common skyblock commands" (pets/storage/armor/equipment/loadouts/stats/dungeon hub/
 * potion bag), so opening these real menus doesn't need typing the command out every time. Each bind
 * only ever sends the one exact real command it's set to, on the player's own key press - not
 * automation, just a shortcut for something you could already type yourself.
 */
public final class CommandKeybindsFeature {

    private static boolean petsWasDown;
    private static boolean storageWasDown;
    private static boolean armorWasDown;
    private static boolean equipmentWasDown;
    private static boolean loadoutsWasDown;
    private static boolean statsWasDown;
    private static boolean dungeonHubWasDown;
    private static boolean potionBagWasDown;

    private CommandKeybindsFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(CommandKeybindsFeature::tick);
    }

    private static void tick(Minecraft client) {
        CommandKeybindsConfig cfg = CommandKeybindsConfig.getInstance();
        if (!cfg.isEnabled() || client.screen != null || client.player == null) {
            petsWasDown = storageWasDown = armorWasDown = equipmentWasDown = false;
            loadoutsWasDown = statsWasDown = dungeonHubWasDown = potionBagWasDown = false;
            return;
        }

        petsWasDown = pollKey(client, cfg.getPetsKey(), petsWasDown, "pets");
        storageWasDown = pollKey(client, cfg.getStorageKey(), storageWasDown, "storage");
        armorWasDown = pollKey(client, cfg.getArmorKey(), armorWasDown, "armor");
        equipmentWasDown = pollKey(client, cfg.getEquipmentKey(), equipmentWasDown, "equipment");
        loadoutsWasDown = pollKey(client, cfg.getLoadoutsKey(), loadoutsWasDown, "loadout");
        statsWasDown = pollKey(client, cfg.getStatsKey(), statsWasDown, "stats");
        dungeonHubWasDown = pollKey(client, cfg.getDungeonHubKey(), dungeonHubWasDown, "warp dungeon_hub");
        potionBagWasDown = pollKey(client, cfg.getPotionBagKey(), potionBagWasDown, "potionbag");
    }

    private static boolean pollKey(Minecraft client, int keyCode, boolean wasDown, String command) {
        boolean isDown = keyCode >= 0 && com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), keyCode);
        if (isDown && !wasDown) {
            client.player.connection.sendCommand(command);
        }
        return isDown;
    }
}
