package com.killer560.hub.roomsim;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Places the sim's rooms a slice at a time, instead of all at once on one server tick.
 *
 * <p>killer560 (2026-09-28): "the instance seems to keep freezing and I have to close it." This is why. A 2x2
 * room is 93x93x81, about 700,000 blocks, and the whole map is several of those - and it was all done in a
 * single synchronous loop on the SERVER THREAD. While that loop runs the integrated server does not tick, so
 * the client has nothing to talk to and the game is frozen solid, with no way to tell it apart from a hang.
 *
 * <p>The work is identical; only its scheduling changes. Each server tick takes a fixed slice and returns, so
 * the server keeps ticking, the loading screen keeps animating, and a big map takes a few visible seconds
 * rather than locking the process. That is also what makes the loading screen honest: there is now something to
 * wait for rather than a frozen window.
 *
 * <p>The slice is deliberately a count of BLOCKS rather than of rooms. Rooms vary by a factor of nine between a
 * 1x1 and a 2x2, so a room-per-tick budget would be smooth for some maps and a stutter for others.
 */
public final class SimBuildQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-roomsim");

    /**
     * Blocks placed per server tick.
     *
     * <p>Sized so a tick's work stays well inside the 50ms budget. Small enough that the server never misses a
     * tick, large enough that a whole floor lands in a few seconds rather than a minute - which matters,
     * because the alternative to a slow build is not a fast one, it is the freeze this replaces.
     */
    private static final int BLOCKS_PER_TICK = 40_000;

    private static final Deque<RoomPlacer.PasteJob> JOBS = new ArrayDeque<>();
    private static Runnable onDone;
    private static int placedTotal;
    private static int jobsTotal;

    private SimBuildQueue() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SimBuildQueue::tick);
    }

    /** Whether anything is still being placed. */
    public static synchronized boolean isBusy() {
        return !JOBS.isEmpty();
    }

    /** Queues a room. Safe to call from the server thread while the queue is already running. */
    public static synchronized void submit(ServerLevel level, RoomLibrary.Room room, int gridX, int gridZ,
                                           int rotation) {
        JOBS.add(new RoomPlacer.PasteJob(level, room, gridX, gridZ, rotation));
        jobsTotal++;
    }

    /**
     * What to run, on the server thread, once every queued room has landed.
     *
     * <p>Set after the rooms are queued. Replacing an existing callback is deliberate: a second build request
     * supersedes the first, and running the old completion afterwards would take the loading screen down for a
     * build that is no longer the one in progress.
     */
    public static synchronized void whenDone(Runnable action) {
        onDone = action;
    }

    /** Throws everything away - for leaving the sim mid-build. */
    public static synchronized void clear() {
        JOBS.clear();
        onDone = null;
        placedTotal = 0;
        jobsTotal = 0;
    }

    private static void tick(net.minecraft.server.MinecraftServer server) {
        Runnable done = null;
        synchronized (SimBuildQueue.class) {
            if (JOBS.isEmpty()) {
                return;
            }
            int budget = BLOCKS_PER_TICK;
            while (budget > 0 && !JOBS.isEmpty()) {
                RoomPlacer.PasteJob job = JOBS.peek();
                int placed = job.step(budget);
                budget -= Math.max(1, placed);
                placedTotal += placed;
                if (job.isDone()) {
                    JOBS.poll();
                }
            }
            if (JOBS.isEmpty()) {
                done = onDone;
                onDone = null;
                LOGGER.info("Sim build finished: {} block(s) across {} room(s)", placedTotal, jobsTotal);
                placedTotal = 0;
                jobsTotal = 0;
            } else {
                // Something to watch while it works, rather than a still frame.
                int remaining = JOBS.size();
                Minecraft.getInstance().execute(() ->
                        SimWorld.buildProgress(remaining + " room(s) left to place"));
            }
        }
        if (done != null) {
            done.run();
        }
    }
}
