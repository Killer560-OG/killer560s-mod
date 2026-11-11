package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Two sim options for testing a whole floor's clear (killer560, 2026-10-20: "spawn mobs in the sim so the test covers
 * syncing and clearing", and "treat the trap room as already cleared with all its secrets ... Auto Trap is not set up
 * yet"). Both are the SIM's own data - the server places the mobs, the server's score counts the trap - so nothing in a
 * client feature knows about either; both are off unless asked for ({@code /simlive}, or the testkit).
 *
 * <h2>Live mobs</h2>
 * At GO, every mob room of the floor (the room database's NORMAL, RARE and CHAMPION) gets {@link #perTile} starred
 * zombies and skeletons per tile on open floor, at spots drawn from a random seeded by the map code and the room's name -
 * one floor gets the same mobs every run - each with {@link #health} (a sim bat blast does 30, a Wither Impact 10,000),
 * and with {@link #wander} walking about at random. A CHAMPION room also gets a starred miniboss (Lost Adventurer or
 * Shadow Assassin, the real player-entity form) with three times that health. Each mob's star tag follows it and shows its
 * health as it drops ({@link SimMobs#spawnLive}), and the sim's own clear-on-entry is switched off while this is on, so a
 * room is cleared only when its last starred mob dies, as on Hypixel. Mobs out of his entity view are out of the client's
 * world, so a clear has to remember where it last saw them.
 *
 * <h2>Trap room cleared</h2>
 * The floor's trap room places no secrets, and at GO is marked cleared and credited with every secret the room database
 * gives it, in the sim's score and on its map (green), as if Auto Trap had already done it - so a reading of the score
 * (Dungeon Autopilot's Solo "300 score reached") is what it will be once the trap is hooked up like any other puzzle.
 */
public final class SimLiveMobs {

    private static final Logger LOGGER = ModLog.get("killer560smod-sim");

    private static volatile int perTile = 0;
    private static volatile double health = 1.0;
    private static volatile boolean wander = false;
    private static volatile boolean minibosses = true;
    private static volatile boolean trapCleared = false;
    /** Fels beside the starred mobs (a floor with mobs, {@link SimMobFloor}). */
    private static volatile boolean fels = false;
    /** Starred mobs placed in each room at the last GO (fels included), by room name. */
    private static final Map<String, Integer> PLACED_IN = new java.util.concurrent.ConcurrentHashMap<>();
    /** Each room's first placed spot at the last GO, by room name (a key with no mob to kill drops here). */
    private static final Map<String, BlockPos> FIRST_SPOT = new java.util.concurrent.ConcurrentHashMap<>();
    /** Rooms whose mobs went on learned spawn spots at the last GO (Blood Rush Helper's), and the blocks they stood on. */
    private static final Map<String, List<BlockPos>> LEARNED_IN = new java.util.concurrent.ConcurrentHashMap<>();
    /** Clear-on-entry as it was before live mobs switched it off, to put it back. */
    private static volatile Boolean entryBefore = null;

    private SimLiveMobs() {
    }

    /**
     * Live mobs from the next GO on: {@code perTile} a tile (0 = off), {@code health} each, {@code wander}, and a
     * miniboss in each CHAMPION room when {@code bosses}.
     */
    public static void configure(int perTile, double health, boolean wander, boolean bosses) {
        SimLiveMobs.perTile = Math.max(0, Math.min(12, perTile));
        SimLiveMobs.health = Math.max(1.0, health);
        SimLiveMobs.wander = wander;
        SimLiveMobs.minibosses = bosses;
        if (SimLiveMobs.perTile > 0) {
            if (entryBefore == null) {
                entryBefore = SimMobs.isClearRoomsOnEntry();
            }
            SimMobs.setClearRoomsOnEntry(false);
        } else if (entryBefore != null) {
            SimMobs.setClearRoomsOnEntry(entryBefore);
            entryBefore = null;
        }
        LOGGER.info("[Sim] live mobs: {}", describe());
    }

    /** Fels from the next GO on: some mob rooms get a starred Fel beside one of their starred mobs, and some an unstarred
     *  one (killer560, Doc 2026-11-01: "make it so fels can spawn for the mobs"). */
    public static void setFels(boolean on) { fels = on; }

    public static boolean isOn() { return perTile > 0; }

    /** Starred mobs placed in {@code roomName} at the last GO (fels included), or -1 when the room got none placed. */
    public static int placedIn(String roomName) { return roomName == null ? -1 : PLACED_IN.getOrDefault(roomName, -1); }

    static BlockPos firstSpot(String roomName) { return roomName == null ? null : FIRST_SPOT.get(roomName); }

    public static void setTrapCleared(boolean on) {
        trapCleared = on;
        LOGGER.info("[Sim] trap room counted cleared with all its secrets: {}", on ? "on" : "off");
    }

    public static String describe() {
        return perTile <= 0 ? "off" : String.format(Locale.US, "%d a tile, %.0f HP, %s, minibosses %s, fels %s", perTile,
                health, wander ? "walking" : "standing", minibosses ? "on" : "off", fels ? "on" : "off");
    }

    /** Whether the trap room's secrets are left out of the build (SimSecrets.place asks). */
    static boolean skipSecrets(String roomName) { return trapCleared && "TRAP".equalsIgnoreCase(typeOf(roomName)); }

    private static String typeOf(String roomName) {
        RoomEntry e = com.killer560.hub.roomdatabase.RoomDatabase.lookupByName(roomName);
        return e == null || e.type == null ? "" : e.type;
    }

    /** SimRun, on GO (client thread): the trap room's credit, then the live mobs. */
    static void onRunStart(Minecraft client) {
        DungeonLayout layout = DungeonLayout.current();
        if (trapCleared) {
            creditTrap(layout);
        }
        if (perTile > 0) {
            placeMobs(client, layout);
        }
    }

    private static void creditTrap(DungeonLayout layout) {
        for (int i = 0; i < layout.roomCount(); i++) {
            RoomEntry e = layout.entry(i);
            String name = layout.name(i);
            if (e == null || name == null || !"TRAP".equalsIgnoreCase(e.type)) continue;
            int secrets = Math.max(0, e.secrets);
            SimScore.creditRoomSecrets(name, secrets);
            if (SimRoomState.markCleared(name)) {
                SimScore.roomCleared();
            }
            LOGGER.info("[Sim] trap room {} counted cleared with its {} secret(s) (Auto Trap is not hooked up yet)", name,
                    secrets);
        }
    }

    private static void placeMobs(Minecraft client, DungeonLayout layout) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || !SimState.canAct(client)) return;
        String code = String.valueOf(SimState.mapCode());
        Map<String, List<BlockPos>> tiles = new TreeMap<>();
        Map<String, Boolean> champion = new TreeMap<>();
        Map<String, Integer> roomIndex = new TreeMap<>();
        Map<String, List<BlockPos>> spots = new TreeMap<>();
        Map<String, com.killer560.hub.autoroutes.RouteCoords.Frame> frames = new HashMap<>();
        boolean looks = SimMenuPrefs.cached().realMobs;
        LEARNED_IN.clear();
        for (int i = 0; i < layout.roomCount(); i++) {
            RoomEntry e = layout.entry(i);
            String name = layout.name(i);
            if (e == null || e.type == null || name == null || "Unknown".equals(name)) continue;
            String type = e.type.toUpperCase(Locale.ROOT);
            if (!type.equals("NORMAL") && !type.equals("RARE") && !type.equals("CHAMPION")) continue;
            List<BlockPos> cs = new ArrayList<>();
            for (int t : layout.tiles(i)) {
                cs.add(DungeonLayout.cellCenter(t));
            }
            tiles.put(name, cs);
            champion.put(name, type.equals("CHAMPION"));
            roomIndex.put(name, i);
            // The room's spawn spots, where Blood Rush Helper has learned them from real runs (killer560, 2026-11-03: "for
            // our clear sim you should be able to add mobs in their blood rush helper spots since that is where they
            // spawn"), turned with the room by its clay corner and rotation. A room with none keeps the seeded spots.
            int[] cr = layout.clayRotation(i);
            if (cr != null) {
                var frame = new com.killer560.hub.autoroutes.RouteCoords.Frame(name, cr[0], cr[1], cr[2]);
                frames.put(name, frame);
                List<BlockPos> learned = com.killer560.hub.bloodrush.BloodRushSpots.worldSpots(name, frame);
                if (!learned.isEmpty()) {
                    spots.put(name, learned);
                }
            }
        }
        int each = perTile;
        double hp = health;
        boolean walk = wander;
        boolean bosses = minibosses;
        boolean withFels = fels;
        PLACED_IN.clear();
        FIRST_SPOT.clear();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            int placed = 0;
            int starredFels = 0;
            int plainFels = 0;
            int looked = 0;
            List<String> per = new ArrayList<>();
            for (Map.Entry<String, List<BlockPos>> e : tiles.entrySet()) {
                java.util.Random rng = new java.util.Random((code + "|" + e.getKey()).hashCode());
                int room = roomIndex.get(e.getKey());
                List<BlockPos> got = new ArrayList<>();
                List<BlockPos> learned = spots.get(e.getKey());
                Map<BlockPos, SimMobLooks.Look> lookAt = new HashMap<>();
                var frame = frames.get(e.getKey());
                if (learned != null) {
                    for (BlockPos s : learned) {
                        BlockPos feet = standable(level, s);
                        if (feet != null && !got.contains(feet)) {
                            got.add(feet);
                            // Real Mob Looks (simblood-1110): what stood on this spot on Hypixel, if it was recorded.
                            SimMobLooks.Look look = looks ? SimMobLooks.lookAt(e.getKey(),
                                    com.killer560.hub.autoroutes.RouteCoords.toRelativeBlock(frame, s)) : null;
                            if (look != null) {
                                lookAt.put(feet, look);
                            }
                        }
                    }
                    if (!got.isEmpty()) {
                        LEARNED_IN.put(e.getKey(), List.copyOf(got));
                    }
                }
                for (BlockPos c : got.isEmpty() ? e.getValue() : List.<BlockPos>of()) {
                    int want = got.size() + each;
                    for (int tries = 0; tries < 400 && got.size() < want; tries++) {
                        int x = c.getX() + rng.nextInt(23) - 11;
                        int z = c.getZ() + rng.nextInt(23) - 11;
                        if (layout.roomAtWorld(x + 0.5, z + 0.5) != room) continue;
                        // c is a tile center at the doorways' feet level + 1: never under that floor less one, nor more than four
                        // above it - a pit or a high ledge was a mob Auto Clear could not reach and a key nobody could pick up
                        // (doc-adm-sim-1101: Flags' key 3 below the floor, then a zombie 8 above it).
                        for (int y = c.getY() - 2; y <= c.getY() + 3; y++) {
                            BlockPos feet = new BlockPos(x, y, z);
                            if (solid(level, feet.below()) && !solid(level, feet) && !solid(level, feet.above())
                                    && level.getFluidState(feet).isEmpty()) {
                                boolean far = true;
                                for (BlockPos o : got) {
                                    far &= o.distSqr(feet) >= 9;
                                }
                                if (far) {
                                    got.add(feet);
                                }
                                break;
                            }
                        }
                    }
                }
                boolean boss = bosses && champion.get(e.getKey()) && !got.isEmpty();
                int k = 0;
                for (BlockPos p : got) {
                    if (lookAt.containsKey(p)) {
                        SimMobLooks.Look look = lookAt.get(p);
                        SimMobs.spawnLook(level, p, look,
                                com.killer560.hub.autoroutes.RouteCoords.toRealYaw(frame, look.yaw()), hp);
                        looked++;
                    } else if (boss && k == 0) {
                        // The room's first spot is its miniboss's (three times the health; it never walks).
                        SimMobs.spawnLive(level, p, SimMobs.Kind.MINIBOSS, hp * 3, false);
                    } else {
                        SimMobs.spawnLive(level, p, k % 3 == 2 ? SimMobs.Kind.SKELETON : SimMobs.Kind.ZOMBIE, hp, walk);
                    }
                    k++;
                    placed++;
                }
                String felNote = "";
                if (withFels && !got.isEmpty()) {
                    // Starred mobs spawn by each other (killer560, Doc 2026-11-01), so a Fel lies a few blocks from one of
                    // the room's starred mobs: a starred one in about two rooms of five, an unstarred one in one of three.
                    if (rng.nextInt(5) < 2) {
                        BlockPos f = besideSpot(level, layout, room, got.get(rng.nextInt(got.size())), got, rng);
                        if (f != null) {
                            SimMobs.spawnFelLive(level, f, true);
                            got.add(f);
                            starredFels++;
                            placed++;
                            felNote += "+fel";
                        }
                    }
                    if (rng.nextInt(3) == 0) {
                        BlockPos f = besideSpot(level, layout, room, got.get(rng.nextInt(got.size())), got, rng);
                        if (f != null) {
                            SimMobs.spawnFelLive(level, f, false);
                            plainFels++;
                            felNote += "+skull";
                        }
                    }
                }
                PLACED_IN.put(e.getKey(), got.size());
                if (!got.isEmpty()) {
                    FIRST_SPOT.put(e.getKey(), got.get(0));
                }
                per.add(e.getKey() + " " + got.size() + (lookAt.isEmpty() ? "" : " (" + lookAt.size() + " real)") + (bosses && champion.get(e.getKey()) ? "+boss" : "") + felNote);
            }
            LOGGER.info("[Sim] live mobs placed: {} starred mob(s) in {} mob room(s) ({}): {}", placed, tiles.size(),
                    describe(), String.join(", ", per));
            if (looks) {
                LOGGER.info("[Sim] real mob looks: {} of {} starred mob(s) as recorded on Hypixel", looked, placed);
            }
            if (withFels) {
                LOGGER.info("[Sim] fels placed: {} starred, {} not starred", starredFels, plainFels);
            }
            SimMobFloor.onMobsPlaced(client);
            int n = placed;
            client.execute(() -> ModChat.send("Sim", ModChat.text("Live mobs: "), ModChat.value(String.valueOf(n)),
                    ModChat.dim(" starred in " + tiles.size() + " room(s)")));
        });
    }

    /** A learned spawn block made standable: itself, or the first block 2 below to 2 above it with floor under and room for
     *  a mob; null when there is none (the sim's capture of the room differs there). */
    private static BlockPos standable(ServerLevel level, BlockPos s) {
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            BlockPos feet = s.offset(0, dy, 0);
            if (solid(level, feet.below()) && !solid(level, feet) && !solid(level, feet.above())
                    && level.getFluidState(feet).isEmpty()) {
                return feet;
            }
        }
        return null;
    }

    /** The rooms whose mobs stood on learned spawn spots at the last GO, and those spots, for tests. */
    public static Map<String, List<BlockPos>> learnedSpotsPlaced() { return Map.copyOf(LEARNED_IN); }

    /** An open floor spot 2-4 blocks from {@code near}, in the same room and clear of {@code taken}, or null. */
    private static BlockPos besideSpot(ServerLevel level, DungeonLayout layout, int room, BlockPos near, List<BlockPos> taken,
                                       java.util.Random rng) {
        // Never below the floor the doorways stand on, less one: a pit or the hollow under a floor is no place for a mob (a
        // key that drops there cannot be reached; doc-adm-sim-1101, a Flags key 3 below the floor).
        int floorMin = DungeonLayout.doorBlock(0).getY() - 1;
        for (int tries = 0; tries < 60; tries++) {
            int x = near.getX() + rng.nextInt(9) - 4;
            int z = near.getZ() + rng.nextInt(9) - 4;
            int dx = x - near.getX();
            int dz = z - near.getZ();
            if (dx * dx + dz * dz < 4 || layout.roomAtWorld(x + 0.5, z + 0.5) != room) continue;
            for (int y = Math.max(near.getY() - 2, floorMin); y <= Math.min(near.getY() + 2, floorMin + 5); y++) {
                BlockPos feet = new BlockPos(x, y, z);
                if (solid(level, feet.below()) && !solid(level, feet) && !solid(level, feet.above())
                        && !solid(level, feet.above(2)) && level.getFluidState(feet).isEmpty()) {
                    boolean far = true;
                    for (BlockPos o : taken) {
                        far &= o.distSqr(feet) >= 4;
                    }
                    if (far) return feet;
                    break;
                }
            }
        }
        return null;
    }

    private static boolean solid(ServerLevel level, BlockPos p) {
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** {@code /simlive off | <per tile> [health] [walk] | trap on|off} - the options above, for a sim floor. */
    public static void registerCommand(
            com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> d) {
        d.register(ClientCommands.literal("simlive")
                .then(ClientCommands.literal("off").executes(ctx -> {
                    configure(0, 1, false, true);
                    ModChat.send("Sim", ModChat.text("Live mobs off (from the next GO)"));
                    return 1;
                }))
                .then(ClientCommands.literal("trap")
                        .then(ClientCommands.literal("on").executes(ctx -> {
                            setTrapCleared(true);
                            ModChat.send("Sim", ModChat.text("Trap room counted cleared (from the next floor)"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(ctx -> {
                            setTrapCleared(false);
                            ModChat.send("Sim", ModChat.text("Trap room as it is"));
                            return 1;
                        })))
                .then(ClientCommands.argument("perTile", IntegerArgumentType.integer(0, 12))
                        .executes(ctx -> live(IntegerArgumentType.getInteger(ctx, "perTile"), 1, false))
                        .then(ClientCommands.argument("health", IntegerArgumentType.integer(1, 1_000_000))
                                .executes(ctx -> live(IntegerArgumentType.getInteger(ctx, "perTile"),
                                        IntegerArgumentType.getInteger(ctx, "health"), false))
                                .then(ClientCommands.literal("walk").executes(ctx -> live(
                                        IntegerArgumentType.getInteger(ctx, "perTile"),
                                        IntegerArgumentType.getInteger(ctx, "health"), true))))));
    }

    private static int live(int each, int hp, boolean walk) {
        configure(each, hp, walk, true);
        ModChat.send("Sim", ModChat.text("Live mobs from the next GO: "), ModChat.value(describe()));
        return 1;
    }
}
