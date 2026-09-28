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

import java.util.List;

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

    /** Half-width of the beam. A hitscan line misses a mob you were clearly aiming at; this is a corridor. */
    private static final double BEAM_RADIUS = 1.0;

    /** Far above any sim mob's health, so nothing survives a hit it visibly took. */
    private static final float BEAM_DAMAGE = 10_000f;

    /** Vanilla's own left-click rate is the cap. Faster is not more practice, it is just more packets. */
    private static final int BEAM_COOLDOWN_TICKS = 4;

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
        // Edge OR held: the beam is a weapon, and requiring a fresh click per mob would make a room of mobs a
        // finger exercise rather than a route exercise.
        if (!attacking) {
            wasAttacking = false;
            return;
        }
        wasAttacking = true;
        if (tickCounter - lastBeamTick < BEAM_COOLDOWN_TICKS) {
            return;
        }
        lastBeamTick = tickCounter;
        fire(client);
    }

    /** Fires the beam: everything living in a corridor along the look vector, up to the first wall. */
    private static void fire(Minecraft client) {
        var player = client.player;
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(BEAM_RANGE));

        // Stop at the wall. A beam that shoots through the room boundary would kill the next room's mobs before
        // he has opened the door to it, which would quietly ruin the thing being practised.
        BlockHitResult wall = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 stop = wall != null && wall.getType() == HitResult.Type.BLOCK ? wall.getLocation() : end;

        AABB corridor = new AABB(eye, stop).inflate(BEAM_RADIUS);
        List<Entity> hits = client.level.getEntities(player, corridor,
                e -> e instanceof LivingEntity && e.isAlive() && e != player);
        if (hits.isEmpty()) {
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        List<java.util.UUID> ids = hits.stream().map(Entity::getUUID).toList();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (java.util.UUID id : ids) {
                Entity target = level.getEntity(id);
                if (target instanceof LivingEntity living && living.isAlive()) {
                    living.hurtServer(level, level.damageSources().magic(), BEAM_DAMAGE);
                }
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
