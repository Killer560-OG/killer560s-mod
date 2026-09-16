package com.killer560.hub.thorn;

import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Stun-spot waypoints for the Thorn fight - the extension point for killer560's coordinates (none are built in; the
 * list lives in {@code killer560smod-thorn.json} under {@code "stunSpotList"}, see {@link ThornConfig}). Each spot whose
 * {@code floor} matches the current floor ("F4" / "M4" / "ANY") gets a depth-tested block box (legit - same as Secret
 * Waypoints / Waypoint Routes boxes) and, if Stun Spot Labels is on and the spot has a label, a billboard label drawn
 * see-through like Waypoint Routes' numbers.
 */
public final class StunSpotWaypoints {

    private StunSpotWaypoints() {
    }

    static void render(LevelRenderContext context) {
        ThornConfig cfg = ThornConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isStunSpotsEnabled() || client.level == null || client.player == null || !ThornFeature.inThornBoss()) {
            return;
        }
        List<ThornConfig.StunSpot> spots = cfg.getStunSpots();
        if (spots.isEmpty()) {
            return;
        }
        String floor = ThornFeature.thornFloor();
        float[] c = WorldRenderUtils.argbToFloats(cfg.getStunSpotColor());
        Camera camera = client.gameRenderer.getMainCamera();
        for (ThornConfig.StunSpot spot : spots) {
            if (!spot.appliesTo(floor)) {
                continue;
            }
            double bx = Math.floor(spot.x());
            double by = Math.floor(spot.y());
            double bz = Math.floor(spot.z());
            AABB box = new AABB(bx, by, bz, bx + 1, by + 1, bz + 1);
            WorldRenderUtils.renderFilledBox(context, box, c[0], c[1], c[2], 0.3f);
            WorldRenderUtils.renderOutlineBox(context, box, c[0], c[1], c[2], 1f, 2f);
            if (cfg.isStunSpotLabels() && spot.label() != null && !spot.label().isBlank()) {
                renderLabel(context, camera, bx + 0.5, by + 1.6, bz + 0.5, spot.label(), 0xFF000000 | cfg.getStunSpotColor());
            }
        }
    }

    /** Same billboard transform as {@code WaypointRoutesFeature.renderText} (26.1.2 nametag transform, SEE_THROUGH). */
    private static void renderLabel(LevelRenderContext context, Camera camera, double x, double y, double z,
                                    String text, int color) {
        var bufferSource = context.bufferSource();
        PoseStack poseStack = context.poseStack();
        if (bufferSource == null || poseStack == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Vec3 cam = camera.position();
        double dist = Math.sqrt(cam.distanceToSqr(x, y, z));
        float s = 0.025f * (float) Math.min(8.0, Math.max(1.0, dist / 12.0));
        poseStack.pushPose();
        try {
            poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
            poseStack.mulPose(camera.rotation());
            poseStack.scale(s, -s, s);
            font.drawInBatch(text, -font.width(text) / 2f, -font.lineHeight / 2f, color, false, poseStack.last().pose(),
                    bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
        } finally {
            poseStack.popPose();
        }
    }
}
