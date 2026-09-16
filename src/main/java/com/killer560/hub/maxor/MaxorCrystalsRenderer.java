package com.killer560.hub.maxor;

import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;

/**
 * Optional highlight for the Energy Crystals that are really in the world during F7/M7 Phase 1.
 * <p>
 * NoammAddons' {@code MaxorsCrystals.kt} has NO highlight and NO crystal coordinates, so nothing here is drawn
 * from a hardcoded position: every box is the bounding box of a live {@code END_CRYSTAL} entity. Depth-tested
 * through the repo's shared {@link WorldRenderUtils} (the same renderer {@code f7spots/F7SpotsRenderer} and
 * {@code thorn}'s stun spots use) - informational marker, not an ESP, and the repo's through-walls pipelines are
 * private to their own packages.
 */
public final class MaxorCrystalsRenderer {

    private MaxorCrystalsRenderer() {
    }

    static void render(LevelRenderContext context) {
        MaxorConfig cfg = MaxorConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isHighlightEnabled() || client.level == null || client.player == null
                || !MaxorCrystalsFeature.inP1()) {
            return;
        }
        float[] c = WorldRenderUtils.argbToFloats(cfg.getHighlightColor());
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity.getType() != EntityType.END_CRYSTAL) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            WorldRenderUtils.renderOutlineBox(context, box, c[0], c[1], c[2], 1f, 2f);
            if (cfg.isHighlightFilled()) {
                WorldRenderUtils.renderFilledBox(context, box, c[0], c[1], c[2], 0.3f);
            }
        }
    }
}
