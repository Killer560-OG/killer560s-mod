package com.killer560.hub.dungeonextras;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/** Registers Custom Mage Beam, Auto Dialogue and Breaker Aura. */
public final class DungeonExtrasFeature {

    private DungeonExtrasFeature() {
    }

    public static void register() {
        AutoDialogueFeature.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MageBeamFeature.onClientTick(client);
            AutoDialogueFeature.onClientTick(client);
            BreakerAuraFeature.onClientTick(client);
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(MageBeamFeature::onWorldRender);
    }
}
