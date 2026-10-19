package com.killer560.hub.chocolatefactory.egg;

import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds the egg an Egglocator points at from its particle trail, the way SkyHanni's HoppityEggLocator does (killer560,
 * 2026-10-19: "it should be able to find eggs even if they arent observable ... build the mixin style from skyhanni", "snap to
 * the right points, those are the only ones it can be at").
 * <p>
 * After a right click of the Egglocator the server draws the start of a curve toward the closest unfound egg as happy-villager
 * particles (speed 0, count 1). For 5 s after the click every such particle is offered to a {@link TrailFitter}; once it holds
 * four points the fitted curve is followed to its end, and that end is snapped to the nearest egg spot on this island within
 * {@link EggPositions#MAX_SNAP} blocks. Only a snapped spot is ever shown: an egg can be nowhere else. A later fit that snaps
 * nowhere keeps the last good spot (SkyHanni does the same).
 * <p>
 * The guess stays until that egg is found, the Egglocator says none are left, or the world changes - not on a timer, because
 * a far egg can take longer than any timer to walk to. Everything here runs on the render thread: the mixin reads the packet
 * after {@code ensureRunningOnSameThread}, as SkyHanni's does.
 */
public final class LocatorTrail {

    /** How long after a click the trail's particles are read (SkyHanni: 5 s). */
    private static final long TRAIL_MS = 5_000L;
    /** When, after a click, a click that produced particles but no egg is called unreliable (SkyHanni: 5.5 s). */
    private static final long VERDICT_MS = 5_500L;
    /** A trail point further than this from the previous one belongs to something else (SkyHanni: 3.0). */
    private static final double MAX_STEP = 3.0;
    /** A point this close to a fishing hook is the bobber's own particle, not the trail (SkyHanni: 0.3). */
    private static final double HOOK_RADIUS = 0.3;
    private static final org.slf4j.Logger LOGGER = ModLog.get("killer560smod-chocolatefactory");

    private static final TrailFitter FITTER = new TrailFitter();

    private static long clickedAt;
    /** The snapped egg spot (its table point), or null. */
    private static Vec3 guess;
    /** True from a click until it produces a snapped guess; read for the "unreliable" verdict. */
    private static boolean pending;
    /** The last click produced particles and fits but none of them snapped to a known egg. */
    private static boolean unreliable;
    private static boolean verdictLogged;
    private static Object level;

    private LocatorTrail() {
    }

    /** The Egglocator was right-clicked: a new trail begins. The old guess stays until the new trail replaces it. */
    static void onUse(long now) {
        clickedAt = now;
        FITTER.reset();
        pending = true;
        unreliable = false;
        verdictLogged = false;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            Vec3 p = client.player.position();
            LOGGER.info("[Egglocator] click at " + fmt(p) + " " + EggPositions.debugState(p));
        }
    }

    /** Clears the guess (the egg was found, the Egglocator says none are left, or the world changed). */
    static void clearGuess(String why) {
        if (guess != null || !FITTER.isEmpty()) {
            LOGGER.info("[Egglocator] cleared (" + why + ")");
        }
        guess = null;
        pending = false;
        unreliable = false;
        FITTER.reset();
    }

    /** Client tick: clears on a world change and gives the 5.5 s verdict on a click that found nothing. */
    static void tick(Minecraft client, long now) {
        if (client.level != level) {
            level = client.level;
            clearGuess("world change");
            clickedAt = 0L;
        }
        if (pending && clickedAt != 0L && now - clickedAt > VERDICT_MS && !verdictLogged) {
            verdictLogged = true;
            pending = false;
            if (!FITTER.isEmpty()) {
                unreliable = true;
                LOGGER.info("[Egglocator] no egg from this click: " + FITTER.count() + " trail points, no fitted end within "
                        + EggPositions.MAX_SNAP + " of a known spot (use it again)");
            } else {
                LOGGER.info("[Egglocator] no trail particles arrived within " + TRAIL_MS + "ms of the click");
            }
        }
    }

    /**
     * Reads a particle packet on the render thread. Only the Egglocator's trail counts: happy villager, speed 0, count 1,
     * within 5 s of a click.
     */
    public static void onParticlePacket(ClientboundLevelParticlesPacket packet, long now) {
        if (clickedAt == 0L || now - clickedAt > TRAIL_MS) {
            return;
        }
        if (packet.getParticle() == null || packet.getParticle().getType() != ParticleTypes.HAPPY_VILLAGER) {
            return;
        }
        Vec3 p = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        long t = now - clickedAt;
        if (packet.getMaxSpeed() != 0f || packet.getCount() != 1) {
            LOGGER.info("[Egglocator] happy villager at " + fmt(p) + " skipped: speed=" + packet.getMaxSpeed() + " count="
                    + packet.getCount() + " t=" + t + "ms");
            return;
        }
        boolean added = FITTER.tryAdd(p, MAX_STEP, LocatorTrail::atFishingHook);
        String reject = FITTER.lastReject();
        LOGGER.info("[Egglocator] trail #" + FITTER.count() + " at " + fmt(p) + " t=" + t + "ms"
                + (reject.isEmpty() ? "" : " skipped: " + reject));
        if (!added) {
            return;
        }
        Vec3 end = FITTER.solve();
        if (end == null) {
            return;
        }
        Vec3 snapped = EggPositions.snap(end);
        if (snapped == null) {
            LOGGER.info("[Egglocator] fitted end " + fmt(end) + " from " + FITTER.count() + " points: no spot within "
                    + EggPositions.MAX_SNAP + " (" + EggPositions.debugState(end) + ")");
            return;
        }
        if (!snapped.equals(guess)) {
            LOGGER.info("[Egglocator] fitted end " + fmt(end) + " from " + FITTER.count() + " points -> egg at " + fmt(snapped)
                    + String.format(" (%.1f away)", snapped.distanceTo(end)));
        }
        guess = snapped;
        pending = false;
        unreliable = false;
    }

    private static boolean atFishingHook(Vec3 p) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return false;
        }
        AABB box = new AABB(p, p).inflate(HOOK_RADIUS);
        for (FishingHook hook : client.level.getEntitiesOfClass(FishingHook.class, box)) {
            if (hook.position().distanceTo(p) <= HOOK_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /** The egg spot the last trail points at (a table point: the egg's block corner), or null when there is none. */
    public static Vec3 guess(long now) {
        return Minecraft.getInstance().player == null ? null : guess;
    }

    /** True when the last click's trail fitted to no known egg (the Egglocator line says to use it again). */
    public static boolean unmatched(long now) {
        return unreliable;
    }

    private static String fmt(Vec3 p) {
        return String.format("%.2f %.2f %.2f", p.x, p.y, p.z);
    }
}
