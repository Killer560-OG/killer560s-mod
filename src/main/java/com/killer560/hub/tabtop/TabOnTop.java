package com.killer560.hub.tabtop;

import com.killer560.hub.abilitykeybinds.AbilityKeybindsFeature;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * The player list (Tab) draws above every mod HUD layer and overlay (killer560, 2026-11-06: "tab should always go over any
 * other thing on screen"). Vanilla's layer is swapped for a no-op and run again as the LAST Fabric layer, registered at
 * CLIENT_STARTED so it lands after every other entrypoint's addLast (the Dark Mode precedent). The same layer lets
 * {@link AbilityKeybindsFeature#hidesPlayerList} keep the list down for a tap that casts an ability on the same key.
 */
public final class TabOnTop {

    private static HudElement vanilla;

    private TabOnTop() {
    }

    public static void register() {
        HudElementRegistry.replaceElement(VanillaHudElements.PLAYER_LIST, orig -> {
            vanilla = orig;
            return (graphics, deltaTracker) -> { };
        });
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "player_list_on_top"), (graphics, deltaTracker) -> {
                    if (vanilla != null && !AbilityKeybindsFeature.hidesPlayerList(Minecraft.getInstance())) {
                        vanilla.extractRenderState(graphics, deltaTracker);
                    }
                }));
    }
}
