package com.killer560.hub.bosssim;

import com.killer560.hub.util.ModLog;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hypixel's lava in the boss, as ADM recorded it (boss fights with their packets):
 * <ul>
 * <li>The server checks every 2 ticks whether it sees you in lava by the old 1.8 rule: your box shrunk to x/z +-0.2 and
 * feet +0.4 to +1.4 overlapping any lava block. On the P3 floor that is feet below 106.6.</li>
 * <li>If so, and it has not bounced you in the last 4 ticks, your motion becomes exactly (0, 2.25, 0), or (0, 3.0375, 0)
 * when you look at pitch -40.7 or further up. Any lava, no health lost, never on fire.</li>
 * <li>The client's own physics does the rest (from 106.00: 108.25, 110.03, 111.69 ...).</li>
 * <li>About half the bounces come with the hurt tilt on Hypixel; the sim sends none (killer560, 2026-11-05).</li>
 * </ul>
 * The sim is the server here: it sets the motion and tells the client, as Hypixel's does. Nothing on the client moves him.
 */
final class BossLava {

    private static final Logger LOGGER = ModLog.get("killer560smod-bosssim");
    private static final float HIGH_PITCH = -40.7f;
    private static final double VY = 2.25, VY_HIGH = 3.0375;
    private static final int COOLDOWN = 4;

    private static final Map<UUID, Integer> bouncedAt = new HashMap<>();
    private static int tick;
    private static int bounces;

    private BossLava() {
    }

    /** Lava bounces since the game started (the testkit reads it). */
    static int bounces() {
        return bounces;
    }

    static void tick(MinecraftServer server, ServerLevel level) {
        tick++;
        if (tick % 2 != 0) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || p.isCreative() || !inLava(level, p)) {
                continue;
            }
            Integer last = bouncedAt.get(p.getUUID());
            if (last != null && tick - last < COOLDOWN) {
                continue;
            }
            bouncedAt.put(p.getUUID(), tick);
            double vy = p.getXRot() <= HIGH_PITCH ? VY_HIGH : VY;
            p.fallDistance = 0;
            p.clearFire();
            // A launch on purpose, so it passes the sim's no-knockback filter. Hypixel tilts the camera on about half the
            // bounces (ADM's capture); the sim does not (killer560 2026-11-05: lava "moves my pov like im taking damage").
            com.killer560.hub.roomsim.SimLaunch.push(p, new Vec3(0.0, vy, 0.0));
            bounces++;
            LOGGER.info("[BossSim] lava bounce at y {} (vy {})", String.format(java.util.Locale.ROOT, "%.2f", p.getY()), vy);
        }
    }

    /** The 1.8 in-lava check: box x/z +-0.2, feet +0.4 to +1.4, any lava block it overlaps. */
    static boolean inLava(ServerLevel level, ServerPlayer p) {
        for (int x = (int) Math.floor(p.getX() - 0.2); x <= (int) Math.floor(p.getX() + 0.2); x++) {
            for (int y = (int) Math.floor(p.getY() + 0.4); y <= (int) Math.floor(p.getY() + 1.4); y++) {
                for (int z = (int) Math.floor(p.getZ() - 0.2); z <= (int) Math.floor(p.getZ() + 0.2); z++) {
                    if (level.getFluidState(new BlockPos(x, y, z)).is(FluidTags.LAVA)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
