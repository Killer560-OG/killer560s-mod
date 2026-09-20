package com.killer560.hub.dungeonextras;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Custom Mage Beam - port of NoammAddons' {@code MageBeam.kt} (origin/26.1.2). The Mage beam arrives as a run of
 * {@code ClientboundLevelParticlesPacket}s with {@code ParticleTypes.FIREWORK}, spaced ~0.5 blocks along a straight
 * line, sent within one tick of each other. Consecutive collinear points (|dot| &gt; 0.99, &lt; 0.5 blocks from the
 * current end, &lt;= 1 tick apart) extend the same beam; anything else starts a new one. Hypixel sends every beam
 * twice, so exact duplicate points are ignored. A beam renders once it has at least 6 points, as a single line
 * from its first to its last point. Firework particle packets are cancelled while in a dungeon if Hide Particles
 * is on (reference cancels unconditionally).
 */
public final class MageBeamFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-dungeonextras");
    private static final double POINT_SPACE = 0.5;
    private static final int MIN_POINTS = 6;

    private static final List<Beam> BEAMS = new ArrayList<>();
    private static long tick = 0;

    private MageBeamFeature() {
    }

    private static final class Beam {
        final List<Vec3> points = new ArrayList<>();
        final long createdTick;
        final long createdMs;
        long updateTick;
        boolean logged;

        Beam(Vec3 point, long tick) {
            points.add(point);
            createdTick = tick;
            updateTick = tick;
            createdMs = System.currentTimeMillis();
        }

        Vec3 min() {
            return points.get(0);
        }

        Vec3 max() {
            return points.get(points.size() - 1);
        }

        boolean inLine(Vec3 point) {
            if (points.size() < 2) {
                return true;
            }
            Vec3 axis = max().subtract(min()).normalize();
            Vec3 toPoint = point.subtract(max()).normalize();
            boolean onLine = Math.abs(axis.dot(toPoint)) > 0.99;
            return onLine && point.distanceToSqr(max()) < POINT_SPACE * POINT_SPACE;
        }
    }

    static void onClientTick(Minecraft client) {
        if (client.level == null) {
            if (!BEAMS.isEmpty()) {
                BEAMS.clear();
            }
            return;
        }
        tick++;
        int duration = DungeonExtrasConfig.getInstance().getMageBeamDurationTicks();
        Iterator<Beam> it = BEAMS.iterator();
        while (it.hasNext()) {
            if (tick - it.next().createdTick >= duration) {
                it.remove();
            }
        }
    }

    /** Called from {@code DungeonExtrasPacketMixin} on the main thread. @return true to cancel vanilla handling. */
    public static boolean onParticlePacket(ClientboundLevelParticlesPacket packet) {
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        if (!cfg.isMageBeamEnabled() || !DungeonState.isInDungeon()) {
            return false;
        }
        if (packet.getParticle() == null || packet.getParticle().getType() != ParticleTypes.FIREWORK) {
            return false;
        }
        onPoint(new Vec3(packet.getX(), packet.getY(), packet.getZ()));
        return cfg.isMageBeamHideParticles();
    }

    private static void onPoint(Vec3 point) {
        for (Beam beam : BEAMS) {
            if (beam.points.contains(point)) {
                return;
            }
        }
        Beam last = BEAMS.isEmpty() ? null : BEAMS.get(BEAMS.size() - 1);
        if (last != null && tick - last.updateTick <= 1 && last.inLine(point)) {
            last.points.add(point);
            last.updateTick = tick;
            if (!last.logged && last.points.size() >= MIN_POINTS) {
                last.logged = true;
                LOGGER.info("[DungeonExtras] Mage beam detected from {} (first {} points).", last.min(), MIN_POINTS);
            }
        } else {
            BEAMS.add(new Beam(point, tick));
        }
    }

    static void onWorldRender(LevelRenderContext context) {
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        if (!cfg.isMageBeamEnabled() || BEAMS.isEmpty()) {
            return;
        }
        float[] rgba = WorldRenderUtils.argbToFloats(cfg.getMageBeamColor());
        long durationMs = cfg.getMageBeamDurationTicks() * 50L;
        long now = System.currentTimeMillis();
        for (Beam beam : BEAMS) {
            if (beam.points.size() < MIN_POINTS) {
                continue;
            }
            float alpha = rgba[3];
            if (cfg.isMageBeamFade()) {
                float progress = Math.min(1f, Math.max(0f, (now - beam.createdMs) / (float) durationMs));
                alpha *= 1f - progress;
            }
            if (alpha <= 0.01f) {
                continue;
            }
            // Width used to be handed straight to the GL line width, which a core profile clamps to 1px -
            // killer560 (2026-09-20): "make it so the width is much more impactful". The beam body is now a
            // real world-space quad whose thickness the slider actually controls; the thin line stays as a
            // core so a low width still reads at range, where a 5cm quad is under a pixel wide.
            renderBeamQuad(context, beam.min(), beam.max(), rgba[0], rgba[1], rgba[2], alpha,
                    cfg.getMageBeamWidth() * DungeonExtrasConfig.BEAM_WIDTH_BLOCKS);
            WorldRenderUtils.renderLineStrip(context, List.of(beam.min(), beam.max()),
                    rgba[0], rgba[1], rgba[2], alpha, 2f);
        }
    }

    /** Draws the beam as a camera-facing quad {@code widthBlocks} thick. Billboarded (widened across both the
     *  beam axis and the line of sight) so it never thins out to nothing when viewed edge-on, and emitted with
     *  both windings so face culling can't hide one side. */
    private static void renderBeamQuad(LevelRenderContext context, Vec3 start, Vec3 end,
                                       float r, float g, float b, float a, float widthBlocks) {
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        Vec3 axis = end.subtract(start);
        if (axis.lengthSqr() < 1.0E-6) {
            return;
        }
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        Vec3 toCam = start.add(end).scale(0.5).subtract(cam);
        Vec3 side = axis.cross(toCam);
        if (side.lengthSqr() < 1.0E-6) {
            side = axis.cross(new Vec3(0, 1, 0));
            if (side.lengthSqr() < 1.0E-6) {
                side = new Vec3(1, 0, 0);
            }
        }
        side = side.normalize().scale(Math.max(0.01f, widthBlocks) * 0.5);

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        org.joml.Matrix4f matrix = poseStack.last().pose();
        VertexConsumer buffer = bufferSource.getBuffer(RenderTypes.debugFilledBox());

        Vec3 s0 = start.subtract(side);
        Vec3 s1 = start.add(side);
        Vec3 e0 = end.subtract(side);
        Vec3 e1 = end.add(side);
        quad(buffer, matrix, s0, e0, e1, s1, r, g, b, a);
        quad(buffer, matrix, s1, e1, e0, s0, r, g, b, a);

        poseStack.popPose();
    }

    private static void quad(VertexConsumer buffer, org.joml.Matrix4f matrix, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3,
                             float r, float g, float b, float a) {
        vertex(buffer, matrix, p0, r, g, b, a);
        vertex(buffer, matrix, p1, r, g, b, a);
        vertex(buffer, matrix, p2, r, g, b, a);
        vertex(buffer, matrix, p3, r, g, b, a);
    }

    private static void vertex(VertexConsumer buffer, org.joml.Matrix4f matrix, Vec3 p,
                               float r, float g, float b, float a) {
        buffer.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }
}
