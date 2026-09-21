package com.killer560.hub.partydata;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Party-reported map facts that {@link com.killer560.hub.interop.PartyInteropState} has no slot for: rooms
 * and doors a party mate's client identified (name/world position/grid cell), and each player's own secret
 * total. Kept in the same shape PartyInteropState uses for its own facts (last write wins per key, source
 * always something that arrived over the wire - our own relay, or, since the Cross-Mod Bridge
 * ({@code com.killer560.hub.bridge}), another mod's party socket, which the reporter string then names) so it reads the same way
 * if it is ever folded in there.
 * <p>
 * <b>Not fed into {@link com.killer560.hub.livemap.LiveMapFeature}.</b> The live map builds its layout
 * entirely from {@link com.killer560.hub.livemap.LiveMapFeature#groupsView()} - this client's own scan - and
 * has no method that accepts an externally-known room or door. For a party mate's discovery to actually fill
 * in a room on OUR map before we have scanned it ourselves, {@code LiveMapFeature}/{@code DungeonLayout}
 * would need a new seam - roughly, a way to register a "provisional" {@link com.killer560.hub.roomdatabase.RoomEntry}
 * + grid cell that {@link com.killer560.hub.livemap.DungeonLayout#capture()} falls back to for a cell this
 * client has not scanned yet, replaced the moment a real scan disagrees. That is real map-rendering work
 * outside this package's ownership, so it is left as a documented gap rather than reached into
 * {@code livemap} for. This class exists so the data is not simply thrown away in the meantime, and so a
 * later feature (or a settings-tab status line) has somewhere to read it from.
 */
public final class PartyRoomIntel {

    public record KnownRoom(String name, int x, int z, int col, int row, String reporter, long atMs) {
    }

    public record KnownDoor(int x, int z, int col, int row, String type, String reporter, long atMs) {
    }

    private static final int MAX_ENTRIES = 128;

    private static final Object LOCK = new Object();
    /** Keyed by "col,row" - a dungeon grid cell only ever holds one room, so later reports for the same cell
     *  replace earlier ones (a party mate's client re-sending after a fuller scan, say). */
    private static final Map<String, KnownRoom> ROOMS = new LinkedHashMap<>();
    private static final Map<String, KnownDoor> DOORS = new LinkedHashMap<>();
    /** Per-player secret totals - {@code dg.v1.playerSecrets}. Keyed by the relay-verified sender name. */
    private static final Map<String, Integer> PLAYER_SECRETS = new LinkedHashMap<>();

    private PartyRoomIntel() {
    }

    public static void offerRoom(String name, int x, int z, int col, int row, String reporter) {
        String key = col + "," + row;
        synchronized (LOCK) {
            ROOMS.put(key, new KnownRoom(name, x, z, col, row, reporter, System.currentTimeMillis()));
            trim(ROOMS);
        }
    }

    public static void offerDoor(int x, int z, int col, int row, String type, String reporter) {
        String key = col + "," + row;
        synchronized (LOCK) {
            DOORS.put(key, new KnownDoor(x, z, col, row, type, reporter, System.currentTimeMillis()));
            trim(DOORS);
        }
    }

    public static void offerPlayerSecrets(String player, int secrets) {
        if (player == null || player.isBlank()) {
            return;
        }
        synchronized (LOCK) {
            Integer held = PLAYER_SECRETS.get(player);
            // Monotonic within a run, same rule PartyInteropState uses for its own counters.
            if (held == null || secrets > held) {
                PLAYER_SECRETS.put(player, secrets);
            }
        }
    }

    public static Map<String, KnownRoom> roomSnapshot() {
        synchronized (LOCK) {
            return new LinkedHashMap<>(ROOMS);
        }
    }

    public static Map<String, KnownDoor> doorSnapshot() {
        synchronized (LOCK) {
            return new LinkedHashMap<>(DOORS);
        }
    }

    public static Map<String, Integer> playerSecretsSnapshot() {
        synchronized (LOCK) {
            return new LinkedHashMap<>(PLAYER_SECRETS);
        }
    }

    /** New dungeon run (or left the dungeon) - everything here is per-run, same lifecycle as
     *  {@link com.killer560.hub.interop.PartyInteropState#reset()}. */
    static void reset() {
        synchronized (LOCK) {
            ROOMS.clear();
            DOORS.clear();
            PLAYER_SECRETS.clear();
        }
    }

    private static void trim(Map<String, ?> map) {
        while (map.size() > MAX_ENTRIES) {
            map.remove(map.keySet().iterator().next());
        }
    }
}
