package com.killer560.hub.livemap;

import com.killer560.hub.partydata.PartyRoomIntel;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Fills in cells THIS client has not scanned yet with rooms/doors {@link PartyRoomIntel} received from
 * teammates (our own relay, or another mod's socket via {@code com.killer560.hub.bridge}) - the seam
 * {@code PartyRoomIntel}'s own class doc asked {@code livemap} to add. Merged at most once per client tick
 * (or on a grid reset), from {@link DungeonLayout#capture()}; never touched from a per-frame render path.
 * <p>
 * <b>Local scans always win.</b> A cell {@link DungeonLayout} already has an answer for (a local room group,
 * or a door type it has actually resolved) is skipped here entirely, and the moment local scan resolves a
 * cell that used to be reported-only, this class drops it - see {@link #mergeIfNeeded}'s
 * {@code localRoomKnown}/{@code localDoorKnown} checks.
 * <p>
 * <b>Conflicting reports.</b> {@link PartyRoomIntel} itself keeps only the newest report per cell (a party
 * mate's own client re-sending after a fuller scan). This class instead keeps the FIRST report it accepts
 * for a given still-unscanned cell - two teammates disagreeing about the same cell should not make the map
 * flicker between their answers; whichever arrived first stands until a local scan settles the cell for
 * good, at which point both the disagreement and the placeholder disappear together.
 * <p>
 * <b>Display only.</b> Nothing here is written into {@link DungeonLayout}'s own {@code roomOf}/{@code
 * doorType}/{@code doorLocked} arrays - those also drive the teleport pathfinders and Auto Blood Rush
 * ({@code livemap.autoclear}), and unverified network data must never steer automation. {@link MapPainter}
 * reads {@link #reportedRoomsView()}/{@link #reportedDoorsView()} directly for drawing only.
 * <p>
 * <b>Room identity.</b> A reported room's name is resolved against the exact same real room database local
 * scanning uses ({@link RoomDatabase#lookupByName}), so it gets the same real type/secrets-total and draws
 * in its real colour. A name that does not resolve (a stale local database, a mismatched-build teammate, a
 * corrupted string, ...) still gets a cell - drawn generic, see {@link MapPainter#drawReportedRoom} - rather
 * than being dropped; nothing in this class can throw on a bad name.
 * <p>
 * <b>Coordinates.</b> The wire format's room {@code col}/{@code row} are tile indices into
 * {@link DungeonLayout}'s 11x11 grid; a room can only ever occupy one of the 6 even indices per axis
 * (0/2/4/6/8/10) - the dungeon's real 6x6 room layout, with the odd indices being the connectors between
 * them. A room report is snapped to its nearest such slot (see {@link #anchorRoomCell}); a door report's
 * col/row is used as an exact tile index, since a door is a single cell. Anything the 11x11 grid cannot
 * contain at all is ignored outright - {@code PartyDataProtocol} should already have rejected it, but this
 * class never trusts an upstream validator alone for something that gets drawn on screen.
 */
final class PartyMapIntel {

    /** One teammate-reported room, anchored at a single 6x6 grid slot. The wire format carries one point per
     *  room, not its full tile footprint, so a multi-tile room shows as a single 16-unit box here until this
     *  client's own scan reveals its real shape and takes over. */
    record ReportedRoom(int idx, String reportedName, RoomEntry entry, String reporter) {
        int col() {
            return idx % DungeonLayout.GRID;
        }

        int row() {
            return idx / DungeonLayout.GRID;
        }
    }

    record ReportedDoor(int idx, int type, String reporter) {
    }

    private static final Map<Integer, ReportedRoom> ROOMS = new HashMap<>();
    private static final Map<Integer, ReportedDoor> DOORS = new HashMap<>();
    private static int mergedTick = Integer.MIN_VALUE;
    private static int mergedGeneration = Integer.MIN_VALUE;

    private PartyMapIntel() {
    }

    /** Same lifecycle as the map's own grid reset (run end/start, world change) - called from
     *  {@link LiveMapFeature#resetGrid}, not only from a generation-mismatch inside {@link #mergeIfNeeded},
     *  so a stale {@link PartyRoomIntel} snapshot can never briefly outlive the map's own reset. */
    static void reset() {
        ROOMS.clear();
        DOORS.clear();
        mergedTick = Integer.MIN_VALUE;
        mergedGeneration = Integer.MIN_VALUE;
    }

    /** Direct reference, not a copy - same pattern as {@link LiveMapFeature#groupsView()} - so the render
     *  path allocates nothing walking this every frame. */
    static Collection<ReportedRoom> reportedRoomsView() {
        return ROOMS.values();
    }

    static Collection<ReportedDoor> reportedDoorsView() {
        return DOORS.values();
    }

    static ReportedRoom reportedRoomAt(int idx) {
        return ROOMS.get(idx);
    }

    /**
     * Merges {@link PartyRoomIntel}'s current snapshot in, at most once per {@code tick} (a {@code
     * generation} change - a new run - forces a clean re-merge first; {@link PartyRoomIntel} resets on the
     * same run boundary, but this guards against the two resets landing on different ticks). Cheap: at most
     * 128 rooms + 128 doors ({@link PartyRoomIntel}'s own cap), a handful of times a second.
     *
     * @param localRoomKnown cell already belongs to a local {@code RoomGroup}
     * @param localDoorKnown cell's door type is already resolved locally (world scan or the held map)
     */
    static void mergeIfNeeded(int tick, int generation, IntPredicate localRoomKnown, IntPredicate localDoorKnown) {
        if (tick == mergedTick && generation == mergedGeneration) {
            return;
        }
        if (generation != mergedGeneration) {
            ROOMS.clear();
            DOORS.clear();
        }
        mergedTick = tick;
        mergedGeneration = generation;

        for (PartyRoomIntel.KnownRoom r : PartyRoomIntel.roomSnapshot().values()) {
            Integer idx = anchorRoomCell(r.col(), r.row());
            if (idx == null) {
                continue; // out of range for the 11x11 grid at all - ignore rather than guess
            }
            if (localRoomKnown.test(idx)) {
                ROOMS.remove(idx); // local scan has settled this cell since
                continue;
            }
            ReportedRoom existing = ROOMS.get(idx);
            if (existing == null) {
                ROOMS.put(idx, new ReportedRoom(idx, r.name(), resolveEntry(r.name()), r.reporter()));
            } else if (existing.entry() == null && java.util.Objects.equals(existing.reportedName(), r.name())) {
                // Same report as before ("keep first" - a different name for this cell is still ignored
                // below), just retried against the room database - it may have finished downloading since
                // the first report came in (RoomDatabase.ensureLoading runs on its own schedule, not ours).
                RoomEntry resolved = resolveEntry(r.name());
                if (resolved != null) {
                    ROOMS.put(idx, new ReportedRoom(idx, existing.reportedName(), resolved, existing.reporter()));
                }
            }
            // else: a different name reported for a cell we already have one for - keep the first (existing).
        }

        for (PartyRoomIntel.KnownDoor d : PartyRoomIntel.doorSnapshot().values()) {
            if (d.col() < 0 || d.col() >= DungeonLayout.GRID || d.row() < 0 || d.row() >= DungeonLayout.GRID) {
                continue;
            }
            int idx = d.row() * DungeonLayout.GRID + d.col();
            if (localDoorKnown.test(idx)) {
                DOORS.remove(idx);
                continue;
            }
            int type = doorTypeFromWire(d.type());
            if (type == DungeonLayout.DOOR_NONE) {
                continue;
            }
            DOORS.computeIfAbsent(idx, k -> new ReportedDoor(idx, type, d.reporter()));
        }
    }

    /** Snaps a wire (col,row) tile index to the nearest of the 6 even (room) slots per axis - the dungeon's
     *  real 6x6 room grid - or null if the raw index is not even inside the 11x11 grid at all. */
    private static Integer anchorRoomCell(int col, int row) {
        if (col < 0 || col >= DungeonLayout.GRID || row < 0 || row >= DungeonLayout.GRID) {
            return null;
        }
        int rc = Math.max(0, Math.min(DungeonLayout.GRID - 1, Math.round(col / 2f) * 2));
        int rr = Math.max(0, Math.min(DungeonLayout.GRID - 1, Math.round(row / 2f) * 2));
        return rr * DungeonLayout.GRID + rc;
    }

    private static RoomEntry resolveEntry(String name) {
        try {
            return name == null || name.isBlank() ? null : RoomDatabase.lookupByName(name);
        } catch (RuntimeException e) {
            return null; // an unmapped/bad name draws generic (MapPainter.drawReportedRoom) - never crashes
        }
    }

    private static int doorTypeFromWire(String wire) {
        if (wire == null) {
            return DungeonLayout.DOOR_NONE;
        }
        return switch (wire) {
            case "normal" -> DungeonLayout.DOOR_NORMAL;
            case "wither" -> DungeonLayout.DOOR_WITHER;
            case "blood" -> DungeonLayout.DOOR_BLOOD;
            case "entrance" -> DungeonLayout.DOOR_ENTRANCE;
            default -> DungeonLayout.DOOR_NONE; // unknown/future wire value - never guess a type to draw
        };
    }
}
