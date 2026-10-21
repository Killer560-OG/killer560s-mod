package com.killer560.hub.trail;

import com.killer560.hub.compat.McRender;
import com.killer560.hub.util.ChromaWave;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The Ribbon Trail (killer560, 2026-10-13, a Roblox "Using Trails" picture: "can you make trails have an option to do
 * something like this as well?"): one continuous band streaming behind the player, the head colour at the body running
 * to the tail colour at the end, fading to nothing there.
 *
 * <p>The band is a WORLD-FIXED vertical strip like a Roblox Trail (killer560, 2026-10-14: "it looks to be horizontal and
 * depending on how i move my mouse sometimes it is vertical" / "I want the ribbon to be vertical behind them, not
 * horizontal or any angle"): each point of the path gets two vertices with the SAME x and z, one at the band's bottom
 * and one at its top ({@link #bandBottom}), so nothing about it depends on the camera, pitch, velocity or lean. The
 * old camera-facing billboard flipped between flat and upright as the view swung.
 *
 * <p>Points are taken from the player's INTERPOLATED position every frame, but only every {@link #SPACING} blocks, so
 * the band does not jitter with frame rate or idle wobble; each point remembers when it was laid and expires after
 * Ribbon Length seconds, so standing still lets the band shrink back into the player over that time. At draw time the
 * points (oldest first, then the live anchor) are smoothed with two [1 2 1] passes; the points sit at the feet and
 * the band stands Ribbon Height tall above them.
 *
 * <p>Everything lives in preallocated arrays capped at {@link #MAX_POINTS}: no allocation per frame, one draw call.
 * The finished vertices are written into {@link #verts} before submitting, because on 26.2 the geometry callback runs
 * later in the frame ({@link McRender#inCameraSpace}).
 *
 * <p>One instance per player: {@link #LOCAL} is your own ribbon, and {@link RemoteTrails} keeps one for each player
 * whose shared trail is drawn (2026-10-13). The static test hooks read {@link #LOCAL}.
 */
public final class RibbonTrail {

    /** Your own ribbon. */
    public static final RibbonTrail LOCAL = new RibbonTrail();

    /** The most points the ribbon keeps (ring buffer); a faster mover gets a shorter ribbon instead of more points. */
    public static final int MAX_POINTS = 256;
    /** Blocks between two laid points. */
    public static final double SPACING = 0.2;
    /** The band's middle as a share of eye height (1.62 standing gives 0.75, so a 1-block band runs 0.25 to 1.25). */
    private static final double MIDDLE = 0.463;
    /** The band's bottom never goes below this, however tall it is. */
    private static final double MIN_BOTTOM = 0.05;
    /** Blocks behind him (against his movement, or his facing when still) where the band starts. */
    private static final double BACK_OFFSET = 0.3;
    /** Length of the leash that finds "behind him"; longer turns the start of the band round more slowly. */
    private static final double LEASH = 0.5;

    // Ring buffer of laid points, oldest at (head - count).
    private final double[] rx = new double[MAX_POINTS];
    private final double[] ry = new double[MAX_POINTS];
    private final double[] rz = new double[MAX_POINTS];
    private final long[] rt = new long[MAX_POINTS];
    /** True for the first point of a piece that starts after a teleport in Break mode (no band joins it to the one before). */
    private final boolean[] rbreak = new boolean[MAX_POINTS];
    private int head = 0;
    private int count = 0;
    private ClientLevel lastLevel;
    // The leash point dragged after his feet, and the unit "behind him" direction it gives.
    private double leashX;
    private double leashZ;
    private double backX;
    private double backZ;
    private boolean hasBack = false;

    // Per-frame scratch, oldest first, the live anchor last (MAX_POINTS + 1 entries).
    private final double[] px = new double[MAX_POINTS + 1];
    private final double[] py = new double[MAX_POINTS + 1];
    private final double[] pz = new double[MAX_POINTS + 1];
    private final float[] age = new float[MAX_POINTS + 1];
    private final float[] along = new float[MAX_POINTS + 1];
    private final boolean[] brk = new boolean[MAX_POINTS + 1];
    private double lastFeetX;
    private double lastFeetY;
    private double lastFeetZ;
    private boolean hasLastFeet = false;

    /** Two vertices (bottom, top) per point, each x y z r g b a, in world space. */
    private final float[] verts = new float[(MAX_POINTS + 1) * 2 * 7];
    private int vertPoints = 0;
    private final McRender.Geometry geometry = this::emit;

    // Test hooks (read by the testkit).
    private long framesDrawn = 0;
    private int lastPointCount = 0;
    private double lastLength = 0;
    private int lastBreaks = 0;
    private int lastSegments = 0;
    private int teleports = 0;

    RibbonTrail() {
    }

    /** Frames your own ribbon was drawn. */
    public static long framesDrawn() {
        return LOCAL.framesDrawn;
    }

    /** Points (laid + the anchor) in your ribbon's last drawn frame; 0 when the last frame drew nothing. */
    public static int lastPointCount() {
        return LOCAL.lastPointCount;
    }

    /** Length in blocks of your band drawn last frame; 0 when it drew nothing. */
    public static double lastLength() {
        return LOCAL.lastLength;
    }

    /** Laid points your ribbon currently holds. */
    public static int pointCount() {
        return LOCAL.count;
    }

    public static void clear() {
        LOCAL.reset();
    }

    /** Teleports (a jump past {@link TeleportMode#MIN_JUMP} in one frame) your ribbon has seen since it was last cleared. */
    public static int teleportsSeen() {
        return LOCAL.teleports;
    }

    /** Gaps (Break mode teleports) in your ribbon's last drawn frame. */
    public static int lastBreaks() {
        return LOCAL.lastBreaks;
    }

    /** Quads in your ribbon's last drawn frame: one per joined pair of points, none across a gap. */
    public static int lastSegments() {
        return LOCAL.lastSegments;
    }

    /** Your ribbon's last drawn vertices (see {@link #vertices()}). */
    public static float[] lastVertices() {
        return LOCAL.vertices();
    }

    /** World-space x y z of every vertex of the last drawn frame, bottom then top for each point, oldest first;
     *  empty when the last frame drew nothing. The test hook for "vertical, and the same from every camera angle". */
    public float[] vertices() {
        int pts = lastPointCount == 0 ? 0 : vertPoints;
        float[] out = new float[pts * 2 * 3];
        for (int v = 0; v < pts * 2; v++) {
            System.arraycopy(verts, v * 7, out, v * 3, 3);
        }
        return out;
    }

    /** Height of the band's bottom above the feet for a player with {@code eyeHeight} and a band {@code height} tall. */
    static double bandBottom(double eyeHeight, double height) {
        return Math.max(MIN_BOTTOM, eyeHeight * MIDDLE - height / 2.0);
    }

    /** This ribbon's frames drawn (the shared trails' test hook). */
    public long frames() {
        return framesDrawn;
    }

    /** Points in this ribbon's last drawn frame. */
    public int drawnPoints() {
        return lastPointCount;
    }

    /** Length in blocks of this ribbon's last drawn band. */
    public double drawnLength() {
        return lastLength;
    }

    void reset() {
        hasBack = false;
        hasLastFeet = false;
        teleports = 0;
        lastBreaks = 0;
        lastSegments = 0;
        head = 0;
        count = 0;
        lastPointCount = 0;
        lastLength = 0;
    }

    private void push(double x, double y, double z, long t, boolean gap) {
        rbreak[head] = gap;
        rx[head] = x;
        ry[head] = y;
        rz[head] = z;
        rt[head] = t;
        head = (head + 1) % MAX_POINTS;
        if (count < MAX_POINTS) {
            count++;
        }
    }

    private int idx(int fromOldest) {
        return Math.floorMod(head - count + fromOldest, MAX_POINTS);
    }

    /**
     * Lays new points behind {@code player} and draws; called every frame while this ribbon is shown. {@code spec} is
     * the look ({@code length} is the seconds a point lasts), {@code fadeEnds} the head/tail colours Name Fade uses (null
     * falls back to the spec's colours), and {@code hidden} lays points but draws nothing (your own ribbon in first
     * person with Show In First Person off).
     */
    void draw(LevelRenderContext context, Player player, TrailSpec spec, int[] fadeEnds, boolean hidden) {
        draw(context, player, spec, fadeEnds, hidden, RibbonTrailConfig.getInstance().getTeleportMode());
    }

    /** As above, with {@code onTeleport} deciding what the ribbon does across a teleport (see {@link TeleportMode}). */
    void draw(LevelRenderContext context, Player player, TrailSpec spec, int[] fadeEnds, boolean hidden,
              TeleportMode onTeleport) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level != lastLevel) {
            reset();
            lastLevel = level;
        }
        if (player == null || level == null || spec == null) {
            reset();
            return;
        }
        long now = Util.getMillis();
        long lifeMs = Math.max(1L, Math.round(spec.length() * 1000.0));

        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 feet = player.getPosition(partial);
        // A teleport moves him a long way in one frame. The leash goes with him, so "behind" keeps its direction and the
        // anchor moves by exactly the jump.
        boolean teleported = hasLastFeet
                && TeleportMode.isTeleport(dist(lastFeetX, lastFeetY, lastFeetZ, feet.x, feet.y, feet.z));
        if (teleported && hasBack) {
            leashX += feet.x - lastFeetX;
            leashZ += feet.z - lastFeetZ;
            teleports++;
        }
        lastFeetX = feet.x;
        lastFeetY = feet.y;
        lastFeetZ = feet.z;
        hasLastFeet = true;
        // Behind him, not inside his body (killer560, 2026-10-13: from the side the red end showed in front of his
        // legs). "Behind" comes from a point on a short leash dragged after his feet: it always lies back along the way
        // he came, swings round smoothly as he turns (so the points laid on the way keep the band continuous), and
        // stays put while he stands still. Before he has moved at all it starts behind his back.
        if (!hasBack) {
            double bodyYaw = Math.toRadians(Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot));
            leashX = feet.x + Math.sin(bodyYaw) * LEASH;
            leashZ = feet.z - Math.cos(bodyYaw) * LEASH;
            hasBack = true;
        }
        double lx0 = leashX - feet.x, lz0 = leashZ - feet.z;
        double ll = Math.sqrt(lx0 * lx0 + lz0 * lz0);
        if (ll > LEASH) {
            leashX = feet.x + lx0 / ll * LEASH;
            leashZ = feet.z + lz0 / ll * LEASH;
        }
        if (ll > 1.0e-4) {
            backX = lx0 / ll;
            backZ = lz0 / ll;
        }
        double ax = feet.x + backX * BACK_OFFSET;
        double az = feet.z + backZ * BACK_OFFSET;
        double ay = feet.y;

        // Lay points every SPACING blocks along the way from the newest point to the anchor.
        if (count == 0) {
            push(ax, ay, az, now, false);
        } else {
            int n = idx(count - 1);
            double lx = rx[n], ly = ry[n], lz = rz[n];
            double dx = ax - lx, dy = ay - ly, dz = az - lz;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (teleported && onTeleport == TeleportMode.BREAK) {
                push(ax, ay, az, now, true);
            } else if (teleported) {
                // Connect and Arc: a bridge of points from where he left to where he landed, ending on the anchor.
                int steps = TeleportMode.bridgeSteps(d, SPACING);
                for (int i = 1; i <= steps; i++) {
                    double t = i / (double) steps;
                    push(lx + dx * t, ly + dy * t + onTeleport.lift(t, d), lz + dz * t, now, false);
                }
            } else if (d >= SPACING) {
                double ux = dx / d, uy = dy / d, uz = dz / d;
                int steps = (int) Math.min(MAX_POINTS, Math.floor(d / SPACING));
                for (int i = 1; i <= steps; i++) {
                    push(lx + ux * SPACING * i, ly + uy * SPACING * i, lz + uz * SPACING * i, now, false);
                }
            }
        }
        // Expire from the tail.
        while (count > 0 && now - rt[idx(0)] > lifeMs) {
            count--;
        }

        if (hidden) {
            lastPointCount = 0;
            lastLength = 0;
            return;
        }

        // Oldest first, then the live anchor (skipped if it sits on the newest point).
        int n = 0;
        for (int i = 0; i < count; i++) {
            int k = idx(i);
            px[n] = rx[k];
            py[n] = ry[k];
            pz[n] = rz[k];
            age[n] = (now - rt[k]) / (float) lifeMs;
            brk[n] = rbreak[k] && i > 0;
            n++;
        }
        if (n == 0 || dist(px[n - 1], py[n - 1], pz[n - 1], ax, ay, az) > 1.0e-3) {
            px[n] = ax;
            py[n] = ay;
            pz[n] = az;
            age[n] = 0f;
            brk[n] = false;
            n++;
        }
        if (n < 2) {
            lastPointCount = 0;
            lastLength = 0;
            return;
        }
        smooth(px, py, pz, n, brk);
        smooth(px, py, pz, n, brk);

        // Arc length from the tail, normalised: 0 at the tail, 1 at the anchor.
        double total = 0;
        along[0] = 0f;
        for (int i = 1; i < n; i++) {
            // A gap (Break mode) adds no length: the colours run on across it as if the pieces were one band.
            if (!brk[i]) {
                total += dist(px[i - 1], py[i - 1], pz[i - 1], px[i], py[i], pz[i]);
            }
            along[i] = (float) total;
        }
        if (total < 0.05) {
            lastPointCount = 0;
            lastLength = 0;
            return;
        }
        for (int i = 0; i < n; i++) {
            along[i] /= (float) total;
        }

        // Strictly vertical: bottom and top share the point's x and z.
        double bottom = bandBottom(player.getEyeHeight(), spec.width());
        double top = bottom + spec.width();
        float baseAlpha = spec.opacity() / 100f;
        // The chroma phase is the same for every point of a frame: read the clock once, not once per point.
        float chromaT = 0f;
        if (spec.mode() == RibbonTrailConfig.ColorMode.CHROMA) {
            long period = (long) ChromaWave.BASE_PERIOD_MS;
            chromaT = (ChromaWave.now() % period) / (float) period;
        }
        int o = 0;
        for (int i = 0; i < n; i++) {
            float u = along[i];
            float fade = Math.min(Math.max(0f, 1f - age[i]), u);
            float alpha = baseAlpha * fade;
            int rgb = colorAt(spec, fadeEnds, u, chromaT);
            float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, bl = (rgb & 0xFF) / 255f;
            float x = (float) px[i], z = (float) pz[i];
            o = put(o, x, (float) (py[i] + bottom), z, r, g, bl, alpha);
            o = put(o, x, (float) (py[i] + top), z, r, g, bl, alpha);
        }
        vertPoints = n;
        int breaks = 0;
        for (int i = 1; i < n; i++) {
            if (brk[i]) {
                breaks++;
            }
        }
        lastBreaks = breaks;
        lastSegments = n - 1 - breaks;
        lastPointCount = n;
        lastLength = total;
        framesDrawn++;
        McRender.inCameraSpace(context, RenderTypes.debugFilledBox(), geometry);
    }

    private int put(int o, float x, float y, float z, float r, float g, float b, float a) {
        verts[o] = x;
        verts[o + 1] = y;
        verts[o + 2] = z;
        verts[o + 3] = r;
        verts[o + 4] = g;
        verts[o + 5] = b;
        verts[o + 6] = a;
        return o + 7;
    }

    /** One quad per segment, in both windings so it reads the same from either side whatever the culling (double-sided). */
    private void emit(PoseStack.Pose pose, VertexConsumer buffer) {
        Matrix4f m = pose.pose();
        for (int i = 0; i + 1 < vertPoints; i++) {
            if (brk[i + 1]) {
                continue;
            }
            int l0 = i * 14, r0 = l0 + 7, l1 = l0 + 14, r1 = l0 + 21;
            v(buffer, m, l0);
            v(buffer, m, r0);
            v(buffer, m, r1);
            v(buffer, m, l1);
            v(buffer, m, l1);
            v(buffer, m, r1);
            v(buffer, m, r0);
            v(buffer, m, l0);
        }
    }

    private void v(VertexConsumer buffer, Matrix4f m, int o) {
        buffer.addVertex(m, verts[o], verts[o + 1], verts[o + 2])
                .setColor(verts[o + 3], verts[o + 4], verts[o + 5], verts[o + 6]);
    }

    /** Colour at {@code u} along the band (1 = at the player, 0 = the tail end). 0xRRGGBB. {@code fadeEnds}: Name Fade's
     *  head and tail colours, or null to fall back to the spec's own colours. {@code chromaT} is Chroma's 0..1 phase. */
    static int colorAt(TrailSpec spec, int[] fadeEnds, float u, float chromaT) {
        switch (spec.mode()) {
            case CHROMA: {
                // The colours flow from the player toward the tail as time passes.
                return ChromaWave.hsv((1f - u) * 0.6f - chromaT, 1f, 1f);
            }
            case NAME_FADE: {
                if (fadeEnds != null && fadeEnds.length >= 2) {
                    return lerp(fadeEnds[fadeEnds.length - 1], fadeEnds[0], u);
                }
                break;
            }
            default:
                break;
        }
        int[] c = spec.colors();
        if (c.length >= 3) {
            return u >= 0.5f ? lerp(c[1], c[0], (u - 0.5f) * 2f) : lerp(c[2], c[1], u * 2f);
        }
        return lerp(c[c.length - 1], c[0], u);
    }

    /** {@code a} at k = 0 to {@code b} at k = 1. 0xRRGGBB. */
    private static int lerp(int a, int b, float k) {
        k = Math.max(0f, Math.min(1f, k));
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * k);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * k);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * k);
        return r << 16 | g << 8 | bl;
    }

    /** One [1 2 1] / 4 pass over the interior points; the two ends, and the ends of each piece split by a gap, stay put. */
    private static void smooth(double[] x, double[] y, double[] z, int n, boolean[] gap) {
        if (n < 3) {
            return;
        }
        double prevX = x[0], prevY = y[0], prevZ = z[0];
        for (int i = 1; i < n - 1; i++) {
            double cx = x[i], cy = y[i], cz = z[i];
            if (gap[i] || gap[i + 1]) {
                prevX = cx;
                prevY = cy;
                prevZ = cz;
                continue;
            }
            x[i] = (prevX + 2 * cx + x[i + 1]) * 0.25;
            y[i] = (prevY + 2 * cy + y[i + 1]) * 0.25;
            z[i] = (prevZ + 2 * cz + z[i + 1]) * 0.25;
            prevX = cx;
            prevY = cy;
            prevZ = cz;
        }
    }

    private static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
