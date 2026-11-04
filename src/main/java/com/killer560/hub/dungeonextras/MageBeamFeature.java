package com.killer560.hub.dungeonextras;

import com.killer560.hub.compat.McPackets;
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
        if (McPackets.particle(packet) == null || McPackets.particle(packet).getType() != ParticleTypes.FIREWORK) {
            return false;
        }
        onPoint(new Vec3(McPackets.particleX(packet), McPackets.particleY(packet), McPackets.particleZ(packet)));
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
            int mode = cfg.getMageBeamColorMode();
            renderBeamBody(context, beam.quads(shape, widthBlocks), cfg.getMageBeamColor(), cfg.getMageBeamColor2(),
                    mode, alpha, now);
            // The core line only where the body is too thin to see (killer560, 2026-11-01: with Flat and a wide beam "the
            // very middle is still a bit darker than the rest ... for all variants"): the 2 px core was drawn over the
            // body in the same translucent color, so wherever both showed, the middle doubled up. Now it is drawn only
            // while the body is under 2 px on screen, at range or at the thinnest widths.
            // beam.core is only replaced, never changed, so the render callback may read it later in the frame (26.2).
            if (coreNeeded(context, beam.min(), beam.max(), widthBlocks)) {
                float[] c = colorAt(cfg.getMageBeamColor(), cfg.getMageBeamColor2(), mode, 0f, now);
                WorldRenderUtils.renderLineStrip(context, beam.core, c[0], c[1], c[2], alpha, 2f);
                coreDraws++;
            }
            bodyDraws++;
        }
    }

    private static volatile int coreDraws;
    private static volatile int bodyDraws;

    /** Beam bodies drawn, and of those how many also drew the core line (testkit evidence). */
    public static int[] drawCounts() {
        return new int[]{bodyDraws, coreDraws};
    }

    /** Whether the body's width at its nearest point to the camera is under 2 screen pixels, so the core line must show it. */
    static boolean coreNeeded(LevelRenderContext context, Vec3 start, Vec3 end, float widthBlocks) {
        Vec3 cam = com.killer560.hub.compat.McRender.cameraPos(context);
        if (cam == null) {
            return true;
        }
        Vec3 ab = end.subtract(start);
        double len2 = ab.lengthSqr();
        double t = len2 < 1.0E-9 ? 0 : Math.max(0, Math.min(1, cam.subtract(start).dot(ab) / len2));
        double dist = Math.max(0.05, cam.distanceTo(start.add(ab.scale(t))));
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        double vfov = Math.toRadians(Math.max(30, Math.min(110, mc.options.fov().get())));
        double screenH = mc.getWindow().getHeight();
        double px = widthBlocks / (2.0 * dist * Math.tan(vfov / 2.0)) * screenH;
        return px < 2.0;
    }

    /** RGB (0..1) at {@code s} (0 = the beam's start, 1 = its end) for the color mode, at time {@code nowMs}. */
    static float[] colorAt(int c1, int c2, int mode, float s, long nowMs) {
        int argb;
        if (mode == DungeonExtrasConfig.BEAM_COLOR_FADE) {
            argb = lerpColor(c1, c2, s);
        } else if (mode == DungeonExtrasConfig.BEAM_COLOR_ROTATING) {
            // One full rainbow along the beam, turning once every two seconds.
            float hue = (float) (((nowMs % 2000L) / 2000.0 + s) % 1.0);
            argb = java.awt.Color.HSBtoRGB(hue, 0.85f, 1f);
        } else {
            argb = c1;
        }
        return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f};
    }

    private static int lerpColor(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
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

    /** Pieces a beam is cut into along its length for Rotating, so the rainbow runs along it; Solid and Fade need one
     *  (a fade between the two ends is exactly what the vertex colors blend). */
    private static final int ROTATING_PIECES = 24;

    /** Draws {@link #beamQuads} with both windings, so face culling cannot hide a side. Each quad's corners 0 and 3 are
     *  at the beam's start and 1 and 2 at its end, so the color can run from start to end. */
    private static void renderBeamBody(LevelRenderContext context, List<Vec3[]> quads, int c1, int c2, int mode,
                                       float a, long now) {
        if (quads.isEmpty()) {
            return;
        }
        int pieces = mode == DungeonExtrasConfig.BEAM_COLOR_ROTATING ? ROTATING_PIECES : 1;
        float[][] colors = new float[pieces + 1][];
        for (int k = 0; k <= pieces; k++) {
            colors[k] = colorAt(c1, c2, mode, k / (float) pieces, now);
        }
        McRender.inCameraSpace(context, com.killer560.hub.util.EspTypes.FILLED, (pose, buffer) -> {
            org.joml.Matrix4f matrix = pose.pose();
            for (Vec3[] q : quads) {
                for (int k = 0; k < pieces; k++) {
                    double s0 = k / (double) pieces;
                    double s1 = (k + 1) / (double) pieces;
                    Vec3 p0 = lerp(q[0], q[1], s0);
                    Vec3 p1 = lerp(q[0], q[1], s1);
                    Vec3 p2 = lerp(q[3], q[2], s1);
                    Vec3 p3 = lerp(q[3], q[2], s0);
                    float[] ca = colors[k];
                    float[] cb = colors[k + 1];
                    quad(buffer, matrix, p0, p1, p2, p3, ca, cb, cb, ca, a);
                    quad(buffer, matrix, p3, p2, p1, p0, ca, cb, cb, ca, a);
                }
            }
        });
    }

    private static Vec3 lerp(Vec3 from, Vec3 to, double t) {
        return t <= 0 ? from : t >= 1 ? to : from.add(to.subtract(from).scale(t));
    }

    private static void quad(VertexConsumer buffer, org.joml.Matrix4f matrix, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3,
                             float[] c0, float[] c1, float[] c2, float[] c3, float a) {
        vertex(buffer, matrix, p0, c0[0], c0[1], c0[2], a);
        vertex(buffer, matrix, p1, c1[0], c1[1], c1[2], a);
        vertex(buffer, matrix, p2, c2[0], c2[1], c2[2], a);
        vertex(buffer, matrix, p3, c3[0], c3[1], c3[2], a);
    }

    private static void vertex(VertexConsumer buffer, org.joml.Matrix4f matrix, Vec3 p,
                               float r, float g, float b, float a) {
        buffer.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }
}
