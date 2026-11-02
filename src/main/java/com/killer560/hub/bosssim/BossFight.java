package com.killer560.hub.bosssim;

import com.killer560.hub.compat.McEntities;
import com.killer560.hub.util.ModLog;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * The F7/M7 boss fight in the sim: Maxor, Storm, Goldor, Necron and (on Master Mode) the Wither King, on the server
 * thread, one server tick at a time.
 *
 * <p>Every line is Hypixel's own text and comes at Hypixel's pace (server ticks from the phase's first line), as
 * recorded by the reference mod ADM (undonecoffee/adm) and the mod's own trackers. What the players DO is simplified to
 * what one player can do alone - the parts the mod's features read are kept exact:
 * <ul>
 * <li><b>P1 Maxor</b>: the two Energy Crystals appear on their towers; walking into one picks it up, using it at a pylon
 * places it; two placed fire the laser, which stuns Maxor and empties the pylons, and the next set is back on the towers
 * 34 ticks after his laser line; hits on him while he is stunned wear him down; the second
 * stun ends him. Then his floor opens over Storm's.</li>
 * <li><b>P2 Storm</b>: Storm hovers from pillar to pillar; standing on a pillar's pad drops it, and a drop with Storm under
 * it crushes him. Two crushes and he dies; his floor opens over S1.</li>
 * <li><b>P3 Goldor</b>: {@link BossStations} and {@link BossDevices} - every terminal, lever and device, the gates, the
 * core. Goldor is beaten in the core; its floor opens over Necron.</li>
 * <li><b>P4 Necron</b>: his lines, then hits wear him down. On F7 that ends the run; on M7 his platform opens.</li>
 * <li><b>P5</b> (M7): the Wither King's dragons spawn at their statues (Hypixel's flame burst over the statue, the dragon
 * 100 server ticks later); each falls to hits. The relics are not modeled.</li>
 * </ul>
 */
public final class BossFight {

    private static final Logger LOGGER = ModLog.get("killer560smod-bosssim");

    public enum Phase { IDLE, P1, P2, P3, P4, P5, DONE }

    public enum Start { P1, P2, S1, S2, S3, S4, CORE, P4, P5 }

    // ---- geometry shared with BossArena ---------------------------------------------------------------------------

    /** One Storm pillar: its 7x7 square's corner, its resting bottom, its pad's corner (null: no pad) and pad block. */
    record Pillar(String name, int minX, int minZ, int restBottom, int[] pad, String padBlock) {
        boolean under(Vec3 p) {
            return p.x >= minX && p.x < minX + 7 && p.z >= minZ && p.z < minZ + 7;
        }

        boolean onPad(Vec3 p) {
            return pad != null && p.x >= pad[0] && p.x < pad[0] + 7 && p.z >= pad[1] && p.z < pad[1] + 7
                    && p.y >= BossArena.P2_FLOOR + 1 && p.y < BossArena.P2_FLOOR + 4;
        }
    }

    /** ADM's measured pillars and pads (P2Storm.kt), the same squares the mod's Diorite Glass covers. */
    static final List<Pillar> PILLARS = List.of(
            new Pillar("Purple", 97, 62, 175, new int[]{111, 91}, "minecraft:purple_wool"),
            new Pillar("Yellow", 43, 62, 175, new int[]{29, 91}, "minecraft:yellow_wool"),
            new Pillar("Green", 43, 38, 175, new int[]{29, 9}, "minecraft:lime_wool"),
            new Pillar("Red", 97, 38, 181, null, "minecraft:red_wool"));
    static final int PILLAR_TOP = 182;

    /** Holes opened by each boss's death, as {x1, z1, x2, z2} at that phase's floor. */
    static final int[] P1_HOLE = {66, 56, 80, 70};
    static final int[] P2_HOLE = {104, 30, 110, 36};
    static final int[] CORE_HOLE = {50, 72, 58, 80};
    static final int[] P4_HOLE = {50, 72, 58, 80};

    /** Necron's side platforms (ADM P4Necron.kt PLATFORMS), x and z. */
    static final int[][] NECRON_PLATFORMS = {{54, 42}, {28, 50}, {80, 50}, {28, 102}, {80, 102}};

    /** The five dragons' statues (WitherDragon.java): x, statue top y, z - and the color the mod names them by. */
    static final int[][] DRAGON_STATUES = {{27, 8, 59}, {85, 8, 56}, {27, 8, 94}, {84, 8, 94}, {56, 8, 125}};
    private static final String[] DRAGON_NAMES = {"Red", "Orange", "Green", "Blue", "Purple"};

    // ---- timings (server ticks), Hypixel's as ADM measured them ---------------------------------------------------

    private static final int MAXOR_CRYSTALS = 40;
    private static final int MAXOR_HITS_PER_STUN = 5;
    private static final int MAXOR_STUN_TICKS = 200;
    /** Server ticks from Maxor's laser line to the next crystal set on the towers (NoammAddons MaxorsCrystals.kt). */
    private static final int MAXOR_CRYSTAL_RESPAWN = 34;
    private static final int STORM_AFTER = 62;
    private static final int STORM_MOVE_FROM = 186;
    private static final int STORM_SON = 42;
    private static final int GOLDOR_LEAD = 62;
    private static final int LINE_GAP = 62;
    private static final int GATE_DELAY = 100;
    private static final int GOLDOR_HITS = 10;
    private static final int NECRON_AFTER = 62;
    private static final int NECRON_INTRO = 42;
    private static final int NECRON_HITS = 15;
    private static final int DRAGON_SPAWN = 100;
    private static final int DRAGON_HITS = 8;

    private static final Vec3 P1_SPAWN = new Vec3(73.5, BossArena.P1_FLOOR + 1, 16.5);
    private static final Vec3 MAXOR_AT = new Vec3(73.0, 226.0, 53.0);
    private static final Vec3[] CRYSTAL_TOPS = {new Vec3(64.5, 238.0, 50.5), new Vec3(82.5, 238.0, 50.5)};
    private static final Vec3[] PYLONS = {new Vec3(52.5, 224.0, 41.5), new Vec3(94.5, 224.0, 41.5)};
    private static final Vec3 P2_SPAWN = new Vec3(73.5, BossArena.P2_FLOOR + 1, 50.5);
    private static final Vec3 S1_SPAWN = new Vec3(100.5, BossArena.P3_FLOOR + 1, 40.5);
    private static final Vec3 GOLDOR_AT = new Vec3(54.5, 112.0, 95.5);
    private static final Vec3 CORE_CENTER = new Vec3(54.5, BossArena.P3_FLOOR + 1, 95.5);
    private static final AABB CORE_BOX = new AABB(41, BossArena.P3_FLOOR + 1, 59, 69, BossArena.P3_FLOOR + 20, 118);
    private static final Vec3 NECRON_AT = new Vec3(54.0, 66.0, 76.0);
    private static final Vec3 P4_SPAWN = new Vec3(54.5, BossArena.P4_FLOOR + 1, 70.5);
    private static final Vec3 P5_SPAWN = new Vec3(54.5, BossArena.P5_FLOOR + 1, 76.5);

    private static final String[] MAXOR_TAUNTS = {
            "YOUR WEAPONS CAN'T PIERCE THROUGH MY SHIELD!", "YOUR MOBILITY TRICKS DON'T WORK IN MY DOMAIN!",
            "I HOPE YOU LIKE EXPLOSIONS TOO!", "MY MINIONS WILL HAVE TO WIPE THE FLOOR AFTER I'M DONE WITH YOU ALL!"};
    private static final String[] STORM_TAUNTS = {
            "BEGONE PILLAR!", "No more adventurers, no more heroes, death and thunder!", "FINALLY! This took way too long.",
            "Not just your land, but every kingdom will soon be ruled by our army of undead!",
            "This factory is too small for me!", "Slowing me down will be your greatest accomplishment!",
            "The days are numbered until I am finally unleashed again on the world!",
            "Now that you're a Ghost, can you help me clean up?",
            "The Age of Men is over, we are creating tens, hundreds of withers!!", "THAT WAS ONLY IN MY WAY!"};
    private static final String[] GOLDOR_SECTION_LINES = {
            "The little ants have a brain it seems.", "I will replace that gate with a stronger one!", "YOUR END IS NEAR!!"};
    private static final String[] KING_COUNTS = {"Oh, this one hurts!", "I have more of those.", "My soul is disposable."};

    // ---- state ----------------------------------------------------------------------------------------------------

    private static Phase phase = Phase.IDLE;
    private static MinecraftServer server;
    private static ServerLevel level;
    private static boolean master;
    /** Server ticks since the current phase's first line. */
    private static int t;
    private static int gen;
    private static final Random RNG = new Random();

    private record Later(int at, int gen, String what, Runnable r) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static int clock;

    private static BossMob maxor, storm, goldor, necron;
    private static ServerBossEvent bar;

    // P1
    private static final EndCrystal[] tops = new EndCrystal[2];
    private static final EndCrystal[] placed = new EndCrystal[2];
    private static int carrying;
    private static int maxorStuns;
    private static int maxorHits;
    private static int stunEndsAt = -1;
    private static int maxorKilledAt = -1;
    private static boolean laserCharging;
    // P2
    private static int stormLeg;
    private static int stormHoldUntil;
    private static int crushes;
    private static int stormDeadAt = -1;
    private static int nextStormTaunt = -1;
    private static final int[] pillarBottom = new int[4];
    private static final int[] pillarResetAt = {-1, -1, -1, -1};
    private static final boolean[] pillarSpent = new boolean[4];
    private static final boolean[] pillarDropping = new boolean[4];
    // P3
    private static int section;
    private static final Deque<String> goldorLines = new ArrayDeque<>();
    private static int lastGoldorLine = -1000;
    private static int gateAt = -1;
    private static boolean coreOpen;
    private static boolean goldorArrived;
    private static int goldorHits;
    private static int goldorDeadAt = -1;
    // P4
    private static int necronHits;
    private static boolean necronVulnerable;
    private static int necronDeadAt = -1;
    // P5
    private static final SimDragon[] dragons = new SimDragon[5];
    private static final int[] dragonHits = new int[5];
    private static final boolean[] dragonDead = new boolean[5];
    private static final int[] dragonSpawnAt = {-1, -1, -1, -1, -1};
    private static int kingLine;

    private BossFight() {
    }

    public static Phase phase() {
        return phase;
    }

    /** P3: the section in progress (1-4), 5 once the core is open; 0 outside P3. */
    public static int section() {
        return phase == Phase.P3 ? section : 0;
    }

    public static boolean running() {
        return phase != Phase.IDLE && phase != Phase.DONE;
    }

    // ---- scheduling -----------------------------------------------------------------------------------------------

    static void later(int ticks, String what, Runnable r) {
        LATER.add(new Later(clock + Math.max(1, ticks), gen, what, r));
    }

    // ---- start / stop ---------------------------------------------------------------------------------------------

    /** Clears every entity and all state; the blocks are the arena's (rebuilt before a new fight). */
    static void stop(String why) {
        if (phase != Phase.IDLE) {
            LOGGER.info("[BossSim] fight stopped in {} ({})", phase, why);
        }
        gen++;
        LATER.clear();
        for (BossMob m : new BossMob[]{maxor, storm, goldor, necron}) {
            if (m != null) {
                m.remove();
            }
        }
        maxor = storm = goldor = necron = null;
        for (int i = 0; i < 2; i++) {
            discard(tops[i]);
            discard(placed[i]);
            tops[i] = placed[i] = null;
        }
        for (int i = 0; i < 5; i++) {
            discard(dragons[i]);
            dragons[i] = null;
        }
        if (bar != null) {
            bar.removeAllPlayers();
            bar = null;
        }
        BossStations.reset();
        BossDevices.stop();
        BossTerminals.closeAll(server);
        if (phase != Phase.IDLE) {
            forceChunks(false);
        }
        phase = Phase.IDLE;
    }

    /** The world the fight was in has closed: forget it without touching its (gone) entities or server. */
    static void forget(String why) {
        LOGGER.info("[BossSim] fight forgotten in {} ({})", phase, why);
        gen++;
        LATER.clear();
        maxor = storm = goldor = necron = null;
        java.util.Arrays.fill(tops, null);
        java.util.Arrays.fill(placed, null);
        java.util.Arrays.fill(dragons, null);
        bar = null;
        server = null;
        level = null;
        phase = Phase.IDLE;
    }

    private static void discard(Entity e) {
        if (e != null && !e.isRemoved()) {
            e.discard();
        }
    }

    /** Begins the fight at {@code from}, with the arena already built. Server thread. */
    static void begin(MinecraftServer s, Start from, boolean masterMode) {
        stop("new fight");
        server = s;
        level = s.overworld();
        master = masterMode;
        forceChunks(true);
        carrying = 0;
        maxorStuns = maxorHits = 0;
        stunEndsAt = maxorKilledAt = -1;
        laserCharging = false;
        crushes = 0;
        stormDeadAt = -1;
        nextStormTaunt = -1;
        stormLeg = 0;
        stormHoldUntil = 0;
        for (int i = 0; i < 4; i++) {
            pillarBottom[i] = PILLARS.get(i).restBottom();
            pillarResetAt[i] = -1;
            pillarSpent[i] = false;
            pillarDropping[i] = false;
        }
        goldorLines.clear();
        lastGoldorLine = -1000;
        gateAt = -1;
        coreOpen = goldorArrived = false;
        goldorHits = 0;
        goldorDeadAt = -1;
        necronHits = 0;
        necronVulnerable = false;
        necronDeadAt = -1;
        java.util.Arrays.fill(dragonHits, 0);
        java.util.Arrays.fill(dragonDead, false);
        java.util.Arrays.fill(dragonSpawnAt, -1);
        kingLine = 0;
        LOGGER.info("[BossSim] fight begins at {} ({})", from, master ? "Master Mode" : "Floor 7");
        com.killer560.hub.secrets.DungeonState.simBossBegins();
        switch (from) {
            case P1 -> startP1(true);
            case P2 -> {
                openHole(P1_HOLE, BossArena.P1_FLOOR);
                teleportAll(P2_SPAWN, 0f);
                startP2();
            }
            case S1, S2, S3, S4, CORE -> {
                openHole(P1_HOLE, BossArena.P1_FLOOR);
                openHole(P2_HOLE, BossArena.P2_FLOOR);
                int sec = from == Start.CORE ? 5 : from.ordinal() - Start.S1.ordinal() + 1;
                teleportAll(sectionSpawn(sec), 0f);
                startP3(sec);
            }
            case P4 -> {
                openHole(P1_HOLE, BossArena.P1_FLOOR);
                openHole(P2_HOLE, BossArena.P2_FLOOR);
                openHole(CORE_HOLE, BossArena.P3_FLOOR);
                teleportAll(P4_SPAWN, 180f);
                startP4();
            }
            case P5 -> {
                master = true;
                openHole(P1_HOLE, BossArena.P1_FLOOR);
                openHole(P2_HOLE, BossArena.P2_FLOOR);
                openHole(CORE_HOLE, BossArena.P3_FLOOR);
                openHole(P4_HOLE, BossArena.P4_FLOOR);
                teleportAll(P5_SPAWN, 180f);
                startP5();
            }
        }
    }

    private static Vec3 sectionSpawn(int sec) {
        return switch (sec) {
            case 2 -> new Vec3(100.5, BossArena.P3_FLOOR + 1, 133.5);
            case 3 -> new Vec3(6.5, BossArena.P3_FLOOR + 1, 133.5);
            case 4 -> new Vec3(6.5, BossArena.P3_FLOOR + 1, 38.5);
            case 5 -> new Vec3(54.5, BossArena.P3_FLOOR + 1, 53.5);
            default -> S1_SPAWN;
        };
    }

    static void teleportAll(Vec3 at, float yaw) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.teleportTo(level, at.x, at.y, at.z, java.util.Set.of(), yaw, 0f, true);
            p.fallDistance = 0;
        }
    }

    /** Opens a hole through a floor (and the bedrock bed under P3's and P4's lava, one block lower). */
    private static void openHole(int[] hole, int y) {
        BossBlocks.fill(level, hole[0], y - 1, hole[1], hole[2], y, hole[3], BossBlocks.AIR);
    }

    private static void phase(Phase p) {
        phase = p;
        t = 0;
    }

    private static void showBar(String name, float progress) {
        if (bar == null) {
            bar = new ServerBossEvent(java.util.UUID.randomUUID(), BossChat.legacy(name), BossEvent.BossBarColor.PURPLE,
                    BossEvent.BossBarOverlay.PROGRESS);
        } else {
            bar.setName(BossChat.legacy(name));
        }
        bar.setProgress(Math.max(0f, Math.min(1f, progress)));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            bar.addPlayer(p);
        }
    }

    // ---- the tick ---------------------------------------------------------------------------------------------------

    static void tick(MinecraftServer s) {
        if (phase == Phase.IDLE || phase == Phase.DONE || s != server) {
            return;
        }
        clock++;
        t++;
        if (!LATER.isEmpty()) {
            List<Later> due = new ArrayList<>();
            LATER.removeIf(l -> {
                if (l.gen != gen) {
                    return true;
                }
                if (l.at <= clock) {
                    due.add(l);
                    return true;
                }
                return false;
            });
            for (Later l : due) {
                try {
                    l.r.run();
                } catch (RuntimeException e) {
                    LOGGER.error("[BossSim] scheduled '{}' threw", l.what, e);
                }
            }
        }
        for (BossMob m : new BossMob[]{maxor, storm, goldor, necron}) {
            if (m != null) {
                m.tick();
            }
        }
        switch (phase) {
            case P1 -> tickP1();
            case P2 -> tickP2();
            case P3 -> tickP3();
            case P4 -> tickP4();
            case P5 -> tickP5();
            default -> {
            }
        }
        BossLava.tick(s, level);
    }

    // ================================================================================================== P1 Maxor

    private static void startP1(boolean place) {
        phase(Phase.P1);
        if (place) {
            teleportAll(P1_SPAWN, 0f);
        }
        BossChat.boss(server, "Maxor", "WELL! WELL! WELL! LOOK WHO'S HERE!");
        maxor = new BossMob("Maxor", MAXOR_AT, 17);
        later(42, "maxor 2", () -> say("Maxor", "I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU."));
        later(84, "maxor 3", () -> say("Maxor", "DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE."));
        later(MAXOR_CRYSTALS, "maxor crystals", BossFight::spawnTopCrystals);
        later(300 + RNG.nextInt(100), "maxor taunt", () -> {
            if (phase == Phase.P1 && stunEndsAt < 0 && maxorKilledAt < 0) {
                say("Maxor", MAXOR_TAUNTS[RNG.nextInt(MAXOR_TAUNTS.length)]);
            }
        });
        showBar("§c§lMaxor", 1f);
    }

    private static void spawnTopCrystals() {
        for (int i = 0; i < 2; i++) {
            if (tops[i] == null || tops[i].isRemoved()) {
                tops[i] = crystal(CRYSTAL_TOPS[i]);
            }
        }
    }

    private static EndCrystal crystal(Vec3 at) {
        EndCrystal c = new EndCrystal(level, at.x, at.y, at.z) {
            @Override
            public boolean shouldBeSaved() {
                return false;
            }
        };
        c.setShowBottom(false);
        McEntities.setInvulnerable(c, true);
        level.addFreshEntity(c);
        return c;
    }

    private static void tickP1() {
        if (maxorKilledAt >= 0) {
            int since = t - maxorKilledAt;
            if (since == 59 && maxor != null) {
                maxor.die();
            }
            boolean below = server.getPlayerList().getPlayers().stream().anyMatch(p -> p.getY() < 196.0);
            if (since >= STORM_AFTER && below) {
                if (maxor != null) {
                    maxor.remove();
                    maxor = null;
                }
                startP2();
            }
            return;
        }
        // Walking into a crystal on its tower picks it up.
        for (int i = 0; i < 2; i++) {
            EndCrystal c = tops[i];
            if (c == null || c.isRemoved()) {
                continue;
            }
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.getBoundingBox().inflate(0.5).intersects(c.getBoundingBox())) {
                    pickUpCrystal(p, i);
                    break;
                }
            }
        }
        if (stunEndsAt >= 0 && t >= stunEndsAt) {
            stunEndsAt = -1;
            maxor.armour(true);
            BossChat.chat(server, "§c⚠ Maxor is enraged! ⚠");
        }
    }

    static void pickUpCrystal(ServerPlayer p, int i) {
        if (phase != Phase.P1 || tops[i] == null) {
            return;
        }
        discard(tops[i]);
        tops[i] = null;
        carrying++;
        BossChat.chat(server, "§b" + p.getGameProfile().name() + "§r§a picked up an §r§bEnergy Crystal§r§a!");
    }

    /** A hit on a crystal on its tower picks it up too (and never blows it up). */
    static void attackCrystal(ServerPlayer p, Entity e) {
        for (int i = 0; i < 2; i++) {
            if (tops[i] == e) {
                pickUpCrystal(p, i);
            }
        }
    }

    /** A use near a pylon: places a carried crystal there. Returns true when the use was the pylon's. */
    static boolean usePylon(ServerPlayer p) {
        if (phase != Phase.P1 || maxorKilledAt >= 0) {
            return false;
        }
        for (int i = 0; i < 2; i++) {
            if (p.position().distanceTo(PYLONS[i]) > 5.0 || placed[i] != null) {
                continue;
            }
            if (carrying <= 0) {
                BossChat.chat(server, "§cYou do not current have an §r§bEnergy Crystal§r§c! Find one and bring it here!");
                return true;
            }
            carrying--;
            placed[i] = crystal(PYLONS[i]);
            int n = (placed[0] != null ? 1 : 0) + (placed[1] != null ? 1 : 0);
            BossChat.chat(server, n >= 2 ? "§a2/2 Energy Crystals are now active!" : "§c" + n + "§r§a/2 Energy Crystals are now active!");
            if (n >= 2 && !laserCharging) {
                laserCharging = true;
                later(20, "laser charge", () -> BossChat.chat(server, "§aThe Energy Laser is charging up!"));
                later(60, "laser hit", BossFight::stunMaxor);
            }
            return true;
        }
        return false;
    }

    private static void stunMaxor() {
        if (phase != Phase.P1 || maxor == null) {
            return;
        }
        laserCharging = false;
        say("Maxor", RNG.nextBoolean() ? "THAT BEAM! IT HURTS! IT HURTS!!" : "YOU TRICKED ME!");
        maxor.armour(false);
        maxorHits = 0;
        stunEndsAt = t + MAXOR_STUN_TICKS;
        for (int i = 0; i < 2; i++) {
            discard(placed[i]);
            placed[i] = null;
        }
        // The next set is back on its towers 34 server ticks after the line, during the stun, not after it: NoammAddons'
        // MaxorsCrystals.kt respawn timer (spawnRegex -> tickTimer = 34). It used to come 20 ticks after Maxor was
        // enraged again, which on Hypixel is well after the second set has been picked up (2026-11-02).
        later(MAXOR_CRYSTAL_RESPAWN, "maxor crystals again", BossFight::resetCrystals);
    }

    private static void resetCrystals() {
        if (phase == Phase.P1 && maxorKilledAt < 0) {
            spawnTopCrystals();
        }
    }

    private static void hitMaxor() {
        if (stunEndsAt < 0 || maxorKilledAt >= 0) {
            return;
        }
        maxorHits++;
        float left = 1f - (maxorStuns + maxorHits / (float) MAXOR_HITS_PER_STUN) / 2f;
        showBar("§c§lMaxor", left);
        if (maxorHits < MAXOR_HITS_PER_STUN) {
            return;
        }
        maxorStuns++;
        stunEndsAt = -1;
        if (maxorStuns >= 2) {
            maxorKilledAt = t;
            later(82, "too young", () -> say("Maxor", "I'M TOO YOUNG TO DIE AGAIN!"));
            openHole(P1_HOLE, BossArena.P1_FLOOR);
            carrying = 0;
            LOGGER.info("[BossSim] Maxor down at P1 t {}", t);
        } else {
            maxor.armour(true);
        }
    }

    // ================================================================================================== P2 Storm

    private static void startP2() {
        phase(Phase.P2);
        BossChat.boss(server, "Storm", "Pathetic Maxor, just like expected.");
        storm = new BossMob("Storm", new Vec3(73.0, 176.0, 53.0), 0);
        later(62, "storm 2", () -> say("Storm", "Don't boast about beating this simple-minded Wither."));
        later(124, "storm 3", () -> say("Storm", "My abilities are unparalleled, in many ways I am the last bastion."));
        later(186, "storm 4", () -> say("Storm", "The memory of your death will be your fondest, focus up!"));
        later(424, "storm 5", () -> say("Storm",
                "The power of lightning is quite phenomenal. A single strike can vaporize a person whole."));
        later(486, "storm 6", () -> say("Storm", "I'd be happy to show you what that's like!"));
        later(548, "storm lightning", () -> {
            if (stormDeadAt < 0) {
                say("Storm", RNG.nextBoolean() ? "ENERGY HEED MY CALL!" : "THUNDER LET ME BE YOUR CATALYST!");
            }
        });
        nextStormTaunt = 847;
        stormHoldUntil = STORM_MOVE_FROM;
        showBar("§c§lStorm", 1f);
    }

    private static void tickP2() {
        if (stormDeadAt >= 0) {
            return;
        }
        if (t == nextStormTaunt) {
            say("Storm", STORM_TAUNTS[RNG.nextInt(STORM_TAUNTS.length)]);
            nextStormTaunt = t + 60 + RNG.nextInt(4);
        }
        // Storm's round: a hover over each pillar's square in turn, Purple, Yellow, Green, Red.
        if (t >= stormHoldUntil && storm != null) {
            Pillar target = PILLARS.get(stormLeg % 4);
            Vec3 aim = new Vec3(target.minX() + 3.5, 170.5, target.minZ() + 3.5);
            if (storm.step(aim, 0.4)) {
                stormHoldUntil = t + 120;
                stormLeg++;
            }
        }
        // Pads are read on the 20-tick checks; a read drops that pillar, one block every 4 ticks.
        if (t % 20 == 19) {
            for (int i = 0; i < 4; i++) {
                Pillar p = PILLARS.get(i);
                if (p.pad() == null || pillarSpent[i] || pillarDropping[i] || pillarResetAt[i] >= 0) {
                    continue;
                }
                for (ServerPlayer pl : server.getPlayerList().getPlayers()) {
                    if (p.onPad(pl.position())) {
                        pillarDropping[i] = true;
                        LOGGER.info("[BossSim] Storm pillar {} pressed at P2 t {}", p.name(), t);
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            Pillar p = PILLARS.get(i);
            if (pillarDropping[i] && t % 4 == 0) {
                if (pillarBottom[i] > BossArena.P2_FLOOR + 1) {
                    pillarBottom[i]--;
                    BossBlocks.fill(level, p.minX(), pillarBottom[i], p.minZ(), p.minX() + 6, pillarBottom[i], p.minZ() + 6,
                            BossBlocks.of("minecraft:diorite"));
                } else {
                    pillarDropping[i] = false;
                    pillarResetAt[i] = t + 20;
                    if (storm != null && p.under(storm.pos)) {
                        crush(i);
                    }
                }
            }
            if (pillarResetAt[i] >= 0 && t >= pillarResetAt[i]) {
                pillarResetAt[i] = -1;
                BossBlocks.fill(level, p.minX(), BossArena.P2_FLOOR + 1, p.minZ(), p.minX() + 6, p.restBottom() - 1,
                        p.minZ() + 6, BossBlocks.AIR);
                pillarBottom[i] = p.restBottom();
            }
        }
    }

    private static void crush(int i) {
        crushes++;
        pillarSpent[i] = true;
        LOGGER.info("[BossSim] Storm crushed by {} (crush {}) at P2 t {}", PILLARS.get(i).name(), crushes, t);
        showBar("§c§lStorm", 1f - crushes / 2f);
        if (crushes < 2) {
            say("Storm", RNG.nextBoolean() ? "Ouch, that hurt!" : "Oof");
            stormHoldUntil = t + 60;
            return;
        }
        stormDeadAt = t;
        say("Storm", "I should have known that I stood no chance.");
        openHole(P2_HOLE, BossArena.P2_FLOOR);
        later(STORM_SON, "storm son", () -> say("Storm", "At least my son died by your hands."));
        later(GOLDOR_LEAD, "goldor", () -> {
            if (storm != null) {
                storm.die();
                BossMob dead = storm;
                later(20, "storm gone", dead::remove);
                storm = null;
            }
            startP3(1);
        });
    }

    // ================================================================================================== P3 Goldor

    private static void startP3(int fromSection) {
        phase(Phase.P3);
        section = Math.min(fromSection, 5);
        BossChat.boss(server, "Goldor", "Who dares trespass into my domain?");
        lastGoldorLine = 0;
        goldor = new BossMob("Goldor", GOLDOR_AT, 0);
        goldorLines.add("Little ants, plotting and scheming, thinking they are invincible...");
        goldorLines.add("I won't let you break the factory core, I gave my life to my Master.");
        goldorLines.add("No one matches me in close quarters.");
        BossStations.spawn(level, section);
        BossDevices.start(level, section);
        for (int done = 1; done < section && done <= 3; done++) {
            BossBlocks.fill(level, BossStations.GATES[done - 1][0], BossStations.GATES[done - 1][1], BossStations.GATES[done - 1][2],
                    BossStations.GATES[done - 1][3], BossStations.GATES[done - 1][4], BossStations.GATES[done - 1][5], BossBlocks.AIR);
        }
        if (section >= 5) {
            openCore(false);
        }
        showBar("§c§lGoldor", 1f);
    }

    private static void tickP3() {
        if (!goldorLines.isEmpty() && t - lastGoldorLine >= LINE_GAP) {
            say("Goldor", goldorLines.poll());
            lastGoldorLine = t;
        }
        if (gateAt >= 0 && t >= gateAt) {
            gateAt = -1;
            destroyGate(section);
        }
        BossDevices.tick(level);
        if (coreOpen && !goldorArrived) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (CORE_BOX.contains(p.position())) {
                    goldorArrived = true;
                    goldorLines.add("You have done it, you destroyed the factory...");
                    goldorLines.add("But you have nowhere to hide anymore!");
                    goldorLines.add("YOU ARE FACE TO FACE WITH GOLDOR!");
                    goldor.armour(false);
                    break;
                }
            }
        }
        if (goldorArrived && goldor != null && goldorDeadAt < 0) {
            goldor.step(new Vec3(CORE_CENTER.x, CORE_CENTER.y + 1, CORE_CENTER.z), 0.8);
        }
    }

    /** A station counted: ends its section once every one is done (and only the section in progress can end). */
    static void onStationDone(ServerLevel lvl, BossStations.Station s) {
        if (phase != Phase.P3 || s.section() != section) {
            return;
        }
        if (BossStations.count(section) < BossStations.total(section)) {
            return;
        }
        LOGGER.info("[BossSim] S{} complete at P3 t {}", section, t);
        if (section == 4) {
            openCore(true);
            return;
        }
        BossChat.chat(server, "§aThe gate will open in 5 seconds!");
        BossChat.title(server, "", "§aThe gate will open in 5 seconds!", 0, 40, 0);
        gateAt = t + GATE_DELAY;
    }

    private static void destroyGate(int sec) {
        int[] g = BossStations.GATES[sec - 1];
        BossBlocks.fill(level, g[0], g[1], g[2], g[3], g[4], g[5], BossBlocks.AIR);
        BossChat.chat(server, "§aThe gate has been destroyed!");
        BossChat.title(server, "", "§aThe gate has been destroyed!", 0, 40, 0);
        goldorLines.add(GOLDOR_SECTION_LINES[RNG.nextInt(GOLDOR_SECTION_LINES.length)]);
        section = sec + 1;
        LOGGER.info("[BossSim] gate {} down, S{} in progress at P3 t {}", sec, section, t);
        // A device done before its section began counts the moment that section opens, as on Hypixel.
        onStationDone(level, BossStations.device(section));
    }

    private static void openCore(boolean announce) {
        coreOpen = true;
        section = 5;
        BossBlocks.fill(level, 52, BossArena.P3_FLOOR + 1, 58, 57, BossArena.P3_FLOOR + 6, 58, BossBlocks.AIR);
        if (announce) {
            BossChat.chat(server, "§aThe Core entrance is opening!");
            BossChat.title(server, "", "§aThe Core entrance is opening!", 0, 40, 0);
        }
    }

    private static void hitGoldor() {
        if (!goldorArrived || goldorDeadAt >= 0) {
            return;
        }
        goldorHits++;
        showBar("§c§lGoldor", 1f - goldorHits / (float) GOLDOR_HITS);
        if (goldorHits < GOLDOR_HITS) {
            return;
        }
        goldorDeadAt = t;
        goldorLines.clear();
        say("Goldor", "....");
        goldor.die();
        openHole(CORE_HOLE, BossArena.P3_FLOOR);
        LOGGER.info("[BossSim] Goldor down at P3 t {}", t);
        later(NECRON_AFTER - 10, "forgive", () -> say("Goldor", "Necron, forgive me."));
        later(NECRON_AFTER, "necron", () -> {
            if (goldor != null) {
                goldor.remove();
                goldor = null;
            }
            BossDevices.stop();
            startP4();
        });
    }

    // ================================================================================================== P4 Necron

    private static void startP4() {
        phase(Phase.P4);
        BossChat.boss(server, "Necron", "You went further than any human before, congratulations.");
        necron = new BossMob("Necron", NECRON_AT, 0);
        later(NECRON_INTRO, "necron 2", () -> say("Necron", "I'm afraid, your journey ends now."));
        later(2 * NECRON_INTRO, "necron 3", () -> say("Necron", "Goodbye."));
        later(3 * NECRON_INTRO, "necron 4", () -> {
            say("Necron", "That's a very impressive trick. I guess I'll have to handle this myself.");
            necronVulnerable = true;
            necron.armour(false);
        });
        showBar("§c§lNecron", 1f);
    }

    private static void tickP4() {
        if (necronDeadAt >= 0 && t - necronDeadAt == 40) {
            if (necron != null) {
                necron.remove();
                necron = null;
            }
            if (master) {
                openHole(P4_HOLE, BossArena.P4_FLOOR);
                startP5();
            } else {
                finish();
            }
        }
    }

    private static void hitNecron() {
        if (!necronVulnerable || necronDeadAt >= 0) {
            return;
        }
        necronHits++;
        showBar("§c§lNecron", 1f - necronHits / (float) NECRON_HITS);
        if (necronHits == NECRON_HITS / 2) {
            say("Necron", "ARGH!");
            later(62, "space", () -> say("Necron", "Let's make some space!"));
        }
        if (necronHits >= NECRON_HITS) {
            necronDeadAt = t;
            say("Necron", "All this, for nothing...");
            necron.die();
            LOGGER.info("[BossSim] Necron down at P4 t {}", t);
        }
    }

    // ================================================================================================== P5

    private static void startP5() {
        phase(Phase.P5);
        if (bar != null) {
            bar.removeAllPlayers();
            bar = null;
        }
        // The relics are not modeled: the King's first line comes as the party lands instead of once they are placed.
        later(60, "king", () -> {
            BossChat.boss(server, "Wither King", "You... again?");
            dragonSpawnAt[0] = t;
            dragonSpawnAt[1] = t + 40;
        });
    }

    private static void tickP5() {
        for (int i = 0; i < 5; i++) {
            if (dragonSpawnAt[i] >= 0 && t == dragonSpawnAt[i]) {
                int[] st = DRAGON_STATUES[i];
                // Hypixel's warning: a FLAME burst over the statue (count 20, y 19, spread 2/3/2, speed 0), 100 ticks out.
                level.sendParticles(ParticleTypes.FLAME, st[0], 19.0, st[2], 20, 2.0, 3.0, 2.0, 0.0);
            }
            if (dragonSpawnAt[i] >= 0 && t == dragonSpawnAt[i] + DRAGON_SPAWN && dragons[i] == null) {
                int[] st = DRAGON_STATUES[i];
                SimDragon d = new SimDragon(level);
                d.snapTo(st[0] + 0.5, 20.0, st[2] + 0.5, 0f, 0f);
                level.addFreshEntity(d);
                dragons[i] = d;
                LOGGER.info("[BossSim] {} dragon spawned at P5 t {}", DRAGON_NAMES[i], t);
            }
        }
    }

    private static void hitDragon(SimDragon d) {
        for (int i = 0; i < 5; i++) {
            if (dragons[i] != d || dragonDead[i]) {
                continue;
            }
            if (++dragonHits[i] < DRAGON_HITS) {
                return;
            }
            dragonDead[i] = true;
            d.setHealth(0f);
            later(20, "dragon gone", d::discard);
            BossChat.boss(server, "Wither King", KING_COUNTS[kingLine++ % KING_COUNTS.length]);
            LOGGER.info("[BossSim] {} dragon down at P5 t {}", DRAGON_NAMES[i], t);
            int next = -1;
            for (int j = 0; j < 5; j++) {
                if (dragonSpawnAt[j] < 0) {
                    next = j;
                    break;
                }
            }
            if (next >= 0) {
                dragonSpawnAt[next] = t + 20;
            }
            boolean all = true;
            for (boolean dd : dragonDead) {
                all &= dd;
            }
            if (all) {
                later(40, "p5 done", BossFight::finish);
            }
            return;
        }
    }

    private static void finish() {
        LOGGER.info("[BossSim] boss cleared ({})", master ? "Master Mode" : "Floor 7");
        com.killer560.hub.util.ModChat.log("Sim", Component.literal("Boss cleared."));
        phase = Phase.DONE;
        if (bar != null) {
            bar.removeAllPlayers();
            bar = null;
        }
        com.killer560.hub.secrets.DungeonState.simBossEnds();
    }

    // ---- hits ---------------------------------------------------------------------------------------------------

    private static int lastHitClock = -1;
    private static Object lastHitTarget;

    /** A boss wither or dragon took a hit (melee, arrow or ability). One counts per target per server tick. */
    static void onHurt(Object target) {
        if (clock == lastHitClock && target == lastHitTarget) {
            return;
        }
        lastHitClock = clock;
        lastHitTarget = target;
        if (maxor != null && target == maxor.wither) {
            hitMaxor();
        } else if (goldor != null && target == goldor.wither) {
            hitGoldor();
        } else if (necron != null && target == necron.wither) {
            hitNecron();
        } else if (target instanceof SimDragon d) {
            hitDragon(d);
        }
    }

    static void say(String name, String line) {
        BossChat.boss(server, name, line);
    }

    /** A {@code [BOSS]} line's stand over its boss (see BossChat#boss). */
    static void speak(String name, String line) {
        for (BossMob m : new BossMob[]{maxor, storm, goldor, necron}) {
            if (m != null && m.name.equals(name)) {
                m.speak(line);
            }
        }
    }

    /** Debug and testkit: "P3 S2 t 410". */
    public static String describe() {
        return phase + (phase == Phase.P3 ? " S" + section : "") + " t " + t;
    }

    // ---- the boss as Hypixel shows one ------------------------------------------------------------------------------

    /**
     * A boss: a plain wither with no AI (blue armor on while its health is at or under half), its name on a stand 3.6875
     * above it, and each line's stand 4.09 above it for 41 ticks (Goldor's 61), as ADM measured Hypixel's.
     */
    static final class BossMob {
        final String name;
        final SimWither wither;
        Vec3 pos;
        private ArmorStand tag;
        private ArmorStand speech;
        private int speechUntil = -1;

        BossMob(String name, Vec3 at, int tagDelay) {
            this.name = name;
            this.pos = at;
            wither = new SimWither(level);
            wither.setNoAi(true);
            wither.setSilent(true);
            wither.setNoGravity(true);
            wither.snapTo(at.x, at.y, at.z, 0f, 0f);
            wither.setHealth(1f);
            level.addFreshEntity(wither);
            if (tagDelay <= 0) {
                showTag();
            } else {
                later(tagDelay, name + " tag", this::showTag);
            }
        }

        private void showTag() {
            if (wither.isRemoved()) {
                return;
            }
            tag = marker("§e﴾ §c§l" + name + "§r §e﴿", 3.6875);
        }

        private ArmorStand marker(String text, double dy) {
            ArmorStand s = new TransientStand(level, pos.x, pos.y + dy, pos.z);
            s.setInvisible(true);
            s.setNoGravity(true);
            s.setSilent(true);
            McEntities.setInvulnerable(s, true);
            s.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) 16);
            s.setCustomName(BossChat.legacy(text));
            s.setCustomNameVisible(true);
            level.addFreshEntity(s);
            return s;
        }

        void speak(String line) {
            if (speech != null) {
                speech.discard();
            }
            speech = marker("§4§l" + line, 4.09375);
            speechUntil = t + ("Goldor".equals(name) ? 61 : 41);
        }

        void tick() {
            if (speech != null && t >= speechUntil) {
                speech.discard();
                speech = null;
            }
        }

        void armour(boolean on) {
            if (wither.getHealth() > 0f) {
                wither.setHealth(on ? 1f : wither.getMaxHealth());
            }
        }

        void moveTo(Vec3 p) {
            pos = p;
            wither.snapTo(p.x, p.y, p.z, wither.getYRot(), 0f);
            if (tag != null) {
                tag.snapTo(p.x, p.y + 3.6875, p.z, 0f, 0f);
            }
            if (speech != null) {
                speech.snapTo(p.x, p.y + 4.09375, p.z, 0f, 0f);
            }
        }

        /** One step toward {@code target} of at most {@code speed}; true when there. */
        boolean step(Vec3 target, double speed) {
            Vec3 d = target.subtract(pos);
            double len = d.length();
            if (len <= speed) {
                moveTo(target);
                return true;
            }
            moveTo(pos.add(d.scale(speed / len)));
            return false;
        }

        void die() {
            wither.setHealth(0f);
            if (tag != null) {
                tag.discard();
            }
            if (speech != null) {
                speech.discard();
            }
        }

        void remove() {
            wither.discard();
            if (tag != null) {
                tag.discard();
            }
            if (speech != null) {
                speech.discard();
            }
        }
    }

    /**
     * A boss's wither: no AI (it never charges, shoots or breaks blocks), never despawns, no vanilla boss bar (the fight
     * shows Hypixel's), and every hit goes to the fight instead of its health.
     */
    static final class SimWither extends WitherBoss {
        SimWither(Level level) {
            super(McEntities.WITHER, level);
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        public void startSeenByPlayer(ServerPlayer player) {
        }

        @Override
        public boolean shouldBeSaved() {
            return false;
        }

        @Override
        public boolean hurtServer(ServerLevel lvl, DamageSource source, float amount) {
            if (source.getEntity() instanceof ServerPlayer) {
                onHurt(this);
            }
            return false;
        }
    }

    /** A P5 dragon: frozen where it spawned (no AI), hits go to the fight. */
    static final class SimDragon extends EnderDragon {
        SimDragon(Level level) {
            super(McEntities.ENDER_DRAGON, level);
            setNoAi(true);
        }

        @Override
        public boolean shouldBeSaved() {
            return false;
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        public boolean hurtServer(ServerLevel lvl, DamageSource source, float amount) {
            if (source.getEntity() instanceof ServerPlayer) {
                onHurt(this);
            }
            return false;
        }

        @Override
        public boolean hurt(ServerLevel lvl, EnderDragonPart part, DamageSource source, float amount) {
            if (source.getEntity() instanceof ServerPlayer) {
                onHurt(this);
            }
            return false;
        }
    }

    /**
     * A stand the fight owns: never written with its chunk, so a world closed mid-fight cannot bring back a stale copy.
     * (The arena's chunks are kept loaded for the whole fight - {@link #forceChunks} - so nothing unloads under it.)
     */
    static final class TransientStand extends ArmorStand {
        TransientStand(Level level, double x, double y, double z) {
            super(level, x, y, z);
        }

        @Override
        public boolean shouldBeSaved() {
            return false;
        }
    }

    /**
     * Keeps every chunk of the boss room loaded while a fight runs. Without it a section he is far from unloads, and its
     * stands, frames and bosses come back as NEW objects the fight does not know (2026-11-01: S2's far terminal "was no
     * terminal's" when clicked, 1101-boss-p3).
     */
    private static void forceChunks(boolean on) {
        if (level == null) {
            return;
        }
        for (int cx = BossArena.X0 >> 4; cx <= (BossArena.X0 + BossArena.W - 1) >> 4; cx++) {
            for (int cz = BossArena.Z0 >> 4; cz <= (BossArena.Z0 + BossArena.D - 1) >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    /** P1's crystals for the testkit: true while one waits on tower {@code i}. */
    public static boolean crystalOnTower(int i) {
        return phase == Phase.P1 && tops[i] != null && !tops[i].isRemoved();
    }

    public static int crystalsCarried() {
        return carrying;
    }

    /** Debug and testkit: drives the next stun's hits without a weapon (the same path a hit takes). */
    public static void testHit(String boss) {
        switch (boss) {
            case "Maxor" -> {
                if (maxor != null) {
                    onHurt(maxor.wither);
                }
            }
            case "Goldor" -> {
                if (goldor != null) {
                    onHurt(goldor.wither);
                }
            }
            case "Necron" -> {
                if (necron != null) {
                    onHurt(necron.wither);
                }
            }
            default -> {
                for (SimDragon d : dragons) {
                    if (d != null && !d.isRemoved() && d.getHealth() > 0f) {
                        onHurt(d);
                        return;
                    }
                }
            }
        }
        lastHitClock = -1;
    }

    /** Debug and testkit: the boss wither's position, or null. */
    public static double[] bossPos(String boss) {
        BossMob m = switch (boss) {
            case "Maxor" -> maxor;
            case "Storm" -> storm;
            case "Goldor" -> goldor;
            case "Necron" -> necron;
            default -> null;
        };
        return m == null ? null : new double[]{m.pos.x, m.pos.y, m.pos.z};
    }

    /** Debug and testkit: Storm's crushes so far. */
    public static int stormCrushes() {
        return crushes;
    }

    /** Debug and testkit: places Storm in a pillar's square now (a test cannot wait out his round). */
    public static void testParkStorm(String pillar) {
        for (Pillar p : PILLARS) {
            if (p.name().equalsIgnoreCase(pillar) && storm != null) {
                storm.moveTo(new Vec3(p.minX() + 3.5, 170.5, p.minZ() + 3.5));
                stormHoldUntil = t + 400;
            }
        }
    }

    static BlockPos pylonBlock(int i) {
        return BlockPos.containing(PYLONS[i].x, PYLONS[i].y - 1, PYLONS[i].z);
    }
}
