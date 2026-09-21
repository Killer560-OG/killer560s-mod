package com.killer560.hub.dungeonextras;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/** Registers Custom Mage Beam, Auto Dialogue and Breaker Aura. */
public final class DungeonExtrasFeature {

    private DungeonExtrasFeature() {
    }

    public static void register() {
        AutoDialogueFeature.register();
        // The shared automation gate observes on START_CLIENT_TICK, which always runs before every feature's
        // END_CLIENT_TICK handler no matter what order they registered in. It lives here (rather than in
        // Killer560ModClient) because this package already owns its two persisted settings.
        ClientTickEvents.START_CLIENT_TICK.register(com.killer560.hub.util.ActionGate::onClientTick);
        DungeonExtrasConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MageBeamFeature.onClientTick(client);
            AutoDialogueFeature.onClientTick(client);
            BreakerAuraFeature.onClientTick(client);
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(MageBeamFeature::onWorldRender);
    }
}
