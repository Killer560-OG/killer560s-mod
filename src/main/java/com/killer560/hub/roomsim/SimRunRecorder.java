package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.FeatureGuard;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Saves the map code of real runs, so they can be replayed in the sim.
 *
 * <p>Without this, "Load a Previous Run" is a menu entry that is always empty - the sim can only replay a floor
 * somebody wrote down, and nobody was writing them down.
 *
 * <p>Saved from REAL dungeons only, never from the sim. Replaying a sim map would give back exactly what the
 * sim generated, which is not practice, and the whole value here is the floors he actually got.
 *
 * <p>Once per run, and only once the layout is worth saving. A dungeon map fills in as rooms are discovered, so
 * saving on entry would record an almost empty grid; this waits until a good part of it is known and then
 * writes the best version it saw.
 */
public final class SimRunRecorder {

    /** How many cells must have a room before a layout is worth keeping. */
    private static final int MIN_KNOWN_CELLS = 12;

    /** Re-checked at this interval rather than every tick; a map does not change that fast. */
    private static final int CHECK_INTERVAL_TICKS = 40;

    private static int tickCounter;
    private static boolean savedThisRun;
    private static int bestKnown;

    private SimRunRecorder() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(
                FeatureGuard.start("SimRunRecorder.tick", SimRunRecorder::tick));
    }

    private static void tick(Minecraft client) {
        if (client == null || client.level == null) {
            return;
        }
        // Never in the sim: a sim map replayed is just the generator's own output handed back.
        if (SimState.isActive() || !DungeonState.isInDungeon()) {
            savedThisRun = false;
            bestKnown = 0;
            return;
        }
        if (savedThisRun || ++tickCounter % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        DungeonLayout layout = DungeonLayout.current();
        if (layout == null) {
            return;
        }
        int known = 0;
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            if (layout.roomOfCell(idx) >= 0) {
                known++;
            }
        }
        if (known < MIN_KNOWN_CELLS || known <= bestKnown) {
            return;
        }
        bestKnown = known;
        // Written every time the map gets better rather than once at a threshold: the last write is the most
        // complete picture, and a run that ends early still leaves the best version seen.
        SimRunHistory.recordCurrent(layout, DungeonState.getFloor());
        if (known >= DungeonLayout.GRID * DungeonLayout.GRID / 2) {
            savedThisRun = true;
        }
    }
}
