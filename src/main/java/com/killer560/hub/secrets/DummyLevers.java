package com.killer560.hub.secrets;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Levers that are not secrets and open nothing: the one list every lever reader asks, so no reader can disagree with
 * another about which levers are real.
 *
 * <p>killer560 (2026-10-17), standing in Red Blue's iron-bar cage: "in red blue dont have secret aura ever grab these
 * two levers they are dummy levers. Only the one outside of the cage". The two are the wall levers either side of the
 * nook's chest; the real one is the floor lever outside the cage.
 *
 * <p>Positions are room-relative in the room database's own frame (the frame {@code secretCoords} use), resolved through
 * each identified room's clay corner and rotation with {@link RoomDatabase#toRealCoord}, exactly as Secret Waypoints
 * places a chest, plus the sim's floor shift. So the rule holds at every rotation, on Hypixel (rooms from the live map)
 * and in the sim (rooms from {@code SimRoomIndex}, which the sim server can read too).
 *
 * <p>Red Blue, read off the capture ({@code rooms/Red_Blue.json}, capture = database + 1 in x and z, checked on both of
 * the database's chests: capture 58,70,6 and 40,90,27 are database 57,70,5 and 39,90,26): wall levers facing south at
 * capture 57,70,6 and 60,70,6 (database 56,70,5 and 59,70,5) flank the chest at capture 58,70,6; the real lever is the
 * floor lever at capture 55,69,6 (database 54,69,5), which is also the only Red Blue lever in RSM's secret list
 * (center-relative 39,69,-10). No other room's dummy lever is confirmed yet; add one here and every reader has it.
 *
 * <p>Readers: Secret Aura (and so Auto Secret's "still being taken" wait), Secret Waypoints, Secret Triggerbot, Secret
 * Sound, and Auto Routes' {@code AwaitEvents} (its await count and the run memory Auto Secret counts from).
 */
public final class DummyLevers {

    /** Room name -> database-relative positions of its dummy levers. */
    private static final Map<String, List<BlockPos>> BY_ROOM = Map.of(
            "Red Blue", List.of(new BlockPos(56, 70, 5), new BlockPos(59, 70, 5)));

    /** Hypixel's rooms are re-identified while he explores; their list is re-read at most this often. */
    private static final long LIVE_REFRESH_MS = 250;

    private record Snapshot(Set<Long> world, boolean sim, int simVersion, int shift, int generation, long atMs) {
    }

    private static volatile Snapshot snapshot = new Snapshot(Set.of(), false, -1, 0, -1, 0);

    private DummyLevers() {
    }

    /** The database-relative dummy levers of a room, empty when it has none. */
    public static List<BlockPos> relativeFor(String roomName) {
        return roomName == null ? List.of() : BY_ROOM.getOrDefault(roomName, List.of());
    }

    /**
     * Whether a world block is one of a dummy lever's positions in a room that is placed (sim) or identified with its
     * rotation (Hypixel). Client thread on Hypixel; any thread in the sim.
     */
    public static boolean isDummy(BlockPos pos) {
        return pos != null && current().world().contains(pos.asLong());
    }

    /** Every dummy lever's world position, for tests and the log. */
    public static List<BlockPos> worldPositions() {
        List<BlockPos> out = new java.util.ArrayList<>();
        for (long l : current().world()) {
            out.add(BlockPos.of(l));
        }
        out.sort(java.util.Comparator.comparingLong(BlockPos::asLong));
        return out;
    }

    private static Snapshot current() {
        boolean sim = com.killer560.hub.roomsim.SimState.isActive();
        int shift = DungeonLayout.simYOffset();
        Snapshot s = snapshot;
        if (sim) {
            int version = com.killer560.hub.roomsim.SimRoomIndex.version();
            if (s.sim() && s.simVersion() == version && s.shift() == shift) {
                return s;
            }
            Set<Long> world = new HashSet<>();
            for (com.killer560.hub.roomsim.SimRoomIndex.Placed p : com.killer560.hub.roomsim.SimRoomIndex.placedView()) {
                add(world, p.name(), p.clayX(), p.clayZ(), p.rotation(), shift);
            }
            s = new Snapshot(Set.copyOf(world), true, version, shift, -1, 0);
            snapshot = s;
            return s;
        }
        long now = System.currentTimeMillis();
        int generation = LiveMapFeature.resetGeneration();
        if (!s.sim() && s.generation() == generation && now - s.atMs() < LIVE_REFRESH_MS) {
            return s;
        }
        s = new Snapshot(fromLiveMap(0), false, -1, 0, generation, now);
        snapshot = s;
        return s;
    }

    /** The dummies of every room the live map has identified with its rotation (Hypixel's source). Client thread. */
    private static Set<Long> fromLiveMap(int shift) {
        Set<Long> world = new HashSet<>();
        for (int[] room : LiveMapFeature.identifiedRoomsWithRotation()) {
            RoomEntry entry = LiveMapFeature.roomEntryAt(room[0]);
            if (entry != null) {
                add(world, entry.name, room[1], room[2], room[3], shift);
            }
        }
        return Set.copyOf(world);
    }

    /**
     * What the Hypixel path ({@link LiveMapFeature}'s identified rooms) resolves, whatever world this is - for tests,
     * which compare it with the sim's own answer where the live map knows the sim's rooms. Client thread.
     */
    public static List<BlockPos> liveMapPositions() {
        List<BlockPos> out = new java.util.ArrayList<>();
        for (long l : fromLiveMap(DungeonLayout.simYOffset())) {
            out.add(BlockPos.of(l));
        }
        out.sort(java.util.Comparator.comparingLong(BlockPos::asLong));
        return out;
    }

    private static void add(Set<Long> world, String name, int clayX, int clayZ, int rotation, int shift) {
        for (BlockPos rel : relativeFor(name)) {
            RoomEntry.Pos p = new RoomEntry.Pos();
            p.x = rel.getX();
            p.y = rel.getY();
            p.z = rel.getZ();
            world.add(RoomDatabase.toRealCoord(p, clayX, clayZ, rotation).above(shift).asLong());
        }
    }
}
