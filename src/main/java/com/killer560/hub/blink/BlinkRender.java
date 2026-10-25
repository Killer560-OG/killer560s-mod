package com.killer560.hub.blink;

import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** While a hold is on, a red box where the server last heard he was (Blink's Show Server Position). Draws only. */
final class BlinkRender {

    private BlinkRender() {
    }

    static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(BlinkRender::onWorldRender);
    }

    private static void onWorldRender(LevelRenderContext context) {
        try {
            if (!BlinkFeature.isHolding() || !BlinkConfig.getInstance().isShowServerBox()) {
                return;
            }
            Vec3 p = BlinkFeature.serverPosition();
            if (p == null) {
                return;
            }
            AABB box = new AABB(p.x - 0.3, p.y, p.z - 0.3, p.x + 0.3, p.y + 1.8, p.z + 0.3);
            WorldRenderUtils.renderFilledBox(context, box, 1f, 0.2f, 0.2f, 0.18f);
            WorldRenderUtils.renderOutlineBox(context, box, 1f, 0.25f, 0.25f, 0.9f, 2f);
        } catch (RuntimeException ignored) {
            // A drawing problem never matters to the hold.
        }
    }
}
