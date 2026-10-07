package com.killer560.hub.roomsim;

import com.killer560.hub.util.FeatureGuard;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import com.killer560.hub.compat.McEntities;

/**
 * Combat dummies for the dungeon sim - zombies, skeletons, and Fels.
 *
 * <p>killer560's own spec: "all mobs have one HP and all damage does some amount that will one tap every mob
 * they do not need to have the exact same HP mob specifically don't even really need names." So there is no
 * naming scheme here on purpose - a spawned dummy is identified by its {@link Kind}, nothing else, and every
 * one dies in one hit. Fels are the interesting case: "[Fel] need [Fel] logic where they are that skull that
 * doesn't move and then once you get close, they become the upside down Enderman" - see {@link #wake} for what
 * that becomes here and what it explicitly does not.
 *
 * <p>Everything below is gated on {@link SimState#canAct}, same as {@link SimAbilities}: this package is the one
 * place this mod is allowed to write positions and spawn entities, and that is only true inside the sim's own
 * integrated server. All spawning happens there, via {@code server.execute(...)}, never on the client.
 */
public final class SimMobs {

    /** Hypixel's Fel wakes once a player closes to about this range: 4 blocks, 9 since SkyBlock 0.27.2 ("+5 blocks";
     *  SimHypixelRules). */
    public static final double FEL_WAKE_RADIUS = SimHypixelRules.FEL_WAKE_RADIUS;
    private static final double FEL_WAKE_RADIUS_SQ = FEL_WAKE_RADIUS * FEL_WAKE_RADIUS;

    /** "all mobs have one HP" - killer560. Applies to zombies, skeletons, and the enderman a Fel wakes into. */
    private static final double ONE_HP = 1.0;

    /**
     * The mob kinds the sim can place.
     *
     * <p>killer560's spec (2026-09-29), for testing an auto clear later: "regular star zombies that you can
     * spawn anywhere these will mimic things like skeleton grunts, or bouncy archers or any other starred mob
     * in a room" ({@link #ZOMBIE}/{@link #SKELETON}); Fels, which are "the skull and then whenever you step
     * near them, they are the upside down Enderman" ({@link #FEL}); and "essentially a mini boss style [that]
     * should maybe take more than just one hit just to be sure that your config works with something that
     * doesn't get one shot" ({@link #MINIBOSS}) - which is a {@link SimMiniboss}, the real player-entity form
     * Mob ESP recognises, not a durable mob standing in for one.
     *
     * <p>Every kind can be spawned starred or not - see {@link #spawn} against {@link #spawnStarred} - because
     * telling a starred mob from an ordinary one is the whole job of a clear, and a sim where everything is
     * starred cannot test that.
     */
    public enum Kind {
        ZOMBIE, SKELETON, FEL, BAT, MINIBOSS
    }

    /**
     * A miniboss's health, in half-hearts.
     *
     * <p>Not one, which every other sim mob has. The point of this kind is that it does NOT die to the first
     * hit, so anything tuned around a one-shot - a cooldown, a swap, a retarget - is actually exercised. The sim
     * never raises the player's attack damage, so 20 is two or three swings of an ordinary sword.
     *
     * <p>A real Catacombs miniboss is not a mob at all: it is a PLAYER entity with a version-2 UUID and a name
     * from a fixed list (Shadow Assassin, Lost Adventurer, Frozen Adventurer, Diamond Guy, King Midas), and Mob
     * ESP finds those on a different path from starred mobs entirely. {@link SimMiniboss} produces exactly that,
     * and {@link Kind#MINIBOSS} places one; the durable starred ZOMBIE that used to stand in for it is now only
     * the fallback for when the player entity cannot be built.
     */
    private static final double MINIBOSS_HEALTH = 20.0;

    /**
     * Which miniboss {@link Kind#MINIBOSS} places when the caller does not say.
     *
     * <p>Lost Adventurer, because it is the Catacombs miniboss that turns up in the most rooms - and because it
     * is one of the four whose name fits in a game profile (see {@link SimMiniboss.Name#FROZEN_ADVENTURER}).
     */
    private static final SimMiniboss.Name DEFAULT_MINIBOSS = SimMiniboss.Name.LOST_ADVENTURER;

    /** Every entity this class has spawned (dummies and woken Fels), for {@link #clear}. */
    private static final List<UUID> SPAWNED = new CopyOnWriteArrayList<>();

    /** UUIDs of spawned mobs flagged "starred". Stale (dead) entries are harmless and are wiped by clear(). */
    private static final List<UUID> STARRED = new CopyOnWriteArrayList<>();

    /** Dormant Fel markers not yet woken into an enderman. */
    private static final List<FelMarker> FELS = new CopyOnWriteArrayList<>();

    /**
     * Published each server tick by {@link #refreshStarred}, read directly by {@link #starredAliveIn} and
     * {@link #anyStarredAlive}. A volatile reference to an immutable list is the whole thread-safety story: the
     * refresh runs on the server thread (inside {@code server.execute}), those two queries are meant to be
     * called from whichever thread the door/key logic runs on, and swapping an immutable reference needs no
     * lock in either direction.
     */
    private static volatile List<StarredEntry> starredSnapshot = List.of();

    /** True once any starred mob has been alive; lets {@link #refreshStarred} tell "just spawned, none yet" (0,
     *  never fire) apart from "the last one just died" (nonzero, then 0 - fire once). Server thread only. */
    private static boolean hadStarred;

    /** Set by whichever file owns the wither-key drop and the doors - not this one; see the class prompt. */
    private static volatile Runnable onLastStarredDeath;

    private SimMobs() {
    }

    /** One position snapshot of a starred mob still alive, taken on the server thread. */
    private record StarredEntry(UUID id, double x, double y, double z) {
    }

    /** A dormant Fel: an invisible armour stand wearing a skull. See {@link #spawnFel}. */
    private static final class FelMarker {
        final ArmorStand skull;
        final boolean starred;
        boolean woken;

        FelMarker(ArmorStand skull, boolean starred) {
            this.skull = skull;
            this.starred = starred;
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> registerSummon(dispatcher));
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("SimMobs.tick", SimMobs::tick));
    }

    private static void tick(Minecraft client) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        // Reading and mutating mob/armour-stand state has to happen on the server thread, same reasoning as
        // SimAbilities.teleport: the integrated server, not the client, owns these entities.
        server.execute(() -> {
            ServerLevel level = server.overworld();
            wakeFels(level);
            refreshStarred(level);
            countDeadBats(level);
            SimMimic.tickServer(level);
            countDeadCrypts(level);
            SimMiniboss.tick(level);
        });
        // Client side: the snapshot the server task above publishes is read here, against the map's rooms.
        if (++roomClearTicks >= 10) {
            roomClearTicks = 0;
            markClearedRooms();
        }
    }

    private static int roomClearTicks = 0;

    /**
     * Where each starred mob stood when it was spawned (or woken), so its ROOM is known. Hypixel clears a room the moment
     * its last starred mob dies (the map's white checkmark); until 2026-10-06 the sim never cleared any room, so nothing
     * client-side (Auto Clear, the score's room count) could see a clear. Written on the server thread.
     */
    private static final java.util.Map<UUID, double[]> STARRED_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Starred mobs confirmed DEAD: the entity is there and not alive, or its section is entity-ticking and it is gone. A
     * mob in a section the server is not ticking entities in reads back as null, and missing is never killed (LESSONS:
     * "An entity reads back only from a section the server ticks entities in"). Server thread writes, client reads.
     */
    private static final java.util.Set<UUID> STARRED_DEAD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void recordStarred(UUID id, double x, double y, double z) {
        STARRED_AT.put(id, new double[]{x, y, z});
    }

    /**
     * Marks every room whose recorded starred mobs are all confirmed dead as cleared ({@link SimRoomState#markCleared}),
     * and counts it in the score. Client thread: the room of a position comes from the live map's own layout.
     */
    private static void markClearedRooms() {
        if (STARRED_AT.isEmpty()) {
            return;
        }
        com.killer560.hub.livemap.DungeonLayout layout = com.killer560.hub.livemap.DungeonLayout.current();
        java.util.Map<String, Boolean> allDead = new java.util.HashMap<>();
        for (java.util.Map.Entry<UUID, double[]> e : STARRED_AT.entrySet()) {
            double[] p = e.getValue();
            int room = layout.roomAtWorld(p[0], p[2]);
            String name = layout.name(room);
            if (name == null || "Unknown".equals(name)) {
                continue;
            }
            boolean dead = STARRED_DEAD.contains(e.getKey());
            allDead.merge(name, dead, Boolean::logicalAnd);
        }
        for (java.util.Map.Entry<String, Boolean> e : allDead.entrySet()) {
            if (e.getValue() && SimRoomState.markCleared(e.getKey())) {
                SimScore.roomCleared();
                com.killer560.hub.util.ModLog.get("killer560smod-sim").info("[Sim] room cleared: {} (every starred mob"
                        + " in it is dead)", e.getKey());
            }
        }
    }

    public static void spawn(Minecraft client, BlockPos pos, Kind kind) {
        spawn(client, pos, kind, false, DEFAULT_MINIBOSS);
    }

    public static void spawnStarred(Minecraft client, BlockPos pos, Kind kind) {
        spawn(client, pos, kind, true, DEFAULT_MINIBOSS);
    }

    /**
     * Places one named Catacombs miniboss - a real player entity Mob ESP highlights on its miniboss path, and
     * one that can be hit and killed. See {@link SimMiniboss} for how and why.
     *
     * @param starred whether it counts toward the room's starred-mob tally (and so toward the last-starred-death
     *                hook). A Hypixel miniboss has to die for a full clear, so a room that gates on one wants
     *                this true; it carries no "star ... heart" name-tag stand either way, because the ESP finds
     *                a miniboss by name and never looks for one.
     */
    public static void spawnMiniboss(Minecraft client, BlockPos pos, SimMiniboss.Name name, boolean starred) {
        spawn(client, pos, Kind.MINIBOSS, starred, name == null ? DEFAULT_MINIBOSS : name);
    }

    private static void spawn(Minecraft client, BlockPos pos, Kind kind, boolean starred, SimMiniboss.Name boss) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            switch (kind) {
                case ZOMBIE -> spawnDummy(level, new SimZombie(McEntities.ZOMBIE, level), pos, starred);
                case SKELETON -> spawnDummy(level, new SimSkeleton(McEntities.SKELETON, level), pos, starred);
                case MINIBOSS -> spawnMiniboss(level, pos, boss, starred);
                case FEL -> spawnFel(level, pos, starred);
                // A bat is a SECRET on Hypixel, not a mob worth points - which is exactly why it is here: a
                // 300 run needs every secret, and a player who cannot tell a bat secret from a chest secret
                // cannot tell why their count is short.
                case BAT -> spawnBat(level, pos);
            }
        });
    }

    /**
     * Places a one-HP zombie or skeleton. Server thread only - called from {@link #spawn}.
     *
     * <p>{@code setPersistenceRequired()} is the normal vanilla answer to "don't despawn", but it is not enough
     * here: {@code Mob.checkDespawn()} discards any hostile mob whose {@code EntityType.isAllowedInPeaceful()}
     * is false the moment the level's difficulty reads PEACEFUL, and it does that check BEFORE it ever looks at
     * persistence (confirmed with {@code javap -c} against {@code Mob.class} in the 26.1.2 jar). SimWorld opens
     * the sim on {@code Difficulty.PEACEFUL} on purpose, so a plain {@code Zombie}/{@code Skeleton} would vanish
     * on its very next tick. {@link #SimZombie} and {@link #SimSkeleton} fix that the one place this file can
     * reach without touching SimWorld: they override {@code checkDespawn()} to do nothing at all, which also
     * satisfies "not despawn" more completely than persistence alone would.
     */
    /**
     * Places a real miniboss, falling back to the durable starred zombie this kind used to be.
     *
     * <p>The fallback is not decoration: the player-entity form depends on a Fabric API class, on a version-2
     * UUID reaching the client before the entity does, and on three {@code ServerPlayer} behaviours being
     * overridden - and if any of that ever stops working the sim must still put something in the room that takes
     * more than one hit, rather than leaving it empty with only a log line. There is no setting either way;
     * killer560, 2026-09-29: "it will be something that is done basically on your end only and that the user
     * should never have to do."
     */
    private static void spawnMiniboss(ServerLevel level, BlockPos pos, SimMiniboss.Name name, boolean starred) {
        SimMiniboss.Placed placed = SimMiniboss.place(level, pos, name, MINIBOSS_HEALTH);
        if (placed == null) {
            spawnDummy(level, new SimZombie(McEntities.ZOMBIE, level), pos, starred, MINIBOSS_HEALTH);
            return;
        }
        ServerPlayer boss = placed.boss();
        SPAWNED.add(boss.getUUID());
        if (starred) {
            STARRED.add(boss.getUUID());
            recordStarred(boss.getUUID(), boss.getX(), boss.getY(), boss.getZ());
            // WITH a star stand, because that is how Hypixel does it - see attachStarTag for the log lines
            // off his own client. The stand was CONSTRUCTED alongside the boss in SimMiniboss.place so its
            // entity id is the boss's plus one, which is the first thing MobEspFeature.resolveMob looks at;
            // building it here instead left a gap of two and the ESP could not resolve the miniboss at all.
            attachStarTag(level, boss, name.text(), MINIBOSS_HEALTH, placed.tag());
        }
    }

    private static void spawnDummy(ServerLevel level, Mob mob, BlockPos pos, boolean starred) {
        spawnDummy(level, mob, pos, starred, ONE_HP);
    }

    private static void spawnDummy(ServerLevel level, Mob mob, BlockPos pos, boolean starred, double health) {
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        mob.setHealth((float) health);
        mob.setPersistenceRequired();
        // NEVER MOVES. killer560 (2026-09-28): "on the main their movement is dependent on where you are and
        // will be far too hard to replicate I would rather just have them never move". So a sim mob stands
        // exactly where the capture found it, which is also the only way a practised route means anything -
        // a route timed against mobs that chase you is a route timed against your own path.
        mob.setNoAi(true);
        mob.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(mob);
        SPAWNED.add(mob.getUUID());
        if (starred) {
            STARRED.add(mob.getUUID());
            recordStarred(mob.getUUID(), mob.getX(), mob.getY(), mob.getZ());
            attachStarTag(level, mob);
        }
    }

    /**
     * Gives a starred mob the same name-tag armour stand Hypixel gives it.
     *
     * <p>killer560 asked that "things like mob, ESP work and door ESP and all that style of stuff". Mob ESP does
     * not look at the mob: Hypixel puts the "star ... heart" name on a separate invisible armour stand and every
     * mod, this one included, resolves that stand to the mob beneath it. A sim mob with no stand is invisible to
     * all of it.
     *
     * <p>So rather than teaching those features about the sim, the sim produces what they already read. That is
     * the difference between one change here and a special case in every feature.
     */
    private static void attachStarTag(ServerLevel level, Mob mob) {
        attachStarTag(level, mob, mob.getType().getDescription().getString(), mob.getMaxHealth());
    }

    /**
     * The star tag, in the shape Hypixel really writes it.
     *
     * <p>Taken from killer560's own Map Logger client logs rather than invented - the mod's ESP logged these
     * off the live server:
     *
     * <pre>
     * [DungeonEsp] Resolved starred stand 1103225 " ✯ Frozen Adventurer 6.6M❤" -&gt; player id=1103224 via id-1
     * [DungeonEsp] Resolved starred stand 169937  " ✯ Lost Adventurer 337.5k❤" -&gt; player id=169936 via id-1
     * </pre>
     *
     * So: a LEADING space, the star, the name, then the health run straight into the heart with no space. The
     * sim used "✯ Zombie ❤", which satisfies the ESP's contains-star-and-heart test but is not what he sees.
     *
     * <p>Those same log lines settle something else. A real miniboss DOES carry a star stand, and the stand
     * resolves to a PLAYER entity below it - "-&gt; player id=..." - so the sim's miniboss gets one too. The
     * earlier assumption here that Hypixel gives minibosses no stand was simply wrong, and the ESP's
     * {@code MINIBOSS_NAMES} path is the secondary one, not the way these are normally found. (Which is just
     * as well: "Frozen Adventurer" is 17 characters and a player's profile name is capped at 16 by
     * {@code ByteBufCodecs.PLAYER_NAME = stringUtf8(16)}, verified in the 26.1.2 bytecode, so that entry could
     * never have matched a profile name anyway.)
     */
    private static void attachStarTag(ServerLevel level, net.minecraft.world.entity.LivingEntity mob,
                                      String displayName, double health) {
        attachStarTag(level, mob, displayName, health, null);
    }

    /**
     * @param prebuilt a stand already constructed next to the mob, to keep their entity ids adjacent, or
     *                 null to build one here - which is correct whenever the mob's own construction was the
     *                 last thing to take an id
     */
    private static void attachStarTag(ServerLevel level, net.minecraft.world.entity.LivingEntity mob,
                                      String displayName, double health, ArmorStand prebuilt) {
        ArmorStand tag = prebuilt != null ? prebuilt
                : new ArmorStand(level, mob.getX(), mob.getY() + mob.getBbHeight() + 0.1, mob.getZ());
        tag.snapTo(mob.getX(), mob.getY() + mob.getBbHeight() + 0.1, mob.getZ(), 0f, 0f);
        tag.setInvisible(true);
        tag.setNoGravity(true);
        tag.setNoBasePlate(true);
        tag.setInvulnerable(true);
        // A marker, so the tag has no hitbox to catch hits meant for the mob (the same fix as the blaze labels).
        byte flags = tag.getEntityData().get(ArmorStand.DATA_CLIENT_FLAGS);
        tag.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) (flags | ArmorStand.CLIENT_FLAG_MARKER));
        tag.setCustomName(Component.literal(" ✯ " + displayName + " " + shortHealth(health) + "❤"));
        tag.setCustomNameVisible(true);
        level.addFreshEntity(tag);
        SPAWNED.add(tag.getUUID());
        STAR_TAGS.put(mob.getUUID(), tag.getUUID());
    }

    /** Health the way Hypixel writes it on a name tag: 337.5k, 6.6M. */
    private static String shortHealth(double health) {
        if (health >= 1_000_000) {
            return String.format(java.util.Locale.ROOT, "%.1fM", health / 1_000_000);
        }
        if (health >= 1_000) {
            return String.format(java.util.Locale.ROOT, "%.1fk", health / 1_000);
        }
        return String.valueOf((long) health);
    }

    /** Star tag per starred mob, so the tag can be removed when the mob is. */
    private static final java.util.Map<UUID, UUID> STAR_TAGS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A bat secret. Counted toward secrets when it dies, the way Hypixel counts it.
     *
     * <p>It does not move either, for the same reason nothing else does: a bat that flies away is a secret
     * whose difficulty depends on where it drifted rather than on the route.
     */
    private static void spawnBat(ServerLevel level, BlockPos pos) {
        var bat = new net.minecraft.world.entity.ambient.Bat(
                McEntities.BAT, level);
        secretBatHealth(bat);
        bat.setHealth((float) ONE_HP);
        bat.setNoAi(true);
        bat.setNoGravity(true);
        bat.setPersistenceRequired();
        bat.setPos(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        level.addFreshEntity(bat);
        SPAWNED.add(bat.getUUID());
        BATS.add(bat.getUUID());
    }

    /** Crypt undead alive in the world, id to whether it is the prince that scores. */
    private static final java.util.Map<UUID, Boolean> CRYPT_MOBS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A crypt's undead (or the prince's), spawned when its crypt is blown. Its crypt point is counted when it DIES,
     * as on Hypixel, where "Crypts: N" in the tab list rises on the kill and the prince's kill says
     * "A Prince falls. +1 Bonus Score" in chat. Until 2026-10-05 the sim scored the crypt when the wall broke, which
     * left a client feature that watches for the kill (Auto Routes' crypt node) nothing to see.
     */
    public static void spawnCrypt(Minecraft client, BlockPos pos, boolean scoringPrince) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            SimZombie undead = new SimZombie(McEntities.ZOMBIE, level);
            // killer560 (2026-10-06): "have crypt mobs spawn with the word crypt above them". No star, so nothing
            // that looks for starred mobs counts it.
            undead.setCustomName(Component.literal(scoringPrince ? "Crypt Prince" : "Crypt Undead"));
            undead.setCustomNameVisible(true);
            spawnDummy(level, undead, pos, false);
            CRYPT_MOBS.put(undead.getUUID(), scoringPrince);
        });
    }

    private static void countDeadCrypts(ServerLevel level) {
        if (CRYPT_MOBS.isEmpty()) {
            return;
        }
        for (var it = CRYPT_MOBS.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Entity mob = level.getEntity(e.getKey());
            if (mob != null && mob.isAlive()) {
                continue;
            }
            it.remove();
            SimScore.cryptBlown();
            if (e.getValue()) {
                for (ServerPlayer p : level.players()) {
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal("A Prince falls. +1 Bonus Score"));
                }
            }
        }
    }

    /**
     * The mimic: a baby zombie where its trapped chest stood, as on Hypixel (the score calculator and Auto Routes'
     * Kill Mimic know it as a baby zombie dying). Starred, as the sim's mimic always was, and one hit kills it like
     * every sim mob. Server thread. @return its id
     */
    static java.util.UUID spawnMimic(ServerLevel level, BlockPos chest) {
        SimZombie mimic = new SimZombie(McEntities.ZOMBIE, level);
        mimic.setBaby(true);
        spawnDummy(level, mimic, chest, true);
        return mimic.getUUID();
    }

    /**
     * Gives a sim bat secret the MAX health a Hypixel secret bat carries (Skyblock health, 100 on the lower floors),
     * which is what the client's own secret-bat test reads ({@code AwaitEvents.isSecretBat}, QUOI's 100/200/400/800):
     * with vanilla's 6, or the 1 this used to set, nothing on the client could tell a sim bat secret from any bat. The
     * CURRENT health is left as it is, so a bat still dies to the same hit as before.
     */
    static void secretBatHealth(net.minecraft.world.entity.ambient.Bat bat) {
        bat.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
    }

    /**
     * Counts bats that have died since the last tick, as SECRETS.
     *
     * <p>Polled rather than hooked on a death event: the sim owns these entities and knows exactly which ones
     * are its bats, so checking the handful it spawned is cheaper and more certain than filtering every death
     * in the world.
     */
    private static void countDeadBats(ServerLevel level) {
        if (BATS.isEmpty()) {
            return;
        }
        java.util.Iterator<UUID> it = BATS.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity e = level.getEntity(id);
            if (e == null || !e.isAlive()) {
                it.remove();
                com.killer560.hub.roomsim.SimScore.batKilled();
            }
        }
    }

    /** Bats spawned by the sim, so their death can be counted as a secret rather than as a kill. */
    private static final java.util.Set<UUID> BATS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Places a dormant Fel: an invisible, no-base-plate armour stand wearing a skull, so only the skull shows -
     * an invisible armour stand still renders its equipped items, which is the whole trick. It is invulnerable
     * while dormant because a Fel is not a fightable mob yet on Hypixel either; it only becomes one once it
     * wakes, in {@link #wake}.
     */
    private static void spawnFel(ServerLevel level, BlockPos pos, boolean starred) {
        ArmorStand skull = new ArmorStand(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        skull.setInvisible(true);
        skull.setNoBasePlate(true);
        skull.setShowArms(false);
        skull.setInvulnerable(true);
        skull.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.SKELETON_SKULL));
        level.addFreshEntity(skull);
        FELS.add(new FelMarker(skull, starred));
        if (starred) {
            recordStarred(skull.getUUID(), skull.getX(), skull.getY(), skull.getZ());
        }
    }

    /** Checks every dormant Fel against every player each tick and wakes the ones close enough. */
    private static void wakeFels(ServerLevel level) {
        if (FELS.isEmpty()) {
            return;
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        for (FelMarker fel : FELS) {
            if (fel.woken || fel.skull.isRemoved()) {
                continue;
            }
            for (ServerPlayer player : players) {
                // A placed miniboss IS a ServerPlayer and so is in level.players() - it must not wake a Fel.
                if (SimMiniboss.isPlaced(player)) {
                    continue;
                }
                if (player.distanceToSqr(fel.skull) <= FEL_WAKE_RADIUS_SQ) {
                    wake(level, fel);
                    break;
                }
            }
        }
    }

    /**
     * Replaces a dormant Fel with an enderman where it stood.
     *
     * <p><b>Upside down, using vanilla's own rule.</b> killer560's spec is explicit that the woken form is "the
     * upside down Enderman". This needs no renderer and no mixin: {@code LivingEntityRenderer} checks whether an
     * entity is named "Dinnerbone" or "Grumm" and sets {@code isUpsideDown} on the render state, which flips the
     * model (verified in the 26.1.2 bytecode, 2026-09-28). So the enderman is named Grumm and the name tag is
     * hidden - the flip is a property of the NAME, not of the tag being drawn, so hiding it costs nothing.
     *
     * <p>That is worth preferring over a mixin even though a mixin would work: this is a rendering behaviour
     * the game already has, and a mixin on an entity renderer is a thing that breaks quietly on the next
     * Minecraft version.
     */
    private static void wake(ServerLevel level, FelMarker fel) {
        fel.woken = true;
        double x = fel.skull.getX();
        double y = fel.skull.getY();
        double z = fel.skull.getZ();
        fel.skull.discard();

        SimEnderman enderman = new SimEnderman(McEntities.ENDERMAN, level);
        enderman.getAttribute(Attributes.MAX_HEALTH).setBaseValue(ONE_HP);
        enderman.setHealth((float) ONE_HP);
        enderman.setPersistenceRequired();
        // Vanilla flips anything called Grumm or Dinnerbone. The tag itself is hidden: the flip comes from the
        // name being set, not from it being drawn.
        enderman.setCustomName(net.minecraft.network.chat.Component.literal("Grumm"));
        enderman.setCustomNameVisible(false);
        enderman.setPos(x, y, z);
        level.addFreshEntity(enderman);
        SPAWNED.add(enderman.getUUID());
        if (fel.starred) {
            STARRED.add(enderman.getUUID());
            // Recorded BEFORE the skull counts as gone below, so the room never reads "all dead" in between.
            recordStarred(enderman.getUUID(), x, y, z);
            STARRED_DEAD.add(fel.skull.getUUID());
        }
    }

    /**
     * Rebuilds {@link #starredSnapshot} from whatever is still alive, and fires the last-death hook on the 0-to-
     * nonzero-to-0 transition. A dormant, un-woken starred Fel counts as alive: on Hypixel it has not died yet
     * either, it just has not been fought yet, and a room where it was never approached must not look like every
     * starred mob is already dead.
     */
    private static void refreshStarred(ServerLevel level) {
        List<StarredEntry> alive = new ArrayList<>();
        for (UUID id : STARRED) {
            Entity entity = level.getEntity(id);
            if (entity != null && entity.isAlive()) {
                alive.add(new StarredEntry(id, entity.getX(), entity.getY(), entity.getZ()));
            } else if (!STARRED_DEAD.contains(id)) {
                double[] at = STARRED_AT.get(id);
                if (entity != null || (at != null
                        && level.isPositionEntityTicking(BlockPos.containing(at[0], at[1], at[2])))) {
                    STARRED_DEAD.add(id);
                    // Hypixel's "star ... heart" name goes with its mob; the sim's stand used to stay up forever,
                    // a starred name with nothing under it.
                    UUID tag = STAR_TAGS.remove(id);
                    Entity stand = tag == null ? null : level.getEntity(tag);
                    if (stand != null) {
                        stand.discard();
                    }
                }
            }
        }
        for (FelMarker fel : FELS) {
            if (fel.starred && !fel.woken && !fel.skull.isRemoved()) {
                alive.add(new StarredEntry(fel.skull.getUUID(), fel.skull.getX(), fel.skull.getY(), fel.skull.getZ()));
            }
        }
        starredSnapshot = List.copyOf(alive);

        if (!alive.isEmpty()) {
            hadStarred = true;
        } else if (hadStarred) {
            hadStarred = false;
            Runnable callback = onLastStarredDeath;
            if (callback != null) {
                callback.run();
            }
        }
    }

    /** How many starred mobs are still alive inside {@code area}. Door/key logic owns what happens at zero. */
    public static int starredAliveIn(AABB area) {
        int count = 0;
        for (StarredEntry entry : starredSnapshot) {
            if (area.contains(entry.x(), entry.y(), entry.z())) {
                count++;
            }
        }
        return count;
    }

    /** Whether any starred mob spawned by this class is still alive, anywhere in the sim. */
    /**
     * How many entities this class has spawned, and how many of those were starred.
     *
     * <p>{@link #anyStarredAlive()} reads a snapshot refreshed on tick, so it says "no" both when nothing was
     * spawned and when the snapshot has not caught up - and a gametest cannot tell those apart. These two are
     * written the moment the entity is added, which is the question a test actually needs answered.
     */
    public static int spawnedCount() {
        return SPAWNED.size();
    }

    public static int starredCount() {
        return STARRED.size();
    }

    /**
     * Every starred mob paired with its name-tag armour stand, as {@code {mobUuid, tagUuid}}.
     *
     * <p>For a test that has to check the pair the way Mob ESP reads it, WITHOUT enumerating the level:
     * entity enumeration does not work in a gametest client (a vanilla entity added with
     * {@code addFreshEntity} returning true, in a loaded chunk, still never appears in
     * {@code getAllEntities()}), so a scenario has to look each one up by id instead.
     */
    public static java.util.List<UUID[]> starredPairs() {
        java.util.List<UUID[]> out = new java.util.ArrayList<>();
        for (UUID mob : STARRED) {
            UUID tag = STAR_TAGS.get(mob);
            if (tag != null) {
                out.add(new UUID[]{mob, tag});
            }
        }
        return out;
    }

    public static boolean anyStarredAlive() {
        return !starredSnapshot.isEmpty();
    }

    /** Registers the "last starred mob just died" hook. Does not itself drop a key or open a door. */
    public static void setOnLastStarredDeath(Runnable callback) {
        onLastStarredDeath = callback;
    }

    /** Removes every mob and Fel marker this class spawned, for a run restart. Never touches anything else. */
    /**
     * Forgets the tracked mobs without touching the world.
     *
     * <p>For {@code SimBuilder}, which discards every mob in the level itself and then only needs the
     * bookkeeping cleared - calling {@link #clear(Minecraft)} there would try to reach the client from the
     * server thread mid-build.
     *
     * <p>ALL of it, the starred set included. This used to clear SPAWNED but keep STARRED, FELS and the star tags,
     * so a floor built in a world whose previous leave had not reset the sim (SimState already off when the
     * world unloaded) started with the old floor's starred mobs still counted: 89-sim-starred-mobs read
     * "1 spawned, 5 starred" after spawning one PLAIN zombie, the 5 being mimics from 96-ar (2026-10-06 and
     * 2026-10-07). Every mob of the old floor is gone after a build, so none of them can still be starred.
     */
    public static void forget() {
        // A rebuilt floor's rooms must not be cleared by the last floor's dead, and discarded is not killed: a
        // crypt or mimic the build threw away must not score when it goes. forgetAll covers both.
        forgetAll();
    }

    public static void clear(Minecraft client) {
        // The world's entities are only touched while the sim can act; the bookkeeping below is forgotten either
        // way. It used to return here too, and the leave path (SimWorld.onWorldUnloaded, now on the render thread
        // after the level has gone) would then carry this floor's starred set into the next session.
        MinecraftServer server = SimState.canAct(client) ? client.getSingleplayerServer() : null;
        if (server == null) {
            forgetAll();
            return;
        }
        List<UUID> toDiscard = List.copyOf(SPAWNED);
        List<FelMarker> dormant = List.copyOf(FELS);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (UUID id : toDiscard) {
                Entity entity = level.getEntity(id);
                if (entity != null) {
                    entity.discard();
                }
            }
            for (FelMarker fel : dormant) {
                if (!fel.skull.isRemoved()) {
                    fel.skull.discard();
                }
            }
            // Withdraws the client-side profile entry of every miniboss just discarded, in the same tick.
            SimMiniboss.tick(level);
        });
        forgetAll();
    }

    private static void forgetAll() {
        SPAWNED.clear();
        STARRED_AT.clear();
        STARRED_DEAD.clear();
        CRYPT_MOBS.clear();
        SimMimic.forgetMob();
        STARRED.clear();
        STAR_TAGS.clear();
        FELS.clear();
        starredSnapshot = List.of();
        hadStarred = false;
    }

    /**
     * Plain {@link Zombie} minus the peaceful-discard half of {@code checkDespawn()} - see
     * {@link #spawnDummy}. {@code isSunSensitive()} is overridden too, belt-and-suspenders: dungeon rooms have
     * no sky exposure, but the spec asked for it explicitly ("there is no daylight, but persistence matters").
     */

    /**
     * Whether a sim mob should shrug this off.
     *
     * <p>Every sim mob has 1 HP, which makes ANY environmental damage instantly lethal - and a practice target
     * that dies to the ceiling before he reaches it is worse than no target at all. Measured 2026-09-29: a
     * zombie placed in a two-block-high room vanished within a tick while a miniboss in the same spot lived,
     * because a zombie is 1.95 blocks tall and a player is 1.8, so the zombie's head was in the ceiling and it
     * suffocated. Its star tag survived, which is what a clear would then chase - a name tag with nothing
     * under it.
     *
     * <p>Only the environment is blocked. Damage from him still kills it on the first hit, which is the whole
     * point of a 1 HP dummy.
     */
    private static boolean environmental(net.minecraft.world.damagesource.DamageSource source) {
        return source.getEntity() == null && source.getDirectEntity() == null;
    }

    private static final class SimZombie extends Zombie {
        SimZombie(EntityType<? extends Zombie> type, Level level) {
            super(type, level);
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        public boolean isInvulnerableTo(net.minecraft.server.level.ServerLevel level,
                                        net.minecraft.world.damagesource.DamageSource source) {
            return environmental(source) || super.isInvulnerableTo(level, source);
        }

        @Override
        protected boolean isSunSensitive() {
            return false;
        }
    }

    /** Plain {@link Skeleton} minus {@code checkDespawn()} - see {@link #spawnDummy}. Skeleton has no separate
     *  sun-sensitivity override to disable in this version; checked with {@code javap} against Skeleton and
     *  AbstractSkeleton. */
    private static final class SimSkeleton extends Skeleton {
        SimSkeleton(EntityType<? extends Skeleton> type, Level level) {
            super(type, level);
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        public boolean isInvulnerableTo(net.minecraft.server.level.ServerLevel level,
                                        net.minecraft.world.damagesource.DamageSource source) {
            return environmental(source) || super.isInvulnerableTo(level, source);
        }
    }

    /** Plain {@link EnderMan} minus {@code checkDespawn()} - see {@link #wake}. */
    private static final class SimEnderman extends EnderMan {
        SimEnderman(EntityType<? extends EnderMan> type, Level level) {
            super(type, level);
        }

        @Override
        public void checkDespawn() {
        }
    }
    /**
     * {@code /summon <kind>} - one mob of that kind, standing on him.
     *
     * <p>Starred, like everything else the sim spawns, so Mob ESP and the clear's own counters see it exactly
     * as they would a real one. Spawning at his own feet is deliberate: he asked for "ontop of me", and his
     * own block is guaranteed to be air, which a block two over is not - a mob spawned inside a wall
     * suffocates on the first tick and looks like the command did nothing.
     */
    private static void registerSummon(
            com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2
                    .FabricClientCommandSource> dispatcher) {
        var root = net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("summon")
                .requires(src -> SimState.canAct(Minecraft.getInstance()));
        for (Kind kind : Kind.values()) {
            final Kind chosen = kind;
            root = root.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                    .literal(kind.name().toLowerCase(java.util.Locale.ROOT))
                    .executes(ctx -> summonOne(chosen)));
        }
        dispatcher.register(root);
    }

    private static int summonOne(Kind kind) {
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client) || client.player == null) {
            com.killer560.hub.util.ModChat.send("Sim",
                    com.killer560.hub.util.ModChat.text("Summoning only works inside the sim."));
            return 1;
        }
        net.minecraft.core.BlockPos at = client.player.blockPosition();
        spawnStarred(client, at, kind);
        com.killer560.hub.util.ModChat.send("Sim",
                com.killer560.hub.util.ModChat.text("Summoned "),
                com.killer560.hub.util.ModChat.value(kind.name().toLowerCase(java.util.Locale.ROOT)));
        return 1;
    }

}
