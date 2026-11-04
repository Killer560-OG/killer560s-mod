package com.killer560.hub.mining.ghosts;

import com.killer560.hub.mining.MiningIsland;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Ghost ESP (CHEAT BUILD ONLY, not automation): outlines the Dwarven Mines' Ghosts - the invisible creepers of The
 * Mist - through walls (OdinClient's Ghosts reveal). Draws boxes only; never attacks, clicks or moves. Cheat-only
 * because it shows mobs the game hides.
 */
public final class GhostEspFeature {

    private GhostEspFeature() {
    }

    public static void register() {
        WorldRenderUtils.initThroughWalls();
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FeatureGuard.afterFeatures(
                FeatureBits.register("Ghost ESP render", () -> GhostEspConfig.getInstance().isEnabled()),
                GhostEspFeature::onWorldRender));
    }

    private static void onWorldRender(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !MiningIsland.dwarvenMines() || !MiningIsland.inArea("Mist")) {
            return;
        }
        List<AABB> boxes = new ArrayList<>();
        for (Entity e : client.level.entitiesForRendering()) {
            if (e instanceof Creeper && e.isInvisible() && e.isAlive()) {
                boxes.add(e.getBoundingBox());
                if (boxes.size() >= 64) {
                    break;
                }
            }
        }
        if (boxes.isEmpty()) {
            return;
        }
        GhostEspConfig cfg = GhostEspConfig.getInstance();
        float[] c = WorldRenderUtils.argbToFloats(cfg.getColor());
        int n = boxes.size();
        float[] rgba = new float[n * 4];
        for (int i = 0; i < n; i++) {
            rgba[i * 4] = c[0];
            rgba[i * 4 + 1] = c[1];
            rgba[i * 4 + 2] = c[2];
            rgba[i * 4 + 3] = 1f;
        }
        WorldRenderUtils.renderOutlineBoxes(context, boxes.toArray(new AABB[0]), rgba, n, 2f, cfg.isThroughWalls());
    }
}
