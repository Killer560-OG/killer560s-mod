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

    /** QUOI {@code canPath}: on ground, not in a maze/boulder room, not past the trap's start line. */
    public static boolean canPath(DungeonLayout layout) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.onGround()) {
            return false;
        }
        int room = layout.currentRoom();
        if (room < 0) {
            return true;
        }
        String name = layout.name(room);
        if (name.contains("Maze") || name.contains("Boulder")) {
            return false;
        }
        if (name.contains("Trap")) {
            int[] cr = layout.clayRotation(room);
            if (cr != null && RoomDatabase.toRelativeCoord(player.blockPosition(), cr[0], cr[1], cr[2]).z >= 0) {
                return false;
            }
        }
        return true;
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
        if (!canPath(layout)) {
            return false;
        }
        BlockPos goal = layout.currentRoom() < 0 ? null : DungeonMapPathfinder.getDoorPos(layout, layout.currentRoom(), door);
        if (goal == null) {
            ModChat.send(ClearExecutor.CHAT, ModChat.bad("Could not find door coordinates"));
            return false;
        }
        Runnable arrival = faceOnArrival ? () -> faceDoor(door) : null;
        ClearExecutor.etherPath(goal, arrival);
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
     * QUOI {@code pathToRoom}. Type 0: the room's override spot (by name, or by tile core) when the rotation is known,
     * else the etherwarpable block nearest the tile centre. Type 1 (route start ring) falls back to type 0.
     */
    public static boolean pathToRoom(DungeonLayout layout, int room, int tileIdx, int type) {
        if (!canPath(layout) || (type != 0 && type != 1) || room < 0) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        String name = layout.name(room);
        int[] cr = layout.clayRotation(room);
        int[] override = ROOM_OVERRIDES.get(name);
        if (override == null && CORE_OVERRIDES.containsKey(name) && client.level != null) {
            BlockPos c = DungeonLayout.cellCenter(tileIdx);
            override = CORE_OVERRIDES.get(name).get(RoomDatabase.getCore(client.level, c.getX(), c.getZ()));
        }
        BlockPos goal = null;
        if (override != null && cr != null) {
            RoomEntry.Pos rel = new RoomEntry.Pos();
            rel.x = override[0];
            rel.y = override[1];
            rel.z = override[2];
            // A database y is a HYPIXEL height; the sim shifts the whole floor, so it has to move with it
            // or the override points at air a hundred blocks above the room.
            goal = RoomDatabase.toRealCoord(rel, cr[0], cr[1], cr[2])
                    .above(DungeonLayout.simYOffset());
            // THE OVERRIDE IS A PREFERENCE, NOT A REQUIREMENT. killer560 (2026-10-01): "it doesn't need to go
            // the exact spot that I click instead it just needs to go to that room [...] It can choose anywhere
            // in that room whatever is fastest." An override that is not standable - the room's own geometry
            // differs by a block, or the sim pasted something into that cell - used to fail the whole press
            // ("Couldn't find goal position"), because findDungeonPath refuses a goal it cannot warp onto.
            if (!TeleportUtils.etherwarpable(goal) || !TeleportUtils.underCover(goal)) {
                LOGGER.info("[Path] {}'s recorded spot {} is not standable - using the nearest"
                        + " standable block in the clicked tile instead", name, goal);
                goal = null;
            }
        }
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
