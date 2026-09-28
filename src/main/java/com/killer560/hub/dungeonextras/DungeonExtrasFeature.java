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
        // Migrates his old breakerAuraSelected picks into config/killer560smod-breakeraura/default.json (if that
        // folder has no file yet) before anything ticks, and loads whichever config is active.
        BreakerAuraStore.getInstance();
        BreakerAuraCommands.register();
        // BREAKER AURA TICKS AT THE *START* OF THE TICK, and that is not a detail.
        //
        // A vanilla client decides what to dig before it reports where it is: the dig packet goes out earlier
        // in Minecraft#tick than the player's own movement packet. Fabric's END_CLIENT_TICK runs after that
        // movement packet, so automation hung there sends its break in an order no vanilla client produces.
        //
        // Measured 2026-09-27 against a live GrimAC on a dedicated server, driving the aura through a wall it
        // one-shots: on END_CLIENT_TICK, 808 "Post - player digging" violations, one per break, at the shipped
        // default of one block per cycle. Identical run on START_CLIENT_TICK: zero. Same wall, same rate, same
        // number of blocks broken. A by-hand control holding the attack key in the same arena was clean both
        // times, so the ordering was the whole difference.
        //
        // Priority is unaffected: ActionGate resolves by what asked on the PREVIOUS tick precisely so it does
        // not depend on which feature's handler happens to run first, and it observes on START_CLIENT_TICK
        // itself, registered above this line and therefore before it.
        ClientTickEvents.START_CLIENT_TICK.register(BreakerAuraFeature::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MageBeamFeature.onClientTick(client);
            AutoDialogueFeature.onClientTick(client);
            ManualBreakMonitor.onClientTick(client);
            ForeignBreakerProbe.onClientTick(client);
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(MageBeamFeature::onWorldRender);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(BreakerAuraFeature::onWorldRender);
    }
}
