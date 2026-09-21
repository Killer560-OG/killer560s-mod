package com.killer560.hub.bridge;

import com.killer560.hub.partydata.PartyDataFeature;

import java.util.List;
import java.util.UUID;

/**
 * Everything the adapters need to decide what to do, read once on the client thread (4 Hz) by
 * {@link BridgeFeature} and handed over immutable - so no adapter ever touches Minecraft state from a
 * network thread.
 *
 * @param active          master toggle on AND Party Interop enabled (which also means "Skyblock Only" allows it)
 * @param onHypixel       connected to hypixel.net (p3sim is deliberately excluded - see the staging notes)
 * @param inDungeon       in a real Catacombs run (the {@code /killer560 sim} override does not count)
 * @param floor           "F7", "M4", "E"... or null
 * @param selfName        the signed-in account's name, only if it matches the in-world player's name; else null
 * @param selfUuid        the signed-in account's UUID under the same condition; else null
 * @param teammates       party members other than us ({@code PartyTracker})
 * @param devonianPartyHash Devonian's party hash, or null when it cannot be computed exactly (see
 *                        {@link BridgeFeature#devonianPartyHash})
 * @param serverCode      the sidebar server code NoammAddons/Odin group by, or null
 * @param entranceCol     entrance room's first grid cell, or -1
 * @param entranceRow     ditto
 * @param selfFacts       {@link PartyDataFeature#selfFactSnapshot()} - empty outside a run
 * @param roomCells       every cell of every named room from our own map scan (for NoammAddons' per-cell room packet)
 * @param p3Active        between Goldor's P3 line and the core opening, this run
 * @param runEpoch        bumps every time a run starts - adapters reset their "already sent" state on change
 */
public record BridgeContext(boolean active, boolean onHypixel, boolean inDungeon, String floor,
                            String selfName, UUID selfUuid, List<String> teammates, Integer devonianPartyHash,
                            String serverCode, int entranceCol, int entranceRow,
                            List<PartyDataFeature.SelfFact> selfFacts, List<RoomCell> roomCells,
                            boolean p3Active, int runEpoch) {

    /** One grid cell of a named room, in NoammAddons' {@code dungeonroom} shape. */
    public record RoomCell(String name, int x, int z, int col, int row, boolean isSeparator) {
    }

    public boolean identityKnown() {
        return selfName != null && selfUuid != null;
    }
}
