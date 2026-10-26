package com.killer560.hub.blink;

import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Blink's Show Server Position. While a hold is on: a red box where the server last heard he was. While charging with
 * ticks banked and moving: a green box where a shot fired now would put him (killer560, 2026-10-25: "if i am running with
 * charge it should show where i will end up when I let go"), from {@link BlinkCharge#landingEstimate}. Draws only.
 */
final class BlinkRender {

    private BlinkRender() {
    }

    static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(BlinkRender::onWorldRender);
    }

    private static void onWorldRender(LevelRenderContext context) {
        try {
            if (!BlinkConfig.getInstance().isShowServerBox()) {
                BlinkFeature.boxSkip = "Show Server Position is off";
                return;
            }
            if (BlinkCharge.isActive()) {
                Vec3 land = BlinkCharge.landingEstimate();
                if (land != null) {
                    AABB box = new AABB(land.x - 0.3, land.y, land.z - 0.3, land.x + 0.3, land.y + 1.8, land.z + 0.3);
                    WorldRenderUtils.renderFilledBox(context, box, 0.2f, 1f, 0.3f, 0.18f);
                    WorldRenderUtils.renderOutlineBox(context, box, 0.25f, 1f, 0.35f, 0.9f, 2f);
                    BlinkFeature.landingFrames++;
                }
            }
            if (!BlinkFeature.isHolding()) {
                return;
            }
            Vec3 p = BlinkFeature.serverPosition();
            if (p == null) {
                BlinkFeature.boxSkip = "no server position known yet";
                return;
            }
            AABB box = new AABB(p.x - 0.3, p.y, p.z - 0.3, p.x + 0.3, p.y + 1.8, p.z + 0.3);
            WorldRenderUtils.renderFilledBox(context, box, 1f, 0.2f, 0.2f, 0.18f);
            WorldRenderUtils.renderOutlineBox(context, box, 1f, 0.25f, 0.25f, 0.9f, 2f);
            BlinkFeature.boxFrames++;
            BlinkFeature.boxSkip = "drawn";
        } catch (RuntimeException e) {
            // A drawing problem never matters to the hold; say what it was once per message.
            String why = "threw " + e;
            if (!why.equals(BlinkFeature.boxSkip)) {
                com.killer560.hub.util.ModLog.get("killer560smod-blink").error("[Blink] server box draw threw", e);
            }
            BlinkFeature.boxSkip = why;
        }
    }
}
