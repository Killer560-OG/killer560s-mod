package com.killer560.hub.livemap.autoclear;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Port of QUOI's {@code AutoClearUtils} pieces InteractiveMap uses: {@code canPath}, {@code getLockedDoor},
 * {@code pathToDoor}, {@code pathToRoom} (tile goals with QUOI's room/core overrides). {@code pathToRoom} type 1
 * (Auto Routes start ring) has no route data here - Auto Routes is out of scope - so it falls back to the tile goal.
 */
public final class AutoClearUtils {

    private static final org.slf4j.Logger LOGGER =
            com.killer560.hub.util.ModLog.get("killer560smod-interactivemap");

    private static final Map<String, int[]> ROOM_OVERRIDES = Map.ofEntries(
            Map.entry("Creeper Beams", new int[]{15, 68, 5}),
            Map.entry("Three Weirdos", new int[]{15, 68, 22}),
            Map.entry("Water Board", new int[]{15, 58, 9}),
            Map.entry("Ice Path", new int[]{10, 67, 8}),
            Map.entry("Tic Tac Toe", new int[]{11, 68, 16}),
            Map.entry("Ice Fill", new int[]{15, 69, 7}),
            Map.entry("Quiz", new int[]{15, 68, 5}),
            Map.entry("Boulder", new int[]{15, 68, -2}),
            Map.entry("Teleport Maze", new int[]{15, 68, -2}),
            Map.entry("Old Trap", new int[]{15, 68, -2}),
            Map.entry("New Trap", new int[]{15, 68, -2}),
            Map.entry("Cages", new int[]{15, 64, 16}));

    private static final Map<String, Map<Integer, int[]>> CORE_OVERRIDES = Map.of(
            "Gold", Map.of(35550104, new int[]{5, 68, 15}, 992885012, new int[]{55, 68, 15}),
            "Layers", Map.of(161195688, new int[]{53, 68, 53}),
            "Mage", Map.of(925853313, new int[]{15, 75, 15}),
            "Deathmite", Map.of(706341009, new int[]{5, 68, 15}),
            "Dragon", Map.of(-1334473473, new int[]{15, 68, 18}));

    /** How far (squared) a stand-in for a room spot that is not a full landing may be from it. */
    private static final double NEAR_OVERRIDE_SQ = 9.0;

    private AutoClearUtils() {
    }

    /**
     * The room-relative standing spot {@link #pathToRoom} uses for this room's own tile core (by name), e.g.
     * {@code {15, 68, -2}} for Boulder / Teleport Maze - the doorway-side spot just outside the puzzle floor,
     * not the puzzle interior. Exposed read-only for {@code autopuzzles} (killer560, 2026-09-27: Auto Boulder /
     * Auto Teleport Maze walking back out to etherwarp again re-uses this exact spot rather than a second,
     * separately-guessed coordinate for "the exit").
     * @return a copy of the override, or null if this room has none (a plain floor room needs no special anchor).
     */
    public static int[] roomOverride(String roomName) {
        int[] found = ROOM_OVERRIDES.get(roomName);
        return found == null ? null : found.clone();
    }

    /** The room automation may path out of although its name would refuse it, and the level that permit is for. */
    private static String leavePermit;
    private static Object leavePermitLevel;

    /**
     * Lets {@link #canPath} path out of {@code roomName} - a maze, Boulder or trap room - because the work that brought
     * him there is over (his route there ended, the puzzle's auto finished or was given up on). Dungeon Autopilot / Auto
     * Secret call this after each thing they do; the permit is for this world only and names one room. Client thread.
     */
    public static void permitLeave(String roomName) {
        Minecraft client = Minecraft.getInstance();
        leavePermit = roomName;
        leavePermitLevel = client == null ? null : client.level;
    }

    /**
     * QUOI {@code canPath}: on ground, not in a maze or boulder room, never inside a trap room. Maze and Boulder refuse by
     * room NAME while the puzzle is being done, and stop refusing once it is done: cleared on the map, finished or failed
     * on the tab list, or a {@link #permitLeave} for it (the puzzle that took him in there has ended) - without that the
     * Teleport Maze's end pad stranded Dungeon Autopilot. A trap room always refuses: no ability works in one.
     */
    public static boolean canPath(DungeonLayout layout) {
        return canPath(layout, false);
    }

    /**
     * {@link #canPath}, with {@code landing}: also while he is settling onto the block an etherwarp just put him on - an
     * etherwarp lands him 0.05 above the block and he is "on the ground" only a tick or two later. A planned-ahead trip
     * the tick a route ends on its own landing (Auto Secret) need not wait for that; its first warp is aimed again from
     * exactly where he is (ClearExecutor.reaimFirstHop).
     */
    public static boolean canPath(DungeonLayout layout, boolean landing) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.onGround() && !(landing && settlingOnLanding(player))) {
            return false;
        }
        int room = layout.currentRoom();
        if (room < 0) {
            return true;
        }
        String name = layout.name(room);
        // killer560 (2026-10-06): "it shouldn't be able to etherwarp in trap - just to enter it, but not once it is
        // actually in the room". Abilities do not work in one (docs/SIM.md), so nothing starts a path inside it, done or
        // not; his Auto Routes trap config walks him out.
        if (isTrap(layout, room)) {
            return false;
        }
        boolean restricted = name.contains("Maze") || name.contains("Boulder");
        if (restricted && roomDone(layout, room, name)) {
            return true;
        }
        if (name.contains("Maze") || name.contains("Boulder")) {
            return false;
        }
        // No by-NAME trap rule after isTrap: QUOI's "Trap in the name, back half refuses" outlived the rule above, and
        // the only room it still caught was Arrow Trap - a NORMAL room - so the map did nothing while he stood in its
        // half (killer560, 2026-10-13: "interactive map does not work if i am in arrow trap").
        return true;
    }

    /** At most 0.1 above a block he can stand on and not rising: an etherwarp landing he has not dropped onto yet. */
    private static boolean settlingOnLanding(LocalPlayer player) {
        if (player.getDeltaMovement().y > 0.01) {
            return false;
        }
        net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(player.getX(), player.getY() - 0.1,
                player.getZ());
        double top = below.getY() + 1.0;
        return player.getY() - top >= -0.001 && player.getY() - top <= 0.1 && TeleportUtils.landable(below);
    }

    /** A trap room: the room database says TRAP, or (a room it does not know) the name says Trap. */
    public static boolean isTrap(DungeonLayout layout, int room) {
        if (room < 0) {
            return false;
        }
        var entry = layout.entry(room);
        if (entry != null && entry.type != null) {
            return "TRAP".equalsIgnoreCase(entry.type);
        }
        String name = layout.name(room);
        return name != null && name.contains("Trap");
    }

    private static boolean roomDone(DungeonLayout layout, int room, String name) {
        Minecraft client = Minecraft.getInstance();
        if (name.equals(leavePermit) && client != null && client.level == leavePermitLevel) {
            return true;
        }
        int[] tiles = layout.tiles(room);
        if (tiles != null) {
            for (int t : tiles) {
                if (com.killer560.hub.livemap.LiveMapFeature.isRoomCleared(t)) {
                    return true;
                }
            }
        }
        char tab = com.killer560.hub.scorecalc.ScoreCalculatorFeature.puzzleState(name);
        return tab == '✔' || tab == '✖';
    }

    /** QUOI {@code getLockedDoor}: nearest locked wither/blood door by room distance (locked doors passable). */
    public static int getLockedDoor(DungeonLayout layout) {
        int room = layout.currentRoom();
        if (room < 0) {
            return -1;
        }
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int idx = 0; idx < 121; idx++) {
            int type = layout.doorType(idx);
            if (!layout.isLocked(idx) || (type != DungeonLayout.DOOR_WITHER && type != DungeonLayout.DOOR_BLOOD)) {
                continue;
            }
            int d = DungeonMapPathfinder.getDistToDoor(layout, room, idx, true);
            if (best < 0 || d < bestDist) {
                best = idx;
                bestDist = d;
            }
        }
        return best;
    }

    /**
     * The nearest door out of the room you are standing in, whatever room that is.
     *
     * <p>Written so "walk out of the room when it is done" needs no per-room coordinate. The alternative was a
     * hand-measured exit for every puzzle room, and there are only two of those in {@link #ROOM_OVERRIDES} -
     * inventing the rest is how an automation walks you into a wall. The live map already knows where the doors
     * are: {@link DungeonMapPathfinder#getDoorPos} derives an approach position from the layout, and it stands
     * two blocks back on the reachable side when the door is locked, so a wither door does not become a
     * face-plant.
     *
     * <p>Unlike {@link #getLockedDoor} this does not care what kind of door it is, only that it is the closest
     * one - the goal is to be out of the room, not to open anything.
     *
     * @return a door index, or -1 when the map has no usable layout yet (do not walk on a -1)
     */
    public static int nearestDoorOut(DungeonLayout layout) {
        int room = layout.currentRoom();
        if (room < 0) {
            return -1;
        }
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int idx = 0; idx < 121; idx++) {
            if (!layout.isDoor(idx) || layout.doorType(idx) == DungeonLayout.DOOR_NONE) {
                continue;
            }
            int d = DungeonMapPathfinder.getDistToDoor(layout, room, idx, true);
            if (d < bestDist) {
                bestDist = d;
                best = idx;
            }
        }
        return best;
    }

    /** QUOI {@code pathToDoor}. @return false when pathing couldn't start. */
    public static boolean pathToDoor(DungeonLayout layout, int door, boolean faceOnArrival) {
        if (!canPath(layout, true)) {
            return false;
        }
        // An open door's goal is the doorway itself (its floor block); a locked one's the spot in front of it on his side.
        BlockPos goal = !layout.isLocked(door) || layout.currentRoom() >= 0
                ? DungeonMapPathfinder.getDoorPos(layout, layout.currentRoom(), door) : null;
        if (goal != null && !TeleportUtils.landable(goal)) {
            // Not a block to land on (a slab, stairs, a missing floor): the nearest one that is, per the landing rules.
            goal = TeleportUtils.nearestEtherwarpable(goal);
        }
        if (goal == null) {
            ModChat.send(ClearExecutor.CHAT, ModChat.bad("Could not find door coordinates"));
            return false;
        }
        // Face Door on Arrival turns the camera only for a door that needs aiming at to open - wither and blood. An
        // ordinary doorway leaves his camera exactly as it was (killer560, 2026-10-12: "if i teleport to a door that
        // isnt a wither or blood door then it shouldnt snap my camera").
        int type = layout.doorType(door);
        boolean needsAim = type == DungeonLayout.DOOR_WITHER || type == DungeonLayout.DOOR_BLOOD;
        Runnable arrival = faceOnArrival && needsAim ? () -> faceDoor(door) : null;
        ClearExecutor.etherPath(goal, arrival);
        return true;
    }

    /**
     * A press on the strip joining two tiles of {@code room}: the landing at that spot (the column under the cursor,
     * the block nearest the floor), or the nearest one in the room within a few blocks - never a tile's centre.
     * killer560 (2026-10-12): "since there is a room there that is actual tangible space".
     */
    public static boolean pathToSpot(DungeonLayout layout, int room, int tileIdx, double x, double z) {
        if (!canPath(layout) || room < 0) {
            return false;
        }
        int floor = DungeonLayout.cellCenter(tileIdx).getY() - 1;
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        BlockPos goal = null;
        double best = Double.MAX_VALUE;
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int r = 0; r <= 4 && goal == null; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r || layout.roomAtWorld(bx + dx + 0.5, bz + dz + 0.5) != room) {
                        continue;
                    }
                    for (int dy = -8; dy <= 8; dy++) {
                        mut.set(bx + dx, floor + dy, bz + dz);
                        if (!TeleportUtils.landable(mut) || !TeleportUtils.underCover(mut)) {
                            continue;
                        }
                        double d = (bx + dx + 0.5 - x) * (bx + dx + 0.5 - x) + (bz + dz + 0.5 - z) * (bz + dz + 0.5 - z)
                                + dy * dy * 0.01;
                        if (d < best) {
                            best = d;
                            goal = mut.immutable();
                        }
                    }
                }
            }
        }
        if (goal == null) {
            ModChat.send(ClearExecutor.CHAT, ModChat.bad("Nothing to land on there in "), ModChat.value(layout.name(room)));
            return false;
        }
        LOGGER.info("[Path] map press on {}'s strip at ({}, {}): landing on {}", layout.name(room),
                String.format(java.util.Locale.ROOT, "%.1f", x), String.format(java.util.Locale.ROOT, "%.1f", z), goal);
        ClearExecutor.etherPathExact(goal, null);
        return true;
    }

    public static void faceDoor(int door) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Vec3 eye = new Vec3(player.getX(), player.getY() + (player.isCrouching() ? 1.27 : 1.62), player.getZ());
        TeleportUtils.Rotation dir = TeleportUtils.getDirection(eye, DungeonLayout.doorCentre(door));
        // Rotation 360 rule: move by the wrapped delta from the current running yaw, never set a wrapped yaw.
        float yaw = player.getYRot() + Mth.wrapDegrees(dir.yaw() - player.getYRot());
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Mth.clamp(dir.pitch(), -90f, 90f));
    }

    /**
     * The room's recorded standing spot {@link #pathToRoom} goes to (by name, or by tile core), made a full landing (a
     * block beside it within 3), or null when the room has none or it is not standable - the press then goes to the
     * whole tile. Client thread. {@code quiet}: no log lines (TripCache resolving goals ahead of any press).
     */
    public static BlockPos overrideGoal(DungeonLayout layout, int room, int tileIdx, boolean quiet) {
        Minecraft client = Minecraft.getInstance();
        String name = layout.name(room);
        int[] cr = layout.clayRotation(room);
        int[] override = ROOM_OVERRIDES.get(name);
        if (override == null && CORE_OVERRIDES.containsKey(name) && client.level != null) {
            BlockPos c = DungeonLayout.cellCenter(tileIdx);
            override = CORE_OVERRIDES.get(name).get(RoomDatabase.getCore(client.level, c.getX(), c.getZ()));
        }
        if (override == null || cr == null) {
            return null;
        }
        RoomEntry.Pos rel = new RoomEntry.Pos();
        rel.x = override[0];
        rel.y = override[1];
        rel.z = override[2];
        // A database y is a HYPIXEL height; the sim shifts the whole floor, so it has to move with it
        // or the override points at air a hundred blocks above the room.
        BlockPos goal = RoomDatabase.toRealCoord(rel, cr[0], cr[1], cr[2]).above(DungeonLayout.simYOffset());
        // THE OVERRIDE IS A PREFERENCE, NOT A REQUIREMENT. killer560 (2026-10-01): "it doesn't need to go
        // the exact spot that I click instead it just needs to go to that room [...] It can choose anywhere
        // in that room whatever is fastest." An override that is not standable - the room's own geometry
        // differs by a block, or the sim pasted something into that cell - used to fail the whole press
        // ("Couldn't find goal position"), because findDungeonPath refuses a goal it cannot warp onto.
        if (!TeleportUtils.landable(goal) || !TeleportUtils.underCover(goal)) {
            // A full landing a step or two away keeps the trip where the spot means it to go. Water Board's
            // (15, 58, 9) is a bottom stone-brick stair (no full top, so not a landing): falling back to the
            // whole tile landed him up on the doorway at y 69, and Auto Water then needed two more warps down to
            // the lever floor (killer560's run, 2026-10-15 16:11:39-41; the stair read in testkit
            // 93-solve-waterboard-timing).
            BlockPos near = TeleportUtils.nearestEtherwarpable(goal);
            if (near != null && near.distSqr(goal) <= NEAR_OVERRIDE_SQ) {
                if (!quiet) {
                    LOGGER.info("[Path] {}'s recorded spot {} is not a full landing - using {} beside it", name,
                            goal.toShortString(), near.toShortString());
                }
                return near;
            }
            if (!quiet) {
                LOGGER.info("[Path] {}'s recorded spot {} is not standable - using the nearest"
                        + " standable block in the clicked tile instead", name, goal);
            }
            return null;
        }
        return goal;
    }

    /**
     * QUOI {@code pathToRoom}. Type 0: the room's override spot (by name, or by tile core) when the rotation is known,
     * else the etherwarpable block nearest the tile centre. Type 1 (route start ring) falls back to type 0.
     */
    public static boolean pathToRoom(DungeonLayout layout, int room, int tileIdx, int type) {
        if (!canPath(layout, true) || (type != 0 && type != 1) || room < 0) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        String name = layout.name(room);
        BlockPos goal = overrideGoal(layout, room, tileIdx, false);
        boolean wholeTile = false;
        if (goal == null && client.player != null) {
            // INSIDE THE CLICKED TILE, nearest to him - see TeleportUtils.etherwarpableInTile for why the old
            // 25-block sphere around the tile centre was the wrong search. Falls back to that sphere only when
            // the tile holds no standable block at all, which is a room the map should not have offered.
            BlockPos centre = DungeonLayout.cellCenter(tileIdx);
            goal = TeleportUtils.etherwarpableInTile(centre, client.player.position());
            if (goal == null) {
                goal = TeleportUtils.nearestEtherwarpable(centre);
            } else {
                wholeTile = true;
            }
        }
        if (goal == null) {
            ModChat.send(ClearExecutor.CHAT, ModChat.bad("Couldn't find goal position in "), ModChat.value(name));
            return false;
        }
        if (wholeTile) {
            // "It can choose anywhere in that room whatever is fastest": the planner takes the whole tile and
            // lands wherever the fewest warps do; the block above is only its fallback.
            ClearExecutor.etherPathToTile(goal, tileIdx, null);
        } else {
            ClearExecutor.etherPath(goal, null);
        }
        return true;
    }
}
