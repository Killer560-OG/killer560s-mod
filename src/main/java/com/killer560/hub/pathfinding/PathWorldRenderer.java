package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws the route in the world, SkyHanni-style ({@code data/navigation/PathRenderer.kt}): the path is subdivided with
 * a Catmull-Rom spline so it reads as a smooth line instead of a zig-zag between nodes, only the next stretch is drawn
 * brightly (the rest optionally as a dim line), and the target gets a box, a beam and a "name + distance" label.
 * <p>
 * Uses this mod's own {@code util/WorldRenderUtils} (the same line/box renderer Waypoint Routes, Secret Waypoints and
 * the Interactive Map draw with) and the mod's orange theme by default.
 */
public final class PathWorldRenderer {

    private static final double SUBDIVISION_STEP = 0.5;

    private PathWorldRenderer() {
    }

    public static void render(LevelRenderContext context) {
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || client.player == null || client.level == null) {
            return;
        }
        Camera camera = client.gameRenderer.getMainCamera();
        if (cfg.isSoulWaypoints() && cfg.isFairySouls()) {
            renderSouls(context, camera, cfg);
        }
        if (!NavigationManager.isActive()) {
            return;
        }
        NavigationManager.Target target = NavigationManager.target();
        List<Vec3> path = NavigationManager.path();
        float[] pc = WorldRenderUtils.argbToFloats(cfg.getPathColor());
        float[] tc = WorldRenderUtils.argbToFloats(cfg.getTargetColor());
        float thickness = cfg.getLineThickness();

        if (cfg.isShowPath() && path.size() >= 2) {
            Vec3 pos = client.player.position();
            List<Vec3> smoothed = smoothed(path);
            int startIdx = closestIndex(smoothed, pos);
            // the visible stretch: from the player, along the next N blocks of path
            List<Vec3> near = new ArrayList<>();
            near.add(new Vec3(camera.position().x, camera.position().y - 1.2, camera.position().z));
            double budget = cfg.getVisiblePathLength();
            for (int i = startIdx; i < smoothed.size() && budget > 0; i++) {
                Vec3 point = smoothed.get(i);
                if (!near.isEmpty()) {
                    budget -= near.get(near.size() - 1).distanceTo(point);
                }
                near.add(point);
            }
            if (cfg.isShowWholePath()) {
                WorldRenderUtils.renderLineStrip(context, smoothed, pc[0], pc[1], pc[2], 0.25f, Math.max(1f, thickness - 1f));
            }
            WorldRenderUtils.renderLineStrip(context, near, pc[0], pc[1], pc[2], 0.95f, thickness);
        }

        Vec3 t = target.pos;
        AABB box = new AABB(Math.floor(t.x) - 0.1, Math.floor(t.y) - 0.1, Math.floor(t.z) - 0.1,
                Math.floor(t.x) + 1.1, Math.floor(t.y) + 1.1, Math.floor(t.z) + 1.1);
        WorldRenderUtils.renderOutlineBox(context, box, tc[0], tc[1], tc[2], 1f, thickness);
        WorldRenderUtils.renderLineStrip(context, List.of(new Vec3(t.x, t.y, t.z), new Vec3(t.x, t.y + 25, t.z)),
                tc[0], tc[1], tc[2], 0.5f, thickness);
        if (cfg.isShowTargetLabel()) {
            double distance = NavigationManager.remainingDistance();
            renderText(context, camera, t.x, t.y + 1.8, t.z, target.label, 0xFF000000 | (cfg.getTargetColor() & 0xFFFFFF),
                    String.format(Locale.US, "%.0fm", distance), 0xFF000000 | ModChat.LIGHT_ORANGE, cfg.getTextScale());
        }
    }

    private static void renderSouls(LevelRenderContext context, Camera camera, PathfindingConfig cfg) {
        String island = IslandDetector.graphIsland();
        IslandGraph graph = island == null ? null : GraphRepository.get(island);
        if (graph == null) {
            return;
        }
        Vec3 cam = camera.position();
        float[] c = WorldRenderUtils.argbToFloats(cfg.getTargetColor());
        String profile = ProfileTracker.key();
        for (IslandGraph.Node soul : graph.withTag(IslandGraph.TAG_FAIRY_SOUL)) {
            if (FairySoulStore.isFound(profile, island, soul.x, soul.y, soul.z)) {
                continue;
            }
            double distSq = cam.distanceToSqr(soul.x, soul.y, soul.z);
            if (distSq > 96 * 96) {
                continue;
            }
            AABB box = new AABB(soul.x, soul.y, soul.z, soul.x + 1, soul.y + 1, soul.z + 1);
            WorldRenderUtils.renderOutlineBox(context, box, c[0], c[1], c[2], 0.75f, 2f);
        }
    }

    // NavigationManager always REPLACES its path list (never mutates it), so identity is a valid cache key. Without
    // this the spline below was rebuilt every frame - thousands of Vec3 allocations per frame on a long route.
    private static List<Vec3> lastPath;
    private static List<Vec3> lastSmoothed;

    private static List<Vec3> smoothed(List<Vec3> path) {
        if (path != lastPath) {
            lastPath = path;
            lastSmoothed = subdivide(path);
        }
        return lastSmoothed;
    }

    /** Catmull-Rom subdivision, same smoothing SkyHanni's PathRenderer uses. */
    private static List<Vec3> subdivide(List<Vec3> points) {
        if (points.size() < 2) {
            return points;
        }
        List<Vec3> out = new ArrayList<>(points.size() * 4);
        out.add(points.get(0));
        for (int i = 0; i < points.size() - 1; i++) {
            Vec3 p0 = i - 1 >= 0 ? points.get(i - 1) : points.get(i);
            Vec3 p1 = points.get(i);
            Vec3 p2 = points.get(i + 1);
            Vec3 p3 = i + 2 < points.size() ? points.get(i + 2) : points.get(i + 1);
            int steps = (int) Math.max(1, p1.distanceTo(p2) / SUBDIVISION_STEP);
            for (int step = 1; step <= steps; step++) {
                out.add(catmullRom(p0, p1, p2, p3, (double) step / steps));
            }
        }
        return out;
    }

    private static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        Vec3 a = p1.scale(2.0);
        Vec3 b = p2.subtract(p0).scale(t);
        Vec3 c = p0.scale(2.0).subtract(p1.scale(5.0)).add(p2.scale(4.0)).subtract(p3).scale(t2);
        Vec3 d = p1.scale(3.0).subtract(p0).subtract(p2.scale(3.0)).add(p3).scale(t3);
        return a.add(b).add(c).add(d).scale(0.5);
    }

    private static int closestIndex(List<Vec3> points, Vec3 pos) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            double d = points.get(i).distanceToSqr(pos);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Billboard text - same 26.1.2 nametag transform Waypoint Routes uses (camera rotation, then (+s, -s, +s)). */
    private static void renderText(LevelRenderContext context, Camera camera, double x, double y, double z,
                                   String line1, int color1, String line2, int color2, float scaleMul) {
        var bufferSource = context.bufferSource();
        PoseStack poseStack = context.poseStack();
        if (bufferSource == null || poseStack == null || (line1 == null && line2 == null)) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Vec3 cam = camera.position();
        double dist = Math.sqrt(cam.distanceToSqr(x, y, z));
        float s = 0.025f * scaleMul * (float) Math.min(8.0, Math.max(1.0, dist / 12.0));
        poseStack.pushPose();
        try {
            poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
            poseStack.mulPose(camera.rotation());
            poseStack.scale(s, -s, s);
            float yOff = (line1 != null && line2 != null) ? -font.lineHeight : -font.lineHeight / 2f;
            if (line1 != null) {
                font.drawInBatch(line1, -font.width(line1) / 2f, yOff, color1, false, poseStack.last().pose(),
                        bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
                yOff += font.lineHeight + 1;
            }
            if (line2 != null) {
                font.drawInBatch(line2, -font.width(line2) / 2f, yOff, color2, false, poseStack.last().pose(),
                        bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
            }
        } finally {
            poseStack.popPose();
        }
    }
}
