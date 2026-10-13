package com.killer560.hub.trail;

import com.killer560.hub.compat.McRender;
import com.killer560.hub.namechanger.NameChangerConfig;
import com.killer560.hub.namechanger.NameColor;
import com.killer560.hub.util.ChromaWave;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Util;
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
 */
public final class RibbonTrail {

    /** The most points the ribbon keeps (ring buffer); a faster mover gets a shorter ribbon instead of more points. */
    public static final int MAX_POINTS = 256;
    /** Blocks between two laid points. */
    public static final double SPACING = 0.2;
    /** A jump further than this between frames (a teleport, an AOTV) starts a new ribbon instead of a long streak. */
    private static final double TELEPORT = 6.0;
    /** Waist height as a share of eye height (1.62 standing gives 0.89, sneaking 1.27 gives 0.70). */
    private static final double WAIST = 0.55;

    // Ring buffer of laid points, oldest at (head - count).
    private static final double[] rx = new double[MAX_POINTS];
    private static final double[] ry = new double[MAX_POINTS];
    private static final double[] rz = new double[MAX_POINTS];
    private static final long[] rt = new long[MAX_POINTS];
    private static int head = 0;
    private static int count = 0;
    private static ClientLevel lastLevel;

    // Per-frame scratch, oldest first, the live anchor last (MAX_POINTS + 1 entries).
    private static final double[] px = new double[MAX_POINTS + 1];
    private static final double[] py = new double[MAX_POINTS + 1];
    private static final double[] pz = new double[MAX_POINTS + 1];
    private static final float[] age = new float[MAX_POINTS + 1];
    private static final float[] along = new float[MAX_POINTS + 1];

    /** Two vertices (left, right) per point, each x y z r g b a. */
    private static final float[] verts = new float[(MAX_POINTS + 1) * 2 * 7];
    private static int vertPoints = 0;
    private static final McRender.Geometry GEOMETRY = RibbonTrail::emit;

    // Test hooks (read by the testkit).
    private static long framesDrawn = 0;
    private static int lastPointCount = 0;
    private static double lastLength = 0;

    private RibbonTrail() {
    }

    public static long framesDrawn() {
        return framesDrawn;
    }

    /** Points (laid + the anchor) in the last drawn frame; 0 when the last frame drew nothing. */
    public static int lastPointCount() {
        return lastPointCount;
    }

    /** Length in blocks of the band drawn last frame; 0 when it drew nothing. */
    public static double lastLength() {
        return lastLength;
    }

    /** Laid points currently held. */
    public static int pointCount() {
        return count;
    }

    public static void clear() {
        head = 0;
        count = 0;
        lastPointCount = 0;
        lastLength = 0;
    }

    private static void push(double x, double y, double z, long t) {
        rx[head] = x;
        ry[head] = y;
        rz[head] = z;
        rt[head] = t;
        head = (head + 1) % MAX_POINTS;
        if (count < MAX_POINTS) {
            count++;
        }
    }

    private static int idx(int fromOldest) {
        return Math.floorMod(head - count + fromOldest, MAX_POINTS);
    }

    /** Lays new points and draws; called every frame while the trail is on in Ribbon style. */
    static void render(LevelRenderContext context, TrailConfig cfg) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (level != lastLevel) {
            clear();
            lastLevel = level;
        }
        if (player == null || level == null) {
            clear();
            return;
        }
        long now = Util.getMillis();
        long lifeMs = Math.max(1L, Math.round(cfg.getRibbonSeconds() * 1000.0));

        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 feet = player.getPosition(partial);
        double ax = feet.x;
        double ay = feet.y + player.getEyeHeight() * WAIST;
        double az = feet.z;

        // Lay points every SPACING blocks along the way from the newest point to the anchor.
        if (count == 0) {
            push(ax, ay, az, now);
        } else {
            int n = idx(count - 1);
            double lx = rx[n], ly = ry[n], lz = rz[n];
            double dx = ax - lx, dy = ay - ly, dz = az - lz;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > TELEPORT) {
                clear();
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

        if (!cfg.isShowInFirstPerson() && client.options.getCameraType().isFirstPerson()) {
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
        double half = cfg.getRibbonWidth() / 2.0;
        float baseAlpha = cfg.getOpacityPercent() / 100f;
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
            int rgb = colorAt(cfg, u, now);
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
        McRender.inCameraSpace(context, RenderTypes.debugFilledBox(), GEOMETRY);
    }

    private static int put(int o, float x, float y, float z, float r, float g, float b, float a) {
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
    private static void emit(PoseStack.Pose pose, VertexConsumer buffer) {
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

    private static void v(VertexConsumer buffer, Matrix4f m, int o) {
        buffer.addVertex(m, verts[o], verts[o + 1], verts[o + 2])
                .setColor(verts[o + 3], verts[o + 4], verts[o + 5], verts[o + 6]);
    }

    /** Colour at {@code u} along the band (1 = at the player, 0 = the tail end). 0xRRGGBB. */
    static int colorAt(TrailConfig cfg, float u, long now) {
        switch (cfg.getColorMode()) {
            case CHROMA: {
                long period = (long) ChromaWave.BASE_PERIOD_MS;
                float t = (ChromaWave.now() % period) / (float) period;
                // The colours flow from the player toward the tail as time passes.
                return ChromaWave.hsv((1f - u) * 0.6f - t, 1f, 1f);
            }
            case NAME_FADE: {
                NameChangerConfig nc = NameChangerConfig.getInstance();
                int from = nc.getOwnColor();
                if (from != NameColor.NONE) {
                    int to = nc.isOwnColorFadeEnabled() && nc.getOwnColorFadeTo() != NameColor.NONE
                            ? nc.getOwnColorFadeTo() : from;
                    return lerp(to, from, u);
                }
                break;
            }
            default:
                break;
        }
        if (cfg.getColorCount() >= 3) {
            return u >= 0.5f ? lerp(cfg.getMidColor(), cfg.getHeadColor(), (u - 0.5f) * 2f)
                    : lerp(cfg.getTailColor(), cfg.getMidColor(), u * 2f);
        }
        return lerp(cfg.getTailColor(), cfg.getHeadColor(), u);
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
