package com.killer560.hub.mining.chmap;

import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * What has been found in the Crystal Hollows lobby we are in now.
 *
 * <p>killer560 (2026-09-30) asked for two maps that differ in one thing only: the legit one "will only get
 * structures based off of if I'm actually in them or not", and the cheat one "should scan everything in
 * range to note whether it is there or not". Both end up here; {@link ChFind.Source} records which produced
 * a given find, so the distinction survives being drawn, saved and shared.
 *
 * <p><b>Per lobby, and thrown away when the lobby changes.</b> Hypixel lays the Crystal Hollows out
 * differently on every server, so a find from the last lobby is not merely stale, it is wrong - it would
 * draw a border round terrain that is now something else entirely. The lobby is identified the same way the
 * relay identifies it, and anything learned under a different id is dropped rather than migrated.
 *
 * <p>Nothing here decides what to DRAW - that is the map's job - and nothing here talks to the network. The
 * sharing layer reads {@link #all()} and feeds {@link #addShared} back in.
 */
public final class ChDiscovery {

    private static final Logger LOGGER = ModLog.get("killer560smod-chmap");

    /** Finds for the lobby named by {@link #lobbyId}. */
    private static final Map<ChStructure, ChFind> FINDS = new EnumMap<>(ChStructure.class);

    /** Which lobby {@link #FINDS} belongs to. Null until we know where we are. */
    private static String lobbyId;

    /** The area the sidebar last named, so entering one is noticed once rather than every tick. */
    private static String lastArea = "";

    private ChDiscovery() {
    }

    /**
     * Starts watching where he is.
     *
     * <p>Guarded and caught the same way the other mining trackers are: discovery is a convenience, and a
     * failure in it must never take a tick handler down with it.
     */
    public static void register() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
                com.killer560.hub.util.FeatureGuard.end("ChDiscovery", client -> {
                    try {
                        tick(client);
                    } catch (Exception e) {
                        LOGGER.error("Crystal Hollows discovery tick failed", e);
                    }
                }));
    }

    /**
     * Notices where he is standing, every tick, and records it against the structure he is in.
     *
     * <p>This is the whole of the LEGIT map's discovery: no scanning, nothing read beyond the area name the
     * sidebar already publishes and his own position. Standing in the Mines of Divan is what puts the Mines
     * of Divan on the map, and walking around inside widens its border to the part he has actually covered.
     */
    public static void tick(Minecraft client) {
        // The same check the CH profit tracker and the map screen already use, rather than a new one.
        if (client == null || client.player == null
                || !"CRYSTAL_HOLLOWS".equals(IslandDetector.graphIsland())) {
            return;
        }
        String area = IslandDetector.scoreboardArea();
        ChStructure structure = ChStructure.fromAreaName(area);
        if (structure == null) {
            lastArea = area == null ? "" : area;
            return;
        }
        if (!Objects.equals(lastArea, area)) {
            lastArea = area;
            LOGGER.info("[CHMap] entered {}", structure.displayName);
        }
        record(structure, ChFind.Source.VISITED,
                client.player.getX(), client.player.getY(), client.player.getZ());
    }

    /**
     * Records a position as belonging to a structure, widening what is known rather than replacing it.
     *
     * <p>A find that was SCANNED is upgraded to VISITED when he actually walks into it, never the other way
     * round: having stood somewhere is a stronger claim than having seen it from outside, and a later scan
     * must not quietly downgrade it.
     */
    public static synchronized void record(ChStructure structure, ChFind.Source source,
                                           double x, double y, double z) {
        if (structure == null) {
            return;
        }
        ensureCurrentLobby();
        ChFind existing = FINDS.get(structure);
        if (existing == null) {
            FINDS.put(structure, new ChFind(structure, source, x, y, z));
            return;
        }
        existing.include(x, y, z);
        if (source == ChFind.Source.VISITED) {
            existing.source = ChFind.Source.VISITED;
        }
    }

    /**
     * Folds in a find someone else sent.
     *
     * <p>Only when it is for THIS lobby - the caller checks that, because the room id is the sharing layer's
     * business - and never as an upgrade over something he found himself. A shared find widening his own box
     * is fine and useful; a shared find overwriting the source of one is not, because then the map could not
     * tell him which parts of it he has actually been to.
     */
    public static synchronized void addShared(ChFind incoming) {
        if (incoming == null || incoming.structure == null) {
            return;
        }
        ensureCurrentLobby();
        ChFind existing = FINDS.get(incoming.structure);
        if (existing == null) {
            incoming.source = ChFind.Source.SHARED;
            FINDS.put(incoming.structure, incoming);
            return;
        }
        existing.include(incoming);
    }

    /** Everything known about this lobby. */
    public static synchronized Collection<ChFind> all() {
        ensureCurrentLobby();
        return java.util.List.copyOf(FINDS.values());
    }

    public static synchronized ChFind find(ChStructure structure) {
        ensureCurrentLobby();
        return FINDS.get(structure);
    }

    public static synchronized boolean hasAll(Collection<ChStructure> wanted) {
        ensureCurrentLobby();
        for (ChStructure s : wanted) {
            if (!FINDS.containsKey(s)) {
                return false;
            }
        }
        return true;
    }

    /** Forgets everything. For a lobby change, and for the Lobby Swapper starting a fresh look. */
    public static synchronized void clear() {
        FINDS.clear();
        lastArea = "";
    }

    /**
     * Drops everything if we have moved to a different lobby.
     *
     * <p>Called from every entry point rather than from a world-change event, because the id arrives
     * asynchronously - Hypixel answers {@code locraw} some time after the join - so "which lobby is this"
     * can change while we are already standing in the new one. Checking on use means the first find after a
     * swap lands in the right place even though nothing told us the moment it happened.
     */
    private static void ensureCurrentLobby() {
        String now = com.killer560.hub.relay.HypixelLocation.lobbyKey();
        if (now == null) {
            return;   // do not know yet - keep what we have rather than throwing away a real find
        }
        if (!now.equals(lobbyId)) {
            if (lobbyId != null && !FINDS.isEmpty()) {
                LOGGER.info("[CHMap] new lobby - forgetting {} structure(s) from the last one", FINDS.size());
            }
            lobbyId = now;
            FINDS.clear();
            lastArea = "";
        }
    }

    /** Which lobby the current finds belong to, for the sharing layer to key its room on. */
    public static synchronized String lobbyId() {
        ensureCurrentLobby();
        return lobbyId;
    }
}
