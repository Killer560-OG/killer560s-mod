package com.killer560.hub.dungeonalerts;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.SkyblockGate;

import java.util.HashSet;
import java.util.Set;

/**
 * Rare Room Alert (Skyblocker RareRoomAlert, SkyHanni Trinity Helper; featB-1108): a title and a sound the first time Trinity,
 * Tomioka or Duncan shows up on the map this run, from the room the live map identified (room database name), so it fires
 * when the map reads the room, not only when he walks in. Looks once a second, only in a dungeon with the switch on.
 */
final class RareRoomAlert {

    static final String[] ROOMS = {"Trinity", "Tomioka", "Duncan"};
    static final String KEY = "rareroom";
    private static final Set<String> seen = new HashSet<>();
    private static int poll = 0;

    private RareRoomAlert() {
    }

    static void tick() {
        if (!DungeonAlertsConfig.getInstance().rareRoomAlert || !DungeonState.isInDungeon() || !SkyblockGate.allows() || ++poll < 20) {
            return;
        }
        poll = 0;
        for (String room : ROOMS) {
            if (!seen.contains(room) && LiveMapFeature.hasIdentifiedRoom(room)) {
                seen.add(room);
                DungeonAlertsFeature.alert(KEY, "§d" + room + " §7found!", "Rare room");
            }
        }
    }

    static void onWorldChange() {
        seen.clear();
    }
}
