package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The Terminator, and its Salvation beam.
 *
 * <p>killer560 (2026-09-28): "add terminator to the items [...] For arrows make sure it does its salvation
 * ability as well."
 *
 * <p>Taken from the wiki rather than guessed (checked 2026-09-28): a shot is THREE arrows - one along the look
 * and two at offset angles - on a 0.5 second cooldown. Salvation arms after three hits land, and fires a beam
 * that penetrates up to FIVE enemies, always crits, and reaches 32 blocks.
 *
 * <p>That five-enemy limit is the interesting part and the reason Salvation is worth modelling at all: it is
 * the one thing in the sim that pierces, and the difference between lining a corridor up for it and not is a
 * real routing decision. The mage beam pierces nothing; this pierces five and then stops.
 */
public final class SimTerminator {

    public static final String ITEM_ID = "TERMINATOR";

    /** Three arrows a shot: one straight, two angled. */
    private static final int ARROWS_PER_SHOT = 3;

    /** Degrees either side of the look for the two outer arrows. */
    private static final double SPREAD_DEGREES = 8.0;

    /** How far an arrow reaches before it is considered to have missed. */
    private static final double ARROW_RANGE = 40.0;

    /** Hits needed before Salvation arms. */
    private static final int HITS_TO_ARM = 3;

    /** Salvation penetrates up to five enemies and no more. */
    private static final int SALVATION_PIERCE = 5;

    /** Salvation's reach. */
    private static final double SALVATION_RANGE = 32.0;

    /** Enough to kill anything in the sim outright; sim mobs have one health anyway. */
    private static final float DAMAGE = 10_000f;

    /**
     * The distinct mobs hit since Salvation last fired.
     *
     * <p>Distinct MOBS, not arrow hits. killer560 (2026-09-30): "it should only go off after 3 mobs have been
     * hit not every hit." A shot is three arrows, so counting hits armed Salvation on a single mob that took
     * all three - which is every shot at close range, and is why it felt like it went off constantly.
     */
    private static final java.util.Set<java.util.UUID> MOBS_HIT = new java.util.HashSet<>();

    private static boolean salvationArmed;

    /**
     * Client tick the last shot went out, for the fire rate.
     *
     * <p>Starts far enough in the past that the FIRST click always fires. At zero it sat inside its own
     * cooldown for the first quarter second of every session, which reads exactly like the weapon ignoring
     * the click.
     */
    private static int lastShotTick = -1000;

    /** Counts client ticks, so the cooldown does not depend on frame rate. */
    private static int tickCounter;

    /**
     * Ticks between shots - the SHORTBOW rate, from Hypixel's own formula.
     *
     * <p>Was 10, which is the ZERO attack speed value; at the 100 Attack Speed the sim assumes it is 5, i.e.
     * 4.00 shots a second. See {@link SimAttackSpeed} for the formulas and why bows round differently from
     * melee.
     */
    private static final int SHOT_COOLDOWN_TICKS = SimAttackSpeed.SHORTBOW_INTERVAL_TICKS;

    private SimTerminator() {
    }

    public static void reset() {
        MOBS_HIT.clear();
        salvationArmed = false;
        lastShotTick = -1000;
        tickCounter = 0;
    }

    /** Whether enough time has passed since the last shot, and claims the slot if so. */
    public static boolean readyToFire() {
        if (tickCounter - lastShotTick < SHOT_COOLDOWN_TICKS) {
            return false;
        }
        lastShotTick = tickCounter;
        return true;
    }

    /** Advances the fire-rate clock; called once per client tick. */
    public static void tick() {
        tickCounter++;
    }

    public static boolean isSalvationArmed() {
        return salvationArmed;
    }

    /**
     * One use of the Terminator: Salvation if it is armed, otherwise the three-arrow shot.
     *
     * @return whether anything was fired
     */
    public static boolean use(Minecraft client) {
        if (!SimState.canAct(client)) {
            return false;
        }
        if (salvationArmed) {
            salvation(client);
            salvationArmed = false;
            MOBS_HIT.clear();
            return true;
        }
        shoot(client);
        return true;
    }

    /**
     * The ordinary shot: three arrows, the outer two angled off the look.
     *
     * <p>Hitscan rather than real arrow entities. Real projectiles would be more faithful, but the sim's mobs
     * die to anything and what is being practised is aim and routing, not arrow drop - and three entities per
     * shot across a whole clear is a lot of entities for no gain.
     */
    private static void shoot(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        float yaw = client.player.getYRot();
        float pitch = client.player.getXRot();
        List<UUID> hits = new ArrayList<>();
        for (int i = 0; i < ARROWS_PER_SHOT; i++) {
            double offset = (i - (ARROWS_PER_SHOT - 1) / 2.0) * SPREAD_DEGREES;
            Vec3 dir = fromAngles(yaw + (float) offset, pitch);
            Entity hit = firstAlong(client, eye, dir, ARROW_RANGE, 1).stream().findFirst().orElse(null);
            if (hit != null && !hits.contains(hit.getUUID())) {
                hits.add(hit.getUUID());
            }
        }
        applyDamage(client, hits);
        if (!hits.isEmpty()) {
            // `hits` is already the distinct UUIDs hit by this shot - the loop above refuses duplicates -
            // so the set accumulates distinct mobs ACROSS shots, which is what "3 mobs have been hit" means.
            MOBS_HIT.addAll(hits);
            if (MOBS_HIT.size() >= HITS_TO_ARM && !salvationArmed) {
                salvationArmed = true;
                ModChat.send("Sim", ModChat.value("Salvation ready"));
            }
        }
    }

    /** The beam: straight along the look, through up to five enemies, then it stops. */

    /**
     * Red dust along the Salvation beam. killer560 (2026-09-30): "make it red particles instead."
     *
     * <p>Drawn server-side with sendParticles so every player in the sim sees it, and spaced a third of a
     * block apart so the line reads as a beam rather than a dotted trail.
     */
    private static void salvationParticles(net.minecraft.server.level.ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 along = to.subtract(from);
        double length = along.length();
        if (length < 1.0E-4) {
            return;
        }
        Vec3 step = along.scale(1.0 / length).scale(0.33);
        var dust = new net.minecraft.core.particles.DustParticleOptions(0xFF3030, 1.0f);
        for (double travelled = 0; travelled <= length; travelled += 0.33) {
            Vec3 at = from.add(step.scale(travelled / 0.33));
            level.sendParticles(dust, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
    private static void salvation(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 dir = fromAngles(client.player.getYRot(), client.player.getXRot());
        List<Entity> pierced = firstAlong(client, eye, dir, SALVATION_RANGE, SALVATION_PIERCE);
        applyDamage(client, pierced.stream().map(Entity::getUUID).toList());
        // The visible half, in red.
        var server = client.getSingleplayerServer();
        if (server != null && client.player != null) {
            Vec3 end = eye.add(dir.scale(SALVATION_RANGE));
            java.util.UUID who = client.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(who);
                if (sp != null) {
                    salvationParticles((net.minecraft.server.level.ServerLevel) sp.level(), eye, end);
                }
            });
        }
        ModChat.send("Sim", ModChat.text("Salvation - "), ModChat.value(String.valueOf(pierced.size())),
                ModChat.text(" hit"));
    }

    /**
     * The nearest {@code limit} living entities along a ray, in order.
     *
     * <p>Ordered by distance and then cut, rather than taking whatever the entity list happened to contain -
     * "penetrates up to five" means the five NEAREST, and a beam that skipped the mob in front to hit one
     * behind it would be wrong in the way that matters for lining a shot up.
     */
    private static List<Entity> firstAlong(Minecraft client, Vec3 eye, Vec3 dir, double range, int limit) {
        Vec3 end = eye.add(dir.scale(range));
        BlockHitResult wall = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        Vec3 stop = wall != null && wall.getType() == HitResult.Type.BLOCK ? wall.getLocation() : end;

        List<Entity> found = new ArrayList<>();
        for (Entity e : client.level.getEntities(client.player, new AABB(eye, stop).inflate(1.0),
                e -> e instanceof LivingEntity && e.isAlive() && e != client.player)) {
            if (e.getBoundingBox().inflate(0.3).clip(eye, stop).isPresent()) {
                found.add(e);
            }
        }
        found.sort(Comparator.comparingDouble(e -> eye.distanceToSqr(e.position())));
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    private static void applyDamage(Minecraft client, List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        List<UUID> copy = List.copyOf(ids);
        UUID shooter = client.player == null ? null : client.player.getUUID();
        if (shooter == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            var sp = server.getPlayerList().getPlayer(shooter);
            if (sp == null) {
                return;
            }
            // playerAttack, NOT magic() - see the same note in SimClass.fire. SimMobs treats a source with no
            // entity behind it as environmental and makes every 1 HP sim mob invulnerable to it, so the
            // terminator's shots landed and did nothing.
            var source = level.damageSources().playerAttack(sp);
            for (UUID id : copy) {
                if (level.getEntity(id) instanceof LivingEntity living && living.isAlive()) {
                    living.hurtServer(level, source, DAMAGE);
                }
            }
        });
    }

    /** Vanilla's own yaw/pitch to direction, so an angled arrow goes where the crosshair says it would. */
    private static Vec3 fromAngles(float yaw, float pitch) {
        float y = -yaw * ((float) Math.PI / 180f);
        float p = -pitch * ((float) Math.PI / 180f);
        double cosP = Math.cos(p);
        return new Vec3(Math.sin(y) * cosP, Math.sin(p), Math.cos(y) * cosP);
    }
}
