package com.killer560.hub.powdermining;

import com.killer560.hub.compat.McPackets;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Lockpick Helper (legit, both builds): shows exactly where to click on a treasure chest. Hypixel marks the side to click
 * with {@code minecraft:crit} particles at the chest (Skyblocker CrystalsChestHighlighter: every crit within 0.8 blocks of
 * the chest's center, averaged); this draws a small box at that spot and fills the chest face it is on (the axis where the
 * spot sits furthest from the center). Draw-only: nothing aims or clicks for him.
 *
 * <p>Samples are kept 600 ms; the last spot stays drawn for 3 s after the particles stop (they come in bursts), and goes
 * with the chest.
 */
public final class LockpickHelper {

    private static final long SAMPLE_MS = 600L;
    private static final long HOLD_MS = 3000L;
    private static final double NEAR = 0.8;

    private record Sample(BlockPos chest, double x, double y, double z, long at) {
    }

    private static final ArrayDeque<Sample> SAMPLES = new ArrayDeque<>();
    private static BlockPos spotChest = null;
    private static Vec3 spot = null;
    private static Direction face = null;
    private static long spotAt = 0L;
    private static int samples = 0;
    private static int frames = 0;

    private LockpickHelper() {
    }

    /** A particle packet (client thread). Never cancels it. */
    public static void onParticle(ClientboundLevelParticlesPacket packet) {
        if (TreasureChests.chests().isEmpty() || !PowderMiningConfig.getInstance().isLockpickHelper()) {
            return;
        }
        ParticleOptions p = McPackets.particle(packet);
        if (p == null || p.getType() != ParticleTypes.CRIT) {
            return;
        }
        double x = McPackets.particleX(packet), y = McPackets.particleY(packet), z = McPackets.particleZ(packet);
        for (BlockPos c : TreasureChests.chests().keySet()) {
            double dx = x - (c.getX() + 0.5), dy = y - (c.getY() + 0.4375), dz = z - (c.getZ() + 0.5);
            if (Math.abs(dx) <= NEAR && Math.abs(dy) <= NEAR && Math.abs(dz) <= NEAR) {
                SAMPLES.addLast(new Sample(c, x, y, z, System.currentTimeMillis()));
                samples++;
                if (SAMPLES.size() > 64) {
                    SAMPLES.removeFirst();
                }
                recompute();
                return;
            }
        }
    }

    private static void recompute() {
        long now = System.currentTimeMillis();
        Sample last = SAMPLES.peekLast();
        if (last == null) {
            return;
        }
        double sx = 0, sy = 0, sz = 0;
        int n = 0;
        for (Iterator<Sample> it = SAMPLES.iterator(); it.hasNext(); ) {
            Sample s = it.next();
            if (now - s.at() > SAMPLE_MS) {
                it.remove();
                continue;
            }
            if (s.chest().equals(last.chest())) {
                sx += s.x();
                sy += s.y();
                sz += s.z();
                n++;
            }
        }
        if (n == 0) {
            return;
        }
        BlockPos c = last.chest();
        Vec3 avg = new Vec3(sx / n, sy / n, sz / n);
        double dx = avg.x - (c.getX() + 0.5), dy = avg.y - (c.getY() + 0.4375), dz = avg.z - (c.getZ() + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        Direction f;
        if (ay >= ax && ay >= az) {
            f = dy >= 0 ? Direction.UP : Direction.DOWN;
        } else if (ax >= az) {
            f = dx >= 0 ? Direction.EAST : Direction.WEST;
        } else {
            f = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
        spotChest = c;
        spot = avg;
        face = f;
        spotAt = now;
    }

    static void tick() {
        if (spotChest != null && (!TreasureChests.chests().containsKey(spotChest)
                || System.currentTimeMillis() - spotAt > HOLD_MS
                || !PowderMiningConfig.getInstance().isLockpickHelper())) {
            spotChest = null;
            spot = null;
            face = null;
        }
        if (TreasureChests.chests().isEmpty()) {
            SAMPLES.clear();
        }
    }

    static void render(LevelRenderContext context) {
        BlockPos c = spotChest;
        Vec3 s = spot;
        Direction f = face;
        if (c == null || s == null || f == null || !PowderMiningConfig.getInstance().isLockpickHelper()) {
            return;
        }
        float[] k = WorldRenderUtils.argbToFloats(PowderMiningConfig.getInstance().getLockpickColor());
        WorldRenderUtils.renderFilledFace(context, TreasureChests.box(c).inflate(0.01), f, k[0], k[1], k[2], 0.35f);
        AABB dot = new AABB(s.x - 0.07, s.y - 0.07, s.z - 0.07, s.x + 0.07, s.y + 0.07, s.z + 0.07);
        WorldRenderUtils.renderFilledBoxes(context, new AABB[]{dot}, new float[]{k[0], k[1], k[2], 1f}, 1, 0.9f, true);
        frames++;
    }

    // ---- testkit hooks

    public static int samples() {
        return samples;
    }

    public static int frames() {
        return frames;
    }

    /** The face to click ("EAST", ...), or null with no spot. */
    public static String faceNow() {
        Direction f = face;
        return f == null ? null : f.name();
    }

    public static double[] spotNow() {
        Vec3 s = spot;
        return s == null ? null : new double[]{s.x, s.y, s.z};
    }
}
