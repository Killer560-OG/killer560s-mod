package com.killer560.hub.roomsim;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.dungeonalerts.SpringBootsOverlay;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Bonzo's Staff, the Jerry-chine Gun and Spring Boots in the sim (simitems-1105, killer560 2026-11-05), answered on the
 * sim's server as Hypixel's server answers them. The launches are server motion packets ({@link SimLaunch#push}), never a
 * client position. Numbers: the projectile paths, burst ranges and pushes are ADM's measurements of Hypixel (an Odin
 * addon's P3 sim, facts only); the Spring Boots notes and heights are the ones the mod's own Spring Boots HUD reads on
 * Hypixel ({@link SpringBootsOverlay}, Odin's table). Mana is not modeled in the sim; the Jerry-chine's action bar shows
 * the cost the wiki gives.
 */
public final class SimLaunchItems {

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-sim");

    /** Bonzo: the balloon flies 0.75 a tick from feet + 1.0 + 0.2 look; a burst within 3.5 of feet + 1.5 SETS his motion
     *  to 1.5 flat away from it and 0.5 up. At most one balloon per 4 server ticks. */
    static final double BONZO_SPEED = 0.75, BONZO_REACH = 3.5, BONZO_FLAT = 1.5, BONZO_UP = 0.5;
    static final int BONZO_GAP_TICKS = 4, BONZO_STEPS = 170;
    /** Jerry: bullet k at feet + 1.1 + (0.25 + 0.75k) look, bursting inside a block (or at k = 30); within 4.0 of
     *  feet + 1.62 his motion becomes 0.5 x the flat unit away from it and 0.6 up. At most 3 shots a server tick. */
    static final double JERRY_REACH = 4.0, JERRY_FLAT = 0.5, JERRY_UP = 0.6;
    static final int JERRY_LIFE = 30, JERRY_PER_TICK = 3;
    /** Spring Boots: one charge note every this many server ticks while he sneaks on the ground. */
    static final int SPRING_NOTE_TICKS = 3;
    /** Live balloons and bullets, bounded. */
    private static final int MAX_SHOTS = 48;

    private static final String BONZO_TEXTURE = "f1f99e7c394e13d199d1d22cb082d209068cd7a4de6ca09edff3fc61660ae5cf";
    private static final String JERRY_TEXTURE = "41b830eb4082acec836bc835e40a11282bb51193315f91184337e8d3555583";

    private record Shot(ServerPlayer owner, boolean bonzo, Vec3 origin, Vec3 dir, ArmorStand stand, boolean startsInside,
                        int[] age) {
    }

    private static final List<Shot> SHOTS = new ArrayList<>();
    private static int lastBonzoTick = -100;
    private static int jerryTick = -1, jerryShotsThisTick;
    private static int rapidShots, rapidLastTick = -1000;
    private static int springNotes, springTicks;
    private static ItemStack bonzoHead, jerryHead;
    /** For the testkit: balloons and bullets fired, bursts, launches given. */
    private static int bonzoFired, jerryFired, bursts, launches, springLaunches;
    private static float lastSpringHeight;

    private SimLaunchItems() {
    }

    public static int bonzoFired() { return bonzoFired; }
    public static int jerryFired() { return jerryFired; }
    public static int bursts() { return bursts; }
    public static int launches() { return launches; }
    public static int springLaunches() { return springLaunches; }
    public static float lastSpringHeight() { return lastSpringHeight; }

    /** Whether a right click with this id is one of these items' abilities. */
    static boolean isLaunchItem(String id) {
        return "BONZO_STAFF".equals(id) || "STARRED_BONZO_STAFF".equals(id) || "JERRY_STAFF".equals(id);
    }

    /** A use packet with one of the two guns, on the server thread. Always consumes the click. */
    static boolean use(ServerPlayer sp, String id) {
        int now = sp.level().getServer().getTickCount();
        if ("JERRY_STAFF".equals(id)) {
            if (jerryTick != now) {
                jerryTick = now;
                jerryShotsThisTick = 0;
            }
            if (++jerryShotsThisTick > JERRY_PER_TICK || SHOTS.size() >= MAX_SHOTS) return true;
            fire(sp, false);
            jerryFired++;
            if (now - rapidLastTick > 80) rapidShots = 0;
            rapidShots++;
            rapidLastTick = now;
            sp.sendSystemMessage(Component.literal("§b-" + 30 * rapidShots + " Mana (§6Rapid-fire§b)"), true);
            return true;
        }
        if (now - lastBonzoTick < BONZO_GAP_TICKS || SHOTS.size() >= MAX_SHOTS) return true;
        lastBonzoTick = now;
        fire(sp, true);
        bonzoFired++;
        return true;
    }

    private static void fire(ServerPlayer sp, boolean bonzo) {
        ServerLevel level = (ServerLevel) sp.level();
        Vec3 dir = SimAim.look(sp);
        Vec3 feet = sp.position();
        Vec3 origin = bonzo ? feet.add(0, 1.0, 0).add(dir.scale(0.2)) : feet.add(0, 1.1, 0);
        Vec3 first = bonzo ? origin : origin.add(dir.scale(0.25 + 0.75));
        // The projectile is drawn by an invisible stand wearing the balloon or Jerry head, as on Hypixel (the head sits
        // about 1.6 above the stand's feet).
        ArmorStand stand = new ArmorStand(level, first.x, first.y - 1.6, first.z);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setSilent(true);
        stand.setYRot(sp.getYRot());
        stand.setItemSlot(EquipmentSlot.HEAD, (bonzo ? bonzoHead() : jerryHead()).copy());
        level.addFreshEntity(stand);
        float pitch = (88 + level.getRandom().nextInt(26)) / 63f;
        if (bonzo) {
            level.playSound(null, feet.x, feet.y, feet.z, SoundEvents.GHAST_AMBIENT, SoundSource.HOSTILE, 1f, pitch);
        } else {
            level.playSound(null, feet.x, feet.y, feet.z, SoundEvents.VILLAGER_TRADE, SoundSource.NEUTRAL, 0.5f, pitch);
        }
        SHOTS.add(new Shot(sp, bonzo, origin, dir, stand, bonzo && solid(level, origin), new int[]{0}));
    }

    /** Server tick (SimAbilities.serverTick): flies the shots, charges and launches the Spring Boots. */
    static void tick(MinecraftServer server) {
        if (!SHOTS.isEmpty()) {
            SHOTS.removeIf(SimLaunchItems::step);
        }
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            springTick(sp);
        }
    }

    /** One tick of one shot; true when it is done. */
    private static boolean step(Shot s) {
        if (s.owner.isRemoved() || s.stand.isRemoved() || !(s.stand.level() instanceof ServerLevel level)) {
            s.stand.discard();
            return true;
        }
        int j = ++s.age[0];
        if (s.bonzo) {
            int k = j - 1;
            Vec3 at = s.origin.add(s.dir.scale(BONZO_SPEED * k));
            if (k >= 1 && k <= BONZO_STEPS && (s.startsInside ? k == 2 : solid(level, at) || mobAt(level, at, s.owner))) {
                burst(s, level, at);
                return true;
            }
            if (j > BONZO_STEPS) {
                s.stand.discard();
                return true;
            }
            Vec3 next = s.origin.add(s.dir.scale(BONZO_SPEED * j));
            s.stand.setPos(next.x, next.y - 1.6, next.z);
            return false;
        }
        Vec3 at = s.origin.add(s.dir.scale(0.25 + 0.75 * j));
        if (j < JERRY_LIFE && !solid(level, at) && !mobAt(level, at, s.owner)) {
            Vec3 next = s.origin.add(s.dir.scale(0.25 + 0.75 * (j + 1)));
            s.stand.setPos(next.x, next.y - 1.6, next.z);
            return false;
        }
        burst(s, level, at);
        return true;
    }

    /** A burst: the damage, the sound and particles, and the push when he is in range. */
    private static void burst(Shot s, ServerLevel level, Vec3 at) {
        s.stand.discard();
        bursts++;
        ServerPlayer sp = s.owner;
        Vec3 feet = sp.position();
        Vec3 push = null;
        if (s.bonzo) {
            hurt(level, sp, AABB.ofSize(at, 4, 4, 4), at, 2.0, 1000f);
            level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y, at.z, 30, 0.2, 0.2, 0.2, 0.15);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.AMBIENT, 3f, 1f);
            if (at.distanceTo(feet.add(0, 1.5, 0)) <= BONZO_REACH) {
                double dx = feet.x - at.x, dz = feet.z - at.z, h = Math.sqrt(dx * dx + dz * dz);
                push = h > 0 ? new Vec3(BONZO_FLAT * dx / h, BONZO_UP, BONZO_FLAT * dz / h) : new Vec3(0, BONZO_UP, 0);
            }
        } else {
            hurt(level, sp, AABB.ofSize(at, 1, 1, 1), at, 0.5, 500f);
            level.playSound(null, Math.floor(at.x) + 0.5, Math.floor(at.y) + 0.5, Math.floor(at.z) + 0.5,
                    SoundEvents.VILLAGER_YES, SoundSource.MASTER, 0.35f, 1f);
            level.sendParticles(ParticleTypes.POOF, at.x, at.y, at.z, 1, 0, 0, 0, 0);
            Vec3 d = feet.add(0, 1.62, 0).subtract(at);
            double len = d.length();
            if (len <= JERRY_REACH && len > 0) {
                push = new Vec3(JERRY_FLAT * d.x / len, JERRY_UP, JERRY_FLAT * d.z / len);
            }
        }
        if (push != null) {
            launches++;
            SimLaunch.push(sp, push);
        }
        LOGGER.info(String.format(Locale.ROOT, "[Sim] %s burst at %.2f %.2f %.2f, %.2f from him: %s",
                s.bonzo ? "Bonzo balloon" : "Jerry bullet", at.x, at.y, at.z,
                at.distanceTo(feet.add(0, s.bonzo ? 1.5 : 1.62, 0)),
                push == null ? "no push" : String.format(Locale.ROOT, "push %.2f %.2f %.2f", push.x, push.y, push.z)));
    }

    private static void hurt(ServerLevel level, ServerPlayer sp, AABB box, Vec3 at, double reach, float damage) {
        var source = level.damageSources().indirectMagic(sp, sp);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e != sp && !(e instanceof ArmorStand))) {
            if (e.getBoundingBox().distanceToSqr(at) <= reach * reach) {
                e.hurtServer(level, source, damage);
            }
        }
    }

    /** Inside a block's collision shape. */
    private static boolean solid(ServerLevel level, Vec3 at) {
        BlockPos pos = BlockPos.containing(at);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        var shape = state.getCollisionShape(level, pos, CollisionContext.empty());
        if (shape.isEmpty()) return false;
        for (AABB box : shape.toAabbs()) {
            if (box.move(pos).contains(at)) return true;
        }
        return false;
    }

    /** A mob (not him, not a projectile's stand) whose box holds the point. */
    private static boolean mobAt(ServerLevel level, Vec3 at, ServerPlayer sp) {
        return !level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(at, 0.1, 0.1, 0.1),
                e -> e.isAlive() && e != sp && !(e instanceof ArmorStand)).isEmpty();
    }

    // ---- Spring Boots -----------------------------------------------------------------------------------------------

    /**
     * Sneaking on the ground in Spring Boots plays a charge note every {@link #SPRING_NOTE_TICKS} ticks (two lows, then
     * the highs); letting go of sneak on the ground launches him straight up to the height those notes add up to on the
     * Spring Boots HUD, with the firework launch sound. Leaving the ground or taking them off loses the charge.
     */
    private static void springTick(ServerPlayer sp) {
        if (!sp.isShiftKeyDown() && springNotes == 0) {
            springTicks = 0;
            return;
        }
        boolean wearing = "SPRING_BOOTS".equals(CheatUtils.skyblockId(sp.getItemBySlot(EquipmentSlot.FEET)));
        if (!wearing || !sp.onGround()) {
            springNotes = 0;
            springTicks = 0;
            return;
        }
        ServerLevel level = (ServerLevel) sp.level();
        if (sp.isShiftKeyDown()) {
            if (++springTicks % SPRING_NOTE_TICKS == 0 && springNotes < SpringBootsOverlay.MAX_NOTES) {
                level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.NOTE_BLOCK_PLING.value(),
                        SoundSource.PLAYERS, 1f, SpringBootsOverlay.notePitch(springNotes));
                springNotes++;
            }
            return;
        }
        float height = SpringBootsOverlay.heightFor(springNotes);
        double vy = launchSpeed(height);
        LOGGER.info(String.format(Locale.ROOT, "[Sim] Spring Boots: %d note(s), %.1f blocks, launch vy %.3f",
                springNotes, height, vy));
        springNotes = 0;
        springTicks = 0;
        lastSpringHeight = height;
        springLaunches++;
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS,
                1f, SpringBootsOverlay.LAUNCH_PITCH);
        SimLaunch.push(sp, new Vec3(0, vy, 0));
    }

    /** The upward speed whose vanilla flight (y += v, then v = (v - 0.08) * 0.98 each tick) peaks {@code height} up. */
    static double launchSpeed(double height) {
        double lo = 0, hi = 8;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if (peak(mid) < height) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    static double peak(double v) {
        double y = 0;
        while (v > 0) {
            y += v;
            v = (v - 0.08) * 0.98;
        }
        return y;
    }

    // ---- heads ------------------------------------------------------------------------------------------------------

    private static ItemStack bonzoHead() {
        if (bonzoHead == null) bonzoHead = head(BONZO_TEXTURE, "Balloon");
        return bonzoHead;
    }

    private static ItemStack jerryHead() {
        if (jerryHead == null) jerryHead = head(JERRY_TEXTURE, "Villager");
        return jerryHead;
    }

    private static ItemStack head(String hash, String name) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
        Multimap<String, Property> backing = LinkedHashMultimap.create();
        backing.put("textures", new Property("textures",
                java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        s.set(DataComponents.PROFILE, ResolvableProfile.createResolved(
                new GameProfile(UUID.nameUUIDFromBytes(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name,
                        new PropertyMap(backing))));
        return s;
    }

    /** A sim session ended: no shot or charge carries into the next. */
    static void reset() {
        for (Shot s : SHOTS) {
            s.stand.discard();
        }
        SHOTS.clear();
        springNotes = 0;
        springTicks = 0;
        lastBonzoTick = -100;
        rapidShots = 0;
    }
}
