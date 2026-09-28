package com.killer560.hub.roomsim;

import com.killer560.hub.util.WorldRenderUtils;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * Marks the chests that could be the mimic, differently from the ones that cannot.
 *
 * <p>killer560 (2026-09-28): "make it customly on that sim show any chest that can be a mimic. Not all chests
 * can but if they can label them differently than regular chests."
 *
 * <p>This is a thing the real game deliberately does not tell you, and that is exactly why it belongs in a
 * practice tool: on Hypixel you find the mimic by opening chests until one bites, so the only way to practise
 * the ROUTE that covers every candidate is to be able to see which chests were ever candidates.
 *
 * <p>Drawn as an outline rather than a filled box so a candidate chest in a doorway does not hide what is
 * behind it, and only within a short radius - a full map's worth of outlines every frame is a framerate
 * problem, and anything he cannot walk to in a few seconds is not the chest he is deciding about.
 */
public final class SimMimicRenderer {

    /** Amber, the mod's own accent, so it reads as "this mod is telling you something". */
    private static final float R = 0.80f;
    private static final float G = 0.40f;
    private static final float B = 0.00f;
    private static final float A = 0.85f;

    /** Blocks around the player to draw. Beyond this it is clutter rather than information. */
    private static final double DRAW_RADIUS = 40.0;

    private SimMimicRenderer() {
    }

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(SimMimicRenderer::onWorldRender);
    }

    private static void onWorldRender(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        // Gated on the sim, not merely on the world: these outlines say something that is only true here, and
        // drawing them anywhere else would be inventing information about a real dungeon.
        if (!SimState.canAct(client) || !SimMimic.hasMimic()) {
            return;
        }
        BlockPos around = client.player.blockPosition();
        for (BlockPos pos : SimMimic.candidatesNear(around, DRAW_RADIUS)) {
            AABB box = new AABB(pos).inflate(0.004);
            WorldRenderUtils.renderOutlineBox(context, box, R, G, B, A, 2.0f);
        }
    }
}
