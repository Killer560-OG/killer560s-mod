package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.util.ModChat;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Locale;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.compat.McCompat;

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

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

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
                                mc.execute(() -> mc.setScreenAndShow(new SimMenuScreen(McCompat.screen(mc))));
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
    /** Forgets the door /start opens, so a new map cannot inherit the last one's. */
    public static void clearEntranceDoor() {
        entranceDoorPos = null;
    }

    public static void build(Minecraft client, String code) {
        // On a real server getSingleplayerServer() is null, and the branch below reads that as "no world yet"
        // and opens one - so /simbuild code <x> typed on Hypixel tried to tear him out into a sim world.
        // SimWorld.open now refuses as well; this says so before any of the work starts.
        if (!SimState.canOpen(client)) {
            ModChat.send("Sim", ModChat.text("The dungeon sim only runs in its own world."));
            return;
        }
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
            // Cleared HERE, not in wipeWholeGrid: the wipe is queued after this loop has already recorded
            // every room, so clearing there would throw away the floor that was just indexed.
            // The same per-map state SimWorld.open drops when it opens a fresh sim.
            //
            // /simbuild and the menu's Change Room build INTO an existing sim world, so they never went near
            // SimWorld.open and never reached its reset. The mimic candidate list kept the previous floor's
            // chests - so the mimic was usually picked at a coordinate on a floor that no longer existed, and
            // the renderer drew amber boxes around air - and SimDoors kept the old floor's door blocks, so
            // the sidebar's door count only ever grew and a right-click could still open a door inside a wall.
            SimMimic.reset();
            SimDoors.clear();
            SimBuilder.clearEntranceDoor();
            SimRoomIndex.clear();
            // Clay corner and rotation per NAME TABLE index, for the live map. Collected here because this is
            // the loop that knows both - SimRoomIndex records placements in flood-fill order, which is not
            // the order the name table is in, and the map indexes cells by name-table index.
            final int[][] clayByRoom = new int[decoded.nameTable().length][];
            // How high the whole floor sits, decided once from the rooms that are in it - and BEFORE any of
            // them is pasted, because every coordinate below is derived from it.
            int shift = SimAltitude.plan(level, decoded.nameTable());
            LOGGER.info("Sim altitude: floor shifted {} block(s), occupying y {}..{}",
                    shift, SimAltitude.minWorldY(), SimAltitude.maxWorldY());
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
                // usable(), not merely present: a room captured at an older footprint is a different SIZE from
                // the one the layout planned for, so pasting it runs over its neighbour. Treated as missing,
                // which is already handled and reported, rather than pasted wrongly and reported as fine.
                if (room != null && !room.usable()) {
                    room = null;
                }
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
                // So Secret Waypoints knows this room is here - see SimRoomIndex.
                SimRoomIndex.add(room, gx, gz, rot);
                if (nameIndex >= 0 && nameIndex < clayByRoom.length) {
                    int[] clay = SimSecrets.clayCorner(room, gx, gz, rot);
                    clayByRoom[nameIndex] = new int[]{clay[0], clay[1], rot};
                }
                if (firstPlacedCell[0] < 0) {
                    firstPlacedCell[0] = anchorCell;
                }
                for (int fc : footprint) {
                    if ("Entrance".equalsIgnoreCase(name)) {
                        entranceCell[0] = anchorCell;
                    }
                }
                roomsPlaced[0]++;
                if (SimMimic.roomEligible(name)) {
                    collectChests(level, gx, gz, room, rot);
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
            // The door out of the green room, for /start.
            entranceDoorPos = null;
            for (int cell = 0; cell < decoded.cellDoor().length; cell++) {
                if (decoded.cellDoor()[cell] == DungeonLayout.DOOR_ENTRANCE) {
                    // Shifted with the floor, or the lookup in SimDoors' block index misses: the gate's
                    // blocks were registered at the y the carve found, which moves with the map.
                    entranceDoorPos = DungeonLayout.cellCenter(cell).above(SimAltitude.offset());
                    break;
                }
            }
            // Every doorway, queued to be cut AFTER the rooms are pasted - cutting first would simply be
            // pasted over, which is the same ordering mistake the secrets had.
            final java.util.List<int[]> doorCells = new java.util.ArrayList<>();
            for (int cell = 0; cell < decoded.cellDoor().length; cell++) {
                if (decoded.cellDoor()[cell] == DungeonLayout.DOOR_NONE) {
                    continue;
                }
                int gx = cell % DungeonLayout.GRID;
                int gz = cell / DungeonLayout.GRID;
                // A door cell is odd on exactly one axis; that axis is the one the rooms sit apart on.
                boolean alongX = (gx % 2) == 1;
                doorCells.add(new int[]{cell, alongX ? 1 : 0, decoded.cellDoor()[cell]});
            }
            // Doorways this floor does not use, bricked up.
            //
            // A room's doorways are part of its captured blocks, so one that faces the edge of the map or a
            // neighbour's blank wall is a hole into the void - about five of them on a floor. Worked out from
            // the same measured door masks the layout was built from, so the seal and the layout can never
            // disagree about where a doorway is.
            final java.util.List<int[]> sealCells = new java.util.ArrayList<>();
            for (int cell = 0; cell < decoded.cellRoom().length; cell++) {
                int nameIndex = decoded.cellRoom()[cell];
                int gx = cell % DungeonLayout.GRID;
                int gz = cell / DungeonLayout.GRID;
                if (nameIndex < 0 || gx % 2 != 0 || gz % 2 != 0) {
                    continue;
                }
                RoomDoors.Mask mask = RoomDoors.of(decoded.nameTable()[nameIndex]);
                if (mask == null) {
                    continue;
                }
                mask = RoomDoors.rotate(mask, decoded.cellRotation()[cell]);
                for (int packed : mask.edges()) {
                    int side = RoomDoors.sideOf(packed);
                    // Only from the cell this doorway is actually in, or a 2x2 would seal from all four of
                    // its cells and brick up its own real doors.
                    int[] anchorCellXz = anchorOf(decoded.cellRoom(), cell, nameIndex);
                    int[] door = RoomDoors.doorCell(anchorCellXz[0], anchorCellXz[1],
                            mask.tilesX(), mask.tilesZ(), side, RoomDoors.indexOf(packed));
                    if (door[0] * 2 != gx || door[1] * 2 != gz) {
                        continue;
                    }
                    int cx = gx + RoomDoors.DX[side];
                    int cz = gz + RoomDoors.DZ[side];
                    boolean inGrid = cx >= 0 && cz >= 0 && cx < DungeonLayout.GRID && cz < DungeonLayout.GRID;
                    if (inGrid && decoded.cellDoor()[cz * DungeonLayout.GRID + cx] != DungeonLayout.DOOR_NONE) {
                        continue;   // a real door goes here
                    }
                    var at = DungeonLayout.cellCenter(cx, cz);
                    sealCells.add(new int[]{at.getX(), at.getZ(), RoomDoors.DX[side] != 0 ? 1 : 0});
                }
            }
            // AUDIT: is every door this floor carves actually backed by a doorway in BOTH rooms?
            //
            // This is the measurement for the intermittent unwalkable doorway. Scenario 81 reproduced one
            // between "Crypt" and "Mines" where the opening was completely clear - every column air at foot
            // and head height - and the floor on the approach side was two blocks lower, so he walked in and
            // fell into a trench. The carve only ever removes the air ABOVE a floor it searches for; it never
            // lays one. So a carve at a spot where a room has no doorway punches through the wall into
            // whatever is behind it, and what is behind can be a drop.
            //
            // The room-wide capture heights do NOT explain that case - both those rooms measure at y69 - so
            // this checks the other candidate directly rather than by reading the planner. Built from the same
            // rotated masks the seal above uses, so the audit and the layout cannot disagree about where a
            // doorway is.
            //
            // A warning, not a refusal: a floor with one awkward doorway is still worth practising in, and
            // failing the build would turn an occasional annoyance into a dead feature.
            if (com.killer560.hub.BuildVariant.DEV_TOOLS) {
                java.util.Map<Long, java.util.List<String>> backing = new java.util.HashMap<>();
                for (int cell = 0; cell < decoded.cellRoom().length; cell++) {
                    int nameIndex = decoded.cellRoom()[cell];
                    int gx = cell % DungeonLayout.GRID;
                    int gz = cell / DungeonLayout.GRID;
                    if (nameIndex < 0 || gx % 2 != 0 || gz % 2 != 0) {
                        continue;
                    }
                    RoomDoors.Mask mask = RoomDoors.of(decoded.nameTable()[nameIndex]);
                    if (mask == null) {
                        continue;
                    }
                    mask = RoomDoors.rotate(mask, decoded.cellRotation()[cell]);
                    int[] anchorCellXz = anchorOf(decoded.cellRoom(), cell, nameIndex);
                    for (int packed : mask.edges()) {
                        int side = RoomDoors.sideOf(packed);
                        int[] door = RoomDoors.doorCell(anchorCellXz[0], anchorCellXz[1],
                                mask.tilesX(), mask.tilesZ(), side, RoomDoors.indexOf(packed));
                        if (door[0] * 2 != gx || door[1] * 2 != gz) {
                            continue;
                        }
                        int cx = gx + RoomDoors.DX[side];
                        int cz = gz + RoomDoors.DZ[side];
                        backing.computeIfAbsent((long) cz * DungeonLayout.GRID + cx,
                                k -> new java.util.ArrayList<>()).add(decoded.nameTable()[nameIndex]);
                    }
                }
                int unbacked = 0;
                for (int[] d : doorCells) {
                    int gx = d[0] % DungeonLayout.GRID;
                    int gz = d[0] / DungeonLayout.GRID;
                    java.util.List<String> from = backing.getOrDefault(
                            (long) gz * DungeonLayout.GRID + gx, java.util.List.of());
                    if (from.size() >= 2) {
                        continue;
                    }
                    unbacked++;
                    // NAME both rooms, because the one that is missing its doorway is the one to look at, and
                    // which of the two it is cannot be worked out from the cell number afterwards.
                    int ax = (gx % 2 == 1) ? gx - 1 : gx;
                    int az = (gz % 2 == 1) ? gz - 1 : gz;
                    int bx = (gx % 2 == 1) ? gx + 1 : gx;
                    int bz = (gz % 2 == 1) ? gz + 1 : gz;
                    LOGGER.warn("Sim doors: the door at cell {} ({},{}) is backed by {} of its two rooms {} - "
                                    + "between \"{}\" and \"{}\". The carve will punch through the wall of "
                                    + "whichever has no doorway there, and whatever is behind it becomes the "
                                    + "floor - which is how a doorway ends up clear but unwalkable.",
                            d[0], gx, gz, from.size(), from,
                            roomNameAtCell(decoded, ax, az), roomNameAtCell(decoded, bx, bz));
                }
                if (unbacked == 0) {
                    LOGGER.info("Sim doors: all {} door(s) are backed by a measured doorway in both rooms",
                            doorCells.size());
                }
            }

            // Its own list, run BEFORE the secrets - see the whenDone callback below.
            final java.util.List<Runnable> doorWork = new java.util.ArrayList<>();
            doorWork.add(() -> {
                SimDoors.CHESTS_CARVED_AWAY = 0;
                SimDoors.FLOORED = 0;
                for (int[] d : doorCells) {
                    SimDoors.carveDoorway(level, DungeonLayout.cellCenter(d[0]), d[1] == 1, d[2]);
                }
                for (int[] sc : sealCells) {
                    SimDoors.sealDoorway(level, new net.minecraft.core.BlockPos(sc[0], 70, sc[1]), sc[2] == 1);
                }
                LOGGER.info("Sim doors: {} carved, {} sealed, {} secret chest(s) removed by the carve, "
                                + "{} block(s) of floor laid where a doorway had none",
                        doorCells.size(), sealCells.size(), SimDoors.CHESTS_CARVED_AWAY, SimDoors.FLOORED);
            });
            final int firstCell = entranceCell[0] >= 0 ? entranceCell[0] : firstPlacedCell[0];
            SimBuildQueue.whenDone(() -> {
                // Doorways FIRST, then the secrets.
                //
                // It was the other way round, and a carve is 3 wide by 4 high by 7 deep of air - so a secret
                // chest that happened to sit in a doorway was placed and then deleted. It cost one chest on
                // about one floor in three, silently, and the only reason it was ever noticed is that the
                // build now audits its own secret chests. Cutting the hole before the secrets go in cannot
                // destroy one; the reverse can.
                for (Runnable r : doorWork) {
                    r.run();
                }
                for (Runnable r : afterBuild) {
                    r.run();
                }
                int still = 0;
                StringBuilder gone = new StringBuilder();
                for (var cp : SimSecrets.PLACED_CHESTS) {
                    var st = level.getBlockState(cp);
                    if (st.is(net.minecraft.world.level.block.Blocks.CHEST)) {
                        still++;
                    } else if (gone.length() < 300) {
                        gone.append(cp.toShortString()).append('=')
                                .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                        .getKey(st.getBlock())).append(' ');
                    }
                }
                // A secret chest that does not survive the build is a secret he can never find, and it has
                // happened twice - so the build checks its own work rather than waiting for a scenario to
                // notice. Cheap: it is one block read per secret chest, about thirty a floor.
                // Two secrets on ONE block is a secret he can never find, and checking each position on its
                // own cannot see it: both reads hit the same chest and both pass. The build reported "all 34
                // in place" while the floor held 33, which is how this was found.
                java.util.Set<net.minecraft.core.BlockPos> distinct =
                        new java.util.HashSet<>(SimSecrets.PLACED_CHESTS);
                int collisions = SimSecrets.PLACED_CHESTS.size() - distinct.size();
                if (collisions > 0) {
                    LOGGER.warn("Sim build: {} secret chest(s) share a block with another secret - that many "
                            + "secrets are unreachable, because opening one chest can only count once",
                            collisions);
                }
                if (still < SimSecrets.PLACED_CHESTS.size()) {
                    LOGGER.warn("Sim build: only {} of {} secret chest(s) survived - the rest were replaced "
                            + "by {}", still, SimSecrets.PLACED_CHESTS.size(), gone);
                } else {
                    LOGGER.info("Sim build: all {} secret chest(s) are in place ({} distinct block(s))",
                            still, distinct.size());
                }
                // Said out loud rather than silently dropped: a secret that would have sealed a doorway is
                // skipped, and that is a real chest missing from the floor even though it is the right call.
                if (SimSecrets.chestsInDoorways > 0) {
                    LOGGER.warn("Sim build: {} secret chest(s) skipped because they landed in a carved "
                            + "doorway - a chest there is a door nobody can walk through",
                            SimSecrets.chestsInDoorways);
                }
                // The golden crypts that were already in the rooms, found once the floor is standing.
                // Not every floor has one - only four rooms in the library carry one.
                int princes = SimPrince.scan(level);
                LOGGER.info("Sim prince: {}", princes == 0
                        ? "none on this floor - only some rooms have one"
                        : princes + " found, " + SimPrince.size() + " block(s), first at "
                                + SimPrince.position());
                SimMimic.chooseForMap();
                if (firstCell >= 0) {
                    snapPlayerTo(client, level, firstCell % DungeonLayout.GRID,
                            firstCell / DungeonLayout.GRID);
                }
                client.execute(() -> {
                // The map, on the client thread where LiveMapFeature's arrays live. Without this the live
                // map, the interactive map and every pathfinder that reads the layout are blank in the sim -
                // they all read a grid the world scan can only fill from Hypixel's own markers.
                com.killer560.hub.livemap.LiveMapFeature.publishSimFloor(
                        decoded.cellRoom(), decoded.cellDoor(), decoded.nameTable(), clayByRoom);
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
        // "Not found" is a FLAG, not a negative y.
        //
        // killer560 (2026-09-29): "It isnt teleporting me inside of the starting room either when I create a
        // map." This was it. The sentinel was -1 and the test below was landing < 0 - which was fine while the
        // floor was pasted at its captured heights, and became wrong the moment SimAltitude started dropping
        // the map to the bottom of the void. A bottom-aligned floor occupies y -63..17 (measured in his
        // 17:57:32 build on 2026-09-29), so EVERY standable spot in it has a negative y: the scan found the
        // entrance's floor at -52, the fallback then decided nothing had been found, and he was dropped in at
        // maxWorldY to land on the roof - which is the same symptom as the older "it put me ontop of the room"
        // bug and nothing to do with its cause.
        int landing = 0;
        boolean found = false;
        for (int y = SimAltitude.minWorldY(); y < SimAltitude.maxWorldY() - 2; y++) {
            if (!level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).isAir()
                    && level.getBlockState(new net.minecraft.core.BlockPos(x, y + 1, z)).isAir()
                    && level.getBlockState(new net.minecraft.core.BlockPos(x, y + 2, z)).isAir()) {
                landing = y + 1;
                found = true;
                break;
            }
        }
        if (!found) {
            // Nothing to stand on at the centre - a doorway column, or a room whose middle is a pit. Put him
            // above it rather than inside the floor; falling a few blocks is recoverable, suffocating is not.
            landing = SimAltitude.maxWorldY();
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

    /** The room name at a grid cell, or "(none)", for a log line that has to name both sides of a door. */
    private static String roomNameAtCell(MapCode.Decoded decoded, int gx, int gz) {
        if (gx < 0 || gz < 0 || gx >= DungeonLayout.GRID || gz >= DungeonLayout.GRID) {
            return "(off the grid)";
        }
        int nameIndex = decoded.cellRoom()[gz * DungeonLayout.GRID + gx];
        return nameIndex < 0 ? "(none)" : decoded.nameTable()[nameIndex];
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
        // COLLECT first, discard second. Never both at once.
        //
        // discard() removes the entity from the level's own Int2ObjectLinkedOpenHashMap, which invalidates
        // the iterator being walked - and fastutil does not throw ConcurrentModificationException for that,
        // it throws ArrayIndexOutOfBoundsException from deep inside MapIterator.nextEntry, which looks like
        // anything but the bug it is. killer560 hit it on 2026-09-30: "it got to the point where it said 1
        // room remaining then nothing loaded". The exception killed the build task before it could hand over,
        // so the loading screen simply never came down.
        //
        // Every sim scenario missed this because each one builds a FRESH world with no drops in it, so the
        // loop matched nothing and never removed anything while iterating. It needs a world that has already
        // been played in - which is every world but a test's.
        // The try/catch is the same one clearFloorMobs has, for the same reason: a failure to tidy up must
        // never stop the floor being built. That guard is why the mob clear could not have caused this and
        // the drop clear could.
        try {
            java.util.List<net.minecraft.world.entity.Entity> drops = new java.util.ArrayList<>();
            for (var entity : level.getAllEntities()) {
                if (entity instanceof net.minecraft.world.entity.item.ItemEntity) {
                    drops.add(entity);
                }
            }
            for (var entity : drops) {
                entity.discard();
            }
            if (!drops.isEmpty()) {
                LOGGER.info("Cleared {} floor drop(s) before building", drops.size());
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not clear the sim's floor drops: {}", e.getClass().getSimpleName());
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

    /**
     * The door out of the green room on the map that was just built, in world coordinates.
     *
     * <p>Remembered so {@code /start} knows which door to drop without searching for it. killer560
     * (2026-09-28): "After loading in i should be able to do /start and then it will automatically drop teh
     * door between green room and the room after it."
     */
    private static net.minecraft.core.BlockPos entranceDoorPos;

    /** Where the run's first door is, or null when the map has none. */
    public static net.minecraft.core.BlockPos entranceDoor() {
        return entranceDoorPos;
    }

    /**
     * Removes every mob and armour stand left in the sim world.
     *
     * <p>Only dropped items were cleared before, so a build left the last floor's zombies, skeletons, star
     * name tags and Fel markers standing in the new one's rooms - and because a single-room build never goes
     * through {@code SimWorld.resetPerMapState} either, they accumulated. killer560 (2026-09-29): "wipe any
     * mobs that spawn right now."
     *
     * <p>The player is not a {@code Mob} and is not touched. Everything else in the sim world was put there by
     * a build, so there is nothing here worth keeping.
     */
    private static void clearFloorMobs(ServerLevel level) {
        try {
            java.util.List<net.minecraft.world.entity.Entity> doomed = new java.util.ArrayList<>();
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof net.minecraft.world.entity.Mob
                        || entity instanceof net.minecraft.world.entity.decoration.ArmorStand) {
                    doomed.add(entity);
                }
            }
            for (net.minecraft.world.entity.Entity entity : doomed) {
                entity.discard();
            }
            SimMobs.forget();
        } catch (RuntimeException e) {
            // A failure to tidy up must never stop the floor being built.
            LOGGER.warn("Could not clear the sim's mobs: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * The top-left ROOM cell of the placement a grid cell belongs to.
     *
     * <p>Needed by the doorway sealer: a 2x2's four cells all carry the same room, and a doorway belongs to
     * exactly one of them, so "which cell of this room am I" has to be answered before the doorway's own cell
     * can be worked out.
     */
    private static int[] anchorOf(int[] cellRoom, int cell, int nameIndex) {
        int gx = cell % DungeonLayout.GRID;
        int gz = cell / DungeonLayout.GRID;
        int minX = gx;
        int minZ = gz;
        while (minX - 2 >= 0 && cellRoom[gz * DungeonLayout.GRID + (minX - 2)] == nameIndex) {
            minX -= 2;
        }
        while (minZ - 2 >= 0 && cellRoom[(minZ - 2) * DungeonLayout.GRID + gx] == nameIndex) {
            minZ -= 2;
        }
        return new int[]{minX / 2, minZ / 2};
    }

    private static void wipeWholeGrid(ServerLevel level) {
        clearFloorDrops(level);
        clearFloorMobs(level);
        // Before the level is cleared, so a drop that vanishes with the old floor is not counted
        // as one he picked up.
        SimSecretItems.reset();
        SimSecrets.PLACED_CHESTS.clear();
        SimSecrets.resetAudit();
        SimPrince.reset();
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
     * Finds a room's chests, for the mimic.
     *
     * <p>Read out of the CAPTURED data rather than out of the world, and that is two fixes in one.
     *
     * <p>killer560 (2026-09-28): "I still load in and it freezes and makes me restart." This was the freeze.
     * It walked every position of every room - eighty-eight thousand block reads each, one-point-eight million
     * across a floor - synchronously on the server thread, loading chunks as it went. The build itself had
     * already been cut to 1.3 seconds for nearly three million blocks; this was all the time that was left.
     *
     * <p>It was also wrong. It ran in the loop that QUEUES the pastes, so it read the world before a single
     * block of the room had been placed, and found nothing. The captured palette has the answer without
     * touching the world at all, and without caring when the paste happens.
     */
    private static void collectChests(ServerLevel level, int gridX, int gridZ, RoomLibrary.Room room,
                                      int rotation) {
        // Which palette entries are chests. A palette is a hundred or so strings, so this is nothing.
        java.util.Set<Integer> chestIds = new java.util.HashSet<>();
        for (int i = 0; i < room.palette.size(); i++) {
            String entry = room.palette.get(i);
            if (entry != null && entry.startsWith("minecraft:chest")) {
                chestIds.add(i);
            }
        }
        if (chestIds.isEmpty()) {
            return;
        }
        // The paste's OWN transform, margin and rotation included.
        //
        // This took the tile corner and added the raw captured (x, z), so it was one block off on both axes
        // (the capture starts at the margin corner, one outside the tile) and completely wrong for any room
        // not at rotation 0 - the mimic highlight sat in a different part of the room from the chest, and with
        // rooms now rotated that would be most of them. Sharing rotateLocal with RoomPlacer means the two
        // cannot drift.
        var origin = DungeonLayout.cellCenter(gridZ * DungeonLayout.GRID + gridX);
        int x0 = origin.getX() - RoomLibrary.TILE / 2 - room.margin;
        int z0 = origin.getZ() - RoomLibrary.TILE / 2 - room.margin;
        for (int x = 0; x < room.sizeX; x++) {
            for (int z = 0; z < room.sizeZ; z++) {
                int[] local = RoomPlacer.rotateLocal(x, z, room.sizeX, room.sizeZ, rotation);
                // The room's own band. Walking the global constants indexed a trimmed capture out of
                // bounds and crashed the server on the first floor built.
                for (int y = room.minY; y <= room.maxY; y++) {
                    if (chestIds.contains((int) room.at(x, y, z))) {
                        SimMimic.addCandidate(new net.minecraft.core.BlockPos(
                                x0 + local[0], SimAltitude.toWorld(y), z0 + local[1]));
                    }
                }
            }
        }
    }

    /**
     * The captured starred mobs are deliberately NOT spawned.
     *
     * <p>killer560 (2026-09-29): "do not worry about starred mobs spawning in only mimics princes and crypts.
     * We can do starred mobs much later. Wipe any mobs that spawn right now they are wrong and need reworked
     * anyways."
     *
     * <p>{@code spawnMobsFor} used to put a mob at every position the recorder saw a star name at. Three
     * things were wrong with that and only the first is cheap to fix: the positions were never rotated, so a
     * turned room put its mobs through the walls; they are armour-stand positions rather than mob positions,
     * so they are a tile-and-a-bit off vertically in places; and a one-health no-AI stand-in is not the mob a
     * route is timed against. The capture is still recorded in the room files, so nothing is lost and this
     * comes back when the mobs are done properly.
     *
     * <p>What still spawns: the mimic (one per floor), a crypt's zombie when its wall is blown, and the bats
     * that are genuine secrets. {@link #clearFloorMobs} removes anything else that is left over.
     */
    /**
     * Builds ONE room in the middle of the grid, for drilling a single room.
     *
     * <p>The mode that gets used most while learning a room, which is why the menu offers it directly rather
     * than making him generate a whole map to reach it.
     */
    public static void buildSingleRoom(Minecraft client, String roomName) {
        SimState.setGeneratedFloor(false);
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
        // An EVEN cell. GRID/2 is 5, which is odd, and rooms live on even cells - so a single-room build
        // landed at -105, half a tile off the lattice every grid-keyed feature measures against (the live
        // map, the room scan, secret routes). The room itself looked right, because the paste and the secrets
        // agreed with each other; only anything reading the grid disagreed.
        int centre = (DungeonLayout.GRID / 2) & ~1;
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
            // ALTITUDE FIRST, then the wipe. This was the other way round and it meant Change Room cleared
            // NOTHING.
            //
            // The clear job reads its start y once, in a field initialiser, when submitClear builds it - from
            // SimAltitude.previousMinWorldY(). plan() is what moves the current offset into "previous". Called
            // after the wipe was queued, the job started at the UNSHIFTED y 60 while its end bound, read live,
            // had already become 17 - so the first step saw 60 > 17, called itself done, and the whole
            // previous floor stayed standing with the new room pasted through it. build() has always had this
            // order right; this path did not.
            SimMimic.reset();
            SimDoors.clear();
            SimBuilder.clearEntranceDoor();
            SimRoomIndex.clear();
            SimRoomIndex.add(room, centre, centre, 0);
            SimAltitude.plan(level, new String[]{room.name});
            wipeWholeGrid(level);
            SimBuildQueue.submit(level, room, centre, centre, 0);
            // After the paste, so the ring it inspects is the room's real wall - before it, every column
            // would still be air and the whole perimeter would come out diamond.
            // The room's REAL bounds, which are not centred on the origin for anything bigger than one tile.
            // This sealed origin +- size/2: a 65-wide room spans origin-16 to origin+48, so one diamond wall
            // stood 16 blocks west in the void and the other cut through the room's interior. Same corner the
            // paste uses, so the two cannot disagree.
            int sealX0 = origin.getX() - RoomLibrary.TILE / 2 - RoomLibrary.WALL_MARGIN;
            int sealZ0 = origin.getZ() - RoomLibrary.TILE / 2 - RoomLibrary.WALL_MARGIN;
            SimBuildQueue.submitSeal(level, sealX0, sealZ0,
                    sealX0 + room.sizeX - 1, sealZ0 + room.sizeZ - 1);
            SimBuildQueue.whenDone(() -> {
                // After the geometry, never before: a chest placed first would be overwritten by the paste.
                int secrets = SimSecrets.place(level, room, centre, centre, 0);
                SimMimic.chooseForMap();
                SimScore.reset(Math.max(0, secrets), 1);
                snapPlayerTo(client, level, centre, centre);
                client.execute(() -> SimSecrets.report(roomName, secrets));
                client.execute(() -> {
                    publishSingleRoomMap(room, centre);
                    SimWorld.buildFinished(client, null);
                    ModChat.send("Sim", ModChat.text("Built "), ModChat.value(roomName));
                });
            });
        });
    }

    /**
     * Puts ONE room on the dungeon map, and nothing else.
     *
     * <p>killer560 (2026-09-30): "if i load only a single room make sure it wipes everything else on the map
     * first and the map should only show the room that i loaded not the previous map." The world half of that
     * was already done - {@code wipeWholeGrid} clears the grid before the paste - but the MAP half was not:
     * only {@link #build} ever called {@code LiveMapFeature.publishSimFloor}, so a single-room load left the
     * last floor's twenty-two rooms drawn on the HUD around one room that was the only thing in the world. A
     * map that disagrees with the world that hard is worse than no map, because every pathfinder and the
     * interactive map read the same arrays.
     *
     * <p>Rotation is 0 because {@code buildSingleRoom} pastes at 0; the clay corner comes from the same
     * {@link SimSecrets#clayCorner} call the secrets were placed with, so a waypoint cannot point somewhere
     * the secret is not.
     */
    private static void publishSingleRoomMap(RoomLibrary.Room room, int centre) {
        int cells = DungeonLayout.GRID * DungeonLayout.GRID;
        int[] cellRoom = new int[cells];
        java.util.Arrays.fill(cellRoom, MapCode.NO_ROOM);
        int[] cellDoor = new int[cells];   // DOOR_NONE everywhere: a single room has nothing to connect to
        int tilesX = tilesOf(room.sizeX);
        int tilesZ = tilesOf(room.sizeZ);
        // Every cell the room covers, connectors included - the same convention the generated floor uses, and
        // what lets the map group a multi-tile room back into one room instead of drawing its tiles apart.
        for (int gz = centre; gz <= centre + (tilesZ - 1) * 2 && gz < DungeonLayout.GRID; gz++) {
            for (int gx = centre; gx <= centre + (tilesX - 1) * 2 && gx < DungeonLayout.GRID; gx++) {
                cellRoom[gz * DungeonLayout.GRID + gx] = 0;
            }
        }
        int[] clay = SimSecrets.clayCorner(room, centre, centre, 0);
        com.killer560.hub.livemap.LiveMapFeature.publishSimFloor(cellRoom, cellDoor,
                new String[]{room.name}, new int[][]{{clay[0], clay[1], 0}});
    }

    /** A captured size back to a tile count - {@code RoomLibrary.footprint}'s only inverse. */
    private static int tilesOf(int size) {
        return Math.max(1, (size - 1) / (RoomLibrary.TILE + 1));
    }

    /**
     * Drops the synthetic test room into the middle of the grid.
     *
     * <p>What makes the sim testable before anything has been scanned. Placed at the centre cell so there is
     * room around it, and reported with its coordinates so he can find it rather than hunt.
     */
    public static void buildFlatTest(Minecraft client) {
        if (!SimState.canOpen(client)) {
            ModChat.send("Sim", ModChat.text("The dungeon sim only runs in its own world."));
            return;
        }
        // The flat room is synthetic and has no captured content to measure, so it builds where the
        // capture's own coordinates say - no shift. Reset rather than inherited, or it lands wherever
        // the last real floor happened to sit.
        SimAltitude.reset();
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
        // An EVEN cell. GRID/2 is 5, which is odd, and rooms live on even cells - so a single-room build
        // landed at -105, half a tile off the lattice every grid-keyed feature measures against (the live
        // map, the room scan, secret routes). The room itself looked right, because the paste and the secrets
        // agreed with each other; only anything reading the grid disagreed.
        int centre = (DungeonLayout.GRID / 2) & ~1;
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
