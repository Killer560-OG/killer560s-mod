package com.killer560.hub.armourdye;

import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Wiring for Custom Items (see {@link ArmourDye} for what the feature does and what it borrows from Skyblocker):
 * <ol>
 *   <li>{@code /customitems} opens {@link CustomItemsScreen}, where items are picked and their look is set. The
 *       capture key and the "Add Helmet/Chest/Legs/Boots" buttons it replaces were removed at killer560's request
 *       (2026-10-08).</li>
 *   <li><b>Trim cache invalidation</b> - trim materials and patterns are datapack registries that only exist once a
 *       world is loaded, so the cache is dropped whenever the level changes.</li>
 * </ol>
 * Nothing here talks to the server.
 */
public final class ArmourDyeFeature {

    public static final String FEATURE = "Custom Items";

    private static Object lastLevel = null;

    private ArmourDyeFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("ArmourDyeFeature.onTick", ArmourDyeFeature::onTick));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("customitems").executes(context -> {
                    openMenu();
                    return 1;
                })));
    }

    /** Opens the Custom Items menu on the next task - the chat screen that ran the command closes itself first. */
    public static void openMenu() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        client.execute(() -> client.setScreenAndShow(new CustomItemsScreen(null)));
    }

    private static void onTick(Minecraft client) {
        Object level = client == null ? null : client.level;
        if (level != lastLevel) {
            lastLevel = level;
            // A new world means a new datapack registry set; the next lookup rebuilds from it.
            ArmourTrims.invalidate();
        }
    }
}
