package com.killer560.hub.roomsim;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;

import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import com.killer560.hub.util.ModLog;

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

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

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

        /**
         * Total positions this job will visit, and how many it has visited.
         *
         * <p>killer560 (2026-09-28): "instead of the moving bar at the bottom during the dungeon loading thing
         * make an acttual progress bar." A real bar needs a real denominator, and only the job knows it - a
         * paste knows its room's dimensions before it starts, a clear knows its box. Measured in positions
         * VISITED rather than blocks written, because that is what actually takes the time: a room is mostly
         * uncaptured air that is skipped, and a bar that only counted writes would stall on sparse rooms and
         * race through dense ones.
         */
        long totalWork();

        long doneWork();
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

    /**
     * The region those chunks cover, or null when nothing has been built yet.
     *
     * <p>The UNION of the live set and the last finished build, never one or the other. It returned the live set
     * whenever that was non-empty, and it nearly always was: {@code SimSecrets.place} runs from the build's
     * completion callback, after the queue had already moved the build's chunks into {@link #lastBuiltBounds} and
     * emptied the set, and it records the one or two chunks its chests went into. So the next build cleared only
     * the chunks holding the last room's secrets, and the rest of that room stayed standing wherever the next paste
     * did not happen to write over it. 2026-10-06: the first floor generated after the 133-room single sweep was
     * top-aligned (y 121..505) and stood on a single room's blocks from y -63 to 20 under Flags and Museum
     * (97-sim-roomspawn). Anything that writes after the queue finishes - secrets, doors, secret items picked up
     * and put back - now adds to the box instead of replacing it.
     */
    public static synchronized int[] touchedBounds() {
        return union(boundsOf(touchedChunks), lastBuiltBounds);
    }

    private static int[] union(int[] a, int[] b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }

    /** What the last finished build covered, so the next one knows what to clear. */
    private static int[] lastBuiltBounds;

    private static int[] boundsOf(Set<Long> chunks) {
        if (chunks.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long k : chunks) {
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
     * Relights and resends the built chunks, a few at a time.
     *
     * <p>This is the other half of dropping UPDATE_CLIENTS from the writes: without it the build is invisible,
     * which is worse than slow. But it is also the most expensive thing per unit here - each chunk means a
     * light propagation on the server and a full chunk packet the client has to turn into a mesh - so it is
     * paced rather than done all at once.
     *
     * <p>Counted as work like any other job, so the loading screen stays up and keeps moving through it. The
     * alternative was a bar that reached the end and then a frozen game, which is the worst of both.
     */
    private static final class FinishJob implements Job {

        /** Chunks per tick. Small because each one costs a relight AND a mesh rebuild on the client. */
        private static final int CHUNKS_PER_TICK = 6;

        private final net.minecraft.server.MinecraftServer server;
        private final java.util.List<Long> chunks;
        private int index;

        FinishJob(net.minecraft.server.MinecraftServer server, java.util.List<Long> chunks) {
            this.server = server;
            this.chunks = chunks;
        }

        @Override
        public boolean isDone() {
            return index >= chunks.size();
        }

        @Override
        public long totalWork() {
            return chunks.size();
        }

        @Override
        public long doneWork() {
            return index;
        }

        @Override
        public int step(int budget) {
            var level = server.overworld();
            int did = 0;
            while (did < CHUNKS_PER_TICK && index < chunks.size()) {
                long k = chunks.get(index++);
                int cx = (int) (k >> 32);
                int cz = (int) k;
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                did++;
                if (chunk == null) {
                    continue;
                }
                var lights = level.getChunkSource().getLightEngine();
                var chunkPos = new net.minecraft.world.level.ChunkPos(cx, cz);
                var sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    // A section written behind its own back does not know how many non-air blocks it holds,
                    // and one that still believes it is empty is not sent at all - which looks like half a
                    // room missing rather than like a lighting bug.
                    sections[i].recalcBlockCounts();

                    // And the light engine has to be TOLD the section stopped being empty.
                    //
                    // This is what crashed the client. Writing straight into a section skips everything
                    // setBlock does, including this - so the engine still had no light data allocated for a
                    // section that now holds a room, and propagateLightSources below walked into a null
                    // DataLayer and killed the lighting worker thread. The server then could not save, and
                    // the client froze: exactly the hang killer560 kept having to close by hand.
                    //
                    // Found by reading the gametest client's own log (NPE in LayerLightSectionStorage) rather
                    // than by reasoning about it, after five fixes aimed at the wrong thing.
                    lights.updateSectionStatus(
                            net.minecraft.core.SectionPos.of(chunkPos, chunk.getSectionYFromSectionIndex(i)),
                            sections[i].hasOnlyAir());
                }
                // Only once the sections are registered is it safe to ask for light to be propagated.
                lights.setLightEnabled(chunkPos, true);
                lights.propagateLightSources(chunkPos);
                chunk.markUnsaved();
                // NO chunk packet here, on purpose.
                //
                // killer560 (2026-09-28): "It did it again." Sending them myself was the freeze, and pacing
                // the sends did not fix it because the cost is on HIS side - the client turns each chunk into
                // a mesh, and two hundred meshes is a stall however they arrive. Vanilla already solves this:
                // it streams chunks to a player as they come into range, a few per tick, at a rate tuned for
                // exactly this. The build now happens while he is held somewhere else entirely (see
                // SimBuilder.holdPlayer), so these chunks were never sent to him in the first place and
                // vanilla delivers them normally when he is finally put in the room.
            }
            // Reported as one unit of the write budget per chunk, not as the thousands of blocks it touches -
            // the pacing here is CHUNKS_PER_TICK, and returning the real block count would make the queue
            // think its budget was spent and starve anything queued behind it.
            return did;
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

    /**
     * How many builds have RUN TO COMPLETION since the game started.
     *
     * <p>Exists because {@link #isBusy} alone cannot tell "the build has finished" from "the build has not
     * started yet", and both read as not-busy. Every sim scenario in the testkit waited on {@code !isBusy()}
     * straight after asking for a floor, so it sailed through the gap before the jobs were queued and then
     * measured an empty world: on 2026-09-29 that reported "found 0 chest(s) in the built floor" three runs
     * in a row while the log showed the build starting a second later and 21 rooms queued. A counter that only
     * ever goes up is unambiguous - snapshot it, ask for the floor, wait for it to change.
     *
     * <p>{@code progress()} cannot serve here: {@code finishedWork} is zeroed the moment the queue empties, so
     * it reads 0 before a build and 0 after one.
     */
    public static synchronized long buildsFinished() {
        return buildsFinished;
    }

    private static long buildsFinished;

    /**
     * How far through the whole build we are, 0 to 1.
     *
     * <p>Counts jobs already finished as complete rather than forgetting them, or the bar would jump backwards
     * every time one job ended and the next began.
     */
    public static synchronized float progress() {
        long total = finishedWork;
        long done = finishedWork;
        for (Job j : JOBS) {
            total += j.totalWork();
            done += j.doneWork();
        }
        return total <= 0 ? 0f : Math.min(1f, (float) ((double) done / total));
    }

    /** Work from jobs that have already finished and left the queue. */
    private static long finishedWork;

    /** Queues a room. Safe to call from the server thread while the queue is already running. */
    public static synchronized void submit(ServerLevel level, RoomLibrary.Room room, int gridX, int gridZ,
                                           int rotation) {
        markStart();
        JOBS.add(new RoomPlacer.PasteJob(level, room, gridX, gridZ, rotation));
        jobsTotal++;
    }

    /**
     * Starts the clock if this is the first job of a build.
     *
     * <p>It used to live in {@link #submit} alone, and that made the build TIMER lie on every path that queues
     * something else first. {@code SimBuilder.buildSingleRoom} wipes before it pastes, so the clear went in
     * through {@link #submitClear} - which did not set this - and the paste then found a non-empty queue and
     * did not set it either. {@code startedAtMs} was therefore still the PREVIOUS build's, and the single-room
     * load on 2026-09-30 reported "Sim build took 112851 ms" for work the log's own timestamps put at about
     * three seconds: 112.8 seconds is exactly the gap back to the floor he had generated two minutes earlier.
     * A timer that measures from the last build is worse than no timer, because it reads as a catastrophic
     * regression in whichever path happens to queue a clear first.
     */
    private static void markStart() {
        if (JOBS.isEmpty()) {
            startedAtMs = System.currentTimeMillis();
        }
    }

    /** When the current build began, so the log can say how long it actually took rather than how long it felt. */
    private static long startedAtMs;

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
        markStart();
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
        markStart();
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
        private int y = SimAltitude.minWorldY();
        private boolean done;
        private long visited;
        private final long total;

        SealJob(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
            this.total = (long) (maxX - minX + 1) * (maxZ - minZ + 1)
                    * (RoomLibrary.MAX_Y - RoomLibrary.MIN_Y + 1);
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
        public long totalWork() {
            return total;
        }

        @Override
        public long doneWork() {
            return visited;
        }

        @Override
        public int step(int budget) {
            int written = 0;
            // A SCAN budget as well as a write budget, for the same reason ClearJob has one: only the ring
            // positions can ever write, so a 3x3 seal walks about three quarters of a million positions for
            // twenty-nine thousand writes, and the write budget alone never ends the tick.
            int scanned = 0;
            while (written < budget && scanned < SCAN_BUDGET) {
                scanned++;
                if (y > SimAltitude.maxWorldY()) {
                    done = true;
                    return written;
                }
                visited++;
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

    /**
     * Fills a box with air, a CHUNK SECTION at a time.
     *
     * <p>It walked the box position by position - {@code level.getBlockState} then {@code level.setBlock} on
     * every one of them - and both halves of that were the wrong tool. A section that holds nothing but air
     * can say so in one call ({@code LevelChunkSection.hasOnlyAir}), and the grid's band is 385 blocks tall
     * while a floor occupies about 80 of them, so roughly three quarters of the volume is answerable without
     * reading a single block. The writes went the same way {@code RoomPlacer.SectionWriter} already sends the
     * pastes: straight into the section, with the chunk and section found once instead of per block.
     * {@code FinishJob} below already puts back what that skips - block counts, the light engine's idea of
     * which sections are empty, and the light itself - for every chunk the write touched.
     *
     * <p>This is the same lesson {@code docs/SIM.md} records for the secret scan: "a block scan over a room's
     * volume is almost always a chunk-section scan in disguise". The paste was moved off {@code setBlock} for
     * exactly this reason in September and the clear was left behind, so a wipe cost far more per block than
     * the floor it was wiping.
     */
    private static final class ClearJob implements Job {

        private final ServerLevel level;
        private final int minX;
        private final int minZ;
        private final int maxX;
        private final int maxZ;
        /**
         * The WHOLE world height, not the old floor's band. It was {@code SimAltitude.previousMinWorldY()..
         * previousMaxWorldY()}, which is right only if every block in the box was written at the last build's
         * offset - so anything left by an older build (see {@code touchedBounds}) under a top-aligned floor's
         * band (y 121 and up) was out of reach for good. An all-air section is skipped in one
         * {@code hasOnlyAir()} call, so the extra height costs a few hundred calls, and the clear no longer
         * depends on {@code SimAltitude} bookkeeping at all.
         */
        private final int minY;
        private final int maxY;
        private final java.util.List<Long> chunks = new ArrayList<>();
        private final net.minecraft.world.level.block.state.BlockState air =
                net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

        private int chunkIndex;
        private int sectionIndex;
        private net.minecraft.world.level.chunk.LevelChunk chunk;
        private boolean done;
        private long visited;
        private final long total;

        ClearJob(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
            this.level = level;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.minY = level.getMinY();
            this.maxY = level.getMaxY();
            this.total = (long) (maxX - minX + 1) * (maxZ - minZ + 1)
                    * Math.max(0, maxY - minY + 1);
            for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
                for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                    chunks.add((((long) cx) << 32) ^ (cz & 0xffffffffL));
                }
            }
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public long totalWork() {
            return total;
        }

        @Override
        public long doneWork() {
            return visited;
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
                if (chunkIndex >= chunks.size()) {
                    done = true;
                    return written;
                }
                long key = chunks.get(chunkIndex);
                int cx = (int) (key >> 32);
                int cz = (int) key;
                if (chunk == null) {
                    chunk = level.getChunk(cx, cz);
                    sectionIndex = 0;
                }
                var sections = chunk.getSections();
                if (sectionIndex >= sections.length) {
                    chunk = null;
                    chunkIndex++;
                    continue;
                }
                int index = sectionIndex++;
                int sectionBottom = chunk.getSectionYFromSectionIndex(index) << 4;
                int y0 = Math.max(minY, sectionBottom);
                int y1 = Math.min(maxY, sectionBottom + 15);
                int x0 = Math.max(minX, cx << 4);
                int x1 = Math.min(maxX, (cx << 4) + 15);
                int z0 = Math.max(minZ, cz << 4);
                int z1 = Math.min(maxZ, (cz << 4) + 15);
                if (y1 < y0 || x1 < x0 || z1 < z0) {
                    continue;   // this section is outside the band, or the chunk outside the box
                }
                long cells = (long) (y1 - y0 + 1) * (x1 - x0 + 1) * (z1 - z0 + 1);
                visited += cells;
                scanned++;
                var section = sections[index];
                if (section.hasOnlyAir()) {
                    continue;   // the whole section answered in one call
                }
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                            var old = section.getBlockState(x & 15, y & 15, z & 15);
                            if (old.isAir()) {
                                continue;
                            }
                            section.setBlockState(x & 15, y & 15, z & 15, air, false);
                            // Take the old block entity with it, for the same reason the paste does: a chest
                            // paved over behind Level.setBlock's back leaves a ChestBlockEntity attached to a
                            // position that now holds air, and vanilla throws on the next walk of the chunk.
                            if (old.hasBlockEntity()) {
                                chunk.removeBlockEntity(new net.minecraft.core.BlockPos(x, y, z));
                            }
                            touched(x, z);
                            written++;
                        }
                    }
                }
                scanned += (int) Math.min(SCAN_BUDGET, cells);
                chunk.markUnsaved();
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
        lastBuiltBounds = null;
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
                    finishedWork += job.totalWork();
                    JOBS.poll();
                } else if (placed == 0) {
                    // The job did a tick's worth of work and wrote nothing, which is what hitting its SCAN
                    // budget looks like. Charging Math.max(1, placed) for that let a clear over a void world -
                    // where almost every position is already air - run this loop a hundred and fifty thousand
                    // times on one server tick, holding this class's monitor the whole way, while the loading
                    // screen's own synchronized progress() waited behind it on the render thread. That is the
                    // freeze this budget exists to prevent, re-created by the charge being wrong rather than
                    // the budget being missing.
                    break;
                }
            }
            if (JOBS.isEmpty() && !touchedChunks.isEmpty()) {
                // The finishing pass is WORK, not a formality, so it goes through the queue like everything
                // else. killer560 (2026-09-28): "i loaded in then it froze my game and I had to close it after
                // I actually loaded the map." Doing it in one burst meant relighting two hundred chunks on the
                // server thread and then handing the client two hundred full chunk packets in a single tick -
                // the client has to rebuild a mesh for every one of them, which is a freeze on his side even
                // though the build itself had finished.
                // The bounds are kept BEFORE the set is emptied. They are what the next build clears instead
                // of sweeping the whole grid, and losing them here would quietly undo that - the next load
                // would go back to four million reads and look like the speed fix had been reverted.
                // Unioned, not replaced: a job queued by something other than a build (nothing today) must not
                // shrink the box the next wipe clears.
                lastBuiltBounds = union(boundsOf(touchedChunks), lastBuiltBounds);
                JOBS.add(new FinishJob(server, new ArrayList<>(touchedChunks)));
                touchedChunks.clear();
            }
            if (JOBS.isEmpty()) {
                long ms = System.currentTimeMillis() - startedAtMs;
                LOGGER.info("Sim build took {} ms for {} block(s) across {} job(s)", ms, placedTotal, jobsTotal);
                finishedWork = 0;
                buildsFinished++;
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
