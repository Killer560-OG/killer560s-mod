package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.util.ModChat;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Turns a map code into rooms on the ground.
 *
 * <p>The orchestration only: {@link RoomPlacer} does the block writing and {@link MapCode} does the decoding.
 * This decides what goes where and reports what could not be built.
 *
 * <p><b>A missing room is named, not skipped silently.</b> The library fills up as rooms are captured, so an
 * early sim will be mostly gaps - and a builder that quietly left holes would look exactly like a builder that
 * was broken. Every room it could not find is counted and listed.
 */
public final class SimBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-roomsim");

    private SimBuilder() {
    }

    /**
     * {@code /simbuild flat} and {@code /simbuild code <code>}.
     *
     * <p>Two entry points because they answer different questions: "does any of this work at all" and "does this
     * specific map rebuild". The first needs nothing captured, which is the only reason the sim is testable
     * today.
     */
    public static void register() {
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> dispatcher.register(
                        net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("simbuild")
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                                        .literal("flat")
                                        .executes(ctx -> {
                                            buildFlatTest(Minecraft.getInstance());
                                            return 1;
                                        }))
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                                        .literal("code")
                                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                                                .argument("code", com.mojang.brigadier.arguments.StringArgumentType
                                                        .greedyString())
                                                .executes(ctx -> {
                                                    build(Minecraft.getInstance(),
                                                            com.mojang.brigadier.arguments.StringArgumentType
                                                                    .getString(ctx, "code"));
                                                    return 1;
                                                })))
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                                        .literal("run")
                                        .executes(ctx -> {
                                            // No entrance door on the map yet, so nothing to hold shut - the
                                            // countdown still runs, which is the half that is testable today.
                                            SimRun.begin(Minecraft.getInstance(), null);
                                            return 1;
                                        }))
                                .executes(ctx -> {
                                    ModChat.send("Sim", ModChat.dim(
                                            "/simbuild flat  |  /simbuild code <code>  |  /simbuild run"));
                                    return 1;
                                })));
    }

    /**
     * Builds the whole map from a code.
     *
     * <p>Runs on the server thread. Pasting a room is tens of thousands of block writes and doing that from the
     * client thread would freeze the game rather than merely be slow.
     */
    public static void build(Minecraft client, String code) {
        MapCode.Decoded decoded = MapCode.decode(code);
        if (decoded == null) {
            ModChat.send("Sim", ModChat.text("That map code is not valid."));
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            // Reached from the main menu, where there is no world yet. It used to return silently here, which
            // is why "Create a New Map" and "Load a Previous Run" both dropped him into an empty sim: the world
            // opened, nothing built it, and nothing said so.
            SimWorld.open(client, code, c -> build(c, code), "Building the map");
            return;
        }
        SimWorld.buildProgress("Placing rooms");
        server.execute(() -> {
            ServerLevel level = server.overworld();
            int missing = 0;
            // Counted in an array so the lambda can write to it - rooms actually built, which is the score's
            // room denominator.
            final int[] roomsPlaced = {0};
            StringBuilder missingNames = new StringBuilder();
            for (int cell = 0; cell < decoded.cellRoom().length; cell++) {
                int nameIndex = decoded.cellRoom()[cell];
                if (nameIndex < 0) {
                    continue;
                }
                String name = decoded.nameTable()[nameIndex];
                RoomLibrary.Room room = RoomLibrary.get(name);
                if (room == null) {
                    missing++;
                    if (missingNames.indexOf(name) < 0) {
                        missingNames.append(missingNames.isEmpty() ? "" : ", ").append(name);
                    }
                    continue;
                }
                int gx = cell % DungeonLayout.GRID;
                int gz = cell / DungeonLayout.GRID;
                SimBuildQueue.submit(level, room, gx, gz, decoded.cellRotation()[cell]);
                roomsPlaced[0]++;
                spawnMobsFor(client, level, room, gx, gz);
                if (SimMimic.roomEligible(name)) {
                    collectChests(level, gx, gz, room);
                }
            }
            // One mimic per MAP, chosen once everything is down. Picking while placing would give the first
            // eligible room a far better chance than the last.
            SimMimic.chooseForMap();
            // The score's denominators come from the map that was actually built, not from a guess. Without
            // them "explore" divides by zero and the whole score is meaningless - and a score screen that
            // invents its own totals is worse than one that says it does not know.
            SimScore.reset(SimMimic.candidateCount(), roomsPlaced[0]);
            final int m = missing;
            final String names = missingNames.toString();
            final int roomCount = roomsPlaced[0];
            SimBuildQueue.whenDone(() -> client.execute(() -> {
                SimWorld.buildFinished(client, null);
                ModChat.send("Sim", ModChat.text("Built "), ModChat.value(String.valueOf(roomCount)),
                        ModChat.text(" room(s)."));
                if (m > 0) {
                    ModChat.send("Sim", ModChat.dim(m + " cell(s) had no captured room: " + names));
                }
            }));
            LOGGER.info("Sim build: {} room(s) queued, {} cells missing a room", roomCount, m);
        });
    }

    /**
     * Finds the chests in a placed room and offers them as mimic candidates.
     *
     * <p>Read back out of the world rather than out of the room data, because what matters is where the chest
     * actually ended up - a rotated room puts its chests somewhere the room-local coordinates do not say.
     */
    private static void collectChests(ServerLevel level, int gridX, int gridZ, RoomLibrary.Room room) {
        var origin = DungeonLayout.cellCenter(gridZ * DungeonLayout.GRID + gridX);
        int x0 = origin.getX() - RoomLibrary.TILE / 2;
        int z0 = origin.getZ() - RoomLibrary.TILE / 2;
        var cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int x = 0; x < room.sizeX; x++) {
            for (int z = 0; z < room.sizeZ; z++) {
                for (int y = RoomLibrary.MIN_Y; y <= RoomLibrary.MAX_Y; y++) {
                    cursor.set(x0 + x, y, z0 + z);
                    if (level.getBlockState(cursor).is(net.minecraft.world.level.block.Blocks.CHEST)) {
                        SimMimic.addCandidate(cursor);
                    }
                }
            }
        }
    }

    /**
     * Puts the room's captured starred mobs back where they stood.
     *
     * <p>A room without them is scenery. The positions were recorded room-local so they follow the room
     * wherever it is placed; they are NOT rotated yet, which is wrong for a rotated room and is called out
     * here rather than hidden, because the fix needs the same coordinate transform the placer uses and that
     * is worth doing once rather than twice.
     */
    private static void spawnMobsFor(Minecraft client, ServerLevel level, RoomLibrary.Room room,
                                     int gridX, int gridZ) {
        if (room.mobSpawns.isEmpty()) {
            return;
        }
        var origin = DungeonLayout.cellCenter(gridZ * DungeonLayout.GRID + gridX);
        int worldX0 = origin.getX() - RoomLibrary.TILE / 2;
        int worldZ0 = origin.getZ() - RoomLibrary.TILE / 2;
        for (String spawn : room.mobSpawns) {
            String[] parts = spawn.split(",");
            if (parts.length < 4) {
                continue;
            }
            try {
                int lx = Integer.parseInt(parts[0]);
                int ly = Integer.parseInt(parts[1]);
                int lz = Integer.parseInt(parts[2]);
                SimMobs.spawnStarred(client,
                        new net.minecraft.core.BlockPos(worldX0 + lx, ly, worldZ0 + lz), SimMobs.Kind.ZOMBIE);
            } catch (NumberFormatException ignored) {
                // a malformed line in a hand-edited room file should skip that mob, not the whole room
            }
        }
    }

    /**
     * Builds ONE room in the middle of the grid, for drilling a single room.
     *
     * <p>The mode that gets used most while learning a room, which is why the menu offers it directly rather
     * than making him generate a whole map to reach it.
     */
    public static void buildSingleRoom(Minecraft client, String roomName) {
        RoomLibrary.Room room = RoomLibrary.get(roomName);
        if (room == null) {
            ModChat.send("Sim", ModChat.text("No captured room called " + roomName));
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            // Not in a world yet - which is the normal case, because this is reached from the MAIN MENU. It
            // used to open an empty sim here and ask him to run a command once inside, so the room he picked
            // was never placed. The build is queued instead and runs the moment the world exists.
            SimWorld.open(client, "", c -> buildSingleRoom(c, roomName), "Loading " + roomName);
            return;
        }
        int centre = DungeonLayout.GRID / 2;
        SimWorld.buildProgress("Placing " + roomName);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            // Through the queue, not straight into a 700k-block loop on this thread - that is what froze the
            // game. The completion callback is what takes the loading screen down.
            SimBuildQueue.submit(level, room, centre, centre, 0);
            SimBuildQueue.whenDone(() -> {
                spawnMobsFor(client, level, room, centre, centre);
                client.execute(() -> {
                    SimWorld.buildFinished(client, null);
                    ModChat.send("Sim", ModChat.text("Built "), ModChat.value(roomName));
                });
            });
        });
    }

    /**
     * Drops the synthetic test room into the middle of the grid.
     *
     * <p>What makes the sim testable before anything has been scanned. Placed at the centre cell so there is
     * room around it, and reported with its coordinates so he can find it rather than hunt.
     */
    public static void buildFlatTest(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null && Minecraft.getInstance().level == null) {
            SimWorld.open(client, "", SimBuilder::buildFlatTest, "Loading the test room");
            return;
        }
        if (server == null) {
            ModChat.send("Sim", ModChat.text("Not in a local world."));
            return;
        }
        RoomLibrary.Room room = FlatTestRoom.ensure();
        int centre = DungeonLayout.GRID / 2;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            SimBuildQueue.submit(level, room, centre, centre, 0);
            var origin = DungeonLayout.cellCenter(centre * DungeonLayout.GRID + centre);
            SimBuildQueue.whenDone(() -> client.execute(() -> {
                SimWorld.buildFinished(client, null);
                ModChat.send("Sim",
                    ModChat.text("Flat test room built at "),
                    ModChat.value(String.format(Locale.US, "%d %d %d",
                            origin.getX(), origin.getY(), origin.getZ())));
            }));
        });
    }
}
