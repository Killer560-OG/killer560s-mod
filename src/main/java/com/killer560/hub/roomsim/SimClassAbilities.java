package com.killer560.hub.roomsim;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.util.ModLog;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The sim's class abilities (simclasses-1116), on the sim's server, as Hypixel does them: the Drop action (Q,
 * {@code DROP_ITEM}) is the class ULTIMATE and Drop Stack (Ctrl+Q, {@code DROP_ALL_ITEMS}) the class ABILITY
 * ({@code SimDropActionMixin}); in a sim nothing is ever dropped. Cooldowns are server ticks; the chat lines are the
 * ones in killer560's 26.3 logs ("Used Healing Circle!", "Healing Circle is now available!", "Your Archer ULTIMATE
 * Rapid Fire is now available!", "Thunderstorm is ready to use! Press DROP to activate it!", "This ability is on
 * cooldown for 2s."). Numbers are the wiki's class pages and his logs; what is estimated is listed in docs/SIM.md.
 * Sim mobs have one health, so every hit kills; the damage passives (Bloodlust, Doubleshot...) change nothing here.
 */
public final class SimClassAbilities {

    private static final Logger LOGGER = ModLog.get("killer560smod-sim");
    private static final float LETHAL = 10_000f;
    /** "X is ready to use! Press DROP to activate it!" while an ultimate sits unused: 21 s after GO, then every 30 s. */
    private static final int FIRST_REMINDER = 21 * 20;
    private static final int REMINDER_EVERY = 30 * 20;

    /** One player's cooldowns and running effects. Server thread; one per player in a live sim, cleared with it. */
    private static final class State {
        /** The class these cooldowns belong to. */
        DungeonClass cls;
        int abilityReadyAt;
        int ultReadyAt;
        boolean abilityPending;
        boolean ultPending;
        int nextReminder = Integer.MAX_VALUE;
        /** The running ultimate (Thunderstorm, Rapid Fire, Ragnarok, Castle of Stone) and when it ends. */
        DungeonClass active;
        int activeUntil;
        /** Healing Circle: where, how big, until when. */
        Vec3 circle;
        double circleRadius;
        int circleUntil;
        /** Guided Sheep in flight. */
        Sheep sheep;
        Vec3 sheepStep;
        int sheepUntil;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static volatile int casts;

    private SimClassAbilities() {
    }

    /** Casts so far (abilities and ultimates), for tests. */
    public static int castsForTest() { return casts; }

    private static State state(ServerPlayer sp) {
        return STATES.computeIfAbsent(sp.getUUID(), k -> new State());
    }

    /**
     * Server thread: a Drop ({@code ultimate}) or Drop Stack action from a player in a live sim. Returns true when it
     * was taken (always, in a sim: Hypixel never drops an item for either key in a dungeon).
     */
    public static boolean onDrop(ServerPlayer sp, boolean ultimate) {
        if (!SimAbilities.simServer(sp) || !(sp.level() instanceof ServerLevel level)) return false;
        sp.containerMenu.sendAllDataToRemote();
        SimClasses.Pick pick = SimClasses.of(sp);
        State s = state(sp);
        if (s.cls != pick.cls()) switchClass(s, pick.cls());
        int now = level.getServer().getTickCount();
        int readyAt = ultimate ? s.ultReadyAt : s.abilityReadyAt;
        if (now < readyAt) {
            sp.sendSystemMessage(Component.literal("This ability is on cooldown for " + ((readyAt - now + 19) / 20) + "s.")
                    .withStyle(ChatFormatting.RED));
            return true;
        }
        boolean solo = SimClasses.onlyOne(level.getServer(), sp);
        int cooldown = cooldownTicks(pick, ultimate, solo);
        if (ultimate) {
            s.ultReadyAt = now + cooldown;
            s.ultPending = true;
            s.nextReminder = Integer.MAX_VALUE;
        } else {
            s.abilityReadyAt = now + cooldown;
            s.abilityPending = true;
        }
        casts++;
        String name = ultimate ? ultimateName(pick.cls()) : abilityName(pick.cls());
        LOGGER.info("[SimClass] {} used {} ({} {}), cooldown {} ticks", sp.getGameProfile().name(), name,
                SimClasses.name(pick.cls()), pick.level(), cooldown);
        // Wish says its heal line instead (no "Used Wish!" in his logs, 37 heal lines).
        if (!(ultimate && pick.cls() == DungeonClass.HEALER)) {
            sp.sendSystemMessage(Component.literal("Used ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(name).withStyle(ChatFormatting.GOLD))
                    .append(Component.literal("!").withStyle(ChatFormatting.GREEN)));
        }
        cast(level, sp, pick, ultimate, s, now);
        return true;
    }

    public static String abilityName(DungeonClass c) {
        return switch (c) {
            case HEALER -> "Healing Circle";
            case MAGE -> "Guided Sheep";
            case BERSERKER -> "Throwing Axe";
            case ARCHER -> "Explosive Shot";
            case TANK -> "Seismic Wave";
        };
    }

    public static String ultimateName(DungeonClass c) {
        return switch (c) {
            case HEALER -> "Wish";
            case MAGE -> "Thunderstorm";
            case BERSERKER -> "Ragnarok";
            case ARCHER -> "Rapid Fire";
            case TANK -> "Castle of Stone";
        };
    }

    /**
     * Cooldown in ticks. Wiki: Healing Circle 2 s, Wish 120 s, Guided Sheep 30 s, Throwing Axe 10 s, Ragnarok 60 s,
     * Explosive Shot 40 s less 2 s every 5 levels (20 s at 50), Seismic Wave 15 s, Castle of Stone 150 s; his logs:
     * Rapid Fire 100 s, Thunderstorm 500 s base (his 154 s). Mage takes Efficient Spells' 25% off every one plus 1% every
     * other level, that part doubled as the only Mage: 69% off fits both his Guided Sheep (9-10 s) and Thunderstorm.
     */
    static int cooldownTicks(SimClasses.Pick pick, boolean ultimate, boolean solo) {
        int level = pick.level();
        double seconds = switch (pick.cls()) {
            case HEALER -> ultimate ? 120 : 2;
            case MAGE -> ultimate ? 500 : 30;
            case BERSERKER -> ultimate ? 60 : 10;
            case ARCHER -> ultimate ? 100 : 40 - 2 * (level / 5);
            case TANK -> ultimate ? 150 : 15;
        };
        if (pick.cls() == DungeonClass.MAGE) {
            seconds *= 1 - Math.min(0.75, 0.25 + (level / 2) * (solo ? 0.02 : 0.01));
        }
        return (int) Math.round(seconds * 20);
    }

    private static void cast(ServerLevel level, ServerPlayer sp, SimClasses.Pick pick, boolean ultimate, State s, int now) {
        int lv = pick.level();
        switch (pick.cls()) {
            case HEALER -> {
                if (ultimate) {
                    wish(level, sp, lv);
                } else {
                    // A healing orb lands where he looks (12 blocks at most) and heals there: radius 3 + 0.5 and duration
                    // 2 s + 1 s every 5 levels (base radius and duration are estimates).
                    s.circle = rayEnd(level, sp, 12);
                    s.circleRadius = 3 + 0.5 * (lv / 5);
                    s.circleUntil = now + 20 * (2 + lv / 5);
                    level.playSound(null, s.circle.x, s.circle.y, s.circle.z, SoundEvents.SPLASH_POTION_BREAK, SoundSource.PLAYERS, 1f, 1f);
                }
            }
            case MAGE -> {
                if (ultimate) {
                    s.active = DungeonClass.MAGE;
                    s.activeUntil = now + 15 * 20;
                } else {
                    launchSheep(level, sp, s, now);
                }
            }
            case BERSERKER -> {
                if (ultimate) {
                    s.active = DungeonClass.BERSERKER;
                    s.activeUntil = now + 15 * 20;
                    sp.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 15 * 20, 0));
                } else {
                    Vec3 eye = sp.getEyePosition();
                    LivingEntity hit = firstAlong(level, sp, 20);
                    Vec3 end = hit != null ? hit.getBoundingBox().getCenter() : rayEnd(level, sp, 20);
                    trail(level, eye, end, ParticleTypes.CRIT);
                    if (hit != null) hurt(level, sp, hit);
                }
            }
            case ARCHER -> {
                if (ultimate) {
                    s.active = DungeonClass.ARCHER;
                    s.activeUntil = now + 20 * (4 + lv / 10);
                } else {
                    LivingEntity hit = firstAlong(level, sp, 50);
                    Vec3 at = hit != null ? hit.getBoundingBox().getCenter() : rayEnd(level, sp, 50);
                    trail(level, sp.getEyePosition(), at, ParticleTypes.CRIT);
                    level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 3, 0.5, 0.5, 0.5, 0);
                    level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1f, 1f);
                    hitLine(sp, "Explosive Shot", blast(level, sp, at, 4));
                }
            }
            case TANK -> {
                if (ultimate) {
                    s.active = DungeonClass.TANK;
                    s.activeUntil = now + 20 * 20;
                    // 70% damage reduction for 20 s; vanilla Resistance III is 60% (the nearest it has).
                    sp.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 20, 2));
                } else {
                    seismicWave(level, sp);
                }
            }
        }
    }

    /** Wish: every player in the sim to full health and an absorption shield of 20% of max health, +1% a level. */
    private static void wish(ServerLevel level, ServerPlayer caster, int lv) {
        double total = 0;
        double shieldOfCaster = 0;
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (!p.isAlive() || p.level() != level) continue;
            float healed = p.getMaxHealth() - p.getHealth();
            float shield = p.getMaxHealth() * (0.20f + 0.01f * lv);
            p.setHealth(p.getMaxHealth());
            // Absorption is capped by MAX_ABSORPTION (0 without the effect), so the effect lifts the cap first.
            // Estimated: the shield lasts 30 s (no duration on the wiki).
            p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 30 * 20, Math.max(0, (int) Math.ceil(shield / 4) - 1)));
            p.setAbsorptionAmount(Math.max(p.getAbsorptionAmount(), shield));
            total += healed;
            if (p == caster) {
                shieldOfCaster = shield;
                p.sendSystemMessage(Component.literal("Your Wish healed you for " + num(healed)
                        + " health and granted you an absorption shield with " + num(shield) + " health!").withStyle(ChatFormatting.GREEN));
            } else {
                p.sendSystemMessage(Component.literal(caster.getGameProfile().name() + "'s Wish healed you for " + num(healed)
                        + " health and granted you an absorption shield with " + num(shield) + " health!").withStyle(ChatFormatting.GREEN));
            }
            level.sendParticles(ParticleTypes.HEART, p.getX(), p.getY() + 2, p.getZ(), 6, 0.4, 0.3, 0.4, 0);
        }
        caster.sendSystemMessage(Component.literal("Your Wish healed your entire team for " + num(total)
                + " health and shielded them for " + num(shieldOfCaster) + "!").withStyle(ChatFormatting.GREEN));
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1.5f);
    }

    /** A sheep runs along his look (0.6 blocks a tick, 3 s at most) and blows up on the first mob or wall, 4 blocks round. */
    private static void launchSheep(ServerLevel level, ServerPlayer sp, State s, int now) {
        removeSheep(s);
        Sheep sheep = com.killer560.hub.compat.McEntities.SHEEP.create(level, EntitySpawnReason.TRIGGERED);
        if (sheep == null) return;
        Vec3 look = sp.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        flat = flat.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : flat.normalize();
        sheep.setNoAi(true);
        sheep.setNoGravity(true);
        com.killer560.hub.compat.McEntities.setInvulnerable(sheep, true);
        sheep.setPos(sp.getX() + flat.x, sp.getY(), sp.getZ() + flat.z);
        sheep.setYRot(sp.getYRot());
        if (!level.addFreshEntity(sheep)) return;
        s.sheep = sheep;
        s.sheepStep = flat.scale(0.6);
        s.sheepUntil = now + 60;
    }

    private static void tickSheep(ServerLevel level, ServerPlayer sp, State s, int now) {
        Sheep sheep = s.sheep;
        if (sheep.isRemoved()) {
            s.sheep = null;
            return;
        }
        Vec3 from = sheep.position();
        Vec3 to = from.add(s.sheepStep);
        boolean wall = level.clip(new ClipContext(from.add(0, 0.5, 0), to.add(0, 0.5, 0), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, sheep)).getType() == HitResult.Type.BLOCK;
        boolean mob = !level.getEntitiesOfClass(Mob.class, sheep.getBoundingBox().inflate(1.0),
                e -> e != sheep && e.isAlive()).isEmpty();
        if (wall || mob || now >= s.sheepUntil) {
            Vec3 at = sheep.position().add(0, 0.5, 0);
            removeSheep(s);
            level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 2, 0.3, 0.3, 0.3, 0);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1f, 1f);
            hitLine(sp, "Guided Sheep", blast(level, sp, at, 4));
            return;
        }
        sheep.setPos(to.x, to.y, to.z);
    }

    private static void removeSheep(State s) {
        if (s.sheep != null) s.sheep.discard();
        s.sheep = null;
    }

    /** Seismic Wave: a wave along his look, 12 blocks long and 2 wide, hits every mob in it. */
    private static void seismicWave(ServerLevel level, ServerPlayer sp) {
        Vec3 look = sp.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        flat = flat.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : flat.normalize();
        Vec3 start = sp.position();
        for (int i = 1; i <= 12; i += 2) {
            Vec3 at = start.add(flat.scale(i));
            level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.2, at.z, 4, 0.4, 0.1, 0.4, 0.02);
        }
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.6f, 0.6f);
        AABB box = new AABB(start, start.add(flat.scale(12))).inflate(2, 2, 2);
        for (Mob m : level.getEntitiesOfClass(Mob.class, box, e -> e.isAlive() && !(e instanceof Sheep))) {
            Vec3 d = m.position().subtract(start);
            double along = d.x * flat.x + d.z * flat.z;
            double side = Math.abs(d.x * flat.z - d.z * flat.x);
            if (along >= 0 && along <= 12 && side <= 2 && Math.abs(d.y) <= 2) hurt(level, sp, m);
        }
    }

    /** Hits every mob within {@code radius} of {@code at}: {count, health taken}. */
    private static double[] blast(ServerLevel level, ServerPlayer sp, Vec3 at, double radius) {
        double[] out = new double[2];
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(at, at).inflate(radius),
                e -> e.isAlive() && !(e instanceof Sheep))) {
            if (m.getBoundingBox().getCenter().distanceToSqr(at) > radius * radius) continue;
            out[0]++;
            out[1] += m.getHealth();
            hurt(level, sp, m);
        }
        return out;
    }

    /** "Your Guided Sheep hit 2 enemies for 4 damage." - said when it hit anything, as Hypixel does. */
    private static void hitLine(ServerPlayer sp, String what, double[] hit) {
        if (hit[0] < 1) return;
        int n = (int) hit[0];
        sp.sendSystemMessage(Component.literal("Your " + what + " hit " + n + (n == 1 ? " enemy" : " enemies") + " for "
                + num(hit[1]) + " damage.").withStyle(ChatFormatting.GRAY));
    }

    /** Hypixel's number shape: thousands commas, one decimal only when there is one ("223,742.2", "6,068"). */
    static String num(double v) {
        String s = String.format(Locale.US, "%,.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static void hurt(ServerLevel level, ServerPlayer sp, LivingEntity e) {
        // playerAttack, not magic(): SimMobs refuses damage with no entity behind it (SimClass.fire).
        e.hurtServer(level, level.damageSources().playerAttack(sp), LETHAL);
    }

    private static Vec3 rayEnd(ServerLevel level, ServerPlayer sp, double range) {
        Vec3 eye = sp.getEyePosition();
        Vec3 end = eye.add(sp.getLookAngle().scale(range));
        BlockHitResult wall = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, sp));
        return wall.getType() == HitResult.Type.BLOCK ? wall.getLocation() : end;
    }

    /** The nearest mob whose box his look crosses before a wall, or null. */
    private static LivingEntity firstAlong(ServerLevel level, ServerPlayer sp, double range) {
        Vec3 eye = sp.getEyePosition();
        Vec3 stop = rayEnd(level, sp, range);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(eye, stop).inflate(0.6),
                e -> e.isAlive() && !(e instanceof Sheep))) {
            var hit = m.getBoundingBox().inflate(0.3).clip(eye, stop);
            if (hit.isEmpty()) continue;
            double d = eye.distanceToSqr(hit.get());
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    private static void trail(ServerLevel level, Vec3 from, Vec3 to, ParticleOptions particle) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 0.5) return;
        Vec3 step = d.scale(0.8 / len);
        Vec3 at = from.add(step);
        for (double t = 0.8; t < len; t += 0.8, at = at.add(step)) {
            level.sendParticles(particle, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
    }

    /** GO: every ultimate is ready, and its reminder comes 21 s in (his logs: 21-22 s after Mort's line). */
    static void onGo(MinecraftServer server) {
        int now = server.getTickCount();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            State s = state(sp);
            if (now >= s.ultReadyAt) s.nextReminder = now + FIRST_REMINDER;
        }
    }

    /** A new class pick: its abilities start ready and the old class's running ultimate, circle and sheep end. */
    private static void switchClass(State s, DungeonClass cls) {
        s.cls = cls;
        s.abilityReadyAt = s.ultReadyAt = 0;
        s.abilityPending = s.ultPending = false;
        s.nextReminder = Integer.MAX_VALUE;
        s.active = null;
        s.circle = null;
        removeSheep(s);
    }

    /** Server tick of a live sim (from {@link SimClasses}): cooldown lines, reminders, running effects. */
    static void tick(MinecraftServer server, int now) {
        if (STATES.isEmpty()) return;
        boolean running = SimRun.isRunning();
        Iterator<Map.Entry<UUID, State>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, State> e = it.next();
            ServerPlayer sp = server.getPlayerList().getPlayer(e.getKey());
            State s = e.getValue();
            if (sp == null || !(sp.level() instanceof ServerLevel level)) {
                removeSheep(s);
                it.remove();
                continue;
            }
            SimClasses.Pick pick = SimClasses.of(sp);
            if (s.cls != pick.cls()) switchClass(s, pick.cls());
            if (s.abilityPending && now >= s.abilityReadyAt) {
                s.abilityPending = false;
                sp.sendSystemMessage(Component.literal(abilityName(pick.cls()) + " is now available!").withStyle(ChatFormatting.GREEN));
            }
            if (s.ultPending && now >= s.ultReadyAt) {
                s.ultPending = false;
                sp.sendSystemMessage(Component.literal("Your " + SimClasses.name(pick.cls()) + " ULTIMATE " + ultimateName(pick.cls())
                        + " is now available!").withStyle(ChatFormatting.GREEN));
                s.nextReminder = now + REMINDER_EVERY;
            }
            if (running && now >= s.nextReminder && now >= s.ultReadyAt) {
                s.nextReminder = now + REMINDER_EVERY;
                sp.sendSystemMessage(Component.literal(ultimateName(pick.cls()) + " is ready to use! Press DROP to activate it!")
                        .withStyle(ChatFormatting.GREEN));
            }
            if (s.sheep != null) tickSheep(level, sp, s, now);
            if (s.circle != null) tickCircle(level, s, now, pick.level());
            if (s.active != null) tickActive(level, sp, s, now);
        }
    }

    /** Each second: the circle's particles, and 2% of max health (+0.5% every 5 levels) to every player inside. */
    private static void tickCircle(ServerLevel level, State s, int now, int lv) {
        if (now >= s.circleUntil) {
            s.circle = null;
            return;
        }
        if ((s.circleUntil - now) % 20 != 0) return;
        Vec3 c = s.circle;
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, c.x, c.y + 0.3, c.z, 20, s.circleRadius / 2, 0.2, s.circleRadius / 2, 0);
        float pct = 0.02f + 0.005f * (lv / 5);
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (p.isAlive() && p.level() == level && p.position().distanceToSqr(c) <= s.circleRadius * s.circleRadius) {
                p.heal(p.getMaxHealth() * pct);
            }
        }
    }

    private static void tickActive(ServerLevel level, ServerPlayer sp, State s, int now) {
        if (now >= s.activeUntil) {
            s.active = null;
            return;
        }
        int left = s.activeUntil - now;
        switch (s.active) {
            case MAGE -> {
                // Thunderstorm: once a second, lightning on every mob within 10 blocks.
                if (left % 20 != 0) return;
                for (Mob m : level.getEntitiesOfClass(Mob.class, sp.getBoundingBox().inflate(10),
                        e -> e.isAlive() && !(e instanceof Sheep))) {
                    LightningBolt bolt = com.killer560.hub.compat.McEntities.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
                    if (bolt != null) {
                        bolt.setVisualOnly(true);
                        bolt.setPos(m.getX(), m.getY(), m.getZ());
                        level.addFreshEntity(bolt);
                    }
                    hurt(level, sp, m);
                }
            }
            case ARCHER -> {
                // Rapid Fire: 5 arrows a second along his look.
                if (left % 4 != 0) return;
                LivingEntity hit = firstAlong(level, sp, 50);
                trail(level, sp.getEyePosition(), hit != null ? hit.getBoundingBox().getCenter() : rayEnd(level, sp, 50),
                        ParticleTypes.CRIT);
                level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 0.6f, 1.2f);
                if (hit != null) hurt(level, sp, hit);
            }
            default -> {
            }
        }
    }

    /** Leaving the sim: sheep go, every cooldown is forgotten. */
    static void reset() {
        for (State s : STATES.values()) removeSheep(s);
        STATES.clear();
    }

    /** Testkit: {ability ticks left, ultimate ticks left} for a player by name, or null. */
    public static int[] cooldownsForTest(MinecraftServer server, String name) {
        ServerPlayer sp = server.getPlayerList().getPlayerByName(name);
        State s = sp == null ? null : STATES.get(sp.getUUID());
        if (s == null) return new int[]{0, 0};
        int now = server.getTickCount();
        return new int[]{Math.max(0, s.abilityReadyAt - now), Math.max(0, s.ultReadyAt - now)};
    }

    /** Testkit: whether the player's ultimate effect (Thunderstorm, Rapid Fire, Ragnarok, Castle of Stone) is running. */
    public static boolean ultActiveForTest(MinecraftServer server, String name) {
        ServerPlayer sp = server.getPlayerList().getPlayerByName(name);
        State s = sp == null ? null : STATES.get(sp.getUUID());
        return s != null && s.active != null && server.getTickCount() < s.activeUntil;
    }
}
