package com.killer560.hub.chocolatefactory.egg;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the egg an Egglocator points at from its particle trail (killer560, 2026-10-19: "it should be able to find eggs even
 * if they arent observable ... build the mixin style from skyhanni"). SkyHanni's method: after a right click of the
 * Egglocator, the trail is a run of happy-villager particles with speed 0 and count 1 for about 5 seconds; the trail leads
 * to the egg. Here the guess is the last trail point received (SkyHanni fits a curve and snaps it to a known egg table; this
 * keeps the raw point, so no table is needed and no data is taken from anywhere). The guess stays until the egg is found, the
 * Egglocator says none are left, or a minute passes.
 */
public final class LocatorTrail {

    /** How long after a click the trail's particles are read (SkyHanni: 5 s). */
    private static final long TRAIL_MS = 5_000L;
    /** How long a guess is kept after the trail. */
    private static final long GUESS_MS = 60_000L;
    private static final int MAX_POINTS = 400;

    private static long clickedAt;
    private static final List<Vec3> points = new ArrayList<>();
    private static Vec3 guess;
    private static long guessAt;

    private LocatorTrail() {
    }

    /** The Egglocator was right-clicked: a new trail begins. */
    static void onUse(long now) {
        clickedAt = now;
        points.clear();
    }

    /** Clears the guess (the egg was found, or the Egglocator says none are left). */
    static void clearGuess() {
        guess = null;
        points.clear();
    }

    /** Reads a particle packet. Only the Egglocator's trail counts: happy villager, speed 0, count 1, inside the window. */
    public static void onParticlePacket(ClientboundLevelParticlesPacket packet, long now) {
        if (clickedAt == 0L || now - clickedAt > TRAIL_MS) {
            return;
        }
        if (packet.getParticle() == null || packet.getParticle().getType() != ParticleTypes.HAPPY_VILLAGER) {
            return;
        }
        if (packet.getMaxSpeed() != 0f || packet.getCount() != 1) {
            return;
        }
        Vec3 p = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        if (points.size() < MAX_POINTS) {
            points.add(p);
        }
        guess = p;
        guessAt = now;
    }

    /**
     * The egg's position, or null when there is none to show: the trail's last point snapped to the nearest egg this client
     * has seen on the island (EggPositions). A trail point with no known egg within reach is not drawn, because no egg
     * can be there.
     */
    public static Vec3 guess(long now) {
        Vec3 raw = rawGuess(now);
        return raw == null ? null : EggPositions.snap(raw);
    }

    /** True when a trail is live but no known egg lies within reach of it (the Egglocator line says so). */
    public static boolean unmatched(long now) {
        Vec3 raw = rawGuess(now);
        return raw != null && EggPositions.snap(raw) == null;
    }

    private static Vec3 rawGuess(long now) {
        if (guess == null || now - guessAt > GUESS_MS) {
            return null;
        }
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? null : guess;
    }
}
