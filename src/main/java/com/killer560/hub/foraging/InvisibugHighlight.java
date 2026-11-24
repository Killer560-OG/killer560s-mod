package com.killer560.hub.foraging;

import com.killer560.hub.compat.McPackets;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.phys.AABB;

/**
 * Invisibug Highlight (Galatea Hunting): an Invisibug is invisible and sends no entity the client can read; its only mark is
 * ONE crit particle every 5 ticks that circles (radius about 0.5, same height, a turn in about 90 ticks) around its spot
 * (recorded 11-23, killer560s-mod-logs/invisibug-1123-recording.md). Each particle is chained onto the nearby chain it
 * continues (same height, a short step, 3 to 7 ticks later); once a chain has 7 points and three of them 6 steps apart sit on
 * a circle of the recorded radius, its center is the bug and a box is drawn there. A hit's crits (counts above 1, scattered
 * heights, no steady circle) never qualify. A bug is dropped 2 seconds after its particles stop. Display only: sends nothing,
 * both builds; Through Walls is cheat-only. Off by default; reads only on Galatea.
 */
public final class InvisibugHighlight {

    private static final int AMBER = 0xFFFFAA00;
    private static final int BUGS = 8;
    private static final int POINTS = 7;
    private static final long EXPIRE = 40;

    public static final IslandConfig CFG = IslandConfig.of("foraging", "invisibug")
            .num("range", 48, 8, 64)
            .num("color", 0, Integer.MIN_VALUE, Integer.MAX_VALUE)
            .flag("throughWalls", false);

    /** Per chain: the last {@link #POINTS} points (x, z pairs, oldest first), y, time of the last point, points seen. Packet thread only. */
    private static final double[][] CHAIN_XZ = new double[BUGS][POINTS * 2];
    private static final double[] CHAIN_Y = new double[BUGS];
    private static final long[] CHAIN_T = new long[BUGS];
    private static final int[] CHAIN_N = new int[BUGS];
    private static final double[][] CENTER = new double[BUGS][2];
    private static final boolean[] OK = new boolean[BUGS];
    private static volatile AABB[] boxes = new AABB[0];

    private InvisibugHighlight() {
    }

    public static void register() {
        int bit = FeatureBits.register("Invisibug Highlight", CFG::isEnabledRaw);
        WorldRenderUtils.initThroughWalls();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("InvisibugHighlight.tick", bit, InvisibugHighlight::tick));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> client.execute(InvisibugHighlight::clear));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FeatureGuard.afterFeatures(bit, context -> {
            AABB[] b = boxes;
            if (b.length == 0) {
                return;
            }
            int argb = CFG.num("color");
            float[] c = com.killer560.hub.util.BoxVerts.rgba(argb == 0 ? ThemeAccent.accent(AMBER) : argb, 1f);
            float[] rgba = new float[b.length * 4];
            for (int i = 0; i < b.length; i++) {
                System.arraycopy(c, 0, rgba, i * 4, 4);
            }
            WorldRenderUtils.renderOutlineBoxes(context, b, rgba, b.length, 2f,
                    com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && CFG.on("throughWalls"));
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
        double range = CFG.num("range");
        if (mc.player.distanceToSqr(x, y, z) > range * range) {
            return;
        }
        long now = mc.level.getGameTime();
        int slot = -1;
        int oldest = 0;
        for (int i = 0; i < BUGS; i++) {
            if (CHAIN_T[i] < CHAIN_T[oldest]) {
                oldest = i;
            }
            long dt = now - CHAIN_T[i];
            if (CHAIN_N[i] == 0 || dt < 3 || dt > 7 || Math.abs(CHAIN_Y[i] - y) > 0.02) {
                continue;
            }
            double[] p = CHAIN_XZ[i];
            double step = Math.hypot(x - p[POINTS * 2 - 2], z - p[POINTS * 2 - 1]);
            if (step >= 0.08 && step <= 0.3) {
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
        OK[slot] = ++CHAIN_N[slot] >= POINTS && circle(slot);
        if (OK[slot]) {
            rebuild();
        }
    }

    /** Fits a circle through the first, middle and last of the chain's points; true (center stored) when its radius is the recorded one. */
    private static boolean circle(int slot) {
        double[] p = CHAIN_XZ[slot];
        int m = POINTS - 1;
        double ax = p[0], az = p[1], bx = p[m], bz = p[m + 1], cx = p[2 * m], cz = p[2 * m + 1];
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

    /** Rebuilds the drawn boxes from the confirmed chains (once per particle that confirms, not per frame). */
    private static void rebuild() {
        AABB[] out = new AABB[BUGS];
        int n = 0;
        for (int i = 0; i < BUGS; i++) {
            if (OK[i]) {
                double[] c = CENTER[i];
                out[n++] = new AABB(c[0] - 0.3, CHAIN_Y[i] - 0.3, c[1] - 0.3, c[0] + 0.3, CHAIN_Y[i] + 0.3, c[1] + 0.3);
            }
        }
        boxes = java.util.Arrays.copyOf(out, n);
    }

    private static void tick(Minecraft mc) {
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

    /** Forgets every chain (world change, leaving Galatea, switch off). */
    public static void clear() {
        for (int i = 0; i < BUGS; i++) {
            CHAIN_N[i] = 0;
            CHAIN_T[i] = 0;
            OK[i] = false;
        }
        boxes = new AABB[0];
    }
}
