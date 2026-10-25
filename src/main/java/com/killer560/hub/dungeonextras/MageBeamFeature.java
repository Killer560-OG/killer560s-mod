package com.killer560.hub.dungeonextras;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import com.killer560.hub.compat.McRender;

/**
 * Custom Mage Beam - port of NoammAddons' {@code MageBeam.kt} (origin/26.1.2). The Mage beam arrives as a run of
 * {@code ClientboundLevelParticlesPacket}s with {@code ParticleTypes.FIREWORK}, spaced ~0.5 blocks along a straight
 * line, sent within one tick of each other. Consecutive collinear points (|dot| &gt; 0.99, &lt; 0.5 blocks from the
 * current end, &lt;= 1 tick apart) extend the same beam; anything else starts a new one. Hypixel sends every beam
 * twice, so exact duplicate points are ignored. A beam renders once it has at least 6 points, as a single line
 * from its first to its last point. Firework particle packets are canceled while in a dungeon if Hide Particles
 * is on (reference cancels unconditionally).
 */
public final class MageBeamFeature {

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
        // The body's faces and the core line, rebuilt only when the beam grew or the shape/width changed, not every frame.
        private List<Vec3[]> quads;
        private Vec3[] core;
        private int quadsPoints = -1;
        private int quadsShape;
        private float quadsWidth;

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

        List<Vec3[]> quads(int shape, float widthBlocks) {
            if (quads == null || quadsPoints != points.size() || quadsShape != shape || quadsWidth != widthBlocks) {
                quads = beamQuads(min(), max(), shape, widthBlocks);
                core = new Vec3[]{min(), max()};
                quadsPoints = points.size();
                quadsShape = shape;
                quadsWidth = widthBlocks;
            }
            return quads;
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
        // Custom Mage Beam on means the vanilla firework particles are always hidden (killer560, 2026-10-18).
        return true;
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
        int shape = cfg.getMageBeamShape();
        float widthBlocks = cfg.getMageBeamWidth() * DungeonExtrasConfig.BEAM_WIDTH_BLOCKS;
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
            // killer560 (2026-09-20): "make it so the width is much more impactful". The beam body is a real
            // world-space solid whose thickness the slider actually controls; the thin line stays as a core so a
            // low width still reads at range, where a 5cm solid is under a pixel wide.
            // 2026-10-18: "should not rotate as I run around it" - the body used to be one quad turned to face the
            // camera, so it swung round with every step. It is now fixed to the beam (see beamQuads).
            renderBeamBody(context, beam.quads(shape, widthBlocks), rgba[0], rgba[1], rgba[2], alpha);
            // beam.core is only replaced, never changed, so the render callback may read it later in the frame (26.2).
            WorldRenderUtils.renderLineStrip(context, beam.core, rgba[0], rgba[1], rgba[2], alpha, 2f);
        }
    }

    /**
     * The beam body's faces for {@code shape}, as quads of four world-space corners. Nothing here knows where the camera
     * is, which is the point: the cross-section is laid out from the beam's own axis and the world's up, so walking round
     * the beam never turns it. Square is four faces round the axis (a square beam coming out), Flat one horizontal ribbon,
     * Cylinder an eight-sided prism. A vertical beam, which has no side to call "up", uses world X as its reference.
     */
    public static List<Vec3[]> beamQuads(Vec3 start, Vec3 end, int shape, float widthBlocks) {
        List<Vec3[]> quads = new ArrayList<>();
        Vec3 axis = end.subtract(start);
        if (axis.lengthSqr() < 1.0E-6) {
            return quads;
        }
        Vec3 dir = axis.normalize();
        Vec3 ref = Math.abs(dir.y) > 0.999 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = dir.cross(ref).normalize();   // sideways, horizontal for any beam that is not vertical
        Vec3 v = u.cross(dir).normalize();     // the beam's own "up"
        double half = Math.max(0.01f, widthBlocks) * 0.5;
        if (shape == DungeonExtrasConfig.BEAM_SHAPE_FLAT) {
            Vec3 o = u.scale(half);
            quads.add(new Vec3[]{start.subtract(o), end.subtract(o), end.add(o), start.add(o)});
            return quads;
        }
        int sides = shape == DungeonExtrasConfig.BEAM_SHAPE_CYLINDER ? 8 : 4;
        Vec3[] ring = new Vec3[sides];
        for (int i = 0; i < sides; i++) {
            // Square: corners at 45, 135, ... so its faces face along u and v. Cylinder: its radius is half the width.
            double a = Math.PI * 2.0 * (i + 0.5) / sides;
            double r = sides == 4 ? half * Math.sqrt(2.0) : half;
            ring[i] = u.scale(Math.cos(a) * r).add(v.scale(Math.sin(a) * r));
        }
        for (int i = 0; i < sides; i++) {
            Vec3 a = ring[i];
            Vec3 b = ring[(i + 1) % sides];
            quads.add(new Vec3[]{start.add(a), end.add(a), end.add(b), start.add(b)});
        }
        return quads;
    }

    /** Draws {@link #beamQuads} with both windings, so face culling can't hide a side. */
    private static void renderBeamBody(LevelRenderContext context, List<Vec3[]> quads,
                                       float r, float g, float b, float a) {
        if (quads.isEmpty()) {
            return;
        }
        McRender.inCameraSpace(context, RenderTypes.debugFilledBox(), (pose, buffer) -> {
            org.joml.Matrix4f matrix = pose.pose();
            for (Vec3[] q : quads) {
                quad(buffer, matrix, q[0], q[1], q[2], q[3], r, g, b, a);
                quad(buffer, matrix, q[3], q[2], q[1], q[0], r, g, b, a);
            }
        });
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
