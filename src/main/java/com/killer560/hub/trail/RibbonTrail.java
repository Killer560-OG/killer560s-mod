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
 * The Ribbon trail style (killer560, 2026-10-13, a Roblox "Using Trails" picture: "can you make trails have an option
 * to do something like this as well?"): one continuous band streaming behind the player from about waist height, the
 * head colour at the body running to the tail colour at the end, fading to nothing there.
 *
 * <p>Points are taken from the player's INTERPOLATED position every frame, but only every {@link #SPACING} blocks, so
 * the band does not jitter with frame rate or idle wobble; each point remembers when it was laid and expires after
 * Ribbon Length seconds, so standing still lets the band shrink back into the player over that time. At draw time the
 * points (oldest first, then the live anchor) are smoothed with two [1 2 1] passes, and each one gets a sideways
 * vector perpendicular to both the path and the line to the camera: from behind (F5) that lies flat, from the side
 * it stands up, so the band reads at full width from wherever it is seen.
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
    /** A jump further than this between frames (a teleport, an AOTV) starts a new ribbon instead of a long streak. */
    private static final double TELEPORT = 6.0;
    /** Waist height as a share of eye height (1.62 standing gives 0.89, sneaking 1.27 gives 0.70). */
    private static final double WAIST = 0.55;
    /** Blocks behind him (against his movement, or his facing when still) where the band starts. */
    private static final double BACK_OFFSET = 0.3;
    /** Length of the leash that finds "behind him"; longer turns the start of the band round more slowly. */
    private static final double LEASH = 0.5;

    // Ring buffer of laid points, oldest at (head - count).
    private final double[] rx = new double[MAX_POINTS];
    private final double[] ry = new double[MAX_POINTS];
    private final double[] rz = new double[MAX_POINTS];
    private final long[] rt = new long[MAX_POINTS];
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

    /** Two vertices (left, right) per point, each x y z r g b a. */
    private final float[] verts = new float[(MAX_POINTS + 1) * 2 * 7];
    private int vertPoints = 0;
    private final McRender.Geometry geometry = this::emit;

    // Test hooks (read by the testkit).
    private long framesDrawn = 0;
    private int lastPointCount = 0;
    private double lastLength = 0;

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
        head = 0;
        count = 0;
        lastPointCount = 0;
        lastLength = 0;
    }

    private void push(double x, double y, double z, long t) {
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
        double ay = feet.y + player.getEyeHeight() * WAIST;

        // Lay points every SPACING blocks along the way from the newest point to the anchor.
        if (count == 0) {
            push(ax, ay, az, now);
        } else {
            int n = idx(count - 1);
            double lx = rx[n], ly = ry[n], lz = rz[n];
            double dx = ax - lx, dy = ay - ly, dz = az - lz;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > TELEPORT) {
                reset();
                push(ax, ay, az, now);
            } else if (d >= SPACING) {
                double ux = dx / d, uy = dy / d, uz = dz / d;
                int steps = (int) Math.min(MAX_POINTS, Math.floor(d / SPACING));
                for (int i = 1; i <= steps; i++) {
                    push(lx + ux * SPACING * i, ly + uy * SPACING * i, lz + uz * SPACING * i, now);
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
            n++;
        }
        if (n == 0 || dist(px[n - 1], py[n - 1], pz[n - 1], ax, ay, az) > 1.0e-3) {
            px[n] = ax;
            py[n] = ay;
            pz[n] = az;
            age[n] = 0f;
            n++;
        }
        if (n < 2) {
            lastPointCount = 0;
            lastLength = 0;
            return;
        }
        smooth(px, py, pz, n);
        smooth(px, py, pz, n);

        // Arc length from the tail, normalised: 0 at the tail, 1 at the anchor.
        double total = 0;
        along[0] = 0f;
        for (int i = 1; i < n; i++) {
            total += dist(px[i - 1], py[i - 1], pz[i - 1], px[i], py[i], pz[i]);
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

        Vec3 cam = McRender.cameraPos(context);
        double half = spec.width() / 2.0;
        float baseAlpha = spec.opacity() / 100f;
        double lastSx = 0, lastSy = 0, lastSz = 0;
        int o = 0;
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - 1);
            int b = Math.min(n - 1, i + 1);
            double tx = px[b] - px[a], ty = py[b] - py[a], tz = pz[b] - pz[a];
            double vx = cam.x - px[i], vy = cam.y - py[i], vz = cam.z - pz[i];
            // side = tangent x to-camera
            double cx = ty * vz - tz * vy;
            double cy = tz * vx - tx * vz;
            double cz = tx * vy - ty * vx;
            double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (len < 1.0e-6) {
                // Looking straight down the path: lie flat (tangent x up).
                cx = -tz;
                cy = 0;
                cz = tx;
                len = Math.sqrt(cx * cx + cz * cz);
                if (len < 1.0e-6) {
                    cx = 1;
                    cz = 0;
                    len = 1;
                }
            }
            cx /= len;
            cy /= len;
            cz /= len;
            // Keep neighbouring sides pointing the same way, or the band twists through itself.
            if (i > 0 && cx * lastSx + cy * lastSy + cz * lastSz < 0) {
                cx = -cx;
                cy = -cy;
                cz = -cz;
            }
            lastSx = cx;
            lastSy = cy;
            lastSz = cz;

            float u = along[i];
            float fade = Math.min(Math.max(0f, 1f - age[i]), u);
            float alpha = baseAlpha * fade;
            int rgb = colorAt(spec, fadeEnds, u);
            float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, bl = (rgb & 0xFF) / 255f;
            float lx = (float) (px[i] - cx * half), ly = (float) (py[i] - cy * half), lz = (float) (pz[i] - cz * half);
            float qx = (float) (px[i] + cx * half), qy = (float) (py[i] + cy * half), qz = (float) (pz[i] + cz * half);
            o = put(o, lx, ly, lz, r, g, bl, alpha);
            o = put(o, qx, qy, qz, r, g, bl, alpha);
        }
        vertPoints = n;
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

    /** One quad per segment, in both windings so it reads the same from either side whatever the culling. */
    private void emit(PoseStack.Pose pose, VertexConsumer buffer) {
        Matrix4f m = pose.pose();
        for (int i = 0; i + 1 < vertPoints; i++) {
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
     *  head and tail colours, or null to fall back to the spec's own colours. */
    static int colorAt(TrailSpec spec, int[] fadeEnds, float u) {
        switch (spec.mode()) {
            case CHROMA: {
                long period = (long) ChromaWave.BASE_PERIOD_MS;
                float t = (ChromaWave.now() % period) / (float) period;
                // The colours flow from the player toward the tail as time passes.
                return ChromaWave.hsv((1f - u) * 0.6f - t, 1f, 1f);
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

    /** One [1 2 1] / 4 pass over the interior points; the two ends stay where they are. */
    private static void smooth(double[] x, double[] y, double[] z, int n) {
        if (n < 3) {
            return;
        }
        double prevX = x[0], prevY = y[0], prevZ = z[0];
        for (int i = 1; i < n - 1; i++) {
            double cx = x[i], cy = y[i], cz = z[i];
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
