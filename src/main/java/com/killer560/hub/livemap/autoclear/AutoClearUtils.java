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
            goal = RoomDatabase.toRealCoord(rel, cr[0], cr[1], cr[2]);
        }
        if (goal == null) {
            goal = TeleportUtils.nearestEtherwarpable(DungeonLayout.cellCenter(tileIdx));
        }
        if (goal == null) {
            ModChat.send(ClearExecutor.CHAT, ModChat.bad("Couldn't find goal position in "), ModChat.value(name));
            return false;
        }
        ClearExecutor.etherPath(goal, null);
        return true;
    }
}
