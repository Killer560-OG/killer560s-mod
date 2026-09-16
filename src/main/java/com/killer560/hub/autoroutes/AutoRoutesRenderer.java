package com.killer560.hub.autoroutes;

import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Node markers for the current room's route (QUOI Box / Filled box / Cylinder styles, per-type or uniform colours,
 * thickness + height sliders, the active node in its own colour), the chain line between consecutive nodes, and in
 * edit mode: node indices, the recorded path as a faint line, and a faint highlight on every block any dungeon
 * breaker node will break (air = red outline, present = white fill - QUOI's DB editor). Nothing is drawn while the
 * Interactive Map is open (the feature decides that; see {@link AutoRoutesFeature#isRenderHidden}).
 * <p>
 * Everything is depth-tested against the world like Secret Waypoints, and drawn through {@link WorldRenderUtils}
 * only. Any exception is caught by the feature's render hook, which disables the feature instead of taking the
 * frame down.
 */
public final class AutoRoutesRenderer {

    private static final int RING_SEGMENTS = 40;
    private static final double GROUND_OFFSET = 0.03;
    private static final double LABEL_DISTANCE = 40.0;
    private static final float BREAKER_ALPHA = 0.28f;
    private static final float PATH_ALPHA = 0.35f;

    private AutoRoutesRenderer() {
    }

    static void render(LevelRenderContext ctx, Route route, RouteCoords.Frame frame, boolean editMode,
                       RouteNode activeNode) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || route == null || frame == null) {
            return;
        }
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        float thickness = cfg.getThickness();
        double height = cfg.getHeight();
        Camera camera = client.gameRenderer.getMainCamera();
        Vec3 playerPos = client.player.position();

        List<Vec3> chain = new ArrayList<>();
        List<RouteNode> ordered = route.nodesInPathOrder();
        for (RouteNode node : ordered) {
            Vec3 real = RouteCoords.toReal(frame, node.relativePos());
            chain.add(real.add(0, height / 2.0, 0));
            int argb = node == activeNode ? cfg.getActiveColorArgb() : cfg.colorFor(node);
            float[] c = WorldRenderUtils.argbToFloats(argb);
            float alpha = c.length > 3 ? c[3] : 1f;
            if (alpha <= 0.01f) {
                alpha = 1f;
            }
            AABB box = node.boundingBox(real, height);
            switch (cfg.getRenderStyle()) {
                case FILLED -> {
                    WorldRenderUtils.renderFilledBox(ctx, box, c[0], c[1], c[2], alpha * 0.35f);
                    WorldRenderUtils.renderOutlineBox(ctx, box, c[0], c[1], c[2], alpha, thickness);
                }
                case CYLINDER -> {
                    double r = Math.max(0.1, node.radius) / 2.0;
                    WorldRenderUtils.renderLineStrip(ctx, ring(real, r, GROUND_OFFSET), c[0], c[1], c[2], alpha, thickness);
                    if (height > 0.15) {
                        WorldRenderUtils.renderLineStrip(ctx, ring(real, r, height), c[0], c[1], c[2], alpha * 0.6f,
                                Math.max(0.5f, thickness * 0.6f));
                    }
                }
                default -> WorldRenderUtils.renderOutlineBox(ctx, box, c[0], c[1], c[2], alpha, thickness);
            }
            if (editMode && playerPos.distanceTo(real) <= LABEL_DISTANCE) {
                // 1-based, same as /ar list and /ar delete - the number on the label has to be the number
                // you can type (2026-09-16 review).
                int index = route.indexOf(node) + 1;
                renderLabel(ctx, camera, real.x, real.y + height + 0.35, real.z,
                        "#" + index + " " + node.type.label(), argb | 0xFF000000);
            }
        }
        if (chain.size() >= 2) {
            float[] a = WorldRenderUtils.argbToFloats(cfg.getActiveColorArgb());
            WorldRenderUtils.renderLineStrip(ctx, chain, a[0], a[1], a[2], 0.4f, Math.max(0.5f, thickness / 2f));
        }

        if (editMode) {
            renderPath(ctx, route, frame, thickness);
            renderBreakerBlocks(ctx, client, route, frame);
        }
    }

    /** The recorded movement as a faint polyline - where playback will actually walk. */
    private static void renderPath(LevelRenderContext ctx, Route route, RouteCoords.Frame frame, float thickness) {
        RoutePath path = route.path();
        if (path.size() < 2) {
            return;
        }
        List<Vec3> points = new ArrayList<>();
        int stride = Math.max(1, path.size() / 400); // a 10-minute path still draws as ~400 segments
        for (int i = 0; i < path.size(); i += stride) {
            RoutePath.Sample s = path.get(i);
            Vec3 real = RouteCoords.toReal(frame, s.x(), s.y(), s.z());
            points.add(real.add(0, 0.05, 0));
        }
        RoutePath.Sample last = path.last();
        if (last != null) {
            points.add(RouteCoords.toReal(frame, last.x(), last.y(), last.z()).add(0, 0.05, 0));
        }
        WorldRenderUtils.renderLineStrip(ctx, points, 1f, 0.65f, 0.2f, PATH_ALPHA, Math.max(0.5f, thickness / 2f));
    }

    /** killer560: "blocks that will be broken by any node get a faint highlight while in edit mode". */
    private static void renderBreakerBlocks(LevelRenderContext ctx, Minecraft client, Route route, RouteCoords.Frame frame) {
        for (RouteNode node : route.nodes()) {
            if (node.type != RouteNode.Type.DUNGEON_BREAKER) {
                continue;
            }
            for (BlockPos rel : node.breakerBlocks) {
                BlockPos real = RouteCoords.toRealBlock(frame, rel);
                AABB box = new AABB(real.getX(), real.getY(), real.getZ(), real.getX() + 1, real.getY() + 1, real.getZ() + 1);
                if (client.level.getBlockState(real).isAir()) {
                    WorldRenderUtils.renderOutlineBox(ctx, box, 1f, 0.2f, 0.2f, 0.5f, 1.5f);
                } else {
                    WorldRenderUtils.renderFilledBox(ctx, box, 1f, 1f, 1f, BREAKER_ALPHA);
                    WorldRenderUtils.renderOutlineBox(ctx, box, 1f, 1f, 1f, 0.6f, 1.5f);
                }
            }
        }
    }

    private static List<Vec3> ring(Vec3 centre, double radius, double yOffset) {
        List<Vec3> points = new ArrayList<>(RING_SEGMENTS + 1);
        double y = centre.y + yOffset;
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double angle = (Math.PI * 2 * i) / RING_SEGMENTS;
            points.add(new Vec3(centre.x + Math.cos(angle) * radius, y, centre.z + Math.sin(angle) * radius));
        }
        return points;
    }

    /** Camera-facing text at a world position - {@code posmsg/PosmsgRenderer.renderLabel}. */
    private static void renderLabel(LevelRenderContext ctx, Camera camera, double x, double y, double z, String text,
                                    int color) {
        var bufferSource = ctx.bufferSource();
        PoseStack poseStack = ctx.poseStack();
        if (bufferSource == null || poseStack == null || text == null || text.isBlank()) {
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
            font.drawInBatch(text, -font.width(text) / 2f, -font.lineHeight / 2f, color, false,
                    poseStack.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
        } finally {
            poseStack.popPose();
        }
    }
}
