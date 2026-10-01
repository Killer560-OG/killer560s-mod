package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
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
 *
 * <h2>The arrows are real entities, and that is the whole of the 2026-09-30 fix</h2>
 *
 * <p>killer560 (2026-09-30): "The terminator still does not shoot 3 arrows or shoot like a normal shortbow
 * would." It already fired three and already needed no ammunition - but the three were HITSCAN, so there was
 * nothing in the air to see. From where he stands, a weapon that fires three invisible arrows and a weapon
 * that fires none are the same weapon. So the shot now spawns three real {@link Arrow} entities on the
 * integrated server's own thread, at the velocity a fully-drawn vanilla bow gives, and vanilla does the
 * flight, the drop and the damage.
 *
 * <p>The hitscan damage went with it. If the arrows do the damage, a second helping from a ray cast is a
 * double hit, and every mob in the sim has one health - so it would have been invisible in the only place it
 * could be measured.
 *
 * <p>The arrows cannot be picked up ({@link AbstractArrow.Pickup#DISALLOWED}) and discard themselves shortly
 * after they stop, so a practice clear at four shots a second does not carpet the floor.
 *
 * <p>Salvation's arming had to move with the damage. It arms on THREE DISTINCT MOBS - killer560's own rule,
 * "it should only go off after 3 mobs have been hit not every hit" - and a shot is three arrows, so counting
 * arrow hits would arm it on a single mob that took all three. The count therefore comes from
 * {@link ServerLivingEntityEvents#ALLOW_DAMAGE}, the hook {@code SimSurvival} already uses, filtered to
 * damage whose DIRECT entity is one of this weapon's own arrows. That is what keeps "distinct mobs across
 * shots" exactly as it was.
 */
public final class SimTerminator {

    public static final String ITEM_ID = "TERMINATOR";

    /** Three arrows a shot: one straight, two angled. */
    private static final int ARROWS_PER_SHOT = 3;

    /** Degrees either side of the look for the two outer arrows. */
    private static final double SPREAD_DEGREES = 8.0;

    /** How far the Salvation beam's ray cast reaches before it is considered to have missed. */
    private static final double ARROW_RANGE = 40.0;

    /**
     * Launch velocity, in vanilla's own units - {@code BowItem} fires a fully drawn bow at 3.0.
     *
     * <p>"there is no drawing the bow" (killer560, 2026-09-30) means every shot leaves at full power, so this
     * is a constant rather than something derived from a draw time the sim deliberately does not have.
     */
    private static final float ARROW_VELOCITY = 3.0f;

    /** Zero, because the 8-degree spread above is the spread. Vanilla's bow uses 1.0 for its own wobble. */
    private static final float ARROW_INACCURACY = 0.0f;

    /** Hard ceiling on an arrow's life, so one that flies out of a doorway still goes away. */
    private static final int ARROW_LIFE_TICKS = 60;

    /** Ticks an arrow may sit in a wall before it is discarded - see the class doc on carpeting the floor. */
    private static final int ARROW_GROUND_TICKS = 10;

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
     *
     * <p>Concurrent because it is written from the SERVER thread (the damage hook) and cleared from the
     * CLIENT thread (the moment Salvation fires).
     */
    private static final java.util.Set<java.util.UUID> MOBS_HIT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Written by the server thread's damage hook, read by the client thread's use(). */
    private static volatile boolean salvationArmed;

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

    /** Arrows actually put in the world, so a test can prove the weapon acted rather than trust a log line. */
    private static volatile int arrowsFired;

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

    /**
     * Call once from {@code Killer560ModClient#onInitializeClient}.
     *
     * <p>Registers the one thing this file cannot do from the client: notice that one of its own arrows landed.
     * {@code ServerLivingEntityEvents.ALLOW_DAMAGE} is the hook {@code SimSurvival} already uses, so this adds
     * no new mechanism - and the filter is on the DIRECT entity being a {@link TerminatorArrow}, which is a
     * class only this file constructs, so no other arrow in any world can be mistaken for one.
     */
    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (SimState.isActive()
                    && source.getDirectEntity() instanceof TerminatorArrow arrow
                    && entity != arrow.getOwner()) {
                recordHit(entity);
            }
            // Never refuses anything - this listener is only here to count.
            return true;
        });
    }

    public static void reset() {
        MOBS_HIT.clear();
        salvationArmed = false;
        lastShotTick = -1000;
        tickCounter = 0;
        arrowsFired = 0;
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

    /** Distinct mobs hit since Salvation last fired. For tests, which cannot see the set itself. */
    public static int mobsHit() {
        return MOBS_HIT.size();
    }

    /** Arrow entities put in the world since {@link #reset}. A shot that fired nothing cannot hide behind this. */
    public static int arrowsFired() {
        return arrowsFired;
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
     * The ordinary shot: three real arrows, the outer two angled off the look.
     *
     * <p>Spawned on the integrated server's thread against the server's own player, like every other world
     * write in this package - see {@code SimDoors}' class doc. The yaw and pitch are read on the client,
     * because that is the only place that knows where the crosshair is right now; only the spawn crosses over.
     */
    private static void shoot(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null || client.player == null) {
            return;
        }
        final float yaw = client.player.getYRot();
        final float pitch = client.player.getXRot();
        final UUID who = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(who);
            if (sp == null || !(sp.level() instanceof ServerLevel level)) {
                return;
            }
            for (int i = 0; i < ARROWS_PER_SHOT; i++) {
                double offset = (i - (ARROWS_PER_SHOT - 1) / 2.0) * SPREAD_DEGREES;
                Vec3 dir = fromAngles(yaw + (float) offset, pitch);
                TerminatorArrow arrow = new TerminatorArrow(level, sp);
                arrow.shoot(dir.x, dir.y, dir.z, ARROW_VELOCITY, ARROW_INACCURACY);
                if (level.addFreshEntity(arrow)) {
                    arrowsFired++;
                }
            }
            level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ARROW_SHOOT,
                    SoundSource.PLAYERS, 1.0f, 1.0f);
        });
    }

    /**
     * One of this weapon's arrows landed on something.
     *
     * <p>Runs on the SERVER thread. Distinct mobs only, and the chat line is bounced back to the client
     * thread, which is where {@code ModChat} belongs.
     */
    private static void recordHit(LivingEntity target) {
        if (target == null || salvationArmed || !MOBS_HIT.add(target.getUUID())) {
            return;
        }
        if (MOBS_HIT.size() >= HITS_TO_ARM) {
            salvationArmed = true;
            Minecraft.getInstance().execute(() ->
                    ModChat.send("Sim", ModChat.value("Salvation ready")));
        }
    }

    /**
     * Red dust along the Salvation beam. killer560 (2026-09-30): "make it red particles instead."
     *
     * <p>Drawn server-side with sendParticles so every player in the sim sees it, and spaced a third of a
     * block apart so the line reads as a beam rather than a dotted trail.
     */
    private static void salvationParticles(ServerLevel level, Vec3 from, Vec3 to) {
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

    /**
     * The beam: straight along the look, through up to five enemies, then it stops.
     *
     * <p>Still hitscan, and deliberately: Salvation IS a beam on Hypixel. It is the ordinary shot that had no
     * business being one.
     */
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
                    salvationParticles((ServerLevel) sp.level(), eye, end);
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

    /**
     * A vanilla arrow that knows it came out of the Terminator, and tidies itself up.
     *
     * <p>A SUBCLASS rather than a tracked set of UUIDs, because the damage hook then needs no bookkeeping and
     * nothing can leak: {@code instanceof TerminatorArrow} is the whole test, and an arrow that despawns takes
     * its own identity with it. The entity TYPE is still {@code EntityType.ARROW} - the four-argument
     * {@link Arrow} constructor hard-codes it (javap-verified against the 26.1.2 merged jar) - so the client
     * builds an ordinary arrow from the AddEntity packet and nothing needs registering. Same trick as
     * {@code SimMobs.SimZombie}.
     *
     * <p>{@code inGroundTime} is {@code protected} on {@link AbstractArrow}, which is why the cleanup can live
     * here and needed no accessor mixin.
     */
    private static final class TerminatorArrow extends Arrow {

        private int livedTicks;

        TerminatorArrow(Level level, LivingEntity owner) {
            // Positions itself at the owner's eye and calls setOwner - both verified in the 26.1.2 bytecode.
            //
            // THE WEAPON MUST BE A REAL WEAPON. This argument was ItemStack.EMPTY, and 26.1.2's AbstractArrow
            // constructor refuses that outright, so EVERY shot threw before an arrow existed:
            //
            //   java.lang.IllegalArgumentException: Invalid weapon firing an arrow
            //     at AbstractArrow.<init>(AbstractArrow.java:126)
            //     at Arrow.<init>(Arrow.java:38)
            //     at SimTerminator$TerminatorArrow.<init>(SimTerminator.java:406)
            //     at SimTerminator.lambda$shoot$0(SimTerminator.java:249)
            //
            // Thrown on the server thread inside the shoot lambda, which is why it never reached him as a crash
            // and read instead as the weapon doing nothing at all - killer560 (2026-10-01): "I still cannot
            // shoot the terminator without an arrow. Nor does it perform like a shortbow or shoot 3 arrows."
            // All three of those are this one exception: arrowsFired could never increment, so the earlier note
            // that the Terminator "has fired three arrows since it was written, invisibly" was wrong. It has
            // never fired one.
            //
            // A bow satisfies it and is the honest answer anyway - a shortbow is what fires these on Hypixel.
            super(level, owner, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            // "make it so I can shoot without arrows" cuts both ways: none are consumed and none are given
            // back either, so the pickup item above is never reachable.
            pickup = Pickup.DISALLOWED;
            setBaseDamage(DAMAGE);
            setCritArrow(true);
        }

        @Override
        public void tick() {
            super.tick();
            if (++livedTicks > ARROW_LIFE_TICKS || inGroundTime > ARROW_GROUND_TICKS) {
                discard();
            }
        }
    }
}
