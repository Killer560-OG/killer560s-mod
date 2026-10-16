package com.killer560.hub.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Background work that has nothing to wait for at startup, started once the game has finished loading instead of
 * during it (fps-sim-1015).
 *
 * <p>The room library read (~0.5 s of CPU and ~75 MB of garbage) and AP3's planner warm-up (~160 MB of garbage) both
 * started from the client entrypoint, so they ran alongside the game's own resource loading and added their garbage to
 * its collections before the title screen. Neither is needed before the first world: the sim's screens and Predictive
 * Rooms already wait on {@code RoomLibrary.isReady()} and start the load themselves if it has not begun, and the
 * warm-up only has to finish before the first Fast Align. So they start on the first client tick with a screen (or a
 * world) up, which is the title screen arriving.
 *
 * <p>Client thread only. A task registered after that point runs on the next tick.
 */
public final class AfterStartup {

    private static final List<Runnable> PENDING = new ArrayList<>();
    private static boolean registered;

    private AfterStartup() {
    }

    /** Runs {@code task} (which should hand its work to its own thread) once loading has finished. */
    public static void run(Runnable task) {
        PENDING.add(task);
        if (!registered) {
            registered = true;
            ClientTickEvents.END_CLIENT_TICK.register(AfterStartup::tick);
        }
    }

    private static void tick(Minecraft client) {
        // The first screen (the title screen, or first-launch onboarding) is only set once the initial resource load
        // has finished; before that there is no screen, just the loading overlay. (Minecraft.getOverlay() would say
        // it directly but is gone in 26.2.)
        if (PENDING.isEmpty() || (com.killer560.hub.compat.McCompat.screen(client) == null && client.level == null)) {
            return;
        }
        List<Runnable> now = new ArrayList<>(PENDING);
        PENDING.clear();
        for (Runnable r : now) {
            try {
                r.run();
            } catch (RuntimeException e) {
                ModLog.get("killer560smod").warn("A startup task failed", e);
            }
        }
    }
}
