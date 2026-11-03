package com.killer560.hub.miningqol;

import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * The pre-2.0 mining features (killer560's to-do Doc, 2026-11-01, "MINING FEATURES PRE 2.0"): one registration for all
 * seven, one client tick that updates them in order (state first), one world-render pass. The post-2.0 mining work lives
 * on the staging-mining* branches and is not part of this.
 */
public final class MiningQolFeatures {

    private static volatile boolean resetRequested = false;

    private MiningQolFeatures() {
    }

    public static void register() {
        WorldRenderUtils.initThroughWalls();
        AbilityReadyAlert.register();
        PickobulusEstimate.register();
        AttackHoldProbe.register();
        HudElementRegistry.register(new CommissionHud.Element());
        HudElementRegistry.register(new AbilityReadyAlert.Popup());
        HudElementRegistry.register(new AbilityReadyAlert.Timer());
        // DISCONNECT runs on the Netty thread: only a flag here, the tick clears.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetRequested = true);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("MiningQolFeatures", client -> {
            if (resetRequested) {
                resetRequested = false;
                AbilityReadyAlert.reset();
                PickobulusEstimate.resetChat();
                CommissionProgress.reset();
            }
            MiningState.tick(client);
            MinesAreaMap.tick();
            CommissionProgress.tick(client);
            CommissionHud.tick(client);
            CommissionMobs.tick(client);
            AbilityReadyAlert.tick(client);
            PickobulusEstimate.tick(client);
            MiningHighlights.tick(client);
            RaffleLog.tick(client);
            MithrilStone.tick(client);
        }));
        // COLLECT_SUBMITS, while the frame still gathers what to draw (as the Etherwarp Overlay since 2026-10-13). From
        // AFTER_TRANSLUCENT_TERRAIN the boxes were drawn from a different frame's camera on some frames, so while he jumped or
        // sneaked the carpet fill sank under the carpet's top (killer560, 2026-11-03: "moving around still messes up the
        // carpet highlight"; testkit 1103-mining-carpet-stable: 11 of 24 frames on 26.3 before, see the report).
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            try {
                MiningHighlights.render(context);
                PickobulusEstimate.render(context);
                CommissionHud.render(context);
                CommissionMobs.render(context);
            } catch (RuntimeException e) {
                com.killer560.hub.util.ModLog.get("killer560smod-miningqol").error("[MiningQol] render failed", e);
            }
        });
    }
}
