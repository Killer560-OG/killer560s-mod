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
        redrawLingeringBeam(client);
        SimTerminator.tick();
        if (!pressed || tickCounter - lastBeamTick < BEAM_COOLDOWN_TICKS) {
            return;
        }
        // THE TERMINATOR FIRES ON LEFT CLICK TOO, and the beam must not also go off.
        //
        // killer560 (2026-09-30): "it needs to function like a shortbow. So on left or right click it shoots
        // 3 arrows". Holding the Terminator therefore takes the left click entirely - the mage beam is what
        // you get with any OTHER item in hand.
        var held = client.player == null ? null : client.player.getMainHandItem();
        if (held != null && SimTerminator.ITEM_ID.equals(
                com.killer560.hub.cheatutils.CheatUtils.skyblockId(held))) {
            if (SimTerminator.readyToFire()) {
                SimTerminator.use(client, true);
            }
            return;
        }
        // And not immediately after an ability. killer560 (2026-09-30): "Dont make hyperion left click when i
        // teleport as well." A right-click ability makes the client swing, which reads here as a left click
        // and fired the beam on every teleport.
        if (SimAbilities.usedAbilityRecently()) {
            return;
        }
        lastBeamTick = tickCounter;
        fire(client);
    }

    /**
     * Keeps the last beam on screen for a few ticks instead of one.
     *
     * <p>killer560 (2026-09-29): "for the mage beam have it last a bit longer." It was drawn once, in the tick
     * it fired, and an ELECTRIC_SPARK lives well under half a second - so at 20 ticks a second the line was
     * gone almost before he had finished the click that made it. Nothing about the hit depended on the
     * drawing, so what he was judging his aim by was a flicker.
     *
     * <p>Re-emitted from the STORED endpoints rather than re-fired, which matters: re-running {@link #fire}
     * would re-aim at wherever the camera has drifted to since and would hit again. This draws the same line
     * the shot actually took, for as long as {@link #BEAM_LINGER_TICKS}, and the particles' own fade does the
     * rest.
     */
    private static void redrawLingeringBeam(Minecraft client) {
        if (lingerTicks <= 0 || lingerFrom == null || lingerTo == null || client.level == null) {
            return;
        }
        lingerTicks--;
        drawBeam(client, lingerFrom, lingerTo);
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
    /**
     * Particles along the beam.
     *
     * <p>Client side, because it is a picture rather than a fact - nothing about the hit depends on it, and
     * spawning particles through the server for something only he sees would be a packet per point.
     *
     * <p>Spaced by distance rather than a fixed count, so a short beam is not a dense clot and a long one is
     * not a dotted line. Started slightly out from the eye, or the first particles sit inside his own head.
     */
    private static void drawBeam(Minecraft client, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 0.01) {
            return;
        }
        Vec3 dir = delta.scale(1.0 / length);
        for (double d = BEAM_PARTICLE_START; d < length; d += BEAM_PARTICLE_SPACING) {
            Vec3 at = from.add(dir.scale(d));
            client.level.addParticle(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK,
                    at.x, at.y, at.z, 0.0, 0.0, 0.0);
        }
    }

    /** How far apart the beam's particles sit, and how far out the first one starts. */
    private static final double BEAM_PARTICLE_SPACING = 0.4;
    private static final double BEAM_PARTICLE_START = 1.0;

    /**
     * Ticks the beam keeps being re-drawn after the shot - see {@link #redrawLingeringBeam}.
     *
     * <p>Eight, which is a little under half a second and slightly shorter than the beam's own cooldown, so
     * two shots in a row never overlap into one continuous line.
     */
    private static final int BEAM_LINGER_TICKS = 8;

    private static Vec3 lingerFrom;
    private static Vec3 lingerTo;
    private static int lingerTicks;

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
        // The line he can see. killer560 (2026-09-28): "the sim needs to generate a particle line for the mage
        // beam."
        //
        // Drawn to where the beam ACTUALLY stopped - the mob it hit, or the wall, or its full range - rather
        // than always to maximum range. A beam whose particles overshoot the thing it killed would teach the
        // wrong idea of its reach, and reach is most of what aiming one is.
        Vec3 visibleEnd = nearest != null
                ? nearest.getBoundingBox().getCenter()
                : stop;
        drawBeam(client, eye, visibleEnd);
        lingerFrom = eye;
        lingerTo = visibleEnd;
        lingerTicks = BEAM_LINGER_TICKS;

        if (nearest == null) {
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        java.util.UUID id = nearest.getUUID();
        java.util.UUID shooter = player.getUUID();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            var sp = server.getPlayerList().getPlayer(shooter);
            if (sp == null) {
                return;
            }
            if (level.getEntity(id) instanceof LivingEntity living && living.isAlive()) {
                // playerAttack, NOT magic(). A sim mob has 1 HP, so SimMobs blocks every damage source with no
                // entity behind it - otherwise a practice target suffocates on the ceiling before he reaches
                // it. damageSources().magic() has neither an entity nor a direct entity, so it was classified
                // as environmental and the beam was silently harmless: scenario 70 fired at a mob five blocks
                // in front, with the client holding all four mobs, and nothing died. The sceptre and superboom
                // already name the player here; these two call sites were left behind.
                living.hurtServer(level, level.damageSources().playerAttack(sp), BEAM_DAMAGE);
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
