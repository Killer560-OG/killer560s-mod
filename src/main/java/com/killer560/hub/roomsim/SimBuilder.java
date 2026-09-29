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
            // Where to put him when it is built.
            //
            // killer560 (2026-09-28): "when i tried to generate a map everything was broken it made rooms but
            // i wasnt spawnedd inside od green room." It was landing him in the first cell the map code
            // happened to fill, which is wherever the name table started rather than where a run begins. The
            // entrance is marked in the map with DOOR_ENTRANCE, so it is found rather than guessed at, and the
            // first-filled cell is kept only as a fallback for maps that carry no entrance.
            final int[] firstPlacedCell = {-1};
            final int[] entranceCell = {-1};
            final java.util.List<Runnable> afterBuild = new java.util.ArrayList<>();
            StringBuilder missingNames = new StringBuilder();
            // One paste per ROOM, not per cell.
            //
            // killer560 (2026-09-28): "you need to make the rooms auto resize in the regular map as well."
            // A map code names a room in every cell it covers, and this loop used to paste once for each of
            // them - so a 2x2 room went down four times on top of itself, each copy anchored a cell further
            // along, and the result looked like a room smeared across its own footprint. Every floor he loads
            // has rooms like that, so it is not an edge case.
            //
            // Cells that share a room index AND touch are one placement, found by flood fill. Two separate
            // copies of the same room on one floor stay separate, because they do not touch.
            boolean[] handled = new boolean[decoded.cellRoom().length];
            for (int cell = 0; cell < decoded.cellRoom().length; cell++) {
                if (handled[cell]) {
                    continue;
                }
                int nameIndex = decoded.cellRoom()[cell];
                if (nameIndex < 0) {
                    continue;
                }
                String name = decoded.nameTable()[nameIndex];
                RoomLibrary.Room room = RoomLibrary.get(name);
                if (room == null) {
                    handled[cell] = true;
                    missing++;
                    if (missingNames.indexOf(name) < 0) {
                        missingNames.append(missingNames.isEmpty() ? "" : ", ").append(name);
                    }
                    continue;
                }
                // The whole footprint this placement covers, and its top-left cell - which is the anchor the
                // capture measured from, so it is the anchor the paste has to use.
                java.util.List<Integer> footprint = floodFill(decoded.cellRoom(), handled, cell, nameIndex);
                int anchor = footprint.get(0);
                for (int fc : footprint) {
                    if (fc % DungeonLayout.GRID <= anchor % DungeonLayout.GRID
                            && fc / DungeonLayout.GRID <= anchor / DungeonLayout.GRID) {
                        anchor = fc;
                    }
                }
                int gx = anchor % DungeonLayout.GRID;
                int gz = anchor / DungeonLayout.GRID;
                SimBuildQueue.submit(level, room, gx, gz, decoded.cellRotation()[cell]);
                final int rot = decoded.cellRotation()[cell];
                final int fgx = gx;
                final int fgz = gz;
                final int anchorCell = anchor;
                final RoomLibrary.Room fr = room;
                // Queued to run after the whole map is placed, for the same reason as a single room: a secret
                // written before the paste reaches that cell would simply be pasted over.
                afterBuild.add(() -> SimSecrets.place(level, fr, fgx, fgz, rot));
                if (firstPlacedCell[0] < 0) {
                    firstPlacedCell[0] = anchorCell;
                }
                for (int fc : footprint) {
                    if (decoded.cellDoor()[fc] == DungeonLayout.DOOR_ENTRANCE) {
                        entranceCell[0] = anchorCell;
                    }
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
            // Everything that was here before, so a new map never shows the last one's rooms in its gaps.
            wipeWholeGrid(level);
            final int firstCell = entranceCell[0] >= 0 ? entranceCell[0] : firstPlacedCell[0];
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
        // The same spot death sends him back to - one definition of "the middle of the room", so the place he
        // starts and the place he returns to cannot drift apart.
        SimSurvival.setHome(new net.minecraft.core.BlockPos(x, y, z));
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

    /**
     * Every cell reachable from {@code start} that carries the same room, marking them handled as it goes.
     *
     * <p>Touching matters as well as matching: a floor can hold two copies of one room, and they are two
     * placements rather than one enormous misshapen one. Orthogonal only, because a room that meets another
     * corner to corner is not the same room.
     */
    private static java.util.List<Integer> floodFill(int[] cellRoom, boolean[] handled, int start, int nameIndex) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        java.util.Deque<Integer> queue = new java.util.ArrayDeque<>();
        queue.add(start);
        handled[start] = true;
        while (!queue.isEmpty()) {
            int at = queue.poll();
            out.add(at);
            int gx = at % DungeonLayout.GRID;
            int gz = at / DungeonLayout.GRID;
            for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = gx + step[0];
                int nz = gz + step[1];
                if (nx < 0 || nz < 0 || nx >= DungeonLayout.GRID || nz >= DungeonLayout.GRID) {
                    continue;
                }
                int n = nz * DungeonLayout.GRID + nx;
                if (!handled[n] && cellRoom[n] == nameIndex) {
                    handled[n] = true;
                    queue.add(n);
                }
            }
        }
        return out;
    }

    /**
     * Wipes every cell of the dungeon grid.
     *
     * <p>Queued like everything else, and safe to ask for because the clear now bounds how much it READS as
     * well as how much it writes - in a void world nearly every position it visits is already air, and an
     * unbounded scan over the whole grid is exactly the freeze the queue exists to prevent.
     */
    /**
     * Removes dropped items from the sim world.
     *
     * <p>killer560 (2026-09-28): "make sure you clear all floor drops on every generation of a room." They
     * survive a rebuild because clearing blocks does not touch entities, so a room loaded three times has
     * three runs' worth of litter on its floor - and item secrets are placed as dropped items, so the pile
     * grows with every generation and stops meaning anything.
     *
     * <p>Only ever inside the sim, and only item entities: this is a delete, and a broad one in the wrong
     * world would be unforgivable.
     */
    private static void clearFloorDrops(ServerLevel level) {
        int removed = 0;
        for (var entity : level.getAllEntities()) {
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity) {
                entity.discard();
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.info("Cleared {} floor drop(s) before building", removed);
        }
    }

    /**
     * Where the player waits while the map is built.
     *
     * <p>killer560 (2026-09-28): "it should fully generate everything before I actually can join."
     *
     * <p>Far enough from the dungeon grid that not one build chunk is within any render distance, so the
     * client is never sent them while they are half-written. That makes "fully generated before you join"
     * literally true rather than merely hidden behind a loading screen - and it is also what fixes the freeze,
     * because the chunks then arrive through vanilla's own streaming when he is put in the room, a few per
     * tick, instead of two hundred at once from me.
     */
    private static final net.minecraft.core.BlockPos HOLDING_AREA =
            new net.minecraft.core.BlockPos(8000, 200, 8000);

    /**
     * Moves the player out to the holding area for the duration of a build.
     *
     * <p>A barrier under his feet, because the sim world is void and a player waiting on a loading screen
     * still falls - and falling would drop him back through the region being built.
     */
    static void holdPlayer(Minecraft client, ServerLevel level) {
        var uuid = client.player == null ? null : client.player.getUUID();
        if (uuid == null) {
            return;
        }
        level.getServer().execute(() -> {
            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(uuid);
            if (sp == null) {
                return;
            }
            level.setBlockAndUpdate(HOLDING_AREA.below(),
                    net.minecraft.world.level.block.Blocks.BARRIER.defaultBlockState());
            sp.teleportTo(level, HOLDING_AREA.getX() + 0.5, HOLDING_AREA.getY(), HOLDING_AREA.getZ() + 0.5,
                    java.util.Set.of(), sp.getYRot(), sp.getXRot(), false);
        });
    }

    private static void wipeWholeGrid(ServerLevel level) {
        clearFloorDrops(level);
        // Out of the way before a single block is written, so nothing he can see is ever half-built.
        holdPlayer(Minecraft.getInstance(), level);
        // Only what the last build actually wrote, when that is known. Sweeping the whole grid meant four
        // million block reads, most of them into chunks that had to be LOADED to answer - in a world whose
        // only contents were one room. The full sweep stays as the fallback for the first build after a
        // restart, when nothing has been recorded yet and the rooms on disk are still real.
        int[] known = SimBuildQueue.touchedBounds();
        SimBuildQueue.forgetTouched();
        if (known != null) {
            SimBuildQueue.submitClear(level, known[0], known[1], known[2], known[3]);
            return;
        }
        var gridMin = DungeonLayout.cellCenter(0);
        var gridMax = DungeonLayout.cellCenter(DungeonLayout.GRID * DungeonLayout.GRID - 1);
        SimBuildQueue.submitClear(level,
                Math.min(gridMin.getX(), gridMax.getX()) - RoomLibrary.TILE,
                Math.min(gridMin.getZ(), gridMax.getZ()) - RoomLibrary.TILE,
                Math.max(gridMin.getX(), gridMax.getX()) + RoomLibrary.TILE,
                Math.max(gridMin.getZ(), gridMax.getZ()) + RoomLibrary.TILE);
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
            var origin = DungeonLayout.cellCenter(centre * DungeonLayout.GRID + centre);
            // killer560 (2026-09-28): "when I go to make a new room have it wipe everything so the only stuff
            // on the map is the current room." The whole grid, not a margin round the new room - a margin
            // leaves the last room still standing wherever it was, and a single-room test with someone else's
            // room over the horizon is not a single-room test.
            wipeWholeGrid(level);
            SimBuildQueue.submit(level, room, centre, centre, 0);
            // After the paste, so the ring it inspects is the room's real wall - before it, every column
            // would still be air and the whole perimeter would come out diamond.
            SimBuildQueue.submitSeal(level, origin.getX() - room.sizeX / 2, origin.getZ() - room.sizeZ / 2,
                    origin.getX() + room.sizeX / 2, origin.getZ() + room.sizeZ / 2);
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
            wipeWholeGrid(level);
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
