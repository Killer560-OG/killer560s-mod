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

    public static void setTrapCleared(boolean on) {
        trapCleared = on;
        LOGGER.info("[Sim] trap room counted cleared with all its secrets: {}", on ? "on" : "off");
    }

    public static String describe() {
        return perTile <= 0 ? "off" : String.format(Locale.US, "%d a tile, %.0f HP, %s, minibosses %s", perTile, health,
                wander ? "walking" : "standing", minibosses ? "on" : "off");
    }

    /** Whether the trap room's secrets are left out of the build (SimSecrets.place asks). */
    static boolean skipSecrets(String roomName) {
        return trapCleared && "TRAP".equalsIgnoreCase(typeOf(roomName));
    }

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
            if (e == null || name == null || !"TRAP".equalsIgnoreCase(e.type)) {
                continue;
            }
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
        if (server == null || !SimState.canAct(client)) {
            return;
        }
        String code = String.valueOf(SimState.mapCode());
        Map<String, List<BlockPos>> tiles = new TreeMap<>();
        Map<String, Boolean> champion = new TreeMap<>();
        Map<String, Integer> roomIndex = new TreeMap<>();
        for (int i = 0; i < layout.roomCount(); i++) {
            RoomEntry e = layout.entry(i);
            String name = layout.name(i);
            if (e == null || e.type == null || name == null || "Unknown".equals(name)) {
                continue;
            }
            String type = e.type.toUpperCase(Locale.ROOT);
            if (!type.equals("NORMAL") && !type.equals("RARE") && !type.equals("CHAMPION")) {
                continue;
            }
            List<BlockPos> cs = new ArrayList<>();
            for (int t : layout.tiles(i)) {
                cs.add(DungeonLayout.cellCenter(t));
            }
            tiles.put(name, cs);
            champion.put(name, type.equals("CHAMPION"));
            roomIndex.put(name, i);
        }
        int each = perTile;
        double hp = health;
        boolean walk = wander;
        boolean bosses = minibosses;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            int placed = 0;
            List<String> per = new ArrayList<>();
            for (Map.Entry<String, List<BlockPos>> e : tiles.entrySet()) {
                java.util.Random rng = new java.util.Random((code + "|" + e.getKey()).hashCode());
                int room = roomIndex.get(e.getKey());
                List<BlockPos> got = new ArrayList<>();
                for (BlockPos c : e.getValue()) {
                    int want = got.size() + each;
                    for (int tries = 0; tries < 400 && got.size() < want; tries++) {
                        int x = c.getX() + rng.nextInt(23) - 11;
                        int z = c.getZ() + rng.nextInt(23) - 11;
                        if (layout.roomAtWorld(x + 0.5, z + 0.5) != room) {
                            continue;
                        }
                        for (int y = c.getY() - 6; y <= c.getY() + 8; y++) {
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
                    if (boss && k == 0) {
                        // The room's first spot is its miniboss's (three times the health; it never walks).
                        SimMobs.spawnLive(level, p, SimMobs.Kind.MINIBOSS, hp * 3, false);
                    } else {
                        SimMobs.spawnLive(level, p, k % 3 == 2 ? SimMobs.Kind.SKELETON : SimMobs.Kind.ZOMBIE, hp, walk);
                    }
                    k++;
                    placed++;
                }
                per.add(e.getKey() + " " + got.size() + (bosses && champion.get(e.getKey()) ? "+boss" : ""));
            }
            LOGGER.info("[Sim] live mobs placed: {} starred mob(s) in {} mob room(s) ({}): {}", placed, tiles.size(),
                    describe(), String.join(", ", per));
            int n = placed;
            client.execute(() -> ModChat.send("Sim", ModChat.text("Live mobs: "), ModChat.value(String.valueOf(n)),
                    ModChat.dim(" starred in " + tiles.size() + " room(s)")));
        });
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
