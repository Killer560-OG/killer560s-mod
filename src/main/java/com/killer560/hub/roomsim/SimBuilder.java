package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.util.ModChat;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
                (dispatcher, registryAccess) -> {
                    // killer560 (2026-09-28): "also let me do /map to change it."
                    //
                    // Gated with requires(), not merely guarded inside the body. A client command claims its
                    // NAME globally, so an ungated /map would swallow the command on Hypixel as well - and
                    // this mod already refuses to shadow server commands (it is why the item command is
                    // /simitem and not /item). With requires(), the command does not exist outside the sim: it
                    // does not tab-complete there, and whatever the server does with /map still happens.
                    dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("map")
                            .requires(src -> SimState.canAct(Minecraft.getInstance()))
                            .executes(ctx -> {
                                Minecraft mc = Minecraft.getInstance();
                                mc.execute(() -> mc.setScreenAndShow(new SimMenuScreen(mc.screen)));
                                return 1;
                            }));
                    dispatcher.register(
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
                                }));
                });
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
            // Where to put him when it is built - the first cell that actually got a room, so he never lands
            // in a gap the map left empty.
            final int[] firstPlacedCell = {-1};
            final java.util.List<Runnable> afterBuild = new java.util.ArrayList<>();
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
                final int rot = decoded.cellRotation()[cell];
                final int fgx = gx;
                final int fgz = gz;
                final RoomLibrary.Room fr = room;
                // Queued to run after the whole map is placed, for the same reason as a single room: a secret
                // written before the paste reaches that cell would simply be pasted over.
                afterBuild.add(() -> SimSecrets.place(level, fr, fgx, fgz, rot));
                if (firstPlacedCell[0] < 0) {
                    firstPlacedCell[0] = cell;
                }
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
            // The whole grid, in one wipe, so no flatland shows between rooms or in the gaps a map leaves.
            var gridMin = DungeonLayout.cellCenter(0);
            var gridMax = DungeonLayout.cellCenter(DungeonLayout.GRID * DungeonLayout.GRID - 1);
            SimBuildQueue.submitClear(level,
                    Math.min(gridMin.getX(), gridMax.getX()) - RoomLibrary.TILE,
                    Math.min(gridMin.getZ(), gridMax.getZ()) - RoomLibrary.TILE,
                    Math.max(gridMin.getX(), gridMax.getX()) + RoomLibrary.TILE,
                    Math.max(gridMin.getZ(), gridMax.getZ()) + RoomLibrary.TILE);
            final int firstCell = firstPlacedCell[0];
            SimBuildQueue.whenDone(() -> {
                for (Runnable r : afterBuild) {
                    r.run();
                }
                SimMimic.chooseForMap();
                if (firstCell >= 0) {
                    snapPlayerTo(client, level, firstCell % DungeonLayout.GRID,
                            firstCell / DungeonLayout.GRID);
                }
                client.execute(() -> {
                SimWorld.buildFinished(client, null);
                ModChat.send("Sim", ModChat.text("Built "), ModChat.value(String.valueOf(roomCount)),
                        ModChat.text(" room(s)."));
                if (m > 0) {
                    ModChat.send("Sim", ModChat.dim(m + " cell(s) had no captured room: " + names));
                }
                });
            });
            LOGGER.info("Sim build: {} room(s) queued, {} cells missing a room", roomCount, m);
        });
    }

    /**
     * Puts the player in the room that was just built.
     *
     * <p>killer560 (2026-09-28): "I found the room generated it is just really far away my character isnt
     * snapped to it." The sim's grid is anchored at -185,-185 and a room can be a couple of hundred blocks from
     * world spawn, so building it and leaving him at spawn means the work is invisible - he was looking at
     * empty flatland with a dungeon over the horizon.
     *
     * <p>The landing spot is FOUND rather than assumed. Rooms differ in floor height and the captured slice
     * spans y 60 to 140, so a fixed Y drops him inside the floor or a long way above it. This scans down the
     * centre column for the first solid block with two blocks of air on top - the same test that decides
     * whether an etherwarp is legal, and for the same reason: it is what "somewhere you can stand" means.
     *
     * <p>Writing the position directly is correct here and only here: the integrated server is ours, and the
     * whole package is gated on {@link SimState#canAct}. The no-direct-movement rule exists because Hypixel
     * reconstructs movement and lags you back; there is no Hypixel in this world.
     */
    static void snapPlayerTo(Minecraft client, ServerLevel level, int gridX, int gridZ) {
        var origin = DungeonLayout.cellCenter(gridZ * DungeonLayout.GRID + gridX);
        int x = origin.getX();
        int z = origin.getZ();
        // Upwards from the bottom. Scanning DOWN from the top finds the first standable surface from above,
        // which for a room with a ceiling is the ROOF - killer560 (2026-09-28): "it put me ontop of the room
        // instead of insidde it." Coming up from the floor finds the floor.
        int landing = -1;
        for (int y = RoomLibrary.MIN_Y; y < RoomLibrary.MAX_Y - 2; y++) {
            if (!level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).isAir()
                    && level.getBlockState(new net.minecraft.core.BlockPos(x, y + 1, z)).isAir()
                    && level.getBlockState(new net.minecraft.core.BlockPos(x, y + 2, z)).isAir()) {
                landing = y + 1;
                break;
            }
        }
        if (landing < 0) {
            // Nothing to stand on at the centre - a doorway column, or a room whose middle is a pit. Put him
            // above it rather than inside the floor; falling a few blocks is recoverable, suffocating is not.
            landing = RoomLibrary.MAX_Y;
        }
        final int y = landing;
        var uuid = client.player == null ? null : client.player.getUUID();
        if (uuid == null) {
            return;
        }
        level.getServer().execute(() -> {
            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(uuid);
            if (sp != null) {
                sp.teleportTo(level, x + 0.5, y, z + 0.5, java.util.Set.of(), sp.getYRot(), sp.getXRot(), false);
            }
        });
    }

    /** Blocks of flat world to wipe around a room, so it does not sit in a field. */
    private static final int CLEAR_MARGIN = 24;

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
            var origin = DungeonLayout.cellCenter(centre * DungeonLayout.GRID + centre);
            int halfX = room.sizeX / 2 + CLEAR_MARGIN;
            int halfZ = room.sizeZ / 2 + CLEAR_MARGIN;
            SimBuildQueue.submitClear(level, origin.getX() - halfX, origin.getZ() - halfZ,
                    origin.getX() + halfX, origin.getZ() + halfZ);
            SimBuildQueue.submit(level, room, centre, centre, 0);
            SimBuildQueue.whenDone(() -> {
                spawnMobsFor(client, level, room, centre, centre);
                // After the geometry, never before: a chest placed first would be overwritten by the paste.
                int secrets = SimSecrets.place(level, room, centre, centre, 0);
                SimMimic.chooseForMap();
                SimScore.reset(Math.max(0, secrets), 1);
                snapPlayerTo(client, level, centre, centre);
                client.execute(() -> SimSecrets.report(roomName, secrets));
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
            var origin = DungeonLayout.cellCenter(centre * DungeonLayout.GRID + centre);
            SimBuildQueue.submitClear(level, origin.getX() - room.sizeX / 2 - CLEAR_MARGIN,
                    origin.getZ() - room.sizeZ / 2 - CLEAR_MARGIN,
                    origin.getX() + room.sizeX / 2 + CLEAR_MARGIN,
                    origin.getZ() + room.sizeZ / 2 + CLEAR_MARGIN);
            SimBuildQueue.submit(level, room, centre, centre, 0);
            SimBuildQueue.whenDone(() -> {
                snapPlayerTo(client, level, centre, centre);
                client.execute(() -> {
                SimWorld.buildFinished(client, null);
                ModChat.send("Sim",
                    ModChat.text("Flat test room built at "),
                    ModChat.value(String.format(Locale.US, "%d %d %d",
                            origin.getX(), origin.getY(), origin.getZ())));
                });
            });
        });
    }
}
