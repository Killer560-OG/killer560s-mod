package com.killer560.hub.posmsg;

import com.killer560.hub.util.SkyblockGate;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the ring on the ground for every configured Posmsg waypoint - killer560, 2026-09-16: "Remove
 * that hud on the top left it is unneeded for waypoints instead just draw a line around where the
 * message should be sent if i walk into it."
 * <p>
 * The ring is the real trigger boundary, not a decoration: its radius is exactly the distance
 * {@link PosmsgFeature} fires at, so "the circle lit up as I crossed it" and "the message went out"
 * are the same event. Exactly one ring, on the ground: the second ring that used to be drawn at eye
 * height is gone at killer560's request ("there is still this faint upper ring that shouldnt be
 * there", 2026-09-16). The message label sits on the waypoint by default and can be lifted per
 * waypoint via {@link PosmsgEntry#textHeightOffset} and resized via {@link PosmsgEntry#textScale}.
 * Depth-tested (no drawing through walls), same as Secret Waypoints and F7 Spots. Nothing at all is
 * drawn outside the F7/M7 boss fight - see {@link PosmsgFeature#active()}.
 */
public final class PosmsgRenderer {

    /** Segments per ring. 48 is smooth at the 1-10 block radii these waypoints actually use. */
    private static final int SEGMENTS = 48;
    /** Lifted off the floor so the ring doesn't z-fight with the block surface it sits on. */
    private static final double GROUND_OFFSET = 0.05;
    /** Past this the ring is drawn but the label isn't - a room full of labels is unreadable. */
    private static final double LABEL_DISTANCE = 40.0;

    private PosmsgRenderer() {
    }

    static void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (client.level == null || player == null || !SkyblockGate.allows() || !PosmsgFeature.active()) {
            return;
        }
        PosmsgConfig cfg = PosmsgConfig.getInstance();
        Camera camera = client.gameRenderer.getMainCamera();
        for (PosmsgEntry e : cfg.entries()) {
            if (!e.enabled || !e.configured || !e.showRadius) {
                continue;
            }
            float[] c = WorldRenderUtils.argbToFloats(e.color());
            double distance = player.position().distanceTo(new Vec3(e.x, e.y, e.z));
            // Solid once you're inside it, translucent from outside - the same "you are in it now"
            // signal the chat line gives you, a tick before the chat line arrives.
            boolean standingInside = distance <= e.radius;
            float alpha = standingInside ? 1f : 0.65f;
            // Per-waypoint line width, thickened slightly while you're inside so the crossing still reads.
            float thickness = (float) Math.max(0.5, e.thickness) * (standingInside ? 1.5f : 1f);
            WorldRenderUtils.renderLineStrip(context, ring(e, GROUND_OFFSET), c[0], c[1], c[2], alpha, thickness);
            if (distance <= LABEL_DISTANCE) {
                // On the waypoint unless the player lifted it (offset defaults to 0 - killer560's original
                // "Make the text not offset though from the waypoint"). SEE_THROUGH keeps it legible at
                // floor level.
                renderLabel(context, camera, e.x, e.y + e.textHeightOffset, e.z, e.sendText(), e.color(),
                        (float) e.textScale);
            }
        }
    }

    /** A closed horizontal circle at {@code entry.y + yOffset}, first point repeated to close the loop. */
    private static List<Vec3> ring(PosmsgEntry entry, double yOffset) {
        List<Vec3> points = new ArrayList<>(SEGMENTS + 1);
        double y = entry.y + yOffset;
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = (Math.PI * 2 * i) / SEGMENTS;
            points.add(new Vec3(entry.x + Math.cos(angle) * entry.radius, y,
                    entry.z + Math.sin(angle) * entry.radius));
        }
        return points;
    }

    /** Camera-facing text at a world position - same approach as {@code F7SpotsRenderer.renderLabel}. */
    private static void renderLabel(LevelRenderContext context, Camera camera, double x, double y, double z,
                                    String text, int color, float textScale) {
        var bufferSource = context.bufferSource();
        PoseStack poseStack = context.poseStack();
        if (bufferSource == null || poseStack == null || text == null || text.isBlank()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Vec3 cam = camera.position();
        double dist = Math.sqrt(cam.distanceToSqr(x, y, z));
        // Distance-compensated base size (same curve as F7 Spots) times the per-waypoint multiplier.
        float s = 0.025f * (float) Math.min(8.0, Math.max(1.0, dist / 12.0)) * Math.max(0.05f, textScale);
        poseStack.pushPose();
        try {
            poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
            poseStack.mulPose(camera.rotation());
            poseStack.scale(s, -s, s);
            font.drawInBatch(text, -font.width(text) / 2f, -font.lineHeight / 2f, color, false,
                    poseStack.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
        } finally {
            poseStack.popPose();
        }
    }
}
