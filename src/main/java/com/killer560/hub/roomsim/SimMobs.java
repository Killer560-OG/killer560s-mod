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
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

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
     * Mob ESP recognizes, not a durable mob standing in for one.
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

    private SimMobs() {
    }

    /** A dormant Fel: an invisible armor stand wearing a skull, and for a starred one the sleeping enderman under its
     *  tag. See {@link #spawnFel}. */
    private static final class FelMarker {
        final ArmorStand skull;
        final boolean starred;
        /** The starred Fel's sleeping enderman (invisible, untouchable) that wakes in place; null for an unstarred one. */
        final SimEnderman sleeper;
        boolean woken;

        FelMarker(ArmorStand skull, boolean starred, SimEnderman sleeper) {
            this.skull = skull;
            this.starred = starred;
            this.sleeper = sleeper;
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger FELS_PLACED = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger FELS_WOKEN = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile String lastFelWake;

    /** Fels placed since the last {@link #forget}, for tests and the log. */
    public static int felsPlaced() { return FELS_PLACED.get(); }

    /** The last wake: "x y z|distance to the player|starred", or null. */
    public static String lastFelWake() { return lastFelWake; }

    /** Every dormant Fel's skull position as {x, y, z}, for tests (the skull sits on the floor block's top). */
    public static java.util.List<double[]> dormantFels() {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        for (FelMarker f : FELS) {
            if (!f.woken) {
                out.add(new double[]{f.skull.getX(), f.skull.getY() + SKULL_DROP, f.skull.getZ()});
            }
        }
        return out;
    }

    public static void register() {
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> {
                    registerSummon(dispatcher);
                    SimLiveMobs.registerCommand(dispatcher);
                });
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("SimMobs.tick", SimMobs::tick));
        // No sim mob dies to the world: starred mobs on a floor's sky-lit edge burned in the daylight ("onFire, by nothing")
        // within 3 s of GO, and whole key rooms cleared with nobody there (doc-adm-sim-1101). isInvulnerableTo's
        // environmental() did not stop it; refusing the damage outright does.
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (SPAWNED.contains(entity.getUUID()) && environmental(source)) {
                entity.clearFire();
                return false;
            }
            return true;
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (STARRED.contains(entity.getUUID())) {
                KILLED.add(entity.getUUID());
                com.killer560.hub.util.ModLog.get("killer560smod-sim").debug("[Sim] starred {} died at {} ({}, by {})",
                        entity.getName().getString(), entity.blockPosition().toShortString(), source.getMsgId(),
                        source.getEntity() == null ? "nothing" : source.getEntity().getName().getString());
            }
        });
        SimMobFloor.register();
        SimBloodCamp.register();
    }

    private static void tick(Minecraft client) {
        if (!SimState.canAct(client)) return;
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) return;
        // Reading and mutating mob/armor-stand state has to happen on the server thread, same reasoning as
        // SimAbilities.teleport: the integrated server, not the client, owns these entities.
        server.execute(() -> {
            ServerLevel level = server.overworld();
            wakeFels(level);
            refreshStarred(level);
            refreshLiveTags(level);
            countDeadBats(level);
            SimMimic.tickServer(level);
            countDeadCrypts(level);
            SimMiniboss.tick(level);
            SimShadowAssassinLeap.tickServer(level);
        });
        // Client side: the snapshot the server task above publishes is read here, against the map's rooms.
        if (++roomClearTicks >= 10) {
            roomClearTicks = 0;
            // Co-op guests clear and count the rooms they walk into too (coopsim-p5); none in single player.
            List<SimCoop.Spot> guests = SimCoop.guestSpots();
            if (client.player != null) markEnteredRoomCleared(client.player.getX(), client.player.getZ());
            for (SimCoop.Spot g : guests) markEnteredRoomCleared(g.x(), g.z());
            markClearedRooms();
            if (client.player != null) countOtherRooms(client.player.getX(), client.player.getZ());
            for (SimCoop.Spot g : guests) countOtherRooms(g.x(), g.z());
        }
    }

    /**
     * The rooms no mob death counts (autoclear-speed-1020: the sim's "Completed Rooms" read 15/23 on a floor with every mob
     * room and the trap done, so a Solo 300 could never be read): a solved puzzle ({@code SimPuzzles.isRoomComplete}) is
     * counted once, and - with clear-on-entry off, as live mobs have it - the Entrance and a Fairy room once he has been
     * in them, which clear-on-entry would have counted. Client thread, with the room checks.
     */
    private static void countOtherRooms(double px, double pz) {
        if (!SimRun.hasStarted()) return;
        com.killer560.hub.livemap.DungeonLayout layout = com.killer560.hub.livemap.DungeonLayout.current();
        int here = layout.roomAtWorld(px, pz);
        for (int i = 0; i < layout.roomCount(); i++) {
            String name = layout.name(i);
            com.killer560.hub.roomdatabase.RoomEntry entry = layout.entry(i);
            if (name == null || entry == null || entry.type == null) continue;
            String type = entry.type.toUpperCase(java.util.Locale.ROOT);
            boolean count = type.equals("PUZZLE") ? com.killer560.hub.roomsim.puzzles.SimPuzzles.isRoomComplete(name)
                    : !clearRoomsOnEntry && i == here && (type.equals("ENTRANCE") || type.equals("FAIRY"));
            if (count && SimScore.countRoomOnce(name)) {
                com.killer560.hub.util.ModLog.get("killer560smod-sim").info("[Sim] room counted: {} ({})", name,
                        type.equals("PUZZLE") ? "its puzzle is solved" : "he has been in it");
            }
        }
    }

    /**
     * killer560, 2026-10-14: "Treat all rooms as cleared in the sim until we do mob stuff later." A room's mobs are
     * not a real fight in the sim yet, so every mob room counts as cleared (map checkmark, score, Auto Clear, Room
     * Cleared alert) the moment he is inside it, without killing anything - as if its last starred mob died on entry.
     * Puzzle rooms still clear by their puzzle and Blood by its own logic. Client thread, like {@link #markClearedRooms}.
     */
    private static void markEnteredRoomCleared(double x, double z) {
        if (!clearRoomsOnEntry) return;
        // roomAtWorld clamps to the grid's edge, so a position outside the 6x6 grid (the boss room) must not count.
        int min = com.killer560.hub.livemap.LiveMapFeature.START_X - 16;
        int max = com.killer560.hub.livemap.LiveMapFeature.START_X + 6 * 32 - 16;
        int minZ = com.killer560.hub.livemap.LiveMapFeature.START_Z - 16;
        int maxZ = com.killer560.hub.livemap.LiveMapFeature.START_Z + 6 * 32 - 16;
        if (x < min || x >= max || z < minZ || z >= maxZ) return;
        com.killer560.hub.livemap.DungeonLayout layout = com.killer560.hub.livemap.DungeonLayout.current();
        int room = layout.roomAtWorld(x, z);
        String name = layout.name(room);
        if (name == null || "Unknown".equals(name)) return;
        com.killer560.hub.roomdatabase.RoomEntry entry = layout.entry(room);
        String type = entry == null ? null : entry.type;
        if ("PUZZLE".equalsIgnoreCase(type) || "BLOOD".equalsIgnoreCase(type)) return;
        if (SimRoomState.markCleared(name)) {
            SimScore.roomCleared();
            com.killer560.hub.util.ModLog.get("killer560smod-sim").info("[Sim] room cleared: {} (entered; every room"
                    + " counts as cleared until the sim has mob fights)", name);
        }
    }

    private static int roomClearTicks = 0;

    /** On by default. The testkit turns it off for the scenarios that measure a clear by its mobs dying (Auto Clear). */
    private static volatile boolean clearRoomsOnEntry = true;

    public static void setClearRoomsOnEntry(boolean on) { clearRoomsOnEntry = on; }

    public static boolean isClearRoomsOnEntry() { return clearRoomsOnEntry; }

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

    /** Starred mobs the server saw die (Fabric AFTER_DEATH), and how many refreshes in a row each has been missing. */
    private static final java.util.Set<UUID> KILLED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Map<UUID, Integer> MISSING = new java.util.concurrent.ConcurrentHashMap<>();
    /** Ten seconds of checks: a chunk unloading far away read "ticking, entity missing" for longer than three (Mossy, 8 s
     *  after GO, 120 blocks away). A real death is AFTER_DEATH or the entity there and not alive; this is only the fallback. */
    private static final int MISSING_TICKS = 200;

    /** When each starred mob's death was confirmed, in order, so a room's key drops at its LAST death. */
    private static final java.util.Map<UUID, Long> DEATH_ORDER = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong DEATH_SEQ = new java.util.concurrent.atomic.AtomicLong();

    private static void recordStarred(UUID id, double x, double y, double z) { STARRED_AT.put(id, new double[]{x, y, z}); }

    /**
     * Marks every room whose recorded starred mobs are all confirmed dead as cleared ({@link SimRoomState#markCleared}),
     * and counts it in the score. Client thread: the room of a position comes from the live map's own layout.
     */
    private static void markClearedRooms() {
        if (STARRED_AT.isEmpty()) return;
        com.killer560.hub.livemap.DungeonLayout layout = com.killer560.hub.livemap.DungeonLayout.current();
        java.util.Map<String, Boolean> allDead = new java.util.HashMap<>();
        java.util.Map<String, double[]> anyAt = new java.util.HashMap<>();
        java.util.Map<String, Long> lastOrder = new java.util.HashMap<>();
        for (java.util.Map.Entry<UUID, double[]> e : STARRED_AT.entrySet()) {
            double[] p = e.getValue();
            int room = layout.roomAtWorld(p[0], p[2]);
            String name = layout.name(room);
            if (name == null || "Unknown".equals(name)) continue;
            boolean dead = STARRED_DEAD.contains(e.getKey());
            allDead.merge(name, dead, Boolean::logicalAnd);
            // The key drops where the room's LAST starred mob died (Hypixel's stand appears there), not at whichever
            // mob the map iterates last: a 2x2's key landed 55 blocks from the kill, out of Auto Clear's 40-block search.
            long order = DEATH_ORDER.getOrDefault(e.getKey(), 0L);
            if (!anyAt.containsKey(name) || order > lastOrder.getOrDefault(name, -1L)) {
                anyAt.put(name, p);
                lastOrder.put(name, order);
            }
        }
        for (java.util.Map.Entry<String, Boolean> e : allDead.entrySet()) {
            if (e.getValue() && SimRoomState.markCleared(e.getKey())) {
                SimScore.roomCleared();
                SimKeyRoom.onClearedByKills(e.getKey(), anyAt.get(e.getKey()));
                com.killer560.hub.util.ModLog.get("killer560smod-sim").info("[Sim] room cleared: {} (every starred mob"
                        + " in it is dead)", e.getKey());
            }
        }
    }

    public static void spawn(Minecraft client, BlockPos pos, Kind kind) { spawn(client, pos, kind, false, DEFAULT_MINIBOSS); }

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
        if (!SimState.canAct(client)) return;
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) return;
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
     * UUID reaching the client before the entity does, and on three {@code ServerPlayer} behaviors being
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

    /**
     * A LIVE starred mob ({@link SimLiveMobs}, 2026-10-20): {@code health} instead of one, and with {@code wander} its AI on
     * with nothing but a random stroll (no target, no attack: the sim is peaceful and a mob that chases him would make a
     * clear's time depend on where he walked). Its star tag follows it and its health figure drops as it is hit, as
     * Hypixel's does, so everything that reads the tag (Mob ESP, Auto Clear's one-tap call) reads what it would there.
     * Server thread only.
     */
    static void spawnLive(ServerLevel level, BlockPos pos, Kind kind, double health, boolean wander) {
        double hp = Math.max(ONE_HP, health);
        switch (kind) {
            case SKELETON -> {
                SimSkeleton s = new SimSkeleton(McEntities.SKELETON, level);
                spawnDummy(level, s, pos, pos.getY(), true, hp);
                if (wander) {
                    s.wanderOnly();
                }
                LIVE.add(s.getUUID());
            }
            case MINIBOSS -> placeBoss(level, pos, MinibossPick.pick(pos), hp);
            default -> {
                SimZombie z = new SimZombie(McEntities.ZOMBIE, level);
                spawnDummy(level, z, pos, pos.getY(), true, hp);
                if (wander) {
                    z.wanderOnly();
                }
                LIVE.add(z.getUUID());
            }
        }
    }

    /** A live miniboss of {@code name} on {@code pos} (a starred zombie when it cannot be placed). Server thread. */
    private static void placeBoss(ServerLevel level, BlockPos pos, SimMiniboss.Name name, double hp) {
        SimMiniboss.Placed placed = SimMiniboss.place(level, pos, name, hp);
        if (placed == null) {
            spawnDummy(level, new SimZombie(McEntities.ZOMBIE, level), pos, pos.getY(), true, hp);
            return;
        }
        ServerPlayer boss = placed.boss();
        SPAWNED.add(boss.getUUID());
        STARRED.add(boss.getUUID());
        recordStarred(boss.getUUID(), boss.getX(), boss.getY(), boss.getZ());
        attachStarTag(level, boss, name.text(), hp, placed.tag());
        LIVE.add(boss.getUUID());
    }

    /**
     * A starred mob as it looked on Hypixel ({@link SimMobLooks}, Real Mob Looks): its entity type, its six equipment
     * slots, its facing ({@code yaw}, already turned with the room) and its star tag's real name, standing still with
     * {@code health} like every sim mob. A player-model mob is a miniboss when its name is one, else a zombie wearing the
     * player's skin as a head. Server thread.
     */
    static void spawnLook(ServerLevel level, BlockPos pos, SimMobLooks.Look look, float yaw, double health) {
        double hp = Math.max(ONE_HP, health);
        String type = look.type();
        Mob mob = null;
        if (type.endsWith(":player")) {
            for (SimMiniboss.Name n : SimMiniboss.Name.values()) {
                if (n.text().equals(look.name())) {
                    placeBoss(level, pos, n, hp);
                    return;
                }
            }
        } else if (type.equals("minecraft:skeleton")) {
            mob = new SimSkeleton(McEntities.SKELETON, level);
        } else if (!type.equals("minecraft:zombie")) {
            net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(type);
            EntityType<?> et = id == null ? null
                    : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
            Entity made = et == null ? null : et.create(level, net.minecraft.world.entity.EntitySpawnReason.LOAD);
            if (made instanceof Mob m && m.getAttribute(Attributes.MAX_HEALTH) != null) {
                mob = m;
            } else if (made != null) {
                made.discard();
            }
        }
        if (mob == null) {
            mob = new SimZombie(McEntities.ZOMBIE, level);
        }
        if (look.skin() != null && !look.gear().containsKey(EquipmentSlot.HEAD)) {
            mob.setItemSlot(EquipmentSlot.HEAD, SimMobLooks.skinHead(look.skin()));
        }
        for (var g : look.gear().entrySet()) {
            mob.setItemSlot(g.getKey(), g.getValue().copy());
        }
        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
        spawnDummy(level, mob, pos, pos.getY(), false, hp);
        STARRED.add(mob.getUUID());
        recordStarred(mob.getUUID(), mob.getX(), mob.getY(), mob.getZ());
        attachStarTag(level, mob, look.name(), hp);
        LIVE.add(mob.getUUID());
    }

    /** Blood camp (SimBloodCamp): the Watcher, still and unhittable, in his own skull (BloodCampFeature knows him by it). */
    static Entity spawnWatcher(ServerLevel level, double x, double y, double z, String skin) {
        SimZombie w = new SimZombie(McEntities.ZOMBIE, level);
        w.setItemSlot(EquipmentSlot.HEAD, SimMobLooks.skinHead(skin));
        w.setNoGravity(true);
        com.killer560.hub.compat.McEntities.setInvulnerable(w, true);
        w.setCustomName(Component.literal("The Watcher"));
        spawnDummy(level, w, BlockPos.containing(x, y, z), y, false, ONE_HP);
        w.setPos(x, y, z);
        return w;
    }

    /** Blood camp: a mob's skull on an invisible stand, in its display case until the Watcher sends it out. */
    static ArmorStand spawnBloodSkull(ServerLevel level, double x, double y, double z, String skin) {
        ArmorStand s = new ArmorStand(level, x, y, z);
        s.setInvisible(true);
        s.setNoGravity(true);
        com.killer560.hub.compat.McEntities.setInvulnerable(s, true);
        s.setItemSlot(EquipmentSlot.HEAD, SimMobLooks.skinHead(skin));
        level.addFreshEntity(s);
        SPAWNED.add(s.getUUID());
        return s;
    }

    /** Blood camp: the mob a skull lands as, one HP, with Hypixel's "Name 1❤" tag built right after it (ESP maps id-1): {mob, tag}. */
    static Entity[] spawnBloodMob(ServerLevel level, double x, double y, double z, String skin, String name, float yaw) {
        SimZombie m = new SimZombie(McEntities.ZOMBIE, level);
        m.setItemSlot(EquipmentSlot.HEAD, SimMobLooks.skinHead(skin));
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        m.setNoGravity(true);
        ArmorStand tag = new ArmorStand(level, x, y + m.getBbHeight() + 0.1, z);
        spawnDummy(level, m, BlockPos.containing(x, y, z), y, false, ONE_HP);
        m.setPos(x, y, z);
        tag.snapTo(x, y + m.getBbHeight() + 0.1, z, 0f, 0f);
        tag.setInvisible(true);
        tag.setNoGravity(true);
        tag.setNoBasePlate(true);
        com.killer560.hub.compat.McEntities.setInvulnerable(tag, true);
        byte flags = tag.getEntityData().get(ArmorStand.DATA_CLIENT_FLAGS);
        tag.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) (flags | ArmorStand.CLIENT_FLAG_MARKER));
        tag.setCustomName(Component.literal(name + " 1\u2764"));
        tag.setCustomNameVisible(true);
        level.addFreshEntity(tag);
        SPAWNED.add(tag.getUUID());
        return new Entity[]{m, tag};
    }

    /** Which miniboss a live CHAMPION room gets: the two whose profile names fit, picked by the spot (reproducible). */
    private static final class MinibossPick {
        static SimMiniboss.Name pick(BlockPos pos) {
            return (pos.asLong() & 1L) == 0 ? SimMiniboss.Name.LOST_ADVENTURER : SimMiniboss.Name.SHADOW_ASSASSIN;
        }
    }

    /** Live mobs ({@link #spawnLive}): their tags follow them and show their health. */
    private static final java.util.Set<UUID> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** A live mob's tag name before its health figure, and the health that figure last showed. */
    private static final java.util.Map<UUID, String> STAR_NAMES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<UUID, Float> TAG_HEALTH = new java.util.concurrent.ConcurrentHashMap<>();

    /** Server thread, every tick: each live starred mob's tag moved onto it and its health figure brought up to date. */
    private static void refreshLiveTags(ServerLevel level) {
        if (LIVE.isEmpty()) return;
        for (UUID id : LIVE) {
            Entity e = level.getEntity(id);
            UUID tagId = STAR_TAGS.get(id);
            if (!(e instanceof net.minecraft.world.entity.LivingEntity mob) || !mob.isAlive() || tagId == null) continue;
            Entity tag = level.getEntity(tagId);
            if (tag == null) continue;
            double ty = mob.getY() + mob.getBbHeight() + 0.1;
            if (Math.abs(tag.getX() - mob.getX()) > 1.0e-3 || Math.abs(tag.getY() - ty) > 1.0e-3
                    || Math.abs(tag.getZ() - mob.getZ()) > 1.0e-3) {
                tag.snapTo(mob.getX(), ty, mob.getZ(), 0f, 0f);
            }
            float hp = mob.getHealth();
            Float shown = TAG_HEALTH.get(id);
            String base = STAR_NAMES.get(id);
            if (base != null && (shown == null || shown != hp)) {
                TAG_HEALTH.put(id, hp);
                tag.setCustomName(Component.literal(" ✯ " + base + " " + shortHealth(Math.max(0, hp)) + "❤"));
            }
        }
    }

    private static void spawnDummy(ServerLevel level, Mob mob, BlockPos pos, boolean starred) {
        spawnDummy(level, mob, pos, starred, ONE_HP);
    }

    private static void spawnDummy(ServerLevel level, Mob mob, BlockPos pos, boolean starred, double health) {
        spawnDummy(level, mob, pos, pos.getY(), starred, health);
    }

    private static void spawnDummy(ServerLevel level, Mob mob, BlockPos pos, double feetY, boolean starred,
                                   double health) {
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        mob.setHealth((float) health);
        mob.setPersistenceRequired();
        // NEVER MOVES. killer560 (2026-09-28): "on the main their movement is dependent on where you are and
        // will be far too hard to replicate I would rather just have them never move". So a sim mob stands
        // exactly where the capture found it, which is also the only way a practiced route means anything -
        // a route timed against mobs that chase you is a route timed against your own path.
        mob.setNoAi(true);
        mob.setPos(pos.getX() + 0.5, feetY, pos.getZ() + 0.5);
        level.addFreshEntity(mob);
        SPAWNED.add(mob.getUUID());
        if (starred) {
            STARRED.add(mob.getUUID());
            recordStarred(mob.getUUID(), mob.getX(), mob.getY(), mob.getZ());
            attachStarTag(level, mob);
        }
    }

    /**
     * Gives a starred mob the same name-tag armor stand Hypixel gives it.
     *
     * <p>killer560 asked that "things like mob, ESP work and door ESP and all that style of stuff". Mob ESP does
     * not look at the mob: Hypixel puts the "star ... heart" name on a separate invisible armor stand and every
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
        com.killer560.hub.compat.McEntities.setInvulnerable(tag, true);
        // A marker, so the tag has no hitbox to catch hits meant for the mob (the same fix as the blaze labels).
        byte flags = tag.getEntityData().get(ArmorStand.DATA_CLIENT_FLAGS);
        tag.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) (flags | ArmorStand.CLIENT_FLAG_MARKER));
        tag.setCustomName(Component.literal(" ✯ " + displayName + " " + shortHealth(health) + "❤"));
        tag.setCustomNameVisible(true);
        level.addFreshEntity(tag);
        SPAWNED.add(tag.getUUID());
        STAR_TAGS.put(mob.getUUID(), tag.getUUID());
        STAR_NAMES.put(mob.getUUID(), displayName);
    }

    /** Health the way Hypixel writes it on a name tag: 337.5k, 6.6M. */
    private static String shortHealth(double health) {
        if (health >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fM", health / 1_000_000);
        return health >= 1_000 ? String.format(java.util.Locale.ROOT, "%.1fk", health / 1_000) : String.valueOf((long) health);
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
        BATS.put(bat, pos.immutable());
    }

    /** The health a sim crypt mob's tag shows: hypixelskyblock.minecraft.wiki, Crypt Undead, Floor VII (512,578). */
    static final double CRYPT_UNDEAD_TAG_HEALTH = 512_578;

    /** Crypt undead alive in the world, id to whether it is the prince that scores. */
    private static final java.util.Map<UUID, Boolean> CRYPT_MOBS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Where each crypt undead was spawned, so a null read-back counts as killed only where entities tick. */
    private static final java.util.Map<UUID, BlockPos> CRYPT_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A crypt's undead (or the prince's), spawned when its crypt is blown. Its crypt point is counted when it DIES,
     * as on Hypixel, where "Crypts: N" in the tab list rises on the kill and the prince's kill says
     * "A Prince falls. +1 Bonus Score" in chat. Until 2026-10-05 the sim scored the crypt when the wall broke, which
     * left a client feature that watches for the kill (Auto Routes' crypt node) nothing to see.
     */
    public static void spawnCrypt(Minecraft client, BlockPos pos, boolean scoringPrince) {
        if (!SimState.canAct(client)) return;
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            SimZombie undead = new SimZombie(McEntities.ZOMBIE, level);
            // killer560 (2026-10-06): "have crypt mobs spawn with the word crypt above them". No star, so nothing
            // that looks for starred mobs counts it.
            // Hypixel's tag, exactly (killer560's run screenshot, 2026-10-16: "⚔ Crypt Undead 512.6k❤"; only Crypt
            // Undead and Prince exist), so the client reads the sim's crypt mobs as it reads Hypixel's
            // (autoroutes.CryptMobs.TAG). The health shown is the wiki's Floor VII Crypt Undead (512,578); the
            // mob itself still dies to one hit, like every sim mob.
            undead.setCustomName(Component.literal("⚔ " + (scoringPrince ? "Prince" : "Crypt Undead") + " "
                    + shortHealth(CRYPT_UNDEAD_TAG_HEALTH) + "❤"));
            undead.setCustomNameVisible(true);
            // ON THE FLOOR. killer560 (2026-10-13): "make sure the princes and the crypts spawn on the floor once they
            // explode" - a sim mob has no AI and so no gravity, and Big Red Flag's prince was placed above the top
            // block of its tomb, which the blast had just removed, leaving it hanging in the air.
            BlockPos at = pos;
            for (int up = 0; up < 4 && !level.getBlockState(at).getCollisionShape(level, at).isEmpty(); up++) {
                at = at.above();
            }
            double feetY = at.getY();
            for (int down = 0; down < 24; down++) {
                BlockPos below = at.below();
                var shape = level.getBlockState(below).getCollisionShape(level, below);
                if (!shape.isEmpty()) {
                    feetY = below.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
                    break;
                }
                at = below;
                feetY = at.getY();
            }
            spawnDummy(level, undead, at, feetY, false, ONE_HP);
            CRYPT_AT.put(undead.getUUID(), at.immutable());
            CRYPT_MOBS.put(undead.getUUID(), scoringPrince);
        });
    }

    private static void countDeadCrypts(ServerLevel level) {
        if (CRYPT_MOBS.isEmpty()) return;
        for (var it = CRYPT_MOBS.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Entity mob = level.getEntity(e.getKey());
            if (mob != null && mob.isAlive()) {
                CRYPT_AT.put(e.getKey(), mob.blockPosition());
                continue;
            }
            BlockPos at = CRYPT_AT.get(e.getKey());
            if (mob == null && (at == null || !level.isPositionEntityTicking(at))) {
                continue;   // missing in a section the server is not ticking: unknown, not killed
            }
            it.remove();
            CRYPT_AT.remove(e.getKey());
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
        if (BATS.isEmpty()) return;
        java.util.Iterator<java.util.Map.Entry<net.minecraft.world.entity.ambient.Bat, BlockPos>> it =
                BATS.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            net.minecraft.world.entity.ambient.Bat bat = entry.getKey();
            // KILLED only. The entity object is kept rather than its UUID, because getEntity(uuid) is null for a bat
            // in a section the server is not ticking, and that used to count as a kill; a bat discarded by a rebuild
            // (removal reason DISCARDED) is not a secret found either.
            if (bat.isDeadOrDying() || bat.getRemovalReason() == Entity.RemovalReason.KILLED) {
                it.remove();
                com.killer560.hub.roomsim.SimScore.batKilled(entry.getValue());
            } else if (bat.isRemoved()) {
                it.remove();
            }
        }
    }

    /**
     * Bats spawned by the sim, against the block they were placed at, so their death counts as a secret of that room
     * rather than as a kill. Written and read on the server thread.
     */
    private static final java.util.Map<net.minecraft.world.entity.ambient.Bat, BlockPos> BATS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A floor's secret bat, spawned when he comes near it ({@link SimSecretItems}), on the server thread.
     *
     * <p>Hypixel's secret bat appears when a player comes near its spot, and that APPEARANCE is the client signal
     * Auto Routes' {@code await} counts (a bat with Skyblock health first seen within 10 blocks, QUOI's rule) - so a
     * sim bat spawned with the floor, minutes before the route got there, was never counted (2026-10-09, "in sim
     * the await does not detect bats"). Its death is the secret, counted for its room.
     */
    static void spawnSecretBat(ServerLevel level, BlockPos at) {
        var bat = new net.minecraft.world.entity.ambient.Bat(McEntities.BAT, level);
        bat.setPos(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        // Still, like every other sim mob: killer560 asked that nothing wanders, so a bat cannot fly off and turn a
        // fixed secret into a moving target.
        bat.setNoAi(true);
        bat.setNoGravity(true);
        bat.setPersistenceRequired();
        secretBatHealth(bat);
        bat.setHealth((float) ONE_HP);
        level.addFreshEntity(bat);
        SPAWNED.add(bat.getUUID());
        BATS.put(bat, at.immutable());
    }

    /** Live secret bats, for tests. */
    public static int secretBatsAlive() { return BATS.size(); }

    /**
     * How far below the floor a skull's armor stand stands, so the skull it wears rests ON the floor: a full-size stand's
     * head slot draws from about 1.44 blocks over its feet (the head pivot, 23 model pixels up). killer560 (Doc,
     * 2026-11-01): "Fels should just be skulls on the ground black fel skulls that you cannot interact with."
     */
    static final double SKULL_DROP = 1.4375;

    /**
     * Places a dormant Fel (killer560, Doc 2026-11-01: "black fel skulls that you cannot interact with. Once you get close
     * to them then a enderman upside down spawns ... name them dinnerbone ... and not have the nametag show"):
     * <ul>
     *   <li>the skull: an invisible MARKER armor stand (no hitbox, so nothing can hit or click it) wearing a wither
     *       skeleton skull, stood {@link #SKULL_DROP} into the floor so the black skull lies on the ground;</li>
     *   <li>a starred Fel also has its star tag from the start, over a sleeping enderman named Dinnerbone - invisible,
     *       untouchable, never moving - so every client feature that reads star tags (Mob ESP, Auto Clear) knows a
     *       starred mob is there before it wakes, as Hypixel's dormant Fel is an enderman under its tag (the reference
     *       mods' notes: "an invisible Fel shows only its head", "an enderman counts whether it can be seen or not").</li>
     * </ul>
     * It wakes ({@link #wake}) once a player is within {@link #FEL_WAKE_RADIUS}. Server thread.
     */
    private static void spawnFel(ServerLevel level, BlockPos pos, boolean starred) {
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;
        ArmorStand skull = new ArmorStand(level, x, pos.getY() - SKULL_DROP, z);
        skull.setInvisible(true);
        skull.setNoBasePlate(true);
        skull.setShowArms(false);
        skull.setNoGravity(true);
        com.killer560.hub.compat.McEntities.setInvulnerable(skull, true);
        byte flags = skull.getEntityData().get(ArmorStand.DATA_CLIENT_FLAGS);
        skull.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) (flags | ArmorStand.CLIENT_FLAG_MARKER));
        skull.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.WITHER_SKELETON_SKULL));
        level.addFreshEntity(skull);
        SimEnderman sleeper = null;
        if (starred) {
            sleeper = new SimEnderman(McEntities.ENDERMAN, level);
            sleeper.dormant = true;
            sleeper.setInvisible(true);
            spawnDummy(level, sleeper, pos, pos.getY(), true, ONE_HP);
            STAR_NAMES.put(sleeper.getUUID(), "Fel");
            UUID tagId = STAR_TAGS.get(sleeper.getUUID());
            Entity tag = tagId == null ? null : level.getEntity(tagId);
            if (tag != null) {
                tag.setCustomName(Component.literal(" \u272F Fel " + shortHealth(ONE_HP) + "\u2764"));
            }
            LIVE.add(sleeper.getUUID());
        }
        FELS.add(new FelMarker(skull, starred, sleeper));
        FELS_PLACED.incrementAndGet();
    }

    /** {@link #spawnFel} for {@link SimLiveMobs} (server thread). */
    static void spawnFelLive(ServerLevel level, BlockPos pos, boolean starred) { spawnFel(level, pos, starred); }

    /** Checks every dormant Fel against every player each tick and wakes the ones close enough. */
    private static void wakeFels(ServerLevel level) {
        if (FELS.isEmpty()) return;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;
        for (FelMarker fel : FELS) {
            if (fel.woken || fel.skull.isRemoved()) continue;
            double sx = fel.skull.getX();
            double sy = fel.skull.getY() + SKULL_DROP;
            double sz = fel.skull.getZ();
            for (ServerPlayer player : players) {
                // A placed miniboss IS a ServerPlayer and so is in level.players() - it must not wake a Fel.
                if (SimMiniboss.isPlaced(player)) continue;
                double d2 = player.distanceToSqr(sx, sy, sz);
                if (d2 <= FEL_WAKE_RADIUS_SQ) {
                    wake(level, fel);
                    lastFelWake = String.format(java.util.Locale.US, "%.1f %.1f %.1f|%.2f|%s", sx, sy, sz, Math.sqrt(d2),
                            fel.starred);
                    com.killer560.hub.util.ModLog.get("killer560smod-sim").info("[Sim] Fel woke at {} {} {} ({} blocks"
                            + " from {}, {})", (int) Math.floor(sx), (int) Math.floor(sy), (int) Math.floor(sz),
                            String.format(java.util.Locale.US, "%.1f", Math.sqrt(d2)), player.getGameProfile().name(),
                            fel.starred ? "starred" : "not starred");
                    break;
                }
            }
        }
    }

    /**
     * The skull goes and the Fel is an enderman where it lay: a starred Fel's sleeping enderman shows and can be hit, an
     * unstarred one is placed now.
     *
     * <p><b>Upside down, using vanilla's own rule.</b> {@code LivingEntityRenderer.isUpsideDownName} flips anything named
     * "Dinnerbone" or "Grumm" (javap, 26.1.2 and 26.3), so the enderman is named Dinnerbone (his word, and the reference
     * mods' reading of Hypixel's Fel) and the tag is hidden: the flip comes from the name being set, not drawn.
     */
    private static void wake(ServerLevel level, FelMarker fel) {
        fel.woken = true;
        FELS_WOKEN.incrementAndGet();
        double x = fel.skull.getX();
        double y = fel.skull.getY() + SKULL_DROP;
        double z = fel.skull.getZ();
        fel.skull.discard();
        if (fel.sleeper != null && !fel.sleeper.isRemoved()) {
            fel.sleeper.dormant = false;
            fel.sleeper.setInvisible(false);
            return;
        }
        SimEnderman enderman = new SimEnderman(McEntities.ENDERMAN, level);
        enderman.getAttribute(Attributes.MAX_HEALTH).setBaseValue(ONE_HP);
        enderman.setHealth((float) ONE_HP);
        enderman.setPersistenceRequired();
        enderman.setNoAi(true);
        enderman.setPos(x, y, z);
        level.addFreshEntity(enderman);
        SPAWNED.add(enderman.getUUID());
    }

    /**
     * Marks a starred mob dead once it is gone from a ticking section (an unloaded one proves nothing), and takes
     * its star tag down with it.
     */
    private static void refreshStarred(ServerLevel level) {
        for (UUID id : STARRED) {
            Entity entity = level.getEntity(id);
            if ((entity == null || !entity.isAlive()) && !STARRED_DEAD.contains(id)) {
                double[] at = STARRED_AT.get(id);
                boolean ticking = at != null && level.isPositionEntityTicking(BlockPos.containing(at[0], at[1], at[2]));
                // Missing is dead only when it STAYS missing from a ticking section (MISSING_TICKS checks in a row, ten seconds) or the
                // server saw it die (AFTER_DEATH): a mob whose chunk was unloading read as missing for a moment while the
                // section still said ticking, and whole rooms - key rooms among them - "cleared" with nobody near
                // (doc-adm-sim-1101: Mossy cleared 3 s after GO while the test stood at another door; its key never dropped).
                int missing = entity == null && ticking ? MISSING.merge(id, 1, Integer::sum) : 0;
                if (entity != null || !ticking) {
                    MISSING.remove(id);
                }
                if (entity != null || KILLED.contains(id) || missing >= MISSING_TICKS) {
                    STARRED_DEAD.add(id);
                    if (!KILLED.contains(id)) {
                        com.killer560.hub.util.ModLog.get("killer560smod-sim").info("[Sim] starred mob {} counted dead"
                                + " without dying: {}", id, entity != null ? "removed (" + entity.getRemovalReason() + ")"
                                : "missing " + missing + " checks");
                    }
                    DEATH_ORDER.put(id, DEATH_SEQ.incrementAndGet());
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
    }

    /**
     * How many entities this class has spawned, and how many of those were starred - written the moment the entity
     * is added, which is the question a test needs answered.
     */
    public static int spawnedCount() { return SPAWNED.size(); }

    public static int starredCount() { return STARRED.size(); }

    /**
     * Every starred mob paired with its name-tag armor stand, as {@code {mobUuid, tagUuid}}.
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
        BATS.clear();
        STARRED_AT.clear();
        STARRED_DEAD.clear();
        DEATH_ORDER.clear();
        KILLED.clear();
        MISSING.clear();
        CRYPT_MOBS.clear();
        CRYPT_AT.clear();
        SimMimic.forgetMob();
        STARRED.clear();
        STAR_TAGS.clear();
        STAR_NAMES.clear();
        TAG_HEALTH.clear();
        LIVE.clear();
        FELS.clear();
        FELS_PLACED.set(0);
        FELS_WOKEN.set(0);
        lastFelWake = null;
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
        protected boolean isSunSensitive() { return false; }

        /** AI on with a random stroll only (no target, no attack) - a live mob that walks about ({@link #spawnLive}). */
        void wanderOnly() {
            setNoAi(false);
            goalSelector.removeAllGoals(g -> true);
            targetSelector.removeAllGoals(g -> true);
            goalSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.RandomStrollGoal(this, 0.8, 20));
        }
    }

    /** Plain {@link Skeleton} minus {@code checkDespawn()} - see {@link #spawnDummy}. Skeleton has no separate
     *  sun-sensitivity override to disable in this version; checked with {@code javap} against Skeleton and
     *  AbstractSkeleton. */
    private static final class SimSkeleton extends Skeleton {
        SimSkeleton(EntityType<? extends Skeleton> type, Level level) {
            super(type, level);
        }

        /** See {@link SimZombie#wanderOnly}. */
        void wanderOnly() {
            setNoAi(false);
            goalSelector.removeAllGoals(g -> true);
            targetSelector.removeAllGoals(g -> true);
            goalSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.RandomStrollGoal(this, 0.8, 20));
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

    /**
     * A Fel's enderman: never despawns, never teleports, and takes a player's projectile (the Terminator's arrows) like any
     * other hit - vanilla's enderman dodges every projectile by teleporting (javap 26.3 {@code Enderman.hurtServer}: an
     * {@code IS_PROJECTILE} source teleports and returns false), and a Fel that did that could never be cleared. While
     * {@link #dormant} (a starred Fel still a skull) nothing hurts it. Named Dinnerbone with the tag hidden, so it is drawn
     * upside down.
     */
    private static final class SimEnderman extends Enderman {
        volatile boolean dormant;

        SimEnderman(EntityType<? extends Enderman> type, Level level) {
            super(type, level);
            setCustomName(net.minecraft.network.chat.Component.literal("Dinnerbone"));
            setCustomNameVisible(false);
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        protected boolean teleport() { return false; }

        /**
         * A sleeping Fel stays invisible. {@code LivingEntity.updateInvisibilityStatus} (javap 26.2 and 26.3) sets
         * {@code setInvisible(hasEffect(INVISIBILITY))} whenever the effects are re-read - with none, visible - so a plain
         * {@code setInvisible(true)} lasted one tick, the sleeper showed, and Auto Clear shot at a Fel nothing could hurt
         * (doc-adm-sim-1101, Banners on 26.2: "12 bats from here and none touched it").
         */
        @Override
        protected void updateInvisibilityStatus() {
            if (dormant) {
                setInvisible(true);
                return;
            }
            super.updateInvisibilityStatus();
        }

        @Override
        public boolean isSensitiveToWater() { return false; }

        @Override
        public boolean isInvulnerableTo(net.minecraft.server.level.ServerLevel level,
                                        net.minecraft.world.damagesource.DamageSource source) {
            return dormant || environmental(source) || super.isInvulnerableTo(level, source);
        }

        @Override
        public boolean hurtServer(net.minecraft.server.level.ServerLevel level,
                                  net.minecraft.world.damagesource.DamageSource source, float amount) {
            if (dormant) return false;
            if (source.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE)
                    && source.getEntity() instanceof net.minecraft.world.entity.player.Player p) {
                return super.hurtServer(level, level.damageSources().playerAttack(p), amount);
            }
            return super.hurtServer(level, source, amount);
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
