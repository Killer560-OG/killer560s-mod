package com.killer560.hub.ap3;

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
import java.util.Locale;

/**
 * Node markers for the current section's chain: a small box per node, the corridor (centre line + width edges) of a
 * LINE / AXIS_LINE for its length, an arrow for a WALK / RUN's travel, the chain line between consecutive nodes, the
 * active node in its own colour, 1-based labels with length / width, and in edit mode a faint highlight on every
 * block a BREAKER node will break (air = red outline, present = white fill - QUOI's DB editor).
 * <p>
 * Everything is depth-tested against the world and drawn through {@link WorldRenderUtils} only. Any exception is
 * caught by the feature's render hook, which disables the feature instead of taking the frame down.
 */
public final class Ap3Renderer {

    private static final double GROUND_OFFSET = 0.03;
    private static final double LABEL_DISTANCE = 40.0;
    private static final float BREAKER_ALPHA = 0.28f;
    private static final double CORRIDOR_TAIL = 0.25;
    private static final double ARROW_HEAD = 0.35;

    private Ap3Renderer() {
    }

    static void render(LevelRenderContext ctx, Ap3Chain chain, boolean editMode, Ap3Node activeNode) {
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
            AABB box = node.boundingBox(height);
            if (active) {
                WorldRenderUtils.renderFilledBox(ctx, box, c[0], c[1], c[2], alpha * 0.35f);
            }
            WorldRenderUtils.renderOutlineBox(ctx, box, c[0], c[1], c[2], alpha, thickness);
            switch (node.type) {
                case LINE, AXIS_LINE -> renderCorridor(ctx, node, c, alpha, thickness);
                case WALK, RUN -> renderArrow(ctx, node, c, alpha, thickness);
                default -> {
                }
            }
            if (cfg.isShowLabels() && playerPos.distanceTo(real) <= LABEL_DISTANCE) {
                // 1-based, same as /ap3 list and /ap3 delete - the number on the label has to be the number you can type.
                renderLabel(ctx, camera, real.x, real.y + height + 0.35, real.z, label(i + 1, node), argb | 0xFF000000);
            }
        }
        if (chainLine.size() >= 2) {
            float[] a = WorldRenderUtils.argbToFloats(cfg.getActiveColorArgb());
            WorldRenderUtils.renderLineStrip(ctx, chainLine, a[0], a[1], a[2], 0.4f, Math.max(0.5f, thickness / 2f));
        }
        if (editMode) {
            renderBreakerBlocks(ctx, client, chain);
        }
    }

    private static String label(int number, Ap3Node node) {
        StringBuilder sb = new StringBuilder("#").append(number).append(' ').append(node.type.label());
        switch (node.type) {
            case LINE, AXIS_LINE -> sb.append(String.format(Locale.US, " %.1f x %.1f", node.length, node.width));
            case WALK, RUN -> sb.append(String.format(Locale.US, " %.1f", node.length));
            case WAIT -> sb.append(' ').append(node.waitMs).append("ms");
            case LEAP -> sb.append(' ').append(node.leapDescription());
            case LEAP_DETECTOR -> sb.append(" x").append(node.leapCount);
            case BREAKER -> sb.append(' ').append(node.breakerBlocks.size()).append(" blk");
            default -> {
            }
        }
        return sb.toString();
    }

    /** The active span (centre line, tail to length) and the tolerance band (edges at +-width/2). */
    private static void renderCorridor(LevelRenderContext ctx, Ap3Node node, float[] c, float alpha, float thickness) {
        Vec3 d = node.dir();
        Vec3 l = node.left();
        double y = node.y + GROUND_OFFSET;
        Vec3 start = new Vec3(node.x - d.x * CORRIDOR_TAIL, y, node.z - d.z * CORRIDOR_TAIL);
        Vec3 end = new Vec3(node.x + d.x * node.length, y, node.z + d.z * node.length);
        WorldRenderUtils.renderLineStrip(ctx, List.of(start, end), c[0], c[1], c[2], alpha, thickness);
        double hw = node.width / 2.0;
        Vec3 offset = new Vec3(l.x * hw, 0, l.z * hw);
        float edgeAlpha = alpha * 0.45f;
        float edgeThickness = Math.max(0.5f, thickness / 2f);
        WorldRenderUtils.renderLineStrip(ctx, List.of(start.add(offset), end.add(offset)), c[0], c[1], c[2], edgeAlpha, edgeThickness);
        WorldRenderUtils.renderLineStrip(ctx, List.of(start.subtract(offset), end.subtract(offset)), c[0], c[1], c[2], edgeAlpha, edgeThickness);
        if (node.type == Ap3Node.Type.AXIS_LINE) {
            Vec3 axis = Ap3Executor.wallAxisVector(node);
            if (axis != null) {
                // The wall measurement: node centre to the recorded face, at chest height.
                Vec3 from = new Vec3(node.x, node.y + 0.9, node.z);
                Vec3 to = from.add(axis.x * node.wallDistance, 0, axis.z * node.wallDistance);
                WorldRenderUtils.renderLineStrip(ctx, List.of(from, to), c[0], c[1], c[2], alpha * 0.7f, edgeThickness);
            }
        }
    }

    /** Travel direction and distance of a WALK / RUN. */
    private static void renderArrow(LevelRenderContext ctx, Ap3Node node, float[] c, float alpha, float thickness) {
        Vec3 d = node.dir();
        Vec3 l = node.left();
        double y = node.y + GROUND_OFFSET;
        Vec3 start = new Vec3(node.x, y, node.z);
        Vec3 end = new Vec3(node.x + d.x * node.length, y, node.z + d.z * node.length);
        Vec3 headL = end.add((-d.x + l.x) * ARROW_HEAD, 0, (-d.z + l.z) * ARROW_HEAD);
        Vec3 headR = end.add((-d.x - l.x) * ARROW_HEAD, 0, (-d.z - l.z) * ARROW_HEAD);
        WorldRenderUtils.renderLineStrip(ctx, List.of(start, end), c[0], c[1], c[2], alpha, thickness);
        WorldRenderUtils.renderLineStrip(ctx, List.of(headL, end, headR), c[0], c[1], c[2], alpha, thickness);
    }

    /** killer560: "blocks that will be broken by any node get a faint highlight while in edit mode". */
    private static void renderBreakerBlocks(LevelRenderContext ctx, Minecraft client, Ap3Chain chain) {
        for (Ap3Node node : chain.nodes()) {
            if (node.type != Ap3Node.Type.BREAKER) {
                continue;
            }
            for (BlockPos real : node.breakerBlocks) {
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
