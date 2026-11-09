package com.killer560.hub.dungeonextras;

import com.killer560.hub.compat.McPackets;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
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
        // This frame's draw state, filled by onWorldRender and read by the geometry callback later in the same frame
        // (both versions draw submitted geometry after the event), so a frame allocates nothing.
        final int[] cols = new int[ROTATING_PIECES + 1];
        final double[] spans = new double[4];
        int spanCount;
        int pieces;
        double camX, camY, camZ;
        final McRender.Geometry draw = (pose, buffer) -> {
            SINK.matrix = pose.pose();
            SINK.buffer = buffer;
            SINK.camX = camX;
            SINK.camY = camY;
            SINK.camZ = camZ;
            emitBody(quads, min(), max(), camX, camY, camZ, spans, spanCount, cols, pieces, SINK);
        };

        Beam(Vec3 point, long tick) {
            this(point, tick, System.currentTimeMillis());
        }

        Beam(Vec3 point, long tick, long ms) {
            points.add(point);
            createdTick = tick;
            updateTick = tick;
            createdMs = ms;
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
            // Rays of one shot arrive a tick or so apart: they share the first ray's clock so they fade out together.
            Beam b = new Beam(point, tick);
            if (last != null && tick - last.createdTick <= 2) {
                b = new Beam(point, last.createdTick, last.createdMs);
                b.updateTick = tick;
            }
            BEAMS.add(b);
        }
    }

    /** Testkit: feeds one beam point as the particle packet would. */
    public static void pointForTest(double x, double y, double z) {
        onPoint(new Vec3(x, y, z));
    }

    /** Testkit: advances the beam tick by hand. */
    public static void tickForTest() {
        tick++;
    }

    /** Testkit: each beam's start time, in order. */
    public static long[] startTimesForTest() {
        return BEAMS.stream().mapToLong(b -> b.createdMs).toArray();
    }

    /** Testkit: forgets every beam. */
    public static void clearForTest() {
        BEAMS.clear();
    }

    static void onWorldRender(LevelRenderContext context) {
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        if (!cfg.isMageBeamEnabled() || BEAMS.isEmpty()) {
            return;
        }
        Vec3 cam = McRender.cameraPos(context);
        if (cam == null) {
            return;
        }
        float baseAlpha = ((cfg.getMageBeamColor() >>> 24) & 0xFF) / 255f;
        long durationMs = cfg.getMageBeamDurationTicks() * 50L;
        long now = System.currentTimeMillis();
        int shape = cfg.getMageBeamShape();
        float widthBlocks = cfg.getMageBeamWidth() * DungeonExtrasConfig.BEAM_WIDTH_BLOCKS;
        for (Beam beam : BEAMS) {
            if (beam.points.size() < MIN_POINTS) {
                continue;
            }
            float alpha = alphaAt(baseAlpha, now - beam.createdMs, durationMs, cfg.isMageBeamFade());
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
            beam.quads(shape, widthBlocks);
            if (prepareBody(beam, cam, widthBlocks, cfg.getMageBeamColor(), cfg.getMageBeamColor2(), mode, alpha, now)) {
                McRender.inCameraSpace(context, com.killer560.hub.util.EspTypes.FILLED, beam.draw);
            }
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
        int argb = rgbAt(c1, c2, mode, s, nowMs);
        return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f};
    }

    private static int rgbAt(int c1, int c2, int mode, float s, long nowMs) {
        if (mode == DungeonExtrasConfig.BEAM_COLOR_FADE) {
            return lerpColor(c1, c2, s);
        }
        if (mode == DungeonExtrasConfig.BEAM_COLOR_ROTATING) {
            // One full rainbow along the beam, turning once every two seconds.
            float hue = (float) (((nowMs % 2000L) / 2000.0 + s) % 1.0);
            return java.awt.Color.HSBtoRGB(hue, 0.85f, 1f) & 0xFFFFFF;
        }
        return c1 & 0xFFFFFF;
    }

    /** The beam's alpha {@code elapsedMs} into its life: one value for the whole beam, falling linearly to 0 with Fade. */
    public static float alphaAt(float base, long elapsedMs, long durationMs, boolean fade) {
        if (!fade) {
            return base;
        }
        return base * (1f - Math.min(1f, Math.max(0f, elapsedMs / (float) Math.max(1L, durationMs))));
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

    /**
     * No part of the body is drawn within this many blocks (plus the body's own half diagonal) of the camera
     * (magebeamfade-1109). His own shot starts at his eye, so the body's near end sat inside the near plane and each
     * face became one huge flat triangle from the crosshair to the screen's corner (the "orange wedge" he saw fading).
     */
    static final double NEAR_CLEAR = 1.0;

    /** One body vertex, in world coordinates, with its packed ARGB. */
    interface Sink {
        void vertex(double x, double y, double z, int argb);
    }

    /** The render thread's sink: JOML into a scratch vector and one packed color put, no allocation per vertex. */
    private static final class RenderSink implements Sink {
        final org.joml.Vector3f pos = new org.joml.Vector3f();
        org.joml.Matrix4f matrix;
        VertexConsumer buffer;
        double camX, camY, camZ;

        @Override
        public void vertex(double x, double y, double z, int argb) {
            matrix.transformPosition((float) x, (float) y, (float) z, pos);
            buffer.addVertex(pos.x, pos.y, pos.z).setColor(argb);
            double dx = x - camX, dy = y - camY, dz = z - camZ;
            nearestSq = Math.min(nearestSq, dx * dx + dy * dy + dz * dz);
            int a = argb >>> 24;
            alphaLo = Math.min(alphaLo, a);
            alphaHi = Math.max(alphaHi, a);
            vertices++;
        }
    }

    private static final RenderSink SINK = new RenderSink();
    // Testkit evidence from the real draws since the last frameStatsForTest(): nearest vertex, alpha range, count.
    private static double nearestSq = Double.MAX_VALUE;
    private static int alphaLo = 255, alphaHi = 0, vertices;

    /** Testkit: {nearest drawn vertex to the camera in blocks, lowest alpha 0-255, highest alpha, vertex count} since
     *  the last call, then resets. */
    public static double[] frameStatsForTest() {
        double[] out = {Math.sqrt(nearestSq), alphaLo, alphaHi, vertices};
        nearestSq = Double.MAX_VALUE;
        alphaLo = 255;
        alphaHi = 0;
        vertices = 0;
        return out;
    }

    /** Fills the beam's per-frame draw state; false when nothing of it is left to draw. */
    private static boolean prepareBody(Beam beam, Vec3 cam, float widthBlocks, int c1, int c2, int mode, float a, long now) {
        if (beam.quads.isEmpty()) {
            return false;
        }
        beam.camX = cam.x;
        beam.camY = cam.y;
        beam.camZ = cam.z;
        beam.spanCount = clearSpans(beam.min(), beam.max(), cam, widthBlocks, beam.spans);
        beam.pieces = fillColors(c1, c2, mode, a, now, beam.cols);
        return beam.spanCount > 0;
    }

    /**
     * Writes the parts of the beam (as 0..1 fractions from start to end, in pairs) that lie farther than
     * {@link #NEAR_CLEAR} plus the body's half diagonal from the camera: none, one or two spans (two when the beam
     * passes by the camera). Returns how many numbers it wrote.
     */
    static int clearSpans(Vec3 s, Vec3 e, Vec3 cam, float widthBlocks, double[] out) {
        double r = NEAR_CLEAR + Math.max(0.01f, widthBlocks) * 0.5 * Math.sqrt(2.0);
        double dx = e.x - s.x, dy = e.y - s.y, dz = e.z - s.z;
        double fx = s.x - cam.x, fy = s.y - cam.y, fz = s.z - cam.z;
        double qa = dx * dx + dy * dy + dz * dz;
        double qb = 2 * (dx * fx + dy * fy + dz * fz);
        double disc = qb * qb - 4 * qa * (fx * fx + fy * fy + fz * fz - r * r);
        if (qa < 1.0E-9 || disc <= 0) {
            out[0] = 0;
            out[1] = 1;
            return 2;
        }
        double root = Math.sqrt(disc);
        double t1 = (-qb - root) / (2 * qa);
        double t2 = (-qb + root) / (2 * qa);
        int n = 0;
        if (t1 > 0) {
            out[n++] = 0;
            out[n++] = Math.min(1, t1);
        }
        if (t2 < 1) {
            out[n++] = Math.max(0, t2);
            out[n++] = 1;
        }
        return n;
    }

    /**
     * Packed ARGB at each piece boundary, all at one alpha. The faces used to be drawn twice (both windings, but the
     * fill type does not cull), which doubled every face's coverage; one draw at {@code 1 - (1 - a)^2} looks the same.
     */
    private static int fillColors(int c1, int c2, int mode, float a, long now, int[] out) {
        int pieces = mode == DungeonExtrasConfig.BEAM_COLOR_ROTATING ? ROTATING_PIECES : 1;
        int alpha = Math.round((1f - (1f - a) * (1f - a)) * 255f) << 24;
        for (int k = 0; k <= pieces; k++) {
            out[k] = alpha | rgbAt(c1, c2, mode, k / (float) pieces, now);
        }
        return pieces;
    }

    /**
     * Writes the body: only the faces that face the camera (a convex body's front faces never overlap on screen, so the
     * beam has one even alpha everywhere and its fade dims it evenly), each cut to the clear spans and, for Rotating,
     * into pieces, plus the end caps that face the camera (a cut end left open showed as a notch). Each quad's corners
     * 0 and 3 are at the beam's start and 1 and 2 at its end.
     */
    static void emitBody(List<Vec3[]> quads, Vec3 start, Vec3 end, double cx, double cy, double cz, double[] spans,
                         int spanCount, int[] cols, int pieces, Sink out) {
        // From inside the body's cross-section (a beam fired from his own eye) no side faces him: only a cap does.
        boolean anyFront = false;
        boolean solid = false;
        for (Vec3[] q : quads) {
            anyFront |= facesCamera(q, start, cx, cy, cz);
            solid |= Math.abs((q[0].x + q[3].x) * 0.5 - start.x) + Math.abs((q[0].y + q[3].y) * 0.5 - start.y)
                    + Math.abs((q[0].z + q[3].z) * 0.5 - start.z) > 1.0E-9;
        }
        double dx = end.x - start.x, dy = end.y - start.y, dz = end.z - start.z;
        for (int i = 0; solid && i < spanCount; i++) {
            double s = spans[i];
            double ax = start.x + dx * s, ay = start.y + dy * s, az = start.z + dz * s;
            double toward = dx * (ax - cx) + dy * (ay - cy) + dz * (az - cz);
            // A span's start cap faces back along the axis, its end cap forward.
            if (i % 2 == 0 ? toward <= 0 : toward >= 0) {
                continue;
            }
            int k = Math.min(pieces - 1, (int) (s * pieces));
            int c = mix(cols[k], cols[k + 1], s * pieces - k);
            for (Vec3[] q : quads) {
                out.vertex(ax, ay, az, c);
                corner(out, q[0], q[1], s, c);
                corner(out, q[3], q[2], s, c);
                corner(out, q[3], q[2], s, c);
            }
        }
        for (Vec3[] q : quads) {
            if (!anyFront || !facesCamera(q, start, cx, cy, cz)) {
                continue;
            }
            for (int i = 0; i + 1 < spanCount; i += 2) {
                for (int k = 0; k < pieces; k++) {
                    double s0 = Math.max(spans[i], k / (double) pieces);
                    double s1 = Math.min(spans[i + 1], (k + 1) / (double) pieces);
                    if (s1 <= s0) {
                        continue;
                    }
                    int ca = mix(cols[k], cols[k + 1], s0 * pieces - k);
                    int cb = mix(cols[k], cols[k + 1], s1 * pieces - k);
                    corner(out, q[0], q[1], s0, ca);
                    corner(out, q[0], q[1], s1, cb);
                    corner(out, q[3], q[2], s1, cb);
                    corner(out, q[3], q[2], s0, ca);
                }
            }
        }
    }

    /** Outward normal (the start edge's middle less the axis start; zero for Flat, always drawn) toward the camera. */
    private static boolean facesCamera(Vec3[] q, Vec3 start, double cx, double cy, double cz) {
        double nx = (q[0].x + q[3].x) * 0.5 - start.x;
        double ny = (q[0].y + q[3].y) * 0.5 - start.y;
        double nz = (q[0].z + q[3].z) * 0.5 - start.z;
        return nx * (q[0].x - cx) + ny * (q[0].y - cy) + nz * (q[0].z - cz) <= 1.0E-9;
    }

    private static void corner(Sink out, Vec3 a, Vec3 b, double t, int argb) {
        out.vertex(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, argb);
    }

    private static int mix(int a, int b, double t) {
        int r = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            int ca = (a >>> sh) & 0xFF;
            r |= ((int) Math.round(ca + (((b >>> sh) & 0xFF) - ca) * t) & 0xFF) << sh;
        }
        return r;
    }

    /** Testkit: every body vertex a frame would draw for a beam from {@code start} to {@code end} seen from {@code cam},
     *  as x, y, z, alpha (0-255) in a row, through the same code as the real draw. */
    public static double[] bodyForTest(Vec3 cam, Vec3 start, Vec3 end, int shape, float widthBlocks, int argb, float alpha) {
        double[] spans = new double[4];
        int n = clearSpans(start, end, cam, widthBlocks, spans);
        int[] cols = new int[ROTATING_PIECES + 1];
        int pieces = fillColors(argb, argb, DungeonExtrasConfig.BEAM_COLOR_SOLID, alpha, 0L, cols);
        List<Double> got = new ArrayList<>();
        emitBody(beamQuads(start, end, shape, widthBlocks), start, end, cam.x, cam.y, cam.z, spans, n, cols, pieces,
                (x, y, z, c) -> {
                    got.add(x);
                    got.add(y);
                    got.add(z);
                    got.add((double) (c >>> 24));
                });
        return got.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
