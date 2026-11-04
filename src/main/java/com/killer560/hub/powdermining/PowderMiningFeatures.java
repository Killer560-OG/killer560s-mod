package com.killer560.hub.powdermining;

import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * The Crystal Hollows powder grinding features (killer560, 2026-11-03: "no macros or things i am just going to grind
 * chests"): Treasure Chest Highlight and Alert, Lockpick Helper (cheat), Powder Tracker, 2x Powder Alert and Chest Loot
 * Compact. One registration, one tick, one world-render pass, beside {@code miningqol.MiningQolFeatures} (which runs
 * {@code MiningState.tick} and the through-walls init these use).
 */
public final class PowderMiningFeatures {

    private PowderMiningFeatures() {
    }

    public static void register() {
        PowderMiningConfig.getInstance();
        com.killer560.hub.util.PerTickServerClock.register();
        PowderChat.register();
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() && player == net.minecraft.client.Minecraft.getInstance().player && hitResult != null) {
                TreasureChests.onUseBlock(hitResult.getBlockPos());
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        HudElementRegistry.register(new TreasureChests.Timer());
        HudElementRegistry.register(new PowderTracker.Hud());
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("PowderMiningFeatures", client -> {
            PowderChat.tick(client);
            TreasureChests.tick(client);
            LockpickHelper.tick();
        }));
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            try {
                TreasureChests.render(context);
                LockpickHelper.render(context);
            } catch (RuntimeException e) {
                com.killer560.hub.util.ModLog.get("killer560smod-powdermining").error("[PowderMining] render failed", e);
            }
        });
    }
}
