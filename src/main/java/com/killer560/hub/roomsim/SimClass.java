package com.killer560.hub.roomsim;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;


/**
 * The only class the sim has: mage, with the left-click beam.
 *
 * <p>killer560 (2026-09-28): "make it so the only class you can be is mage with the left. Click beam. It does
 * not need the sheep or the ultimate just the beam you can make it so all mobs have one HP and all damage does
 * some amount that will one tap every mob".
 *
 * <p>So this is deliberately not a class system. There is one class, it has one attack, and that attack always
 * kills. That is enough to practise a clear - the thing being practised is the route and the order you kill
 * things in, not the damage numbers - and every piece of it that is not needed is a piece that can be wrong.
 *
 * <p>The beam is hitscan along the look vector, stopping at the first wall. Mobs in the sim have one health, so
 * "damage that one taps" is any damage at all; this uses a large number anyway so that a mob which somehow kept
 * its real health still dies rather than silently surviving and making the sim feel broken.
 */
public final class SimClass {

    /** How far the beam reaches. Long enough to cross a room, short enough not to clear the next one. */
    private static final double BEAM_RANGE = 30.0;

    /** How much a mob's hitbox is forgiven by. A bare line misses something you were clearly aiming at; this
     *  widens the target rather than widening the beam, which is what stops it being a box. */
    private static final double BEAM_RADIUS = 0.6;

    /** Far above any sim mob's health, so nothing survives a hit it visibly took. */
    private static final float BEAM_DAMAGE = 10_000f;

    /** Hypixel's 100 Attack Speed baseline, shared with every other sim weapon - killer560 asked that "all
     *  items assume 100 attack speed equivalent", so the number lives in one place. */
    private static final int BEAM_COOLDOWN_TICKS = SimAttackSpeed.BASE_ATTACK_INTERVAL_TICKS;

    private static int lastBeamTick;
    private static int tickCounter;
    private static boolean wasAttacking;

    private SimClass() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(
                FeatureGuard.start("SimClass.tick", SimClass::tick));
    }

    /**
     * Puts the player in the sim's only class.
     *
     * <p>There is nothing to switch away from, so this is mostly a message - but it is worth saying out loud
     * that the class is fixed, rather than leaving him to wonder why the class selection he expects is missing.
     */
    public static void applyMageOnly(Minecraft client) {
        ModChat.send("Sim", ModChat.text("Class: "), ModChat.value("Mage"),
                ModChat.dim(" (left-click beam; no sheep, no ultimate)"));
    }

    private static void tick(Minecraft client) {
        tickCounter++;
        if (!SimState.canAct(client) || client.options == null) {
            wasAttacking = false;
            return;
        }
        boolean attacking = client.options.keyAttack.isDown();
        // On the PRESS, not while held. He said "left click beam", and a click is an edge - holding to spam was
        // something I invented. It also mattered in testing: a beam that fires every few ticks while the key
        // reads as held keeps shooting wherever the camera happens to point, which killed mobs all over the
        // room and made every result depend on timing.
        boolean pressed = attacking && !wasAttacking;
        wasAttacking = attacking;
        if (!pressed || tickCounter - lastBeamTick < BEAM_COOLDOWN_TICKS) {
            return;
        }
        lastBeamTick = tickCounter;
        fire(client);
    }

    /**
     * Fires the beam at the FIRST mob it reaches. Zero pierce.
     *
     * <p>killer560 (2026-09-28): "The beam has 0 pierce right". It did not - it hit everything in an
     * axis-aligned box from the eye to the endpoint, which is not a corridor: look diagonally across a room and
     * that box covers most of the room. In testing it was killing mobs behind the player and in far corners,
     * which looked like the aim being broken and was really the shape being wrong.
     *
     * <p>Now it walks the actual ray, takes the nearest entity whose hitbox it crosses, and stops there - both
     * at that mob and at the first wall, so it cannot reach into the next room through a door he has not opened.
     */
    private static void fire(Minecraft client) {
        var player = client.player;
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(BEAM_RANGE));

        // The wall first: nothing past it can be hit, so it shortens the search as well as stopping the beam.
        BlockHitResult wall = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 stop = wall != null && wall.getType() == HitResult.Type.BLOCK ? wall.getLocation() : end;

        // A generous search box, then an exact ray test per candidate. The box is only a cheap way to avoid
        // testing every entity in the world; it is NOT the hit shape, which is what went wrong before.
        AABB search = new AABB(eye, stop).inflate(BEAM_RADIUS);
        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : client.level.getEntities(player, search,
                e -> e instanceof LivingEntity && e.isAlive() && e != player)) {
            AABB box = e.getBoundingBox().inflate(BEAM_RADIUS * 0.5);
            var hit = box.clip(eye, stop);
            if (hit.isEmpty()) {
                continue;
            }
            double d = eye.distanceToSqr(hit.get());
            if (d < nearestDist) {
                nearestDist = d;
                nearest = e;
            }
        }
        if (nearest == null) {
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        java.util.UUID id = nearest.getUUID();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            if (level.getEntity(id) instanceof LivingEntity living && living.isAlive()) {
                living.hurtServer(level, level.damageSources().magic(), BEAM_DAMAGE);
            }
        });
    }

    /** Where the beam would stop, for anything that wants to draw it. */
    public static BlockPos beamEnd(Minecraft client) {
        if (client.player == null || client.level == null) {
            return null;
        }
        Vec3 eye = client.player.getEyePosition();
        Vec3 end = eye.add(client.player.getViewVector(1.0f).scale(BEAM_RANGE));
        BlockHitResult wall = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return wall == null ? null : wall.getBlockPos();
    }
}
