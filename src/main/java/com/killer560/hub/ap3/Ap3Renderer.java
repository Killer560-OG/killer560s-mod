package com.killer560.hub.ap3;

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

/**
 * Node markers for the current area's chain: one box per node (the small marker for a 1x1 node, the full trigger
 * box width x length for a sized one - see {@link #renderNodeBox}), an arrow for a WALK / RUN's travel direction, the wall side of an AXIS_ALIGN, a short
 * ray for a LOOK / BOOM, the chain line between consecutive nodes, the active node in its own colour, and 1-based
 * labels. Nodes stacked on the same spot get their labels lifted one step each (killer560: "Nodes stacked in the
 * same spot must draw their labels at different heights so they can be told apart").
 * <p>
 * Everything is depth-tested against the world and drawn through {@link WorldRenderUtils} only. Any exception is
 * caught by the feature's render hook, which disables the feature instead of taking the frame down.
 */
public final class Ap3Renderer {

    private static final double GROUND_OFFSET = 0.03;
    private static final double LABEL_DISTANCE = 40.0;
    private static final double ARROW_HEAD = 0.35;
    private static final double ARROW_LENGTH = 1.5;
    private static final double LOOK_RAY = 2.0;
    /** Two nodes closer than this on the ground are "the same spot" for label stacking. */
    private static final double STACK_RADIUS = 0.35;
    /** Each further node on the same spot lifts its label by this much (in blocks, scaled with the label). */
    private static final double STACK_STEP = 0.3;

    private Ap3Renderer() {
    }

    static void render(LevelRenderContext ctx, Ap3Chain chain, Ap3Node activeNode) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || chain == null) {
            return;
        }
        Ap3Config cfg = Ap3Config.getInstance();
        float thickness = cfg.getThickness();
        double height = cfg.getHeight();
        Camera camera = client.gameRenderer.getMainCamera();
        Vec3 playerPos = client.player.position();

        List<Vec3> chainLine = new ArrayList<>();
        List<Ap3Node> nodes = chain.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            Ap3Node node = nodes.get(i);
            Vec3 real = node.pos();
            chainLine.add(real.add(0, height / 2.0, 0));
            boolean active = node == activeNode;
            int argb = active ? cfg.getActiveColorArgb() : cfg.colorFor(node);
            float[] c = WorldRenderUtils.argbToFloats(argb);
            float alpha = c.length > 3 ? c[3] : 1f;
            if (alpha <= 0.01f) {
                alpha = 1f;
            }
            renderNodeBox(ctx, node, active, height, c, alpha, thickness);
            switch (node.type) {
                case WALK, RUN -> renderArrow(ctx, node, c, alpha, thickness);
                case AXIS_ALIGN -> renderWallSide(ctx, node, c, alpha, thickness);
                case LOOK, BOOM -> renderLookRay(ctx, node, c, alpha, thickness);
                default -> {
                }
            }
            if (cfg.isShowLabels() && playerPos.distanceTo(real) <= LABEL_DISTANCE) {
                // 1-based through the chain's own helper, same as /ap3 list, /ap3 delete and the tab - the number
                // on the label has to be the number you can type (AutoRoutesRenderer does the same).
                String text = label(cfg, chain.numberOf(node), node);
                if (!text.isEmpty()) {
                    int stack = stackIndex(nodes, i);
                    double lift = stack * STACK_STEP * Math.max(0.5f, cfg.getLabelScale());
                    renderLabel(ctx, camera, real.x, real.y + height + 0.35 + cfg.getLabelHeightOffset() + lift, real.z,
                            text, cfg.labelColorFor(node, argb), cfg.getLabelScale());
                }
            }
        }
        if (chainLine.size() >= 2) {
            float[] a = WorldRenderUtils.argbToFloats(cfg.getActiveColorArgb());
            WorldRenderUtils.renderLineStrip(ctx, chainLine, a[0], a[1], a[2], 0.4f, Math.max(0.5f, thickness / 2f));
        }
    }

    /** How many EARLIER nodes share this node's spot - its label goes that many steps higher. */
    private static int stackIndex(List<Ap3Node> nodes, int index) {
        Ap3Node me = nodes.get(index);
        int stack = 0;
        for (int j = 0; j < index; j++) {
            Ap3Node other = nodes.get(j);
            if (Math.abs(other.y - me.y) <= 0.5 && Math.abs(other.x - me.x) <= STACK_RADIUS && Math.abs(other.z - me.z) <= STACK_RADIUS) {
                stack++;
            }
        }
        return stack;
    }

    /**
     * "#3 Align 3x3" with each part behind its own toggle: the 1-based number (killer560: "the very first node
     * is 1 the second is 2 and so on"), the type name, and the per-type detail. Empty when every part is off, so
     * the caller draws nothing rather than a blank label.
     */
    static String label(Ap3Config cfg, int number, Ap3Node node) {
        StringBuilder sb = new StringBuilder();
        if (cfg.isShowNodeNumbers() && number > 0) {
            sb.append('#').append(number);
        }
        if (cfg.isShowNodeType()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(node.type.label());
        }
        if (cfg.isShowNodeDetails()) {
            String detail = detail(node);
            if (!detail.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(detail);
            }
        }
        return sb.toString();
    }

    /** The per-type modifier plus the general ones ("3x3 precise wait:500 close"); empty when there is none. */
    private static String detail(Ap3Node node) {
        StringBuilder sb = new StringBuilder();
        switch (node.type) {
            case LEAP -> sb.append(node.leapDescription());
            case LEAP_COUNTER -> sb.append('x').append(node.leapCount);
            case AXIS_ALIGN -> sb.append(node.wallDir == null ? "no wall" : node.wallDir.getName());
            default -> {
            }
        }
        if (!node.hasDefaultBox()) {
            append(sb, Ap3Node.fmt(node.width) + "x" + Ap3Node.fmt(node.length));
        }
        if (node.precise && node.type.isAlign()) {
            append(sb, "precise");
        }
        if (node.waitAfterMs > 0) {
            append(sb, "wait:" + node.waitAfterMs);
        }
        if (node.closeGate) {
            append(sb, "close");
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(part);
    }

    /**
     * The node's ONE box, always at the EXACT trigger size, centred on the node. killer560 (2026-09-21): "if I go to
     * 1 1 then nothing changes visually ... If I go bigger than 1 1 it does though" - a default-size node used to
     * draw a fixed 0.5-wide marker whatever its real box was, so 0.5x0.5 and 1x1 looked the same; now what is drawn
     * is what {@link Ap3Node#contains} tests. Fixed in world space: laid out on {@link Ap3Node#boxYaw()} (the block
     * grid for an align, the recorded yaw for a walk), never on the player's position or facing. An axis-aligned box
     * is a real AABB (so the active node's fill still works); a turned walk box is drawn as its two rings plus
     * uprights.
     */
    private static void renderNodeBox(LevelRenderContext ctx, Ap3Node node, boolean active, double height,
                                      float[] c, float alpha, float thickness) {
        if (node.isTriggerBoxAxisAligned()) {
            AABB box = node.triggerBox(height);
            if (active) {
                WorldRenderUtils.renderFilledBox(ctx, box, c[0], c[1], c[2], alpha * 0.35f);
            }
            WorldRenderUtils.renderOutlineBox(ctx, box, c[0], c[1], c[2], alpha, thickness);
            return;
        }
        double top = Math.max(0.1, height);
        Vec3[] floor = node.triggerCorners(node.y + GROUND_OFFSET);
        Vec3[] ceil = node.triggerCorners(node.y + top);
        WorldRenderUtils.renderLineStrip(ctx, List.of(floor[0], floor[1], floor[2], floor[3], floor[0]), c[0], c[1], c[2], alpha, thickness);
        WorldRenderUtils.renderLineStrip(ctx, List.of(ceil[0], ceil[1], ceil[2], ceil[3], ceil[0]), c[0], c[1], c[2], alpha, thickness);
        for (int i = 0; i < 4; i++) {
            WorldRenderUtils.renderLineStrip(ctx, List.of(floor[i], ceil[i]), c[0], c[1], c[2], alpha, thickness);
        }
    }

    /** Travel direction of a WALK / RUN (the walk is held until a STOP / align, so no length is drawn). */
    private static void renderArrow(LevelRenderContext ctx, Ap3Node node, float[] c, float alpha, float thickness) {
        Vec3 d = node.dir();
        Vec3 l = node.left();
        double y = node.y + GROUND_OFFSET;
        Vec3 start = new Vec3(node.x, y, node.z);
        Vec3 end = new Vec3(node.x + d.x * ARROW_LENGTH, y, node.z + d.z * ARROW_LENGTH);
        Vec3 headL = end.add((-d.x + l.x) * ARROW_HEAD, 0, (-d.z + l.z) * ARROW_HEAD);
        Vec3 headR = end.add((-d.x - l.x) * ARROW_HEAD, 0, (-d.z - l.z) * ARROW_HEAD);
        WorldRenderUtils.renderLineStrip(ctx, List.of(start, end), c[0], c[1], c[2], alpha, thickness);
        WorldRenderUtils.renderLineStrip(ctx, List.of(headL, end, headR), c[0], c[1], c[2], alpha, thickness);
    }

    /** The wall side an AXIS_ALIGN presses into: a short line from chest height toward that side. */
    private static void renderWallSide(LevelRenderContext ctx, Ap3Node node, float[] c, float alpha, float thickness) {
        Vec3 w = node.wallVector();
        if (w == null) {
            return;
        }
        Vec3 from = new Vec3(node.x, node.y + 0.9, node.z);
        Vec3 to = from.add(w.x * 0.8, 0, w.z * 0.8);
        WorldRenderUtils.renderLineStrip(ctx, List.of(from, to), c[0], c[1], c[2], alpha * 0.7f, Math.max(0.5f, thickness / 2f));
    }

    /** Where a LOOK turns to / a BOOM fires: the recorded yaw+pitch from eye height. */
    private static void renderLookRay(LevelRenderContext ctx, Ap3Node node, float[] c, float alpha, float thickness) {
        double yr = Math.toRadians(node.yaw);
        double pr = Math.toRadians(node.pitch);
        double cp = Math.cos(pr);
        Vec3 from = new Vec3(node.x, node.y + 1.62, node.z);
        Vec3 to = from.add(-Math.sin(yr) * cp * LOOK_RAY, -Math.sin(pr) * LOOK_RAY, Math.cos(yr) * cp * LOOK_RAY);
        WorldRenderUtils.renderLineStrip(ctx, List.of(from, to), c[0], c[1], c[2], alpha * 0.7f, Math.max(0.5f, thickness / 2f));
    }

    /** Camera-facing text at a world position - {@code posmsg/PosmsgRenderer.renderLabel}, including its
     *  {@code textScale} multiplier on top of the distance-based size. */
    private static void renderLabel(LevelRenderContext ctx, Camera camera, double x, double y, double z, String text,
                                    int color, float textScale) {
        var bufferSource = ctx.bufferSource();
        PoseStack poseStack = ctx.poseStack();
        if (bufferSource == null || poseStack == null || text == null || text.isBlank()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Vec3 cam = camera.position();
        double dist = Math.sqrt(cam.distanceToSqr(x, y, z));
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
