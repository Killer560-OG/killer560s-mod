package com.killer560.hub.f7spots;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import com.killer560.hub.witherdragons.P5State;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * All of F7 Spots' world rendering: walk-to waypoint boxes + beams, aim-spot crosshairs, and Storm's crush pads.
 * Everything is depth-tested (no through-walls drawing), same as Secret Waypoints / Waypoint Routes / Thorn's
 * stun spots - these are informational markers, not an ESP.
 */
public final class F7SpotsRenderer {

    private F7SpotsRenderer() {
    }

    static void render(LevelRenderContext context) {
        F7SpotsConfig cfg = F7SpotsConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !F7SpotsFeature.inF7Boss()) {
            return;
        }
        Camera camera = client.gameRenderer.getMainCamera();
        if (cfg.isWalkWaypointsEnabled()) {
            renderWalkWaypoints(context, cfg, client.player, camera);
        }
        if (cfg.isAimSpotsEnabled()) {
            renderAimSpots(context, cfg, client.player, camera);
        }
        if (cfg.isCrushPadHighlightEnabled()) {
            CrushTimer.renderPads(context, cfg);
        }
    }

    private static void renderWalkWaypoints(LevelRenderContext context, F7SpotsConfig cfg, Player player, Camera camera) {
        List<WalkWaypoint> waypoints = cfg.getWalkWaypoints();
        if (waypoints.isEmpty()) {
            return;
        }
        String floor = DungeonState.getFloor();
        Floor7Tracker.Phase phase = currentPhase();
        float beam = cfg.getWalkBeamHeight();
        for (WalkWaypoint w : waypoints) {
            if (!w.appliesTo(floor, phase, cfg.isWalkCurrentPhaseOnly())) {
                continue;
            }
            int argb = w.color() == 0 ? cfg.getWalkColor() : w.color();
            float[] c = WorldRenderUtils.argbToFloats(argb);
            double bx = Math.floor(w.x());
            double by = Math.floor(w.y());
            double bz = Math.floor(w.z());
            AABB box = new AABB(bx, by, bz, bx + 1, by + 1, bz + 1);
            WorldRenderUtils.renderFilledBox(context, box, c[0], c[1], c[2], 0.3f);
            WorldRenderUtils.renderOutlineBox(context, box, c[0], c[1], c[2], 1f, 2f);
            if (beam > 0f) {
                // A "beacon beam" without a new render type: a thin translucent column above the box.
                AABB column = new AABB(bx + 0.35, by + 1, bz + 0.35, bx + 0.65, by + 1 + beam, bz + 0.65);
                WorldRenderUtils.renderFilledBox(context, column, c[0], c[1], c[2], 0.25f);
            }
            String text = labelText(cfg.isWalkLabels() ? w.label() : null, cfg.isWalkDistance(),
                    player.position().distanceTo(new Vec3(bx + 0.5, by + 0.5, bz + 0.5)));
            if (text != null) {
                renderLabel(context, camera, bx + 0.5, by + 1.6, bz + 0.5, text, 0xFF000000 | argb);
            }
        }
    }

    private static void renderAimSpots(LevelRenderContext context, F7SpotsConfig cfg, Player player, Camera camera) {
        List<AimSpot> spots = cfg.getAimSpots();
        List<AimSpot> arrowStack = cfg.isAimArrowStack() ? AimSpots.ARROW_STACK : List.<AimSpot>of();
        List<AimSpot> devonianLb = cfg.isAimDevonianLb() ? AimSpots.DEVONIAN_LB : List.<AimSpot>of();
        if (spots.isEmpty() && arrowStack.isEmpty() && devonianLb.isEmpty()) {
            return;
        }
        DungeonClass self = P5State.selfClass();
        Floor7Tracker.Phase phase = currentPhase();
        for (AimSpot spot : arrowStack) {
            drawAimSpot(context, cfg, player, camera, spot, self, phase);
        }
        for (AimSpot spot : devonianLb) {
            drawAimSpot(context, cfg, player, camera, spot, self, phase);
        }
        for (AimSpot spot : spots) {
            drawAimSpot(context, cfg, player, camera, spot, self, phase);
        }
    }

    private static void drawAimSpot(LevelRenderContext context, F7SpotsConfig cfg, Player player, Camera camera,
                                    AimSpot spot, DungeonClass self, Floor7Tracker.Phase phase) {
        AimSituation situation = spot.situation() == null ? AimSituation.ANY : spot.situation();
        if (!cfg.isAimAllSituations() && !situation.activeIn(phase)) {
            return;
        }
        if (!spot.appliesTo(self, cfg.isAimAllClasses())) {
            return;
        }
        int argb = spot.color() == 0 ? cfg.getAimColor() : spot.color();
        float[] c = WorldRenderUtils.argbToFloats(argb);
        double half = cfg.getAimSize() / 2.0;
        double arm = cfg.getAimSize() * 1.5;
        AABB box = new AABB(spot.x() - half, spot.y() - half, spot.z() - half,
                spot.x() + half, spot.y() + half, spot.z() + half);
        WorldRenderUtils.renderFilledBox(context, box, c[0], c[1], c[2], 0.25f);
        WorldRenderUtils.renderOutlineBox(context, box, c[0], c[1], c[2], 1f, 2f);
        // 3D crosshair through the point so it reads as "aim at exactly here", not "stand in this box".
        WorldRenderUtils.renderLineStrip(context, List.of(
                new Vec3(spot.x() - arm, spot.y(), spot.z()), new Vec3(spot.x() + arm, spot.y(), spot.z())),
                c[0], c[1], c[2], 1f, 2f);
        WorldRenderUtils.renderLineStrip(context, List.of(
                new Vec3(spot.x(), spot.y() - arm, spot.z()), new Vec3(spot.x(), spot.y() + arm, spot.z())),
                c[0], c[1], c[2], 1f, 2f);
        WorldRenderUtils.renderLineStrip(context, List.of(
                new Vec3(spot.x(), spot.y(), spot.z() - arm), new Vec3(spot.x(), spot.y(), spot.z() + arm)),
                c[0], c[1], c[2], 1f, 2f);
        String label = spot.label();
        if (cfg.isAimLabels() && (label == null || label.isBlank())) {
            label = situation.label;
        }
        String text = labelText(cfg.isAimLabels() ? label : null, cfg.isAimDistance(),
                player.position().distanceTo(new Vec3(spot.x(), spot.y(), spot.z())));
        if (text != null) {
            renderLabel(context, camera, spot.x(), spot.y() + half + 0.6, spot.z(), text, 0xFF000000 | argb);
        }
    }

    /** Chat-driven phase first ({@link Floor7Tracker#getPhase()}), falling back to the y-level phase. */
    static Floor7Tracker.Phase currentPhase() {
        Floor7Tracker.Phase phase = Floor7Tracker.getPhase();
        return phase == Floor7Tracker.Phase.UNKNOWN ? Floor7Tracker.getPhaseAt() : phase;
    }

    private static String labelText(String label, boolean distance, double dist) {
        boolean hasLabel = label != null && !label.isBlank();
        if (!hasLabel && !distance) {
            return null;
        }
        if (!distance) {
            return label;
        }
        String d = String.format(Locale.US, "%.0fm", dist);
        return hasLabel ? label + " §7(" + d + ")" : d;
    }

    /** Same billboard transform as Thorn's stun spots / Waypoint Routes' numbers (26.1.2 nametag transform). */
    static void renderLabel(LevelRenderContext context, Camera camera, double x, double y, double z,
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
