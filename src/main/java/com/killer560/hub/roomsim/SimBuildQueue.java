package com.killer560.hub.roomsim;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

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
    private static final int BLOCKS_PER_TICK = 150_000;

    /** A piece of world-writing that can be done a slice at a time. */
    public interface Job {

        /** Does up to {@code budget} blocks and returns how many were written. */
        int step(int budget);

        boolean isDone();
    }

    private static final Deque<Job> JOBS = new ArrayDeque<>();

    /**
     * Chunks this build has touched.
     *
     * <p>Two jobs at once. It tells the end of the build which chunks to send to the client - the writes
     * themselves no longer do that, see {@code RoomPlacer.PLACE_FLAGS} - and it tells the NEXT build what is
     * worth clearing. Wiping the whole grid meant four million block reads, most of them into chunks that had
     * to be loaded to answer, when the only thing actually in the world was the last room.
     */
    private static final Set<Long> touchedChunks = new HashSet<>();

    /** Records a written position's chunk. Cheap enough to call per block: a long key and a set add. */
    static void touched(int x, int z) {
        touchedChunks.add((((long) (x >> 4)) << 32) ^ ((z >> 4) & 0xffffffffL));
    }

    /** The region those chunks cover, or null when nothing has been built yet. */
    public static synchronized int[] touchedBounds() {
        if (touchedChunks.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long k : touchedChunks) {
            int cx = (int) (k >> 32);
            int cz = (int) k;
            minX = Math.min(minX, cx << 4);
            minZ = Math.min(minZ, cz << 4);
            maxX = Math.max(maxX, (cx << 4) + 15);
            maxZ = Math.max(maxZ, (cz << 4) + 15);
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    /**
     * Sends every touched chunk to the players, once.
     *
     * <p>This is the other half of dropping UPDATE_CLIENTS from the writes. Without it the build would be
     * invisible until something else happened to resend those chunks, which is a far worse bug than the slow
     * one it replaces - so it runs on completion, before the loading screen comes down.
     */
    private static void sendTouchedChunks(net.minecraft.server.MinecraftServer server) {
        var level = server.overworld();
        for (long k : touchedChunks) {
            int cx = (int) (k >> 32);
            int cz = (int) k;
            var chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
                continue;
            }
            var packet = new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                    chunk, level.getLightEngine(), null, null);
            for (var sp : server.getPlayerList().getPlayers()) {
                sp.connection.send(packet);
            }
        }
    }
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
     * Queues a region to be wiped to air.
     *
     * <p>killer560 (2026-09-28): "it doesnt do a good job of clearing the surroundings and stuff." The sim
     * world is a flat world, so there is grass and stone everywhere the room does not cover - and a paste only
     * writes the columns that were actually CAPTURED, leaving the rest as flatland poking through the middle of
     * a dungeon. Clearing first is what makes a pasted room look like a room rather than a ruin in a field.
     *
     * <p>Queued ahead of the rooms rather than done inline, for the same reason the pastes are: a margin around
     * a 2x2 room is well over a million blocks and doing it in one go is precisely the freeze this queue
     * exists to prevent.
     */
    public static synchronized void submitClear(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        JOBS.addFirst(new ClearJob(level, minX, minZ, maxX, maxZ));
    }

    /**
     * Seals a room's doorways so a single-room test cannot be walked out of.
     *
     * <p>killer560 (2026-09-28): "if something has a doorway that can be open to path into another room, then
     * you can just fill the doorway so I cannot leave with diamond blocks or soemthing so i know it is normally
     * a door but for single room testing i cant walk out and fall into the void."
     *
     * <p>It fills the room's perimeter ring ONLY WHERE THAT RING IS ALREADY AIR. That one rule does the whole
     * job without needing to know what a doorway is: the ring is the room's own wall almost everywhere, so the
     * only air in it is where a door or an opening is, and those are exactly the holes worth plugging. Nothing
     * has to detect a doorway, and a room with an odd opening nobody anticipated gets sealed too.
     *
     * <p>Diamond because it is unmistakable. The point is not to hide the edge of the room but to mark it -
     * he should be able to tell at a glance which walls are the dungeon's and which are the sim stopping him
     * falling into the void, or he will practise a route that walks through one.
     */
    public static synchronized void submitSeal(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        JOBS.add(new SealJob(level, minX, minZ, maxX, maxZ));
    }

    /** Fills the air in a box's outer ring with an obvious block. */
    private static final class SealJob implements Job {

        private final ServerLevel level;
        private final int minX;
        private final int minZ;
        private final int maxX;
        private final int maxZ;
        private final net.minecraft.core.BlockPos.MutableBlockPos cursor =
                new net.minecraft.core.BlockPos.MutableBlockPos();
        private final net.minecraft.world.level.block.state.BlockState fill =
                net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK.defaultBlockState();

        private int x;
        private int z;
        private int y = RoomLibrary.MIN_Y;
        private boolean done;

        SealJob(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
            this.level = level;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.x = minX;
            this.z = minZ;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public int step(int budget) {
            int written = 0;
            while (written < budget) {
                if (y > RoomLibrary.MAX_Y) {
                    done = true;
                    return written;
                }
                boolean onRing = x == minX || x == maxX || z == minZ || z == maxZ;
                if (onRing) {
                    cursor.set(x, y, z);
                    if (level.getBlockState(cursor).isAir()) {
                        level.setBlock(cursor, fill, RoomPlacer.CLEAR_FLAGS);
                        touched(x, z);
                        written++;
                    }
                }
                if (++z > maxZ) {
                    z = minZ;
                    if (++x > maxX) {
                        x = minX;
                        y++;
                    }
                }
            }
            return written;
        }
    }

    /**
     * Positions a clear may LOOK at in one tick, as opposed to write.
     *
     * <p>Reading a block state is far cheaper than writing one, so this is much larger than the write budget -
     * but it is finite, which is the whole point. At this rate a full 11x11 grid wipe takes a couple of seconds
     * of visible loading instead of one very long frozen frame.
     */
    private static final int SCAN_BUDGET = 250_000;

    /** Fills a box with air, a slice at a time. */
    private static final class ClearJob implements Job {

        private final ServerLevel level;
        private final int minX;
        private final int minZ;
        private final int maxX;
        private final int maxZ;
        private final net.minecraft.core.BlockPos.MutableBlockPos cursor =
                new net.minecraft.core.BlockPos.MutableBlockPos();
        private final net.minecraft.world.level.block.state.BlockState air =
                net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

        private int x;
        private int z;
        private int y = RoomLibrary.MIN_Y;
        private boolean done;

        ClearJob(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
            this.level = level;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.x = minX;
            this.z = minZ;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public int step(int budget) {
            int written = 0;
            int scanned = 0;
            // TWO budgets, and the second one matters more than it looks. Charging only for blocks WRITTEN was
            // safe while this cleared a margin round one room, and became a freeze the moment it was asked to
            // wipe the whole grid: in a void world almost every position is already air, so a tick could look
            // at ten million of them, write nothing, spend no budget and never return. Scanning is work even
            // when it changes nothing.
            while (written < budget && scanned < SCAN_BUDGET) {
                if (y > RoomLibrary.MAX_Y) {
                    done = true;
                    return written;
                }
                scanned++;
                cursor.set(x, y, z);
                // Only touch what is not already air - rewriting air would burn the write budget on nothing.
                if (!level.getBlockState(cursor).isAir()) {
                    level.setBlock(cursor, air, RoomPlacer.CLEAR_FLAGS);
                    touched(x, z);
                    written++;
                }
                if (++z > maxZ) {
                    z = minZ;
                    if (++x > maxX) {
                        x = minX;
                        y++;
                    }
                }
            }
            return written;
        }
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
        touchedChunks.clear();
    }

    /** Forgets which chunks were touched, without cancelling anything - for starting a fresh build. */
    public static synchronized void forgetTouched() {
        touchedChunks.clear();
    }

    private static void tick(net.minecraft.server.MinecraftServer server) {
        Runnable done = null;
        synchronized (SimBuildQueue.class) {
            if (JOBS.isEmpty()) {
                return;
            }
            int budget = BLOCKS_PER_TICK;
            while (budget > 0 && !JOBS.isEmpty()) {
                Job job = JOBS.peek();
                int placed = job.step(budget);
                budget -= Math.max(1, placed);
                placedTotal += placed;
                if (job.isDone()) {
                    JOBS.poll();
                }
            }
            if (JOBS.isEmpty()) {
                sendTouchedChunks(server);
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
