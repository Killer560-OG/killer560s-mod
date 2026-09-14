package com.killer560.hub.doorkeys;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Real Hypixel dungeon Wither/Blood Key ESP, ported from noamm's own {@code DoorKeys.kt}. Real dropped
 * door keys are real {@code ArmorStand}s with a fixed real display name ("Wither Key"/"Blood Key") -
 * this polls real rendered entities each tick (the same real technique Weirdos/Blaze/Livid Solver
 * already use tonight) rather than porting noamm's own entity-metadata-packet hook, since polling needs
 * no new mixin at all for a check this cheap. Highlights the real key with a box and an optional tracer
 * line from your eyes to it - never picks anything up.
 */
public final class DoorKeysFeature {

    private static Entity currentKey = null;
    private static float[] currentColor = null;

    private DoorKeysFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(DoorKeysFeature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(DoorKeysFeature::onWorldRender);
    }

    private static void tick(Minecraft client) {
        DoorKeysConfig cfg = DoorKeysConfig.getInstance();
        if (!cfg.isEnabled() || !DungeonState.isInDungeon() || DungeonState.isBossPhaseActive()
                || client.level == null) {
            currentKey = null;
            return;
        }
        if (currentKey != null && !currentKey.isRemoved()) {
            return;
        }
        currentKey = null;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand)) {
                continue;
            }
            String name = ChatFormatting.stripFormatting(entity.getName().getString());
            if (name == null) {
                continue;
            }
            if ("Wither Key".equals(name) && cfg.isHighlightWither()) {
                currentKey = entity;
                currentColor = new float[]{0.1f, 0.1f, 0.1f};
                break;
            }
            if ("Blood Key".equals(name) && cfg.isHighlightBlood()) {
                currentKey = entity;
                currentColor = new float[]{0.8f, 0.05f, 0.05f};
                break;
            }
        }
    }

    private static void onWorldRender(LevelRenderContext context) {
        DoorKeysConfig cfg = DoorKeysConfig.getInstance();
        if (!cfg.isEnabled() || currentKey == null || currentKey.isRemoved()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        AABB box = currentKey.getBoundingBox().inflate(0.3, 0.3, 0.3);
        WorldRenderUtils.renderOutlineBox(context, box, currentColor[0], currentColor[1], currentColor[2], 1f, 2f);

        if (cfg.isShowTracer()) {
            Vec3 eyes = client.player.getEyePosition();
            Vec3 target = currentKey.position().add(0, 1.0, 0);
            WorldRenderUtils.renderLineStrip(context, List.of(eyes, target),
                    currentColor[0], currentColor[1], currentColor[2], 1f, 2f);
        }
    }
}
