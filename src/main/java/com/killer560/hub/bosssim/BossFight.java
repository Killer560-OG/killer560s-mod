package com.killer560.hub.bosssim;

import com.killer560.hub.compat.McEntities;
import com.killer560.hub.util.ModLog;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
    /** A pillar's top layer (ADM: a crushed pillar hangs at 183-189, the floor cycle draws one up to 189). */
    static final int PILLAR_TOP = 189;

    /**
     * Holes opened by each boss's death, as {x1, z1, x2, z2} at that phase's floor. P2's is the drop under the Red pillar
     * (Hypixel's red pad, 100.5 169 40.5), P3's the core's floor (ADM: x 39-69, z 99-129 under the core).
     */
    static final int[] P1_HOLE = {66, 56, 80, 70};
    static final int[] P2_HOLE = {98, 38, 102, 42};
    static final int[] CORE_HOLE = {41, 99, 68, 117};
    static final int[] P4_HOLE = {50, 72, 58, 80};

    /**
     * Necron's side platforms (ADM P4Necron.kt PLATFORMS and p4-necron.json's extents): centre x, z, then x1, x2, z1, z2.
     * The south one, where everyone lands, is never broken.
     */
    static final int[][] NECRON_PLATFORMS = {{54, 42, 39, 69, 32, 52}, {28, 50, 14, 42, 36, 64}, {80, 50, 66, 94, 36, 64},
            {28, 102, 14, 42, 88, 116}, {80, 102, 66, 94, 88, 116}};
    static final String[] NECRON_PLATFORM_NAMES = {"N", "NW", "NE", "SW", "SE"};
    /** The south platform under the core's floor (x1, x2, z1, z2). */
    static final int[] NECRON_SOUTH = {43, 65, 96, 120};

    /** The five dragons' statues (WitherDragon.java): x, statue top y, z - and the color the mod names them by. */
    static final int[][] DRAGON_STATUES = {{27, 8, 59}, {85, 8, 56}, {27, 8, 94}, {84, 8, 94}, {56, 8, 125}};
    private static final String[] DRAGON_NAMES = {"Red", "Orange", "Green", "Blue", "Purple"};

    // ---- timings (server ticks), Hypixel's as ADM measured them ---------------------------------------------------

    /** Hits on a stunned Maxor that end his stun early (the damage a party bursts it with). */
    private static final int MAXOR_HITS_PER_STUN = 5;
    private static final int STORM_AFTER = 62;
    private static final int STORM_SON = 42;
    private static final int GOLDOR_LEAD = 62;
    private static final int LINE_GAP = 62;
    private static final int GATE_DELAY = 100;
    private static final int GOLDOR_HITS = 10;
    private static final int NECRON_AFTER = 62;
    private static final int NECRON_INTRO = 42;
    private static final int DRAGON_SPAWN = 100;
    private static final int DRAGON_HITS = 8;

    private static final Vec3 P1_SPAWN = new Vec3(73.5, BossArena.P1_FLOOR + 1, 14.5);
    private static final Vec3 MAXOR_AT = new Vec3(73.0, 226.0, 53.0);
    private static final Vec3[] CRYSTAL_TOPS = {new Vec3(64.5, 238.375, 50.5), new Vec3(82.5, 238.375, 50.5)};
    private static final Vec3[] PYLONS = {new Vec3(52.5, 224.375, 41.5), new Vec3(94.5, 224.375, 41.5)};
    /** A P2 start from the menu: Hypixel's Purple pad spot (ADM Spots.kt). */
    private static final Vec3 P2_SPAWN = new Vec3(114.5, BossArena.P2_FLOOR + 2, 94.5);
    private static final Vec3 S1_SPAWN = new Vec3(100.5, BossArena.P3_FLOOR + 1, 40.5);
    private static final Vec3 NECRON_AT = new Vec3(54.0, 66.0, 76.0);
    /** A P4 start from the menu: where the core's floor drops everyone (ADM Spots.kt P4, 54.5 64 108.5). */
    private static final Vec3 P4_SPAWN = new Vec3(54.5, BossArena.P4_FLOOR + 1, 108.5);
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
    private static final String[] MAXOR_SKULL_TAUNTS = {
            "How about you taste some rapid fire Wither Skulls!", "Time for me to blast you away for good!", "Eat Wither Skulls, scum!"};
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
    private static final ArmorStand[][] topLabels = new ArmorStand[2][];
    private static final EndCrystal[] placed = new EndCrystal[2];
    private static final int[] placedAt = {-1, -1};
    private static final ArmorStand[][] pylonStands = new ArmorStand[2][];
    private static final List<Integer> placeTimes = new ArrayList<>();
    private static ItemStack savedSlot8;
    private static int carrying;
    private static boolean pylonsOpen, beaconIn, maxorStunned;
    private static int laserHits, chargeAt = -1, maxorEnrageAt = -1, maxorMoveAt, maxorTauntAt = -1, maxorSkullAt = -1;
    private static int maxorStunLines, maxorDamage, maxorKilledAt = -1, maxorBelowAt = -1;
    private static float maxorBar = 1f, maxorBarFloor, maxorBarRate;
    // P2
    private static int stormLeg;
    private static boolean stormChasing, stormLaterChase, stormToYellow, stormPinned;
    private static int stormEnrageAt = -1, stormTakeoffAt = -1, stormPinnedAt = -1, stormCrush2At = -1, stormLastCrush = -1;
    private static int stormTestHoldUntil = -1, stormGiga2 = 19;
    private static double stormGigaDamage = 9350;
    private static float stormBar = 1f;
    private static int crushes;
    private static int stormDeadAt = -1;
    private static int nextStormTaunt = -1;
    private static final int[] pillarBottom = new int[4];
    private static final int[] pillarResetAt = {-1, -1, -1, -1};
    private static final boolean[] pillarSpent = new boolean[4];
    private static final int[] pillarSteps = new int[4];
    private static final int[] pillarNextStep = {-1, -1, -1, -1};
    private static final int[] pillarLastStep = new int[4];
    private static final int[] pillarFloorAt = {-1, -1, -1, -1};
    private static final boolean[] pillarRaising = new boolean[4];
    private static final int[] pillarLowerTo = {-1, -1, -1, -1};
    // P3
    private static int section;
    private static final Deque<String> goldorLines = new ArrayDeque<>();
    /** The {@link #clock} of Goldor's last line (his queue runs on into P4). */
    private static int lastGoldorLine = -1000;
    private static int gateAt = -1;
    private static boolean coreOpen;
    private static int coreOpenAt = -1;
    private static double goldorS;
    private static int goldorStartN, goldorSprintFrom = -1, goldorSprintSec, goldorFrenzyHits;
    private static double goldorSprintTo = -1;
    private static boolean goldorFirstLap, goldorFlying, goldorAtCore, goldorDtEarly;
    private static final boolean[] goldorReached = new boolean[6];
    private static int goldorHits;
    private static int goldorDeadAt = -1;
    // P4
    private static int necronDeadAt = -1;
    private static int necronPlatform, necronBarPhase;
    private static Vec3 necronSide = new Vec3(0, 0, 1);
    private static String necronTaunt = "";
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
            removeStands(topLabels[i]);
            removeStands(pylonStands[i]);
            topLabels[i] = pylonStands[i] = null;
        }
        if (savedSlot8 != null && server != null) {
            giveCrystal(server.getPlayerList().getPlayers().stream().findFirst().orElse(null), false);
        }
        savedSlot8 = null;
        for (int i = 0; i < 5; i++) {
            discard(dragons[i]);
            dragons[i] = null;
        }
        if (bar != null) {
            bar.removeAllPlayers();
            bar = null;
        }
        BossStations.reset();
        BossTerminals.resetTypes();
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
        placeTimes.clear();
        java.util.Arrays.fill(placedAt, -1);
        pylonsOpen = beaconIn = maxorStunned = false;
        laserHits = maxorStunLines = maxorDamage = 0;
        chargeAt = maxorEnrageAt = maxorTauntAt = maxorSkullAt = maxorKilledAt = maxorBelowAt = -1;
        maxorMoveAt = 0;
        maxorBar = 1f;
        crushes = 0;
        stormDeadAt = -1;
        nextStormTaunt = -1;
        stormLeg = 0;
        stormChasing = stormLaterChase = stormToYellow = stormPinned = false;
        stormEnrageAt = stormTakeoffAt = stormPinnedAt = stormCrush2At = stormLastCrush = stormTestHoldUntil = -1;
        stormBar = 1f;
        for (int i = 0; i < 4; i++) {
            pillarBottom[i] = PILLARS.get(i).restBottom();
            pillarResetAt[i] = -1;
            pillarSpent[i] = false;
            pillarSteps[i] = 0;
            pillarNextStep[i] = -1;
            pillarLastStep[i] = -1000;
            pillarFloorAt[i] = -1;
            pillarRaising[i] = false;
            pillarLowerTo[i] = -1;
        }
        goldorLines.clear();
        lastGoldorLine = -1000;
        gateAt = -1;
        coreOpen = goldorFlying = goldorAtCore = false;
        coreOpenAt = -1;
        goldorFrenzyHits = 0;
        goldorHits = 0;
        goldorDeadAt = -1;
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
                teleportAll(P2_SPAWN, 90f);
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
    //
    // Facts from ADM's measurements of Hypixel after the 2026-10-05/06 boss update (Phases.kt): Maxor and the crystals
    // appear at t 5, the crystals' stands at 28, the pylons open at 80 (the two blocks under the beam go), the beacon is in
    // at 120. Maxor holds still until 84, then flies at the closest player (+1 y) at min(0.9, 0.24 + 0.02 d) and stops
    // within 3. A laser hit needs both crystals in for 28 ticks (the "charging" line), the beacon, and Maxor at y >= 225
    // within 3.5 of the beam, read on the 10-tick checks. A hit freezes him facing south; his stun line comes 6 later (or
    // 62 after a taunt he was in); damage ends the stun (100-199 ticks without), the crystals are back at hit + 40, the
    // placed ones go at + 41. The second hit kills him 8-15 ticks later; his floor crumbles at kill + 59, and Storm's line
    // comes at kill + 62 once someone is below y 196 (2 ticks after the first one gets there, if later).

    private static final int P1_OPEN = 80, P1_BEACON = 120, P1_MOVE = 84, P1_LABELS = 28, P1_FUSE = 28;
    private static final Vec3 BEAM = new Vec3(73.5, 226.0, 73.5);

    private static void startP1(boolean place) {
        phase(Phase.P1);
        if (place) {
            teleportAll(P1_SPAWN, 0f);
        }
        BossChat.boss(server, "Maxor", "WELL! WELL! WELL! LOOK WHO'S HERE!");
        later(5, "maxor", () -> {
            maxor = new BossMob("Maxor", MAXOR_AT, 17);
            maxor.speak("WELL! WELL! WELL! LOOK WHO'S HERE!");
            spawnTopCrystals(false);
        });
        later(P1_LABELS, "crystal labels", () -> {
            for (int i = 0; i < 2; i++) {
                if (tops[i] != null && topLabels[i] == null) {
                    topLabels[i] = crystalLabels(CRYSTAL_TOPS[i]);
                }
            }
        });
        later(42, "maxor 2", () -> say("Maxor", "I'VE BEEN TOLD I COULD HAVE A BIT OF FUN WITH YOU."));
        later(84, "maxor 3", () -> say("Maxor", "DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE."));
        maxorTauntAt = -1;
        maxorSkullAt = RNG.nextInt(100) < 11 ? 436 + RNG.nextInt(12) : -1;
        maxorBar = 1f;
        showBar("§c§lMaxor", 1f);
    }

    private static void spawnTopCrystals(boolean labels) {
        for (int i = 0; i < 2; i++) {
            if (tops[i] == null || tops[i].isRemoved()) {
                tops[i] = crystal(CRYSTAL_TOPS[i]);
                removeStands(topLabels[i]);
                topLabels[i] = labels ? crystalLabels(CRYSTAL_TOPS[i]) : null;
            }
        }
    }

    private static ArmorStand[] crystalLabels(Vec3 at) {
        return new ArmorStand[]{label(at.x, at.y + 0.125, at.z, "§bEnergy Crystal"), label(at.x, at.y - 0.25, at.z, "§e§lCLICK HERE")};
    }

    private static ArmorStand label(double x, double y, double z, String name) {
        ArmorStand s = new TransientStand(level, x, y, z);
        s.snapTo(x, y, z, 0f, 0f);
        s.setInvisible(true);
        s.setNoGravity(true);
        s.setSilent(true);
        McEntities.setInvulnerable(s, true);
        s.setCustomName(BossChat.legacy(name));
        s.setCustomNameVisible(!name.isEmpty());
        level.addFreshEntity(s);
        return s;
    }

    private static void relabel(ArmorStand s, String name) {
        if (s != null) {
            s.setCustomName(name.isEmpty() ? null : BossChat.legacy(name));
            s.setCustomNameVisible(!name.isEmpty());
        }
    }

    private static void removeStands(ArmorStand[] stands) {
        if (stands != null) {
            for (ArmorStand s : stands) {
                discard(s);
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

    private static ServerPlayer closest(Vec3 from) {
        ServerPlayer best = null;
        double bd = Double.MAX_VALUE;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            double d = p.position().distanceToSqr(from);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static boolean maxorInAbility() {
        return maxorTauntAt >= 0 && t >= maxorTauntAt - 1 && t < maxorTauntAt + 62;
    }

    private static boolean maxorCharged() {
        return chargeAt >= 0 && t >= chargeAt;
    }

    private static void tickP1() {
        if (t == P1_OPEN) {
            BossBlocks.set(level, new BlockPos(73, 221, 73), BossBlocks.AIR);
            BossBlocks.set(level, new BlockPos(73, 222, 73), BossBlocks.AIR);
            pylonsOpen = true;
            for (int i = 0; i < 2; i++) {
                pylonStands[i] = new ArmorStand[]{label(PYLONS[i].x, PYLONS[i].y - 0.875, PYLONS[i].z, "§cEnergy Crystal Missing"),
                        label(PYLONS[i].x, PYLONS[i].y - 1.25, PYLONS[i].z, "§e§lCLICK HERE")};
            }
        }
        if (t == P1_BEACON) {
            beaconIn = true;
            BossBlocks.set(level, new BlockPos(73, 221, 73), BossBlocks.of("minecraft:beacon"));
        }
        if (t == P1_BEACON - 1 && maxorTauntAt < 0) {
            maxorTauntAt = P1_BEACON + 161;
        }
        if ((t + 3) % 20 == 0 && bar != null) {
            bar.setProgress(Math.max(0f, Math.min(1f, maxorBar)));
        }
        if (maxor == null) {
            return;
        }
        if (maxorKilledAt >= 0) {
            maxorKilled();
            return;
        }
        if (chargeAt >= 0 && t == chargeAt) {
            BossChat.chat(server, "§aThe Energy Laser is charging up!");
        }
        if (t % 10 == 0) {
            beamColumn();
        }
        if (t == maxorTauntAt) {
            say("Maxor", MAXOR_TAUNTS[RNG.nextInt(MAXOR_TAUNTS.length)]);
        }
        if (t == maxorSkullAt && !maxorStunned && !maxorInAbility()) {
            say("Maxor", MAXOR_SKULL_TAUNTS[RNG.nextInt(MAXOR_SKULL_TAUNTS.length)]);
        }
        if (maxorStunned && t == maxorEnrageAt) {
            BossChat.chat(server, "§c⚠ Maxor is enraged! ⚠");
            maxorStunned = false;
            maxorMoveAt = t + 1 + RNG.nextInt(5);
            maxorTauntAt = t + 161 + RNG.nextInt(4);
            maxorBar = 0.25f;
            maxor.armour(true);
        }
        if (maxorStunned) {
            // Armor off, with now and then a one-tick flicker back on; the bar runs down while he is stunned.
            maxor.armour(RNG.nextInt(25) == 0);
            maxorBar = Math.max(maxorBarFloor, maxorBar - maxorBarRate);
        }
        ServerPlayer near = closest(maxor.pos);
        Vec3 aim = near == null ? maxor.pos.add(0, 0, 1) : near.position().add(0, 1, 0);
        if (maxorStunned) {
            maxor.moveTo(maxor.pos, maxor.pos.add(0, 0, 1));
        } else if (t >= P1_MOVE && t >= maxorMoveAt) {
            double d = maxor.pos.distanceTo(aim);
            if (d > 3.0) {
                maxor.step(aim, Math.min(0.9, 0.24 + 0.02 * d), aim);
            } else {
                maxor.moveTo(maxor.pos, aim);
            }
        } else {
            maxor.moveTo(maxor.pos, aim);
        }
        if (t % 10 == 0 && maxorCharged() && beaconIn && maxorInBeam()) {
            laserHit();
        }
    }

    private static boolean maxorInBeam() {
        return maxor.pos.y >= 225.0 && Math.hypot(maxor.pos.x - BEAM.x, maxor.pos.z - BEAM.z) <= 3.5;
    }

    /** The beam column's glass (73, 223-224, 73; 222 too from t 240): red charged, yellow one crystal in, black none. */
    private static void beamColumn() {
        boolean one = false;
        for (int pt : placeTimes) {
            one |= t - pt >= P1_FUSE;
        }
        String glass = maxorCharged() && beaconIn ? "minecraft:red_stained_glass"
                : one ? "minecraft:yellow_stained_glass" : "minecraft:black_stained_glass";
        for (int y = t >= P1_BEACON + 120 ? 222 : 223; y <= 224; y++) {
            BossBlocks.set(level, 73, y, 73, glass);
        }
    }

    static void pickUpCrystal(ServerPlayer p, int i) {
        if (phase != Phase.P1 || tops[i] == null || maxorKilledAt >= 0) {
            return;
        }
        discard(tops[i]);
        tops[i] = null;
        removeStands(topLabels[i]);
        topLabels[i] = null;
        carrying++;
        giveCrystal(p, true);
        BossChat.chat(server, "§b" + p.getGameProfile().name() + "§r§a picked up an §r§bEnergy Crystal§r§a!");
    }

    /** The carried crystal sits in the SkyBlock Menu's slot (8), as on Hypixel; the slot's item comes back after. */
    private static void giveCrystal(ServerPlayer p, boolean on) {
        if (p == null) {
            return;
        }
        if (on) {
            if (savedSlot8 == null) {
                savedSlot8 = p.getInventory().getItem(8).copy();
            }
            ItemStack star = new ItemStack(Items.NETHER_STAR);
            star.set(DataComponents.CUSTOM_NAME, Component.literal("Energy Crystal")
                    .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.AQUA)));
            p.getInventory().setItem(8, star);
        } else if (savedSlot8 != null) {
            p.getInventory().setItem(8, savedSlot8);
            savedSlot8 = null;
        }
    }

    /** A click on a tower crystal or its stands picks it up; a click on a pylon's stand places one. */
    static boolean useEntity(ServerPlayer p, Entity e) {
        if (phase != Phase.P1) {
            return false;
        }
        for (int i = 0; i < 2; i++) {
            boolean label = topLabels[i] != null && (e == topLabels[i][0] || e == topLabels[i][1]);
            if (e == tops[i] || label) {
                pickUpCrystal(p, i);
                return true;
            }
            if (pylonStands[i] != null && (e == pylonStands[i][0] || e == pylonStands[i][1])) {
                placeAt(p, i);
                return true;
            }
        }
        return false;
    }

    /** A hit on a crystal on its tower picks it up too (and never blows it up). */
    static void attackCrystal(ServerPlayer p, Entity e) {
        for (int i = 0; i < 2; i++) {
            if (tops[i] == e) {
                pickUpCrystal(p, i);
            }
        }
    }

    /** A use near a pylon (within 3.5 across and 4 up or down): places a carried crystal. True when it was the pylon's. */
    static boolean usePylon(ServerPlayer p) {
        if (phase != Phase.P1 || maxorKilledAt >= 0 || !pylonsOpen) {
            return false;
        }
        for (int i = 0; i < 2; i++) {
            Vec3 at = PYLONS[i];
            if (Math.hypot(p.getX() - at.x, p.getZ() - at.z) > 3.5 || Math.abs(p.getY() - at.y) >= 4.0 || placed[i] != null) {
                continue;
            }
            placeAt(p, i);
            return true;
        }
        return false;
    }

    private static void placeAt(ServerPlayer p, int i) {
        if (!pylonsOpen || placed[i] != null || maxorKilledAt >= 0) {
            return;
        }
        if (carrying <= 0) {
            BossChat.chat(server, "§cYou do not current have an §r§bEnergy Crystal§r§c! Find one and bring it here!");
            return;
        }
        carrying--;
        if (carrying == 0) {
            giveCrystal(p, false);
        }
        placed[i] = crystal(PYLONS[i]);
        placedAt[i] = t;
        // Hypixel counts a crystal as active only once it has been in 28 ticks: two placed close together both say 1/2.
        int n = 1;
        for (int pt : placeTimes) {
            if (t - pt >= P1_FUSE) {
                n++;
            }
        }
        placeTimes.add(t);
        BossChat.chat(server, n >= 2 ? "§a" + n + "/2 Energy Crystals are now active!"
                : "§c" + n + "§r§a/2 Energy Crystals are now active!");
        int side = i;
        later(20, "pylon active", () -> {
            if (pylonStands[side] != null && placed[side] != null) {
                relabel(pylonStands[side][0], "§aCrystal Active");
                relabel(pylonStands[side][1], "");
            }
        });
        if (placed[0] != null && placed[1] != null) {
            chargeAt = t + P1_FUSE;
        }
    }

    private static void laserHit() {
        laserHits++;
        int at = t;
        chargeAt = -1;
        if (laserHits == 1) {
            maxorBar = 0.95f;
            maxorBarFloor = 0.30f + RNG.nextFloat() * 0.35f;
            maxorBarRate = 0.004f + RNG.nextFloat() * 0.006f;
        } else {
            maxorBar = 0.12f + RNG.nextFloat() * 0.08f;
            maxorBarFloor = 0.01f;
            maxorBarRate = 0.01f;
        }
        LOGGER.info("[BossSim] Maxor laser hit {} at P1 t {} ({})", laserHits, t, maxor.pos);
        // The next set: back on the towers at hit + 40 (with their stands), the placed ones gone at + 41.
        later(40, "maxor crystals again", () -> {
            if (phase == Phase.P1 && maxorKilledAt < 0) {
                spawnTopCrystals(true);
            }
        });
        later(41, "maxor pylons", () -> {
            if (phase != Phase.P1 || maxorKilledAt >= 0) {
                return;
            }
            for (int i = 0; i < 2; i++) {
                if (placed[i] != null && placedAt[i] <= at) {
                    discard(placed[i]);
                    placed[i] = null;
                    placedAt[i] = -1;
                    if (pylonStands[i] != null) {
                        relabel(pylonStands[i][0], "§cEnergy Crystal Missing");
                        relabel(pylonStands[i][1], "§e§lCLICK HERE");
                    }
                }
            }
            placeTimes.removeIf(pt -> pt <= at);
        });
        if (laserHits >= 2) {
            maxorKilledAt = t + 8 + RNG.nextInt(8);
        }
        if (maxorInAbility()) {
            // A hit inside a taunt's ability is silent: the stun line (and the freeze) wait for its end.
            int lineAt = maxorTauntAt + 62;
            later(lineAt - t, "maxor late stun", () -> {
                if (phase == Phase.P1) {
                    maxorStunLine();
                    if (maxorKilledAt < 0) {
                        freezeMaxor();
                    }
                }
            });
        } else {
            freezeMaxor();
            later(6, "maxor stun line", () -> {
                if (phase == Phase.P1 || phase == Phase.P2) {
                    maxorStunLine();
                }
            });
        }
    }

    private static void freezeMaxor() {
        maxorStunned = true;
        maxorDamage = 0;
        if (maxorTauntAt > t) {
            maxorTauntAt = -1;
        }
        if (laserHits >= 2) {
            return;
        }
        maxorEnrageAt = t + Math.min(240, 100 + RNG.nextInt(100));
    }

    private static void maxorStunLine() {
        maxorStunLines++;
        say("Maxor", RNG.nextBoolean() ? "THAT BEAM! IT HURTS! IT HURTS!!" : "YOU TRICKED ME!");
        int at = t;
        if (maxorStunLines == 2 || laserHits >= 2) {
            later(82, "too young", () -> {
                // Both in P1's ticks: only while he is still there (he despawns at kill + 80).
                if ((phase == Phase.P1 || phase == Phase.P2) && (maxorKilledAt < 0 || at + 82 < maxorKilledAt + 80)) {
                    say("Maxor", "I'M TOO YOUNG TO DIE AGAIN!");
                }
            });
        }
    }

    /** Damage on Maxor: while he is stunned, enough of it ends the stun (a party bursts it in 6-12 ticks). */
    private static void hitMaxor() {
        if (!maxorStunned || maxorKilledAt >= 0 || laserHits >= 2) {
            return;
        }
        if (++maxorDamage == MAXOR_HITS_PER_STUN) {
            maxorEnrageAt = Math.min(maxorEnrageAt, t + 6 + RNG.nextInt(7));
        }
    }

    private static void maxorKilled() {
        int since = t - maxorKilledAt;
        if (since == 0) {
            maxorBar = 0f;
            maxor.armour(false);
            BossBlocks.set(level, new BlockPos(73, 221, 73), BossBlocks.of("minecraft:bedrock"));
            for (int i = 0; i < 2; i++) {
                discard(placed[i]);
                placed[i] = null;
                removeStands(pylonStands[i]);
                pylonStands[i] = null;
            }
            carrying = 0;
            giveCrystal(closest(maxor.pos), false);
            LOGGER.info("[BossSim] Maxor down at P1 t {}", t);
        }
        if (since == STORM_AFTER - 3) {
            // His platforms crumble 3 ticks before Storm's line at the soonest; the death animation with it.
            openHole(P1_HOLE, BossArena.P1_FLOOR);
            maxor.die();
        }
        if (since < 0) {
            return;
        }
        if (maxorBelowAt < 0 && server.getPlayerList().getPlayers().stream().anyMatch(p -> p.getY() < 196.0)) {
            maxorBelowAt = since;
        }
        if (since >= STORM_AFTER && maxorBelowAt >= 0 && since >= maxorBelowAt + 2) {
            BossMob body = maxor;
            later(80 - since, "maxor despawn", body::remove);
            maxor = null;
            startP2();
        }
    }

    // ================================================================================================== P2 Storm
    //
    // ADM's facts (P2Storm.kt, post-update): Storm appears at (103, 188, 53) and flies his opening route at 0.4 a tick,
    // (73, 183, 83) -> (43, 183, 53) -> (73, 183, 23) -> (103, 183, 53), parks at (102.375, 183, 52.375), and leaves 99
    // ticks after his lightning line (548) to chase the closest player 3 above his feet at min(0.9, 0.2 + 0.023 d), holding
    // within 3. Pads are read on the t % 20 == 19 checks: a pad on a resting pillar queues 5 steps, one every 4 ticks; at
    // the floor a pillar waits 28, is drawn up to 189, waits 24 and comes back to 186. A crush is Storm in a pillar's
    // square, his y + 2.975 at or over its bottom, and that pillar stepped in the last 60 ticks. Crush 1 pins him (0-60
    // ticks, median 10, with no Mage beam), then "Storm is enraged!", takeoff 2-3 later: after Purple he flies to Yellow
    // (46, 172.8, 65), after Yellow/Green he chases slower. Crush 2: his death line 0-30 ticks later (median 6). A crushed
    // pillar goes back up at crush + 20 and hangs at 183-189 for good. Giga Lightning at line + 10 and + 19/20.

    private static final Vec3 STORM_AT = new Vec3(103.0, 188.0, 53.0);
    private static final Vec3[] STORM_ROUTE = {new Vec3(73.0, 183.0, 83.0), new Vec3(43.0, 183.0, 53.0),
            new Vec3(73.0, 183.0, 23.0), new Vec3(103.0, 183.0, 53.0)};
    private static final Vec3 STORM_PARK = new Vec3(102.375, 183.0, 52.375);
    private static final Vec3 STORM_TO_YELLOW = new Vec3(46.0, 172.8, 65.0);
    private static final int STORM_LIGHTNING = 548, STORM_LEAVE = 99, STORM_RESET_BOTTOM = 183;

    private static void startP2() {
        phase(Phase.P2);
        BossChat.boss(server, "Storm", "Pathetic Maxor, just like expected.");
        storm = new BossMob("Storm", STORM_AT, 0);
        storm.speak("Pathetic Maxor, just like expected.");
        later(62, "storm 2", () -> say("Storm", "Don't boast about beating this simple-minded Wither."));
        later(124, "storm 3", () -> say("Storm", "My abilities are unparalleled, in many ways I am the last bastion."));
        later(186, "storm 4", () -> say("Storm", "The memory of your death will be your fondest, focus up!"));
        later(424, "storm 5", () -> say("Storm",
                "The power of lightning is quite phenomenal. A single strike can vaporize a person whole."));
        later(486, "storm 6", () -> say("Storm", "I'd be happy to show you what that's like!"));
        double r = RNG.nextDouble();
        nextStormTaunt = r < 0.5 ? 844 + RNG.nextInt(4) : r < 0.85 ? 848 + RNG.nextInt(34) : 882 + RNG.nextInt(20);
        stormGiga2 = 19 + RNG.nextInt(2);
        stormGigaDamage = RNG.nextBoolean() ? 1783 + RNG.nextDouble() * (9350 - 1783) : 9350 + RNG.nextDouble() * (18194 - 9350);
        stormBar = 1f;
        showBar("§c§lStorm", 1f);
    }

    private static void stormLine(String line) {
        say("Storm", line);
    }

    private static void tickP2() {
        if (t == STORM_LIGHTNING || (t == STORM_LIGHTNING + 940 && stormDeadAt < 0)) {
            stormLine(RNG.nextBoolean() ? "ENERGY HEED MY CALL!" : "THUNDER LET ME BE YOUR CATALYST!");
        }
        if (t == STORM_LIGHTNING + 10 || t == STORM_LIGHTNING + stormGiga2) {
            gigaLightning();
        }
        if (stormDeadAt < 0 && (t == STORM_LIGHTNING + 940 + 36 || t == STORM_LIGHTNING + 940 + 46)) {
            gigaLightning();
        }
        if (stormDeadAt < 0 && t == nextStormTaunt) {
            stormLine(STORM_TAUNTS[RNG.nextInt(STORM_TAUNTS.length)]);
            nextStormTaunt = t + 60 + RNG.nextInt(4);
        }
        for (int i = 0; i < 4; i++) {
            tickPillar(i);
        }
        if ((t + 1) % 20 == 0 && bar != null) {
            bar.setProgress(Math.max(0f, Math.min(1f, stormBar)));
        }
        if (stormDeadAt >= 0 && t >= stormDeadAt) {
            stormDead();
            return;
        }
        if (storm == null) {
            return;
        }
        moveStorm();
        if (t % 20 == 19) {
            stormCheck();
        }
        if (stormDeadAt == t) {
            stormDead();
            return;
        }
        if (stormPinned && crushes < 2) {
            if (stormEnrageAt >= 0 && t >= stormEnrageAt && stormTakeoffAt < 0) {
                BossChat.chat(server, "§c⚠ Storm is enraged! ⚠");
                stormTakeoffAt = t + (RNG.nextDouble() < 0.6 ? 2 : 3);
                stormBar = 0.45f;
            }
            if (stormTakeoffAt >= 0 && t >= stormTakeoffAt) {
                stormPinned = false;
                storm.armour(true);
                stormTakeoffAt = -1;
                stormEnrageAt = -1;
                stormChasing = false;
                if ("Purple".equals(PILLARS.get(stormLastCrush).name())) {
                    stormToYellow = true;
                    stormLaterChase = false;
                } else {
                    stormLaterChase = true;
                }
            }
        }
        if (stormPinned && crushes == 1 && stormEnrageAt >= 0) {
            float k = Math.max(0f, Math.min(1f, (t - stormPinnedAt) / (float) Math.max(1, stormEnrageAt - stormPinnedAt)));
            stormBar = 0.95f - 0.5f * k;
        } else if (crushes >= 2 && stormDeadAt > stormCrush2At) {
            stormBar = 0.40f * (1f - (t - stormCrush2At) / (float) (stormDeadAt - stormCrush2At));
        }
    }

    private static void moveStorm() {
        if (stormPinned || t < stormTestHoldUntil) {
            return;
        }
        if (stormToYellow) {
            double d = Math.hypot(storm.pos.x - STORM_TO_YELLOW.x, storm.pos.z - STORM_TO_YELLOW.z);
            if (d <= 2.4) {
                stormToYellow = false;
                stormLaterChase = true;
                return;
            }
            double skip = storm.pos.x > 82 ? 0.02 : storm.pos.x > 70 ? 0.07 : 0.0;
            if (RNG.nextDouble() < skip) {
                return;
            }
            storm.step(STORM_TO_YELLOW, Math.min(0.7157, 0.36 + 0.0134 * d), STORM_TO_YELLOW);
            return;
        }
        ServerPlayer near = closest(storm.pos);
        if (stormLaterChase) {
            if (near == null) {
                return;
            }
            Vec3 aim = near.position().add(0, 3.3, 0);
            double d = Math.hypot(aim.x - storm.pos.x, aim.z - storm.pos.z);
            if (d > 3.0) {
                double sp = 0.145 + 0.0205 * d;
                double dy = Math.max(-0.3, Math.min(0.3, aim.y - storm.pos.y));
                storm.moveTo(new Vec3(storm.pos.x + (aim.x - storm.pos.x) / d * sp, storm.pos.y + dy,
                        storm.pos.z + (aim.z - storm.pos.z) / d * sp), near.position());
            } else {
                storm.moveTo(storm.pos, near.position());
            }
            return;
        }
        if (stormChasing) {
            if (near == null) {
                return;
            }
            Vec3 aim = near.position().add(0, 3.0, 0);
            if (Math.hypot(storm.pos.x - aim.x, storm.pos.z - aim.z) > 3.0) {
                storm.step(aim, Math.min(0.9, 0.2 + 0.023 * storm.pos.distanceTo(aim)), near.position());
            }
            return;
        }
        if (stormLeg < STORM_ROUTE.length) {
            if (storm.pos.distanceTo(STORM_ROUTE[stormLeg]) < 1.0) {
                stormLeg++;
                if (stormLeg == STORM_ROUTE.length) {
                    storm.moveTo(STORM_PARK, null);
                }
            }
            if (stormLeg < STORM_ROUTE.length) {
                storm.step(STORM_ROUTE[stormLeg], 0.40, STORM_ROUTE[stormLeg]);
            }
        }
        if (t >= STORM_LIGHTNING + STORM_LEAVE) {
            stormChasing = true;
        }
    }

    private static boolean pillarResting(int i) {
        return !pillarSpent[i] && pillarSteps[i] == 0 && pillarFloorAt[i] < 0 && !pillarRaising[i] && pillarLowerTo[i] < 0
                && pillarBottom[i] > BossArena.P2_FLOOR + 1;
    }

    private static void stormCheck() {
        for (int i = 0; i < 4; i++) {
            Pillar p = PILLARS.get(i);
            if (p.pad() == null) {
                continue;
            }
            if (pillarResting(i)) {
                for (ServerPlayer pl : server.getPlayerList().getPlayers()) {
                    if (p.onPad(pl.position())) {
                        pillarSteps[i] = 5;
                        LOGGER.info("[BossSim] Storm pillar {} pressed at P2 t {}", p.name(), t);
                        break;
                    }
                }
            }
            if (pillarSteps[i] > 0 && pillarNextStep[i] < 0) {
                pillarNextStep[i] = t;
            }
        }
        if (stormDeadAt >= 0 || stormPinned) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            Pillar p = PILLARS.get(i);
            if (pillarSpent[i]) {
                continue;
            }
            Vec3 s = storm.pos;
            boolean inZone = s.x >= p.minX() && s.x <= p.minX() + 6 && s.z >= p.minZ() && s.z <= p.minZ() + 6;
            if (inZone && s.y + 2.975 >= pillarBottom[i] && t - pillarLastStep[i] <= 60) {
                crush(i);
                return;
            }
        }
    }

    /** One pillar's layers: its steps down, the floor cycle, the reset after a crush. Footprint = 7x7 less its corners. */
    private static void tickPillar(int i) {
        Pillar p = PILLARS.get(i);
        if (pillarResetAt[i] >= 0 && t >= pillarResetAt[i]) {
            pillarResetAt[i] = -1;
            for (int y = BossArena.P2_FLOOR + 1; y < STORM_RESET_BOTTOM; y++) {
                pillarLayer(p, y, false);
            }
            for (int y = STORM_RESET_BOTTOM; y <= PILLAR_TOP; y++) {
                pillarLayer(p, y, true);
            }
            pillarBottom[i] = STORM_RESET_BOTTOM;
            return;
        }
        if (pillarSpent[i]) {
            return;
        }
        int floor = BossArena.P2_FLOOR + 1;
        if (pillarNextStep[i] >= 0 && t >= pillarNextStep[i] && pillarSteps[i] > 0) {
            if (pillarBottom[i] > floor) {
                pillarBottom[i]--;
                pillarLayer(p, pillarBottom[i], true);
                pillarLastStep[i] = t;
                pushStormDown(p, pillarBottom[i]);
            }
            pillarSteps[i]--;
            pillarNextStep[i] = pillarSteps[i] > 0 ? t + 4 : -1;
            if (pillarBottom[i] <= floor) {
                pillarSteps[i] = 0;
                pillarNextStep[i] = -1;
                pillarFloorAt[i] = t;
            }
        }
        if (pillarFloorAt[i] >= 0 && t >= pillarFloorAt[i] + 28) {
            pillarFloorAt[i] = -1;
            pillarRaising[i] = true;
            pillarNextStep[i] = t;
        }
        if (pillarRaising[i] && t >= pillarNextStep[i]) {
            if (pillarBottom[i] < PILLAR_TOP) {
                pillarLayer(p, pillarBottom[i], false);
                pillarBottom[i]++;
                pillarNextStep[i] = t + 4;
            } else {
                pillarRaising[i] = false;
                pillarLowerTo[i] = 186;
                pillarNextStep[i] = t + 24;
            }
        }
        if (pillarLowerTo[i] >= 0 && t >= pillarNextStep[i]) {
            if (pillarBottom[i] > pillarLowerTo[i]) {
                pillarBottom[i]--;
                pillarLayer(p, pillarBottom[i], true);
                pushStormDown(p, pillarBottom[i]);
                pillarNextStep[i] = t + 4;
            } else {
                pillarLowerTo[i] = -1;
                pillarNextStep[i] = -1;
            }
        }
    }

    static boolean inFootprint(int dx, int dz) {
        return !((dx == 0 || dx == 6) && (dz < 2 || dz > 4)) && !((dx == 1 || dx == 5) && (dz == 0 || dz == 6));
    }

    private static void pillarLayer(Pillar p, int y, boolean solid) {
        BlockState st = solid ? BossBlocks.of("minecraft:polished_diorite") : BossBlocks.AIR;
        for (int dx = 0; dx <= 6; dx++) {
            for (int dz = 0; dz <= 6; dz++) {
                if (inFootprint(dx, dz)) {
                    level.setBlock(new BlockPos(p.minX() + dx, y, p.minZ() + dz), st, 2);
                }
            }
        }
    }

    /** A step into his box (y to y + 3.5) pushes him down by the overlap, to 169 at most. */
    private static void pushStormDown(Pillar p, int bottom) {
        if (storm == null || stormDeadAt >= 0) {
            return;
        }
        Vec3 s = storm.pos;
        if (s.x + 0.45 <= p.minX() || s.x - 0.45 >= p.minX() + 7 || s.z + 0.45 <= p.minZ() || s.z - 0.45 >= p.minZ() + 7) {
            return;
        }
        if (s.y >= bottom + 1 || s.y + 3.5 <= bottom) {
            return;
        }
        double y = Math.max(BossArena.P2_FLOOR + 1, bottom - 3.5);
        if (y < s.y) {
            storm.moveTo(new Vec3(s.x, y, s.z), null);
        }
    }

    private static void crush(int i) {
        crushes++;
        Pillar pl = PILLARS.get(i);
        LOGGER.info("[BossSim] Storm crushed by {} (crush {}) at P2 t {} ({})", pl.name(), crushes, t, storm.pos);
        stormLine(RNG.nextBoolean() ? "Ouch, that hurt!" : "Oof");
        storm.armour(false);
        pillarResetAt[i] = t + 20;
        pillarSpent[i] = true;
        pillarSteps[i] = 0;
        pillarNextStep[i] = -1;
        stormToYellow = stormChasing = stormLaterChase = false;
        stormTestHoldUntil = -1;
        stormPinned = true;
        if (crushes >= 2) {
            stormCrush2At = t;
            stormDeadAt = t + stormDeathDelay();
            stormBar = 0.40f;
            return;
        }
        stormPinnedAt = t;
        stormLastCrush = i;
        stormTakeoffAt = -1;
        stormEnrageAt = t + stormPinLength();
        stormBar = 0.95f;
    }

    /** Crush 1 to the enrage with no Mage beam: median 10, about 40% at 0-4, a bump at 21-25 (24 most), a tail to 60. */
    private static int stormPinLength() {
        double r = RNG.nextDouble();
        if (r < 0.40) {
            return RNG.nextInt(5);
        } else if (r < 0.50) {
            return 5 + RNG.nextInt(5);
        } else if (r < 0.72) {
            return 10 + RNG.nextInt(11);
        } else if (r < 0.80) {
            return 24;
        } else if (r < 0.90) {
            return 21 + RNG.nextInt(5);
        }
        return 26 + RNG.nextInt(35);
    }

    /** Crush 2 to the death line: 0-30, median 6. */
    private static int stormDeathDelay() {
        double r = RNG.nextDouble();
        if (r < 0.10) {
            return RNG.nextInt(3);
        } else if (r < 0.55) {
            return 3 + RNG.nextInt(5);
        } else if (r < 0.70) {
            return 8 + RNG.nextInt(3);
        } else if (r < 0.85) {
            return 11 + RNG.nextInt(11);
        } else if (r < 0.92) {
            return 22 + RNG.nextInt(3);
        }
        return 25 + RNG.nextInt(6);
    }

    /** Giga Lightning: every player not under a pillar's square takes it (the line only; the sim does no damage). */
    private static void gigaLightning() {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            boolean covered = false;
            for (Pillar pl : PILLARS) {
                covered |= pl.under(p.position()) && p.getY() >= 168.5 && p.getY() <= 170.5;
            }
            if (!covered) {
                p.sendSystemMessage(BossChat.legacy("§cStorm's§r§7 Giga Lightning hit you for §r§c" + damage(stormGigaDamage)
                        + "§r§7 true damage."));
            }
        }
    }

    /** Hypixel's damage number: thousands commas, one decimal, ".0" dropped. */
    static String damage(double d) {
        double r = Math.round(d * 10) / 10.0;
        String s = String.format(java.util.Locale.ROOT, "%,.1f", r);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** From the death line: "At least my son..." at + 42, the body's death at + 60 and gone at + 80, Goldor at + 62. */
    private static void stormDead() {
        if (t != stormDeadAt) {
            return;
        }
        stormLine("I should have known that I stood no chance.");
        stormBar = 0f;
        later(STORM_SON, "storm son", () -> say("Storm", "At least my son died by your hands."));
        BossMob body = storm;
        later(60, "storm death", () -> {
            if (body != null) {
                body.die();
            }
        });
        later(80, "storm gone", () -> {
            if (body != null) {
                body.remove();
            }
            if (storm == body) {
                storm = null;
            }
        });
        later(GOLDOR_LEAD, "goldor", () -> startP3(1));
    }

    // ================================================================================================== P3 Goldor
    //
    // ADM's facts (GoldorPhase.kt): Goldor walks a closed loop round the four sections, S1 corner (99.55, 119, 40) ->
    // (99.55, 119, 131.7) -> (8.4, 118.5, 131.7) -> (8.4, 118, 40) and back, starting at (80, 119, 40) (s 344.7, 19.5
    // before the S1 corner) at 0.048 a tick for 194 ticks, then 0.06; he never waits for a gate. When a section's door
    // opens while he is still in that section's stretch, 48-59 ticks later he sprints (0.6) to the next corner. Once the
    // core is open (4 ticks at the earliest) and everyone is in the core box, he flies there at 0.8 (core point (53.6,
    // 117, 40)), then in along z, 0.4 to z 56 and 0.07 after. Lines come one at a time, 62 apart. Death ticks hit a
    // section's zone before it starts (and the section in progress once he walked out of it) on n % 60 == 59.

    private static final double G_LOOP = 364.2, G_START_S = 344.7, G_S1_ENTRY = 361.5;
    private static final double[] G_BOUNDS = {0.0, 90.7, 182.1, 272.8, 364.2};
    private static final Vec3[] G_CORNERS = {new Vec3(99.55, 119.0, 40.0), new Vec3(99.55, 119.0, 131.7),
            new Vec3(8.4, 118.5, 131.7), new Vec3(8.4, 118.0, 40.0)};
    private static final double[] G_Y_FROM = {119.0, 118.5, 118.0, 118.1}, G_Y_TO = {119.0, 118.5, 118.0, 119.0};
    private static final double[] G_SPRINT_TO = {91.2, 182.3, 272.8};
    private static final int[] G_START_N = {0, 252, 433, 629, 797};
    private static final Vec3 G_CORE_POINT = new Vec3(53.6, 117.0, 40.0);
    private static final AABB G_CORE_BOX = new AABB(39, 0, 54, 71, 155.5, 118);
    private static final AABB G_STRIP = new AABB(45, 100, 50, 65, 160, 54.5);
    /** Death-tick zones per section (feet y 106-146): x1, x2, z1, z2. */
    private static final double[][] G_DT_ZONES = {{90, 114, 26, 121}, {20, 114, 122, 146}, {-6, 18, 51, 146}, {-6, 90, 26, 50}};
    private static final String[] GOLDOR_TAUNTS = {
            "Do you really think we won't repair everything? Your impact will be minuscule!", "Come closer!",
            "You are breaking precious materials, unforgivable.", "CLOSER!", "There is no stopping me down there!",
            "I am the death zone, you are smart to flee.", "You can't damage me, you can barely slow me down!",
            "Slowing me down only prolongs your pain!", "Closer to me!", "Stop touching those terminals!"};

    static double goldorWalkDist(int age) {
        return age <= 194 ? 0.048 * age : 0.048 * 194 + 0.06 * (age - 194);
    }

    static int goldorSegment(double s) {
        int i = 0;
        while (i < 3 && s >= G_BOUNDS[i + 1]) {
            i++;
        }
        return i;
    }

    static Vec3 goldorTrack(double s) {
        int i = goldorSegment(s);
        double f = Math.max(0, Math.min(1, (s - G_BOUNDS[i]) / (G_BOUNDS[i + 1] - G_BOUNDS[i])));
        Vec3 a = G_CORNERS[i], b = G_CORNERS[(i + 1) % 4];
        return new Vec3(a.x + (b.x - a.x) * f, G_Y_FROM[i] + (G_Y_TO[i] - G_Y_FROM[i]) * f, a.z + (b.z - a.z) * f);
    }

    /** The section (1-4) whose stretch of the loop he is on; the start stretch on the S4 line counts as S1. */
    private static int goldorSection() {
        return goldorFirstLap ? 1 : goldorSegment(goldorS) + 1;
    }

    private static void startP3(int fromSection) {
        boolean fromStorm = phase == Phase.P2;
        phase(Phase.P3);
        section = Math.min(fromSection, 5);
        BossChat.boss(server, "Goldor", "Who dares trespass into my domain?");
        lastGoldorLine = clock;
        goldorStartN = G_START_N[section - 1];
        goldorS = (G_START_S + goldorWalkDist(goldorStartN)) % G_LOOP;
        goldorFirstLap = section == 1;
        goldorSprintFrom = -1;
        goldorSprintTo = -1;
        goldorFlying = goldorAtCore = false;
        java.util.Arrays.fill(goldorReached, false);
        goldor = new BossMob("Goldor", goldorTrack(goldorS), 0);
        goldor.speak("Who dares trespass into my domain?");
        goldorLines.add("Little ants, plotting and scheming, thinking they are invincible...");
        goldorLines.add("I won't let you break the factory core, I gave my life to my Master.");
        goldorLines.add("No one matches me in close quarters.");
        if (section == 1) {
            // S1: 0-3 taunts (2/41/50/7 %), each queued at n 20 + 0..219.
            double r = RNG.nextDouble();
            int n = r < 0.02 ? 0 : r < 0.43 ? 1 : r < 0.93 ? 2 : 3;
            for (int i = 0; i < n; i++) {
                later(20 + RNG.nextInt(220), "goldor taunt", BossFight::queueGoldorTaunt);
            }
        }
        if (fromStorm) {
            // On Goldor's line the hole under the Red pillar opens (Hypixel's frames run 24-30 ticks after it).
            later(24 + RNG.nextInt(7), "p2 hole", () -> openHole(P2_HOLE, BossArena.P2_FLOOR));
        }
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

    private static String lastTaunt;

    private static void queueGoldorTaunt() {
        if (phase != Phase.P3 || goldorDeadAt >= 0) {
            return;
        }
        String line;
        do {
            line = GOLDOR_TAUNTS[RNG.nextInt(GOLDOR_TAUNTS.length)];
        } while (line.equals(lastTaunt));
        lastTaunt = line;
        goldorLines.add(line);
    }

    /** Goldor's lines, one at a time and 62 ticks apart (on into P4 if any are left when he dies). */
    private static void drainGoldorLines() {
        if (!goldorLines.isEmpty() && clock - lastGoldorLine >= LINE_GAP) {
            String line = goldorLines.poll();
            say("Goldor", line);
            lastGoldorLine = clock;
            if ("....".equals(line)) {
                later(52, "forgive", () -> say("Goldor", "Necron, forgive me."));
            }
        }
    }

    private static void tickP3() {
        drainGoldorLines();
        if (gateAt >= 0 && t >= gateAt) {
            gateAt = -1;
            destroyGate(section);
        }
        BossDevices.tick(level);
        if (goldor != null && goldorDeadAt < 0) {
            moveGoldor();
        }
        if (section >= 1 && section <= 4) {
            goldorReached[goldorSection()] = true;
            int w = t % 60;
            if (w == 58) {
                goldorDtEarly = RNG.nextInt(98) < 26;
            }
            if ((w == 58 && goldorDtEarly) || (w == 59 && !goldorDtEarly)) {
                deathTick();
            }
        }
        if (coreOpen && !goldorFlying && t >= coreOpenAt + 4 && !server.getPlayerList().getPlayers().isEmpty()
                && server.getPlayerList().getPlayers().stream().allMatch(p -> G_CORE_BOX.contains(p.position()))) {
            goldorFlying = true;
            goldor.armour(false);
            LOGGER.info("[BossSim] Goldor leaves for the core at P3 t {} from {}", t, goldor.pos);
        }
        if (coreOpen && goldorDeadAt < 0 && t % 10 == 7 && goldor != null) {
            goldorFrenzy();
        }
        if (goldorDeadAt >= 0 && t - goldorDeadAt == NECRON_AFTER - 2) {
            // The floor under the core goes 2 ticks before Necron's line; everyone falls into P4.
            openHole(CORE_HOLE, BossArena.P3_FLOOR);
        }
    }

    private static void moveGoldor() {
        Vec3 pos = goldor.pos;
        if (goldorFlying) {
            if (!goldorAtCore) {
                Vec3 to = G_CORE_POINT.subtract(pos);
                double d = Math.max(1e-6, Math.hypot(to.x, to.z));
                double step = Math.min(0.8, d);
                goldor.moveTo(new Vec3(pos.x + to.x / d * step, Math.max(G_CORE_POINT.y, pos.y - 0.03), pos.z + to.z / d * step),
                        G_CORE_POINT);
                if (d <= 0.8) {
                    goldorAtCore = true;
                    goldorLines.add("You have done it, you destroyed the factory...");
                    goldorLines.add("But you have nowhere to hide anymore!");
                    goldorLines.add("YOU ARE FACE TO FACE WITH GOLDOR!");
                    LOGGER.info("[BossSim] Goldor at the core at P3 t {}", t);
                }
            } else if (pos.z < 110) {
                double v = pos.z < 56.0 ? 0.4 : 0.07;
                goldor.moveTo(new Vec3(pos.x + Math.max(-0.1, Math.min(0.1, 53.6 - pos.x)), Math.max(116.75, pos.y - 0.006),
                        pos.z + v), new Vec3(pos.x, pos.y, pos.z + 10));
            }
            return;
        }
        if (goldorSprintFrom >= 0 && t >= goldorSprintFrom && goldorSprintTo < 0) {
            if (goldorInSection(goldorSprintSec)) {
                goldorSprintTo = G_SPRINT_TO[goldorSprintSec - 1];
            }
            goldorSprintFrom = -1;
        }
        if (goldorSprintTo >= 0) {
            double left = ((goldorSprintTo - goldorS) % G_LOOP + G_LOOP) % G_LOOP;
            if (left <= 0.6) {
                goldorS = goldorSprintTo;
                goldorSprintTo = -1;
            } else {
                goldorS += 0.6;
            }
        } else {
            goldorS += goldorStartN + t < 194 ? 0.048 : 0.06;
        }
        if (goldorS >= G_LOOP) {
            goldorFirstLap = false;
        }
        goldorS %= G_LOOP;
        Vec3 next = goldorTrack(goldorS);
        float yaw = switch (goldorSegment(goldorS)) {
            case 0 -> 0f;
            case 1 -> 90f;
            case 2 -> 180f;
            default -> -90f;
        };
        goldor.moveTo(next, next.add(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw))));
    }

    private static boolean goldorInSection(int sec) {
        return sec == 1 ? goldorS >= G_S1_ENTRY || goldorS < G_BOUNDS[1] : goldorSegment(goldorS) == sec - 1;
    }

    /** A death tick: the line for a player in a zone that is lethal now (the sim does no damage). */
    private static void deathTick() {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Vec3 v = p.position();
            if (G_CORE_BOX.contains(v) || G_STRIP.contains(v) || v.y < 106 || v.y > 146) {
                continue;
            }
            int at = -1;
            for (int i = 0; i < 4 && at < 0; i++) {
                double[] z = G_DT_ZONES[i];
                if (v.x >= z[0] && v.x <= z[1] && v.z >= z[2] && v.z <= z[3]) {
                    at = i + 1;
                }
            }
            if (at < 0 || at < section) {
                continue;
            }
            if (at == section && !(goldorReached[section] && goldorSection() != section)) {
                continue;
            }
            p.sendSystemMessage(BossChat.legacy("§4[BOSS] Goldor§r§c: What do you think you are doing there!"));
            LOGGER.info("[BossSim] death tick: S{} during S{} at P3 t {}", at, section, t);
        }
    }

    /** Goldor's Frenzy once the core is open: n % 10 == 7, 2-14 blocks from him; hit 1 2-13k, 2 44-48k, 3+ 52-60k. */
    private static void goldorFrenzy() {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            double d = p.position().distanceTo(goldor.pos);
            if (d < 2.0 || d > 14.0) {
                goldorFrenzyHits = 0;
                continue;
            }
            goldorFrenzyHits++;
            double dmg = goldorFrenzyHits == 1 ? 2000 + RNG.nextDouble() * 11000
                    : goldorFrenzyHits == 2 ? 44000 + RNG.nextDouble() * 4000 : 52000 + RNG.nextDouble() * 8000;
            String n = RNG.nextInt(10) < 4 ? String.format(java.util.Locale.ROOT, "%,d", Math.round(dmg)) : damage(dmg);
            p.sendSystemMessage(BossChat.legacy("§cGoldor's§r§7 Frenzy hit you for §r§c" + n + "§r§7 damage."));
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
        BossChat.chat(server, "§aThe gate has been destroyed!");
        BossChat.title(server, "", "§aThe gate has been destroyed!", 0, 40, 0);
        // The blocks go 1 tick after the line, as Hypixel's.
        later(1, "gate blocks", () -> BossBlocks.fill(level, g[0], g[1], g[2], g[3], g[4], g[5], BossBlocks.AIR));
        goldorLines.add(GOLDOR_SECTION_LINES[RNG.nextInt(GOLDOR_SECTION_LINES.length)]);
        section = sec + 1;
        LOGGER.info("[BossSim] gate {} down, S{} in progress at P3 t {}", sec, section, t);
        if (section <= 4 && RNG.nextInt(100) < 13) {
            later(20 + RNG.nextInt(180), "goldor taunt", BossFight::queueGoldorTaunt);
        }
        // His catch-up: still in the section's stretch when its door opens, he sprints to the next corner 48-59 later.
        if (!goldorFlying && goldorInSection(sec) && goldorSprintFrom < 0) {
            goldorSprintFrom = t + 48 + RNG.nextInt(12);
            goldorSprintSec = sec;
        }
        // A device done before its section began counts the moment that section opens, as on Hypixel.
        onStationDone(level, BossStations.device(section));
    }

    private static void openCore(boolean announce) {
        coreOpen = true;
        coreOpenAt = t;
        section = 5;
        if (announce) {
            BossChat.chat(server, "§aThe Core entrance is opening!");
            BossChat.title(server, "", "§aThe Core entrance is opening!", 0, 40, 0);
        }
        later(1, "core door", () -> BossBlocks.fill(level, 52, BossArena.P3_FLOOR + 1, 58, 57, BossArena.P3_FLOOR + 6, 58,
                BossBlocks.AIR));
    }

    private static void hitGoldor() {
        if (!goldorFlying || goldorDeadAt >= 0) {
            return;
        }
        goldorHits++;
        showBar("§c§lGoldor", 0.35f * (1f - goldorHits / (float) GOLDOR_HITS));
        if (goldorHits < GOLDOR_HITS) {
            return;
        }
        goldorDeadAt = t;
        // He stops where he is (no death animation) and stays until Necron's body goes; his "...." queues behind
        // anything he still has to say, "Necron, forgive me." 52 after it.
        goldorLines.add("....");
        showBar("§c§lGoldor", 0f);
        LOGGER.info("[BossSim] Goldor down at P3 t {}", t);
        later(NECRON_AFTER, "necron", () -> {
            BossDevices.stop();
            startP4();
        });
    }

    // ================================================================================================== P4 Necron
    //
    // ADM's facts (P4Necron.kt, the post-update script measured on 176 F7 runs): Necron's lines at 0/42/84/126, a taunt
    // at 188, "ARGH!" at 270, "Let's make some space!" at 332; the fight runs on its own clock, not on damage. He holds at
    // mid (54, 66, 76), sidesteps at 80-86 (0.25 a tick, 45-75 degrees off south), holds 3, flies at the closest player (+2)
    // at 0.49 until 98, is teleported back to mid; holds facing the platform he will break from 175, flies again at 0.63
    // at 235-239, back to mid at 240. That platform goes at 275. Nuclear Frenzy on t % 20 == 8 (99-174, 241-309) for
    // players within 12 of mid. Death animation at 369, gone at 389 (Goldor's body with him).

    private static final int N_L1 = 80, N_B1 = 98, N_V1 = 20, N_V2 = 175, N_L2 = 235, N_B2 = 240, N_BREAK = 275;
    private static final int N_TAUNT = 188, N_ARGH = 270, N_SPACE = 332, N_THROES = 310, N_GONE = 389;
    private static final String[] NECRON_TAUNTS = {
            "Sometimes when you have a problem, you just need to destroy it all and start again.", "WITNESS MY RAW NUCLEAR POWER!"};

    private static void startP4() {
        phase(Phase.P4);
        BossChat.boss(server, "Necron", "You went further than any human before, congratulations.");
        necron = new BossMob("Necron", NECRON_AT, 0);
        necron.speak("You went further than any human before, congratulations.");
        double a = Math.toRadians((45 + RNG.nextDouble() * 30) * (RNG.nextBoolean() ? 1 : -1));
        necronSide = new Vec3(-Math.sin(a), -0.9 + RNG.nextDouble() * 1.8, Math.cos(a));
        necronPlatform = RNG.nextInt(NECRON_PLATFORMS.length);
        necronBarPhase = RNG.nextInt(20);
        necronTaunt = NECRON_TAUNTS[RNG.nextInt(NECRON_TAUNTS.length)];
        showBar("§c§lNecron", 0f);
    }

    private static float necronHealth() {
        if (t < N_L1) {
            return 0f;
        } else if (t < N_B1) {
            return 1f - 0.2f * (t - N_L1) / (N_B1 - N_L1);
        } else if (t < N_V2 - 5) {
            return 0.8f;
        } else if (t < N_V2 + 10) {
            return 0.8f - 0.55f * (t - N_V2 + 5) / 15f;
        } else if (t < N_L2) {
            return 0.25f;
        } else if (t < N_B2) {
            return 0.25f - 0.2f * (t - N_L2) / (N_B2 - N_L2);
        }
        return t < N_THROES ? 0.05f : 0f;
    }

    private static void tickP4() {
        drainGoldorLines();
        switch (t) {
            case NECRON_INTRO -> say("Necron", "I'm afraid, your journey ends now.");
            case 2 * NECRON_INTRO -> say("Necron", "Goodbye.");
            case 3 * NECRON_INTRO -> say("Necron", "That's a very impressive trick. I guess I'll have to handle this myself.");
            case N_TAUNT -> say("Necron", necronTaunt);
            case N_ARGH -> say("Necron", "ARGH!");
            case N_SPACE -> say("Necron", "Let's make some space!");
            case N_BREAK -> breakNecronPlatform();
            default -> {
            }
        }
        if (t % 20 == necronBarPhase && bar != null) {
            bar.setProgress(Math.round(necronHealth() * 100) / 100f);
        }
        if (necron != null) {
            moveNecron();
        }
        if (t % 20 == 8 && ((t > N_B1 && t < N_V2) || (t > N_B2 && t < N_THROES))) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.position().distanceTo(NECRON_AT) <= 12.0) {
                    p.sendSystemMessage(BossChat.legacy("§cNecron's§r§7 Nuclear Frenzy hit you for §r§c57,600§r§7 damage."));
                }
            }
        }
        if (t == N_GONE - 20 && necron != null) {
            necronDeadAt = t;
            necron.die();
            LOGGER.info("[BossSim] Necron down at P4 t {}", t);
        }
        if (t == N_GONE) {
            if (necron != null) {
                necron.remove();
                necron = null;
            }
            if (goldor != null) {
                goldor.remove();
                goldor = null;
            }
            if (master) {
                openHole(P4_HOLE, BossArena.P4_FLOOR);
                startP5();
            } else {
                finish();
            }
        }
    }

    private static void moveNecron() {
        int[] pl = NECRON_PLATFORMS[necronPlatform];
        Vec3 target = new Vec3(pl[0], 63.0, pl[1]);
        Vec3 pos = necron.pos;
        ServerPlayer near = closest(pos);
        Vec3 chase = near == null ? NECRON_AT : near.position().add(0, 2, 0);
        if (t >= N_L1 && t < N_L1 + 7) {
            necron.moveTo(pos.add(necronSide.scale(0.25)), pos.add(necronSide.x, 0, necronSide.z));
        } else if (t >= N_L1 + 7 && t < N_L1 + 10) {
            // holds
        } else if (t >= N_L1 + 10 && t < N_B1) {
            necron.step(chase, 0.49, chase);
        } else if (t >= N_L2 && t < N_B2) {
            necron.step(chase, 0.63, chase);
        } else if (t == N_B1 || t == N_B2) {
            necron.moveTo(NECRON_AT, near == null ? null : near.position());
        } else if (t >= N_V1 && t < N_V1 + 80) {
            necron.moveTo(NECRON_AT, NECRON_AT.add(0, 0, 10));
        } else if (t >= N_V2 && t < N_L2) {
            necron.moveTo(NECRON_AT, target);
        } else if (t < N_GONE && t % 5 == 0 && near != null) {
            necron.moveTo(pos, near.position());
        }
    }

    /** One whole side platform to lava in a single tick (the one he faced from 175). */
    private static void breakNecronPlatform() {
        int[] b = NECRON_PLATFORMS[necronPlatform];
        BossBlocks.fill(level, b[2], BossArena.P4_FLOOR, b[4], b[3], BossArena.P4_FLOOR, b[5], BossBlocks.of("minecraft:lava"));
        LOGGER.info("[BossSim] Necron's {} platform broke at P4 t {}", NECRON_PLATFORM_NAMES[necronPlatform], t);
    }

    /** Debug and testkit: the platform Necron breaks this run ("N", "NW", "NE", "SW", "SE"). */
    public static String necronPlatform() {
        return phase == Phase.P4 ? NECRON_PLATFORM_NAMES[necronPlatform] : null;
    }

    private static void hitNecron() {
        // Since Hypixel's boss update Necron's fight runs on its own clock (ADM: "does not wait for damage").
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
            BossChat.boss(server, "The Wither King", "You... again?");
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
            BossChat.boss(server, "The Wither King", KING_COUNTS[kingLine++ % KING_COUNTS.length]);
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

        /** Puts him at {@code p}, facing {@code look} (yaw = atan2(-dx, dz), as Hypixel's withers turn); null keeps his yaw. */
        void moveTo(Vec3 p, Vec3 look) {
            pos = p;
            float yaw = wither.getYRot();
            if (look != null && (Math.abs(look.x - p.x) > 1e-6 || Math.abs(look.z - p.z) > 1e-6)) {
                yaw = (float) Math.toDegrees(Math.atan2(-(look.x - p.x), look.z - p.z));
            }
            wither.snapTo(p.x, p.y, p.z, yaw, 0f);
            wither.setYHeadRot(yaw);
            wither.yBodyRot = yaw;
            if (tag != null) {
                tag.snapTo(p.x, p.y + 3.6875, p.z, 0f, 0f);
            }
            if (speech != null) {
                speech.snapTo(p.x, p.y + 4.09375, p.z, 0f, 0f);
            }
        }

        /** One straight step toward {@code target} of at most {@code speed}, facing {@code look}; true when there. */
        boolean step(Vec3 target, double speed, Vec3 look) {
            Vec3 d = target.subtract(pos);
            double len = d.length();
            if (len <= speed) {
                moveTo(target, look);
                return true;
            }
            moveTo(pos.add(d.scale(speed / len)), look);
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

    /**
     * Debug and testkit: holds Storm under a pillar's square for 400 ticks, where a lure would bring him (a test with one
     * player cannot lure him and stand on the pad at once). Only his free movement is held: a crush still pins him.
     */
    public static void testParkStorm(String pillar) {
        for (Pillar p : PILLARS) {
            if (p.name().equalsIgnoreCase(pillar) && storm != null) {
                storm.moveTo(new Vec3(p.minX() + 3.5, 172.0, p.minZ() + 3.5), null);
                stormTestHoldUntil = t + 400;
            }
        }
    }

    /** Debug and testkit: Storm's state, "leg 2 chasing=false later=false pinned=false". */
    public static String stormState() {
        return "leg " + stormLeg + " chasing=" + stormChasing + " later=" + stormLaterChase + " toYellow=" + stormToYellow
                + " pinned=" + stormPinned + " bottoms=" + java.util.Arrays.toString(pillarBottom);
    }

    /** Debug and testkit: Goldor's place on his loop (s, 0-364.2) and whether he has left for the core. */
    public static String goldorState() {
        return String.format(java.util.Locale.ROOT, "s %.2f flying=%s atCore=%s sprint=%s", goldorS, goldorFlying, goldorAtCore,
                goldorSprintTo >= 0 ? String.valueOf(goldorSprintTo) : goldorSprintFrom >= 0 ? "from " + goldorSprintFrom : "no");
    }

    /** Debug and testkit: server ticks since the current phase's first line. */
    public static int phaseTick() {
        return t;
    }

    /** Debug and testkit: Maxor's laser hits so far. */
    public static int maxorLaserHits() {
        return laserHits;
    }

    static BlockPos pylonBlock(int i) {
        return BlockPos.containing(PYLONS[i].x, PYLONS[i].y - 1, PYLONS[i].z);
    }
}
