package com.killer560.hub.roomsim;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The sim's Spirit Sceptre: a burst of bats that fly out, explode on contact and damage what is around them.
 *
 * <p>killer560 (2026-09-30): "the sim spirit scepter doesnt work." It DID fire - his log has six
 * "[Sim] Spirit Sceptre bats fired" lines at 10:16:17-19 - so this is not a dead handler. What it did was
 * invisible and, standing in a single-room Supertall sim with no mobs in it, indistinguishable from nothing
 * happening at all: no bats, no particles, no sound, and an instant hit on whatever was already inside a box.
 *
 * <p>The box was wrong too, and wrong in a way that changes with which way you are facing.
 * {@code AABB.ofSize(centre, 6, 3, 4)} is axis aligned, so "range" was the X dimension and "width" the Z one
 * whatever the look vector said: facing east the sceptre reached six blocks and spread two to each side,
 * facing south it reached two and spread three. Half the time it hit nothing that was plainly in front of him.
 *
 * <p>So the bats are real now. Five of them leave the hand a couple of ticks apart, fly along the look vector
 * with a little spread, trail soul flame, and explode on the first block or mob they meet - or when they run
 * out of range. The explosion is what damages, in a sphere, which is what the real ability's bats do.
 *
 * <p>Split out of {@code SimItems} rather than rewritten in place so the flight can own a client tick without
 * that file growing a second lifecycle. {@code SimItems.tryUse} still routes {@code BAT_WAND} here, which
 * keeps "exactly one place a right-click is resolved" true.
 *
 * <p>Same safety story as the rest of {@code roomsim}: everything is gated on {@link SimState#canAct}, the
 * particles and the sound are client-side (there is exactly one player in a sim world), and the damage is
 * applied on the integrated server's own thread against the server's own entities.
 */
public final class SimSpiritSceptre {

    /** Bats per cast. The real ability fires a spread; five is enough to read as a burst without a wall of them. */
    private static final int BATS = 5;

    /** Ticks between one bat leaving and the next, so it reads as a burst rather than one clump. */
    private static final int LAUNCH_GAP = 2;

    /** Blocks a bat covers per client tick. */
    private static final double SPEED = 1.2;

    /** How many ticks a bat may fly before it gives up - {@code SPEED * this} is its range in blocks. */
    private static final int MAX_FLIGHT_TICKS = 25;

    /** How far off the look vector a bat may wander, per axis, at launch. */
    private static final double SPREAD = 0.06;

    /** The radius the explosion damages in. */
    private static final double BLAST_RADIUS = 3.0;

    /**
     * Damage one bat's explosion does.
     *
     * <p>An approximation, deliberately: this is not Hypixel's real bat damage formula and must not be quoted
     * as one. Sim mobs have one health (see {@link SimClass}), so what matters is that a hit kills.
     */
    private static final float BLAST_DAMAGE = 30.0f;

    private static final double BLAST_KNOCKBACK = 0.6;

    /** How far in front of the eye a bat starts, so its trail is not drawn inside his own head. */
    private static final double MUZZLE = 0.8;

    private static final Random RNG = new Random();

    /** One bat in flight. Mutable on purpose - there are at most a handful and they are stepped every tick. */
    private static final class Bat {
        Vec3 at;
        Vec3 velocity;
        int delay;
        int ticksLeft = MAX_FLIGHT_TICKS;
        /** The real bat entity carrying this one, or null before it has been spawned. */
        java.util.UUID entity;
    }

    /**
     * A REAL BAT, not a line of particles.
     *
     * <p>killer560 (2026-10-01): "Spirit scepter still shoots its own beams instead of a bat." The flight was
     * drawn as a dense trail of soul-fire flame, which from where he stands is a beam - the one thing the
     * ability is not. So each projectile now carries an actual {@code Bat} entity that is moved along the same
     * path and discarded when it goes off, and the trail is one particle at the bat rather than a line through
     * the whole step.
     *
     * <p>No AI, no gravity, invulnerable and silent: it is a projectile wearing a bat, and anything vanilla
     * would do with a real one - flying off, resting on a ceiling, being hit - would take it off the path the
     * blast is computed from.
     */
    private static void spawnBatEntity(Minecraft client, Bat bat) {
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        Vec3 at = bat.at;
        net.minecraft.world.entity.ambient.Bat entity =
                new net.minecraft.world.entity.ambient.Bat(
                        net.minecraft.world.entity.EntityType.BAT, server.overworld());
        entity.moveTo(at.x, at.y, at.z, 0f, 0f);
        entity.setNoGravity(true);
        entity.setNoAi(true);
        entity.setInvulnerable(true);
        entity.setSilent(true);
        bat.entity = entity.getUUID();
        server.execute(() -> server.overworld().addFreshEntity(entity));
    }

    /** Moves this one's bat entity to where the flight has got to. */
    private static void moveBatEntity(Minecraft client, Bat bat, Vec3 to) {
        var server = client.getSingleplayerServer();
        if (server == null || bat.entity == null) {
            return;
        }
        java.util.UUID id = bat.entity;
        server.execute(() -> {
            Entity entity = server.overworld().getEntity(id);
            if (entity != null) {
                entity.moveTo(to.x, to.y, to.z, entity.getYRot(), 0f);
            }
        });
    }

    /** Takes this one's bat entity away - it has exploded, or the sim has closed. */
    private static void removeBatEntity(Minecraft client, Bat bat) {
        var server = client.getSingleplayerServer();
        if (server == null || bat.entity == null) {
            return;
        }
        java.util.UUID id = bat.entity;
        bat.entity = null;
        server.execute(() -> {
            Entity entity = server.overworld().getEntity(id);
            if (entity != null) {
                entity.discard();
            }
        });
    }

    private static final List<Bat> FLYING = new ArrayList<>();

    private SimSpiritSceptre() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient}. */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SimSpiritSceptre::tick);
    }

    /**
     * Right-click: launch a burst.
     *
     * @return whether anything was fired, so the caller can fall through to normal item behaviour if not
     */
    public static boolean fire(Minecraft client) {
        if (!SimState.canAct(client) || client.player == null || client.level == null) {
            return false;
        }
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getViewVector(1.0f);
        for (int i = 0; i < BATS; i++) {
            Bat bat = new Bat();
            bat.delay = i * LAUNCH_GAP;
            Vec3 dir = look.add(
                    (RNG.nextDouble() - 0.5) * 2 * SPREAD,
                    (RNG.nextDouble() - 0.5) * 2 * SPREAD,
                    (RNG.nextDouble() - 0.5) * 2 * SPREAD).normalize();
            bat.at = eye.add(dir.scale(MUZZLE));
            bat.velocity = dir.scale(SPEED);
            spawnBatEntity(client, bat);
            FLYING.add(bat);
        }
        client.level.playLocalSound(eye.x, eye.y, eye.z, SoundEvents.BAT_TAKEOFF,
                SoundSource.PLAYERS, 0.9f, 1.4f, false);
        return true;
    }

    /** Throws away anything still in the air - for leaving the sim, so a bat cannot outlive its world. */
    public static void clear() {
        Minecraft client = Minecraft.getInstance();
        for (Bat bat : FLYING) {
            removeBatEntity(client, bat);
        }
        FLYING.clear();
    }

    /** Bats currently in the air. For tests, which otherwise cannot tell "fired" from "did nothing". */
    public static int inFlight() {
        return FLYING.size();
    }

    /** How many blasts have gone off since the game started - a counter a test can watch change. */
    public static int blasts() {
        return blastCount;
    }

    private static int blastCount;

    private static void tick(Minecraft client) {
        if (FLYING.isEmpty()) {
            return;
        }
        if (!SimState.canAct(client) || client.level == null) {
            clear();
            return;
        }
        // Copied before stepping: a blast is allowed to touch nothing here, but a bat that finishes is removed
        // from the live list while this walks it.
        List<Bat> still = new ArrayList<>(FLYING.size());
        for (Bat bat : FLYING) {
            if (bat.delay > 0) {
                bat.delay--;
                still.add(bat);
                continue;
            }
            if (step(client, bat)) {
                still.add(bat);
            }
        }
        FLYING.clear();
        FLYING.addAll(still);
    }

    /** @return whether the bat is still flying */
    private static boolean step(Minecraft client, Bat bat) {
        Vec3 from = bat.at;
        Vec3 to = from.add(bat.velocity);

        // A mob first, then the wall: a bat that would cross both in one tick should explode on whichever is
        // nearer, and stepping a whole block at a time means both can be in the same step.
        Entity hitEntity = null;
        double nearest = Double.MAX_VALUE;
        AABB sweep = new AABB(from, to).inflate(0.3);
        for (Entity e : client.level.getEntities(client.player, sweep,
                e -> e instanceof LivingEntity && e.isAlive() && e != client.player)) {
            var clip = e.getBoundingBox().inflate(0.3).clip(from, to);
            if (clip.isEmpty()) {
                continue;
            }
            double d = from.distanceToSqr(clip.get());
            if (d < nearest) {
                nearest = d;
                hitEntity = e;
            }
        }

        BlockHitResult wall = client.level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        boolean wallFirst = wall != null && wall.getType() != HitResult.Type.MISS
                && (hitEntity == null || from.distanceToSqr(wall.getLocation()) < nearest);

        trail(client, from, to);

        if (hitEntity != null && !wallFirst) {
            removeBatEntity(client, bat);
            explode(client, hitEntity.getBoundingBox().getCenter());
            return false;
        }
        if (wallFirst) {
            removeBatEntity(client, bat);
            explode(client, wall.getLocation());
            return false;
        }
        bat.at = to;
        moveBatEntity(client, bat, to);
        if (--bat.ticksLeft <= 0) {
            removeBatEntity(client, bat);
            explode(client, to);
            return false;
        }
        return true;
    }

    /**
     * A wisp at the bat, not a line through where it has been.
     *
     * <p>This drew a soul-fire flame every 0.3 blocks of the step, which at 1.2 blocks a tick is a solid rod
     * of fire - the "beam" killer560 saw instead of a bat. One particle at the leading edge reads as a trail
     * behind something that is itself visible now, which is the bat entity's job.
     */
    private static void trail(Minecraft client, Vec3 from, Vec3 to) {
        if (from.distanceToSqr(to) < 0.0001) {
            return;
        }
        client.level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, to.x, to.y, to.z, 0.0, 0.0, 0.0);
    }

    /**
     * One bat's explosion: the picture on the client, the damage on the server.
     *
     * <p>The damage is worked out server-side from the blast centre rather than handed a list of entities the
     * client picked, so it is the server's own view of what is in range that decides - the same rule
     * {@code SimAbilities.teleport} and {@code SimDoors.openDoor} follow.
     */
    private static void explode(Minecraft client, Vec3 centre) {
        blastCount++;
        client.level.addParticle(ParticleTypes.EXPLOSION, centre.x, centre.y, centre.z, 0.0, 0.0, 0.0);
        for (int i = 0; i < 8; i++) {
            client.level.addParticle(ParticleTypes.SOUL,
                    centre.x + (RNG.nextDouble() - 0.5) * BLAST_RADIUS,
                    centre.y + (RNG.nextDouble() - 0.5) * BLAST_RADIUS,
                    centre.z + (RNG.nextDouble() - 0.5) * BLAST_RADIUS,
                    0.0, 0.02, 0.0);
        }
        client.level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.PLAYERS, 0.5f, 1.6f, false);

        var server = client.getSingleplayerServer();
        if (server == null || client.player == null) {
            return;
        }
        java.util.UUID uuid = client.player.getUUID();
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) {
                return;
            }
            ServerLevel level = (ServerLevel) sp.level();
            AABB blast = AABB.ofSize(centre, BLAST_RADIUS * 2, BLAST_RADIUS * 2, BLAST_RADIUS * 2);
            var source = level.damageSources().playerAttack(sp);
            for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, blast,
                    e -> e.isAlive() && e != sp)) {
                // The BOX is a box; the blast is a sphere. Without this the corners reach 5.2 blocks while the
                // faces reach 3, which is the same axis-dependent reach this file was written to get rid of.
                if (target.getBoundingBox().distanceToSqr(centre) > BLAST_RADIUS * BLAST_RADIUS) {
                    continue;
                }
                target.hurtServer(level, source, BLAST_DAMAGE);
                // Vanilla's own convention: the vector points FROM the target TO the blast, and knockback()
                // pushes the other way.
                com.killer560.hub.compat.McEntities.knockback(
                        target, BLAST_KNOCKBACK, centre.x - target.getX(), centre.z - target.getZ());
            }
        });
    }
}
