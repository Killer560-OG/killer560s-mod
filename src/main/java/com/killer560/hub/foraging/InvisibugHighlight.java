package com.killer560.hub.foraging;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.compat.McPackets;
import com.killer560.hub.compat.McRender;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.shared.SharedWriter;
import com.killer560.hub.util.BoxVerts;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Invisibug Highlight (Galatea Hunting): an Invisibug is an invisible, unnamed armor stand that circles (radius about 0.5,
 * same height, a turn in about 90 ticks) around a fixed spot, and the server marks it with ONE crit particle every 5 ticks
 * (recorded 11-23 and 11-24, killer560s-mod-logs/invisibug-finder-1124-state.md). The stand is sent only within about 16
 * blocks and the crits to about 24, so the particles are the farthest signal. Each particle is chained onto the chain it
 * continues (same height, a short step, 3 to 12 ticks later); from the 3rd point a circle of the recorded radius is fitted
 * through the oldest, middle and newest kept point and its center boxed (refitted with every point, 7 kept). A hit's crits
 * (counts above 1, scattered heights) never qualify. A bug goes 12 ticks after its particles stop, and at once when the catch
 * line comes (the box nearest the player). Bugs come back to the same spots, so every spot a bug was confirmed at is kept in
 * a shared record (bounded) and drawn as a ring with a post ("possible Invisibug") while no live box is there; a crit at a
 * known spot is boxed from its first point. Display only: sends nothing, both builds; Through Walls is cheat-only. Off by
 * default; reads only on Galatea.
 */
public final class InvisibugHighlight {

    private static final int AMBER = 0xFFFFAA00;
    private static final int BUGS = 8;
    private static final int POINTS = 7;
    private static final int FIRST = 3;
    private static final long EXPIRE = 12;
    private static final long MUTE = 20;
    private static final int MAX_SPOTS = 64;
    private static final String CAUGHT = "The Invisibug was more visible than it thought";
    /** Spots (x, crit y, z) where bugs circled in his Galatea scans of 11-23 and 11-24. */
    private static final double[] SEED = {-612.80, 114.00, 64.10, -618.50, 119.60, 94.50, -718.81, 99.60, 29.10,
            -737.50, 123.60, -6.50, -556.90, 116.60, 71.48, -565.21, 118.40, 21.50, -704.00, 124.70, 74.90,
            -592.90, 118.90, 87.58, -630.70, 117.60, 83.53};

    public static final IslandConfig CFG = IslandConfig.of("foraging", "invisibug")
            .num("color", 0, Integer.MIN_VALUE, Integer.MAX_VALUE)
            .flag("spots", true)
            .flag("throughWalls", false);

    /** Per chain: the last {@link #POINTS} points (x, z pairs, oldest first), y, time of the last point, points seen. Main thread only. */
    private static final double[][] CHAIN_XZ = new double[BUGS][POINTS * 2];
    private static final double[] CHAIN_Y = new double[BUGS];
    private static final long[] CHAIN_T = new long[BUGS];
    private static final int[] CHAIN_N = new int[BUGS];
    private static final double[][] CENTER = new double[BUGS][2];
    private static final boolean[] OK = new boolean[BUGS];
    /** Spots just caught (x, y, z, until): their late crits start no new box. */
    private static final double[][] MUTED = new double[4][4];
    /** Known spots (x, y, z each), the first {@link #SEED} ones bundled; their drawn marks, built once per spot. */
    private static final double[] SPOTS = new double[MAX_SPOTS * 3];
    private static final Vec3[][] SPOT_MARK = new Vec3[MAX_SPOTS][];
    private static int spotN;
    private static boolean spotsAsked;
    private static volatile AABB[] boxes = new AABB[0];
    private static volatile Vec3[][] marks = new Vec3[0][];
    private static float[] markRgba = {1f, 1f, 1f, 0.6f};
    private static final McRender.Geometry MARKS = (pose, buffer) -> {
        float[] c = markRgba;
        for (Vec3[] m : marks) {
            BoxVerts.lineStrip(pose, buffer, m, c[0], c[1], c[2], c[3], 1.5f);
        }
    };

    static {
        for (int i = 0; i < SEED.length; i += 3) {
            addSpot(SEED[i], SEED[i + 1], SEED[i + 2]);
        }
        rebuild();
    }

    private InvisibugHighlight() {
    }

    public static void register() {
        int bit = FeatureBits.register("Invisibug Highlight", CFG::isEnabledRaw);
        WorldRenderUtils.initThroughWalls();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("InvisibugHighlight.tick", bit, InvisibugHighlight::tick));
        ChatObserver.subscribe(InvisibugHighlight::onChat);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> client.execute(InvisibugHighlight::clear));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FeatureGuard.afterFeatures(bit, context -> {
            AABB[] b = boxes;
            boolean spots = marks.length > 0 && CFG.on("spots") && onIsland();
            if (b.length == 0 && !spots) {
                return;
            }
            int argb = CFG.num("color");
            float[] c = BoxVerts.rgba(argb == 0 ? ThemeAccent.accent(AMBER) : argb, 1f);
            boolean walls = com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && CFG.on("throughWalls");
            if (b.length > 0) {
                float[] rgba = new float[b.length * 4];
                for (int i = 0; i < b.length; i++) {
                    System.arraycopy(c, 0, rgba, i * 4, 4);
                }
                WorldRenderUtils.renderOutlineBoxes(context, b, rgba, b.length, 2f, walls);
            }
            if (spots) {
                c[3] *= 0.6f;
                markRgba = c;
                McRender.inCameraSpace(context, walls ? WorldRenderUtils.throughWallsLines()
                        : com.killer560.hub.util.EspTypes.LINES, MARKS);
            }
        }));
    }

    /** True on Galatea (testkit may force it). */
    static boolean onIsland() {
        return "GALATEA".equals(IslandDetector.graphIsland());
    }

    /** How many boxes are drawn now, and where (read by the testkit). */
    public static int boxCount() {
        return boxes.length;
    }

    public static double[][] boxCenters() {
        AABB[] b = boxes;
        double[][] out = new double[b.length][];
        for (int i = 0; i < b.length; i++) {
            out[i] = new double[]{b[i].getCenter().x, b[i].getCenter().y, b[i].getCenter().z};
        }
        return out;
    }

    /** The known spots (x, y, z each) and how many are drawn as "possible" marks now (testkit). */
    public static double[][] spotCenters() {
        double[][] out = new double[spotN][];
        for (int i = 0; i < spotN; i++) {
            out[i] = new double[]{SPOTS[3 * i], SPOTS[3 * i + 1], SPOTS[3 * i + 2]};
        }
        return out;
    }

    public static int markCount() {
        return marks.length;
    }

    /** Testkit: forgets every learned spot (in memory only), keeping the bundled ones. */
    public static void forgetLearnedSpots() {
        spotN = SEED.length / 3;
        rebuild();
    }

    /** From the level-particles packet hook (main thread). */
    public static void onParticlePacket(ClientboundLevelParticlesPacket packet) {
        if (McPackets.particle(packet).getType() != ParticleTypes.CRIT || McPackets.particleCount(packet) != 1
                || !CFG.isEnabledRaw() || !onIsland()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        double x = McPackets.particleX(packet);
        double y = McPackets.particleY(packet);
        double z = McPackets.particleZ(packet);
        long now = mc.level.getGameTime();
        for (double[] m : MUTED) {
            if (now < m[3] && Math.abs(m[1] - y) < 0.1 && Math.hypot(m[0] - x, m[2] - z) < 1.0) {
                return;
            }
        }
        int slot = -1;
        int oldest = 0;
        for (int i = 0; i < BUGS; i++) {
            if (CHAIN_T[i] < CHAIN_T[oldest]) {
                oldest = i;
            }
            long dt = now - CHAIN_T[i];
            if (CHAIN_N[i] == 0 || dt < 3 || dt > EXPIRE || Math.abs(CHAIN_Y[i] - y) > 0.02) {
                continue;
            }
            double[] p = CHAIN_XZ[i];
            double step = Math.hypot(x - p[POINTS * 2 - 2], z - p[POINTS * 2 - 1]);
            // one step is about 0.17; a lost particle (out of range for a moment) doubles it
            if (step >= 0.08 && step <= (dt > 7 ? 0.6 : 0.3)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            slot = oldest;
            CHAIN_N[slot] = 0;
        }
        double[] p = CHAIN_XZ[slot];
        System.arraycopy(p, 2, p, 0, POINTS * 2 - 2);
        p[POINTS * 2 - 2] = x;
        p[POINTS * 2 - 1] = z;
        CHAIN_Y[slot] = y;
        CHAIN_T[slot] = now;
        int n = ++CHAIN_N[slot];
        boolean was = OK[slot];
        OK[slot] = n >= FIRST && circle(slot, Math.min(n, POINTS)) || atSpot(slot, x, y, z);
        if (OK[slot] && n == POINTS) {
            learn(CENTER[slot][0], y, CENTER[slot][1]);
        }
        if (OK[slot] || was) {
            rebuild();
        }
    }

    /** Fits a circle through the oldest, middle and newest of the chain's last {@code k} points; true (center stored) when
     *  its radius is the recorded one. */
    private static boolean circle(int slot, int k) {
        double[] p = CHAIN_XZ[slot];
        int a = 2 * (POINTS - k), b = 2 * (POINTS - k + (k - 1) / 2), c = 2 * (POINTS - 1);
        double ax = p[a], az = p[a + 1], bx = p[b], bz = p[b + 1], cx = p[c], cz = p[c + 1];
        double d = 2 * (ax * (bz - cz) + bx * (cz - az) + cx * (az - bz));
        if (Math.abs(d) < 1e-6) {
            return false;
        }
        double a2 = ax * ax + az * az, b2 = bx * bx + bz * bz, c2 = cx * cx + cz * cz;
        double ux = (a2 * (bz - cz) + b2 * (cz - az) + c2 * (az - bz)) / d;
        double uz = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d;
        double r = Math.hypot(ax - ux, az - uz);
        if (r < 0.35 || r > 0.75) {
            return false;
        }
        CENTER[slot][0] = ux;
        CENTER[slot][1] = uz;
        return true;
    }

    /** A crit on the ring of a known spot: that spot is the chain's center. */
    private static boolean atSpot(int slot, double x, double y, double z) {
        for (int i = 0; i < spotN; i++) {
            double r = Math.hypot(SPOTS[3 * i] - x, SPOTS[3 * i + 2] - z);
            if (Math.abs(SPOTS[3 * i + 1] - y) < 0.05 && r > 0.3 && r < 0.8) {
                CENTER[slot][0] = SPOTS[3 * i];
                CENTER[slot][1] = SPOTS[3 * i + 2];
                return true;
            }
        }
        return false;
    }

    /** The catch line (main thread): the box nearest the player is that bug; it goes at once and its late crits are muted. */
    private static void onChat(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (boxes.length == 0 || mc.level == null || mc.player == null || !message.getString().startsWith(CAUGHT)) {
            return;
        }
        int best = -1;
        double bestD = 16 * 16;
        for (int i = 0; i < BUGS; i++) {
            if (OK[i]) {
                double d = mc.player.distanceToSqr(CENTER[i][0], CHAIN_Y[i], CENTER[i][1]);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
        }
        if (best < 0) {
            return;
        }
        double[] m = MUTED[0];
        for (double[] q : MUTED) {
            if (q[3] < m[3]) {
                m = q;
            }
        }
        m[0] = CENTER[best][0];
        m[1] = CHAIN_Y[best];
        m[2] = CENTER[best][1];
        m[3] = mc.level.getGameTime() + MUTE;
        learn(m[0], m[1], m[2]);
        CHAIN_N[best] = 0;
        OK[best] = false;
        rebuild();
    }

    /** Remembers a confirmed spot (once; a nearby known one already covers it) and saves the record off the main thread. */
    private static void learn(double x, double y, double z) {
        if (!addSpot(x, y, z)) {
            return;
        }
        rebuild();
        JsonArray list = new JsonArray();
        for (int i = SEED.length / 3; i < spotN; i++) {
            JsonObject o = new JsonObject();
            double sx = Math.round(SPOTS[3 * i] * 100) / 100.0, sy = Math.round(SPOTS[3 * i + 1] * 100) / 100.0,
                    sz = Math.round(SPOTS[3 * i + 2] * 100) / 100.0;
            o.addProperty("k", Math.round(sx * 10) / 10.0 + "," + Math.round(sy * 10) / 10.0 + "," + Math.round(sz * 10) / 10.0);
            o.addProperty("x", sx);
            o.addProperty("y", sy);
            o.addProperty("z", sz);
            list.add(o);
        }
        JsonObject mine = new JsonObject();
        mine.add("spots", list);
        SharedWriter.submit(() -> SharedRecords.save(SharedRecords.file(RecordCatalog.INVISIBUG_SPOTS), mine));
    }

    private static boolean addSpot(double x, double y, double z) {
        if (spotN >= MAX_SPOTS) {
            return false;
        }
        for (int i = 0; i < spotN; i++) {
            if (Math.abs(SPOTS[3 * i + 1] - y) < 1 && Math.hypot(SPOTS[3 * i] - x, SPOTS[3 * i + 2] - z) < 1.5) {
                return false;
            }
        }
        SPOTS[3 * spotN] = x;
        SPOTS[3 * spotN + 1] = y;
        SPOTS[3 * spotN + 2] = z;
        Vec3[] ring = WorldRenderUtils.ringPoints(x, y, z, 0.6, 16);
        Vec3[] mark = new Vec3[ring.length + 1];
        mark[0] = new Vec3(x + 0.6, y + 6, z);
        System.arraycopy(ring, 0, mark, 1, ring.length);
        SPOT_MARK[spotN++] = mark;
        return true;
    }

    /** Rebuilds the drawn boxes from the confirmed chains, and the marks of the known spots no box stands on (on a change only). */
    private static void rebuild() {
        AABB[] out = new AABB[BUGS];
        int n = 0;
        for (int i = 0; i < BUGS; i++) {
            if (OK[i]) {
                double[] c = CENTER[i];
                out[n++] = new AABB(c[0] - 0.3, CHAIN_Y[i] - 0.3, c[1] - 0.3, c[0] + 0.3, CHAIN_Y[i] + 0.3, c[1] + 0.3);
            }
        }
        Vec3[][] m = new Vec3[spotN][];
        int k = 0;
        for (int s = 0; s < spotN; s++) {
            boolean live = false;
            for (int i = 0; i < n && !live; i++) {
                live = Math.abs(out[i].getCenter().y - SPOTS[3 * s + 1]) < 1
                        && Math.hypot(out[i].getCenter().x - SPOTS[3 * s], out[i].getCenter().z - SPOTS[3 * s + 2]) < 1.5;
            }
            if (!live) {
                m[k++] = SPOT_MARK[s];
            }
        }
        boxes = java.util.Arrays.copyOf(out, n);
        marks = java.util.Arrays.copyOf(m, k);
    }

    /** Reads the learned spots once, off the main thread, the first time the feature runs on Galatea. */
    private static void loadSpots() {
        spotsAsked = true;
        SharedWriter.submit(() -> {
            JsonObject o = SharedRecords.load(SharedRecords.file(RecordCatalog.INVISIBUG_SPOTS));
            JsonArray a = o.has("spots") && o.get("spots").isJsonArray() ? o.getAsJsonArray("spots") : new JsonArray();
            double[] got = new double[Math.min(a.size(), MAX_SPOTS) * 3];
            int n = 0;
            for (JsonElement e : a) {
                if (n >= got.length) {
                    break;
                }
                try {
                    JsonObject s = e.getAsJsonObject();
                    got[n] = s.get("x").getAsDouble();
                    got[n + 1] = s.get("y").getAsDouble();
                    got[n + 2] = s.get("z").getAsDouble();
                    n += 3;
                } catch (RuntimeException bad) {
                    // a damaged entry is skipped
                }
            }
            int count = n;
            Minecraft.getInstance().execute(() -> {
                for (int i = 0; i < count; i += 3) {
                    addSpot(got[i], got[i + 1], got[i + 2]);
                }
                rebuild();
            });
        });
    }

    private static void tick(Minecraft mc) {
        if (!spotsAsked && mc.level != null && CFG.isEnabled() && onIsland()) {
            loadSpots();
        }
        if (boxes.length == 0) {
            return;
        }
        if (mc.level == null || !CFG.isEnabled() || !onIsland()) {
            clear();
            return;
        }
        long now = mc.level.getGameTime();
        boolean dropped = false;
        for (int i = 0; i < BUGS; i++) {
            if (CHAIN_N[i] > 0 && now - CHAIN_T[i] > EXPIRE) {
                CHAIN_N[i] = 0;
                OK[i] = false;
                dropped = true;
            }
        }
        if (dropped) {
            rebuild();
        }
    }

    /** Forgets every chain (world change, leaving Galatea, switch off); the known spots stay. */
    public static void clear() {
        for (int i = 0; i < BUGS; i++) {
            CHAIN_N[i] = 0;
            CHAIN_T[i] = 0;
            OK[i] = false;
        }
        for (double[] m : MUTED) {
            m[3] = 0;
        }
        rebuild();
    }
}
