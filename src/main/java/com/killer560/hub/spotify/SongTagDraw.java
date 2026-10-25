package com.killer560.hub.spotify;

import com.killer560.hub.compat.McRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Song Tag's two render steps, kept out of the mixin package (CLAUDE.md). The line is drawn the way vanilla stacks a
 * nametag over Hypixel's below-name score ({@code EntityRenderer.submitNameDisplay}, javap 26.1.2 and 26.2): the same
 * attachment, light and see-through rule as the name, lifted by one text line ({@code 9 * 1.15 * 0.025} blocks) per line
 * already drawn there, so it sits directly above the name and follows Others' Size with it.
 */
public final class SongTagDraw {

    /** Vanilla's line step between {@code scoreText} and {@code nameTag}. */
    private static final float LINE = 9.0f * 1.15f * 0.025f;
    /** Only players this close carry a song line (16 blocks), so a crowded hub is not a wall of text. */
    private static final double MAX_DISTANCE_SQ = 16.0 * 16.0;

    // Test hooks (read by the testkit).
    private static volatile long submitted;

    private SongTagDraw() {
    }

    /** The song line for this player this frame, or null. {@code distanceSq} is the state's distance to the camera. */
    public static Component tagFor(Entity entity, double distanceSq) {
        Minecraft mc = Minecraft.getInstance();
        if (entity == mc.player) {
            return SongShare.ownTag();
        }
        if (distanceSq > MAX_DISTANCE_SQ) {
            return null;
        }
        UUID id = entity.getUUID();
        return RemoteSongs.tagFor(entity.getName().getString(), id);
    }

    public static void submit(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                              CameraRenderState camera, int yOffset) {
        if (!((Object) state instanceof SongTagState tagged)) {
            return;
        }
        Component song = tagged.killer560smod$songTag();
        if (song == null|| state.nameTag == null || state.nameTagAttachment == null) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0.0f, LINE * (state.scoreText != null ? 2 : 1), 0.0f);
        McRender.submitNameTag(collector, poseStack, state.nameTagAttachment, yOffset, song, !state.isDiscrete,
                state.lightCoords, state.distanceToCameraSq, camera);
        poseStack.popPose();
        submitted++;
    }

    /** Song lines submitted since start. */
    public static long submitted() {
        return submitted;
    }
}
