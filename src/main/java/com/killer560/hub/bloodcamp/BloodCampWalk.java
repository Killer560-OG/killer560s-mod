package com.killer560.hub.bloodcamp;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Auto Blood Camp's "Move Around" (killer560, 2026-11-18, modeled on his own Mage camps, bloodaura-magestudy-1118.md):
 * with the Aura on, it keeps him 3.5-8 blocks from the middle of the landing spots, walks him there when he is farther
 * than 8.5 (back when closer than 3), taps one or two keys every 2-6 s inside that ring like he does, and walks away from a
 * primed TNT. It presses the real forward/back/left/right/jump keys (discrete, never sprint or sneak), picked each tick from
 * his body's current yaw, so the Aura's turning never bends the walk. Never a position or velocity write.
 * <p>
 * His own keys win: any movement key physically down releases ours that tick and keeps them released for
 * {@link #YIELD_TICKS} after his last press. Never leaves the Blood room (a margin inside its walls) and never steps to a
 * cell with no floor, a fluid, fire, cactus, magma or a berry bush. A server correction is reported by ServerCorrections
 * as ever and changes nothing here (the next tick plans again from where he stands). Off: nothing touches his keys.
 */
public final class BloodCampWalk {

    static final int YIELD_TICKS = 10;
    private static final double TOO_FAR = 8.5;
    private static final double BACK_IN = 7.0;
    private static final double TOO_CLOSE = 3.0;
    private static final double CLEAR_OF = 4.0;
    private static final double ROOM_HALF = 13.0;
    private static final double TNT_RANGE = 6.0;

    /** Up, down, left, right, jump. */
    private static final boolean[] MINE = new boolean[5];
    private static long ticks;
    private static long yieldUntil;
    private static int tapLeft;
    private static long nextTapAt;
    private static double tapX;
    private static double tapZ;
    /** 1 = walking in, -1 = backing out, 0 = inside the ring. */
    private static int going;
    private static Vec3 lastPos;
    private static Set<String> heldForTest;
    private static double travelled;
    private static int keyTicks;
    private static int tntFlees;
    private static int taps;

    private BloodCampWalk() {
    }

    /** Testkit: the movement keys (by mapping name) that count as physically held; null = read the keyboard. */
    public static void setHeldForTest(Set<String> held) {
        heldForTest = held;
    }

    public static double travelled() {
        return travelled;
    }

    public static int keyTicks() {
        return keyTicks;
    }

    public static int tntFlees() {
        return tntFlees;
    }

    public static int taps() {
        return taps;
    }

    public static void resetStats() {
        travelled = 0;
        keyTicks = 0;
        tntFlees = 0;
        taps = 0;
    }

    private static KeyMapping[] keys(Minecraft c) {
        return new KeyMapping[]{c.options.keyUp, c.options.keyDown, c.options.keyLeft, c.options.keyRight, c.options.keyJump};
    }

    private static boolean physicallyDown(Minecraft client, KeyMapping mapping) {
        Set<String> forced = heldForTest;
        if (forced != null) {
            return forced.contains(mapping.getName());
        }
        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        if (key == null || key == InputConstants.UNKNOWN || key.getType() == InputConstants.Type.MOUSE) {
            return false;
        }
        return com.killer560.hub.compat.McInput.isNativeKeyDown(client.getWindow(), key.getValue());
    }

    /** Lets go of every key this class holds (to what the keyboard says); nothing else is touched. */
    private static void release(KeyMapping[] k, boolean[] phys) {
        for (int i = 0; i < MINE.length; i++) {
            if (MINE[i]) {
                k[i].setDown(phys[i]);
                MINE[i] = false;
            }
        }
    }

    /**
     * Every client tick (START), before the player's input is read. {@code wanted}: cheat, Aura on, Move Around on, in the
     * Blood room. {@code centre}: the middle of the live landing spots (null = none known: stand still).
     * {@code room}: the room's middle {x, z}.
     */
    static void tick(Minecraft client, boolean wanted, Vec3 centre, double[] room) {
        ticks++;
        LocalPlayer p = client.player;
        boolean holding = false;
        for (boolean b : MINE) {
            holding |= b;
        }
        if (!wanted && !holding) {
            lastPos = null;
            return; // Off: nothing here touches his keys
        }
        KeyMapping[] k = keys(client);
        boolean[] phys = new boolean[5];
        boolean his = false;
        for (int i = 0; i < 5; i++) {
            phys[i] = physicallyDown(client, k[i]);
            his |= phys[i];
        }
        if (his) {
            yieldUntil = ticks + YIELD_TICKS;
        }
        if (p == null || !wanted || ticks < yieldUntil || com.killer560.hub.compat.McCompat.screen(client) != null) {
            release(k, phys);
            tapLeft = 0;
            lastPos = null;
            return;
        }
        Vec3 pos = p.position();
        if (lastPos != null && holding) {
            travelled += Math.hypot(pos.x - lastPos.x, pos.z - lastPos.z);
        }
        lastPos = pos;

        double dx = 0;
        double dz = 0;
        boolean flee = false;
        if (ticks % 2 == 0 || tapLeft == 0 && going == 0) {
            PrimedTnt tnt = nearestTnt(client, p);
            if (tnt != null) {
                dx = pos.x - tnt.getX();
                dz = pos.z - tnt.getZ();
                flee = true;
            }
        }
        if (flee) {
            tapLeft = 0;
            tntFlees++;
        } else if (centre != null) {
            double ox = centre.x - pos.x;
            double oz = centre.z - pos.z;
            double d = Math.hypot(ox, oz);
            if (going >= 0 && d > TOO_FAR || going > 0 && d > BACK_IN) {
                going = 1;
            } else if (going <= 0 && d < TOO_CLOSE || going < 0 && d < CLEAR_OF) {
                going = -1;
            } else {
                going = 0;
            }
            if (going != 0 && d > 1e-6) {
                dx = going * ox;
                dz = going * oz;
                tapLeft = 0;
            } else if (tapLeft > 0) {
                dx = tapX;
                dz = tapZ;
                tapLeft--;
            } else if (ticks >= nextTapAt && d > 1e-6) {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                // A strafe round the cluster, or a half step in or out, 3-8 ticks long, every 2-6 s.
                double a = Math.atan2(oz, ox) + (r.nextBoolean() ? 1 : -1) * (r.nextInt(3) == 0 ? 0 : Math.PI / 2) + (r.nextInt(3) == 0 ? Math.PI : 0);
                double nx = Math.cos(a);
                double nz = Math.sin(a);
                // keep inside the ring: a step that would leave 3.5-8 is not taken
                double nd = Math.hypot(ox - nx * 2.0, oz - nz * 2.0);
                if (nd >= 3.5 && nd <= 8.0) {
                    tapX = nx;
                    tapZ = nz;
                    tapLeft = 3 + r.nextInt(6);
                    taps++;
                }
                nextTapAt = ticks + 40 + r.nextInt(80);
            }
        } else {
            going = 0;
            tapLeft = 0;
        }

        boolean[] want = new boolean[5];
        if (Math.hypot(dx, dz) > 1e-6) {
            double len = Math.hypot(dx, dz);
            dx /= len;
            dz /= len;
            // try the wanted way, then turned 45 and 90 degrees either side, to the first safe one
            double[] turns = {0, 0.785, -0.785, 1.571, -1.571};
            for (double t : turns) {
                double ex = dx * Math.cos(t) - dz * Math.sin(t);
                double ez = dx * Math.sin(t) + dz * Math.cos(t);
                int kind = stepKind(client.level, p, pos.x + ex * 0.9, pos.z + ez * 0.9, room);
                if (kind == 0) {
                    continue;
                }
                double yaw = Math.toRadians(p.getYRot());
                double fwd = ex * -Math.sin(yaw) + ez * Math.cos(yaw);
                double right = ex * -Math.cos(yaw) + ez * -Math.sin(yaw);
                want[0] = fwd > 0.38;
                want[1] = fwd < -0.38;
                want[3] = right > 0.38;
                want[2] = right < -0.38;
                want[4] = kind == 2 && p.onGround();
                break;
            }
        }
        boolean any = false;
        for (int i = 0; i < 5; i++) {
            any |= want[i];
            if (want[i] != k[i].isDown() && (want[i] || MINE[i])) {
                k[i].setDown(want[i]);
            }
            MINE[i] = want[i];
        }
        if (any) {
            keyTicks++;
        }
    }

    private static PrimedTnt nearestTnt(Minecraft client, LocalPlayer p) {
        PrimedTnt best = null;
        double bd = TNT_RANGE * TNT_RANGE;
        for (PrimedTnt t : client.level.getEntitiesOfClass(PrimedTnt.class, p.getBoundingBox().inflate(TNT_RANGE))) {
            double d = t.distanceToSqr(p);
            if (d < bd) {
                bd = d;
                best = t;
            }
        }
        return best;
    }

    /** 0 = do not step there; 1 = fine; 2 = fine but a block up: jump. */
    private static int stepKind(Level level, LocalPlayer p, double x, double z, double[] room) {
        if (room != null && (Math.abs(x - room[0]) > ROOM_HALF || Math.abs(z - room[1]) > ROOM_HALF)) {
            return 0;
        }
        BlockPos feet = BlockPos.containing(x, p.getY() + 0.01, z);
        if (!free(level, feet)) {
            return free(level, feet.above()) && free(level, feet.above(2)) && solid(level, feet) ? 2 : 0;
        }
        if (!free(level, feet.above())) {
            return 0;
        }
        BlockPos below = feet.below();
        if (solid(level, below)) {
            return 1;
        }
        return free(level, below) && solid(level, below.below()) ? 1 : 0; // a one block step down, never a drop
    }

    private static boolean hazard(BlockState s) {
        return s.is(BlockTags.FIRE) || s.is(Blocks.CACTUS) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.SWEET_BERRY_BUSH)
                || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.COBWEB);
    }

    private static boolean free(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty() && !hazard(s);
    }

    private static boolean solid(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return !s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty() && !hazard(s);
    }
}
