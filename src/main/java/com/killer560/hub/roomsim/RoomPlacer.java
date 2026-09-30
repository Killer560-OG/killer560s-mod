package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Writes a captured {@link RoomLibrary.Room} back into a world.
 *
 * <p>Everything here is coordinate/registry plumbing except the rotation, which is the one part a route practice
 * session cannot survive being wrong (killer560's own framing of this task: a room placed at the wrong rotation
 * makes every route practised in it wrong). So the coordinate rotation is not an original formula - it is the
 * exact transform vanilla itself uses, recovered by disassembling {@code StructureTemplate.transform(BlockPos,
 * Mirror, Rotation, BlockPos)} with {@code javap -c} against the mapped 26.1.2 jar (Mirror.NONE branch only,
 * since a room is never mirrored here). With dx = x - pivotX, dz = z - pivotZ, that method's bytecode is:
 * <pre>
 *   CLOCKWISE_90:        (dx, dz) -&gt; (-dz,  dx)
 *   CLOCKWISE_180:       (dx, dz) -&gt; (-dx, -dz)
 *   COUNTERCLOCKWISE_90: (dx, dz) -&gt; ( dz, -dx)
 * </pre>
 * (Confirmed against the {@code StructureTemplate$1} switch-map static initializer, which is the only place the
 * enum's ordinals are pinned to those switch cases - the ordinal order alone is not enough to trust.)
 * {@link #rotateLocal} below is that same transform, specialised to a pivot at the room's own centre and written
 * for plain ints so {@link #selfCheckRotation()} can exercise it with no world at all.
 */
public final class RoomPlacer {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-roomsim");

    /**
     * Bulk-placement flag, verified against vanilla's own usage rather than guessed.
     *
     * <p>{@code javap -c} on {@code StructureTemplate.placeInWorld} shows its main block-writing pass calls
     * {@code setBlock(pos, state, 820)}, where 820 = {@link Block#UPDATE_SKIP_ALL_SIDEEFFECTS} (816: skip
     * on-place, skip block-entity side effects, suppress drops, known-shape) plus {@link Block#UPDATE_INVISIBLE}
     * (4). Neither that flag nor any other {@code UPDATE_*} constant in this version includes
     * {@link Block#UPDATE_NEIGHBORS} (1) - that bit is what would cascade into one shape recalculation per
     * neighbour for every one of a room's ~78,000 blocks (31 x 31 x 81) and hang the server, so it stays off here
     * too. But vanilla's 820 also drops {@link Block#UPDATE_CLIENTS} (2), which is fine for world-gen (the chunk
     * has not been sent to anyone yet) and wrong here: this runs on a level a player is already standing in, so
     * the client must be told. Flag used: {@code UPDATE_CLIENTS | UPDATE_SKIP_ALL_SIDEEFFECTS}.
     */
    /**
     * Flags for a bulk build.
     *
     * <p>{@link Block#UPDATE_CLIENTS} is deliberately NOT here, and that is the single biggest reason a floor
     * now loads in seconds rather than minutes. killer560 (2026-09-28): "I had it start creating a dungeon sim
     * floor and i got bored waiting after about a minute [...] It should load far faster nearly instantly."
     *
     * <p>With that flag, every one of roughly two million block writes queues a client update. The tick counts
     * were never the problem - sixteen ticks of clearing and fifty of pasting is three seconds - the per-block
     * cost was, and almost all of it was telling the client about a block it was about to be told about anyway.
     * The chunks are sent once at the end instead: about two hundred packets in place of two million.
     */
    private static final int PLACE_FLAGS = Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    /** Same flags for wiping a region before a paste - see {@code SimBuildQueue.ClearJob}. */
    public static final int CLEAR_FLAGS = PLACE_FLAGS;

    /** Positions a paste may look at in one tick, as opposed to write. */
    private static final int SCAN_BUDGET = 400_000;

    /**
     * Writes a block straight into its chunk section, bypassing {@code Level.setBlock}.
     *
     * <p>killer560 (2026-09-28): "You still need a way to make the map load faster it takes all of the time
     * and fails still." This is the answer, and it is the difference between the right tool and the wrong one:
     * {@code setBlock} is built for ONE block changing in a live world, so every call re-finds the chunk,
     * re-finds the section, updates heightmaps, pokes the light engine and checks for a block entity. Paying
     * all of that two million times is where the minutes went.
     *
     * <p>A bulk build wants the opposite shape - find the chunk and section once, write many blocks, then fix
     * up heightmaps, lighting and the client ONCE at the end. That is what this does, with the caller keeping
     * the section between writes.
     *
     * <p>The catch is real and is handled at the end of the build rather than ignored: nothing here updates
     * lighting or heightmaps, so a build that stopped after this would be a correctly-shaped room in the dark.
     * {@link #finishChunks} does that pass.
     */
    static final class SectionWriter {

        private final ServerLevel level;
        private net.minecraft.world.level.chunk.LevelChunk chunk;
        private int chunkX = Integer.MIN_VALUE;
        private int chunkZ = Integer.MIN_VALUE;
        private net.minecraft.world.level.chunk.LevelChunkSection section;
        private int sectionIndex = Integer.MIN_VALUE;

        SectionWriter(ServerLevel level) {
            this.level = level;
        }

        /** @return whether the block was written */
        boolean set(int x, int y, int z, BlockState state) {
            // The whole floor is shifted once - see SimAltitude. Applied HERE, at the one place every pasted
            // block passes through, rather than at each of the dozen callers that walk a captured y.
            y += SimAltitude.offset();
            int cx = x >> 4;
            int cz = z >> 4;
            if (cx != chunkX || cz != chunkZ || chunk == null) {
                chunk = level.getChunk(cx, cz);
                chunkX = cx;
                chunkZ = cz;
                sectionIndex = Integer.MIN_VALUE;
            }
            int idx = chunk.getSectionIndex(y);
            if (idx < 0 || idx >= chunk.getSections().length) {
                return false;
            }
            if (idx != sectionIndex) {
                section = chunk.getSections()[idx];
                sectionIndex = idx;
            }
            BlockState old = section.setBlockState(x & 15, y & 15, z & 15, state, false);
            // Take the old BLOCK ENTITY with it.
            //
            // Writing into the section behind Level.setBlock's back skips everything setBlock does, and one
            // of those things is removing the block entity of whatever was there. Paving over a secret chest
            // left a ChestBlockEntity attached to a position now holding stone, and vanilla throws
            // "Invalid block entity minecraft:chest ... got Block{minecraft:stone}" when it next walks the
            // chunk - the gametest log filled with them (2026-09-29). Costs nothing in the normal case:
            // hasBlockEntity is a flag on the state, so only the handful of positions that really had one
            // pay for the removal.
            if (old.hasBlockEntity() && !state.is(old.getBlock())) {
                chunk.removeBlockEntity(new net.minecraft.core.BlockPos(x, y, z));
            }
            chunk.markUnsaved();
            return true;
        }
    }

    /**
     * Puts right everything the fast path skipped, once per chunk.
     *
     * <p>Block counts first, because a section that was written behind its own back does not know how many
     * non-air blocks it holds, and an empty-looking section is not sent to the client at all - which would
     * show up as half a room missing rather than as a lighting bug.
     */
    static void finishChunks(ServerLevel level, java.util.Collection<Long> chunkKeys) {
        for (long key : chunkKeys) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            var chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
                continue;
            }
            for (var section : chunk.getSections()) {
                section.recalcBlockCounts();
            }
            chunk.markUnsaved();
            level.getChunkSource().getLightEngine()
                    .propagateLightSources(new net.minecraft.world.level.ChunkPos(cx, cz));
        }
    }

    /** Stand-in floor for a column the recorder has not seen yet, and the height it goes at. */
    private static final net.minecraft.world.level.block.state.BlockState MARKER =
            net.minecraft.world.level.block.Blocks.RED_CONCRETE.defaultBlockState();

    /** Dungeon floors sit here, so a placeholder at this height lines up with the real ones either side. */
    private static final int MARKER_Y = 69;

    /**
     * The wall margin a captured room was taken with, read off its own size.
     *
     * <p>Captures made before the wall fix are a whole number of 31-block tiles; ones made after carry an extra
     * column each side, so their size is a multiple of 31 plus 2. Deriving it per room rather than assuming the
     * current format is what lets old captures and the synthetic test room keep placing exactly where they
     * always did - assuming it shifted every one of them a block west, which the sim gametest caught by
     * finding the orientation marker one block off.
     */
    /**
     * The room's own recorded margin.
     *
     * <p>Was inferred from the size with {@code size % TILE == WALL_MARGIN * 2}. That inference was only ever
     * right by accident - see {@link RoomLibrary#footprint} - and once the footprint was corrected a 97-wide
     * three-tile room would have failed it and pasted a block off. {@code RoomLibrary} writes the margin into
     * the file now and fills it in for older ones on load, so this just reads it.
     */
    private static int marginOf(RoomLibrary.Room room) {
        return room.margin;
    }

    /** Palette strings already warned about this JVM run, so a renamed/removed block warns once, not per block. */
    private static final Set<String> WARNED_UNRESOLVED = new HashSet<>();

    private RoomPlacer() {
    }

    /**
     * Pastes {@code room} into {@code level} with its (unrotated) top-left corner anchored at grid cell
     * {@code (gridX, gridZ)} - the same {@code DungeonLayout.cellCenter(gridZ * DungeonLayout.GRID + gridX)}
     * minus half a tile that {@link RoomLibrary#capture} used to establish that corner in the first place, so
     * pasting at the room's own capture coordinates with {@code rotation == 0} reproduces the original position
     * exactly.
     *
     * <p>{@code rotation} is degrees clockwise (viewed from above, +X east / +Z south), one of 0/90/180/270 -
     * matching {@link Rotation#CLOCKWISE_90}'s own sense, verified above. It is applied about the room's centre,
     * to BOTH the block positions (via {@link #rotateLocal}) and each block's facing/rotation properties (via
     * vanilla {@link BlockState#rotate(Rotation)}, which already knows how to turn a stair, door, lever, sign or
     * banner without needing to be told which properties exist). Any other value throws.
     *
     * <p>Palette index -1 ("never read") is skipped, not written as air, so an incomplete capture never punches
     * a hole in whatever is already there. A palette entry that no longer resolves to a real block (renamed since
     * capture) is skipped too, with one warning for that string rather than one per block.
     *
     * <p><b>Known limitation, not something this method can fix:</b> {@link RoomLibrary#capture} only ever
     * stored {@code BuiltInRegistries.BLOCK.getKey(state.getBlock())} - the block's registry id, not its
     * {@link BlockState} - so every placed block starts from {@code Block.defaultBlockState()}. Rotation is
     * still applied correctly to that default state, but a captured stair/door's original facing was already
     * discarded before it ever reached the palette; this method has nothing left to reproduce it from.
     *
     * @return how many blocks were actually written (excludes skipped -1 columns and unresolved palette entries)
     */
    /**
     * A paste in progress, so it can be done a slice at a time.
     *
     * <p>Exists because doing it in one go froze the game - see {@link SimBuildQueue}. The placement itself is
     * unchanged and still lives in {@link #paste}; this only remembers how far through it is, by keeping the
     * three loop counters as fields instead of on the stack.
     */
    public static final class PasteJob implements SimBuildQueue.Job {

        private final ServerLevel level;
        private final RoomLibrary.Room room;
        private final Rotation vanillaRotation;
        private final int rotation;
        private final int worldX0;
        private final int worldZ0;

        private int y;
        private int x;
        private int z;
        private boolean done;
        private long visited;
        private final long total;
        private final SectionWriter writer;
        /**
         * Where the "never captured" marker goes for THIS room.
         *
         * <p>{@link #MARKER_Y} is the dungeon floor line, but a room's capture band no longer has to contain
         * it - a trimmed capture can start above it or end below it - so it is clamped into the band. A y
         * outside the band would place no marker at all and the missing column would be a hole again.
         */
        private final int markerY;

        public PasteJob(ServerLevel level, RoomLibrary.Room room, int gridX, int gridZ, int rotation) {
            if (room == null) {
                throw new IllegalArgumentException("room is null");
            }
            this.level = level;
            this.room = room;
            this.rotation = rotation;
            this.vanillaRotation = toVanillaRotation(rotation);
            // The ROOM's band, not the global one. A capture is trimmed to its own content, so walking
            // -64..320 for a room that holds y 60..140 both crashed on the read and made the progress bar
            // five times longer than the work.
            this.y = room.minY;
            this.markerY = Math.min(room.maxY, Math.max(room.minY, MARKER_Y));
            this.total = (long) room.sizeX * room.sizeZ * (room.maxY - room.minY + 1);
            this.writer = new SectionWriter(level);
            BlockPos origin = DungeonLayout.cellCenter(gridZ * DungeonLayout.GRID + gridX);
            // The same offset capture used, including the wall margin. If these two ever disagree every room
            // lands a block off its neighbours and the seams stop lining up.
            this.worldX0 = origin.getX() - RoomLibrary.TILE / 2 - marginOf(room);
            this.worldZ0 = origin.getZ() - RoomLibrary.TILE / 2 - marginOf(room);
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

        /**
         * Places up to {@code budget} blocks and returns how many landed.
         *
         * <p>The count is of blocks WRITTEN, not of positions considered. A room is mostly air that was never
         * captured, and those are skipped without costing anything, so charging the budget for them would make
         * a sparse room take as many ticks as a solid one for no work.
         */
        @Override
        public int step(int budget) {
            int placed = 0;
            int scanned = 0;
            // Bounded by what it LOOKS at as well as what it writes. A room is mostly uncaptured air that
            // costs nothing to write and is not free to walk past - eighty-eight thousand positions for a
            // 1x1, most of them skipped - so without this a sparse room could run the whole room in one tick.
            while (placed < budget && scanned < SCAN_BUDGET) {
                scanned++;
                visited++;
                if (y > room.maxY) {
                    done = true;
                    return placed;
                }
                short paletteIdx = room.at(x, y, z);
                if (paletteIdx < 0 && y == markerY && !room.seenColumn[z * room.sizeX + x]) {
                    // A column that was never captured. Before the sim cleared the ground these showed as
                    // flat-world dirt; now they would be a hole you fall through, which is how killer560 saw
                    // "the floor sometimes" missing after the clearing landed.
                    //
                    // Marked rather than quietly floored with something that looks real: a room is only
                    // partly captured because he has not walked that part yet, and a patch that blends in
                    // would have him practising a route across ground that may not be there. Red concrete
                    // says "this is not the dungeon" at a glance and is still something to stand on.
                    int[] local = rotateLocal(x, z, room.sizeX, room.sizeZ, rotation);
                    writer.set(worldX0 + local[0], y, worldZ0 + local[1], MARKER);
                    SimBuildQueue.touched(worldX0 + local[0], worldZ0 + local[1]);
                    placed++;
                }
                if (paletteIdx >= 0) {
                    BlockState state = resolve(room.palette.get(paletteIdx));
                    if (state != null) {
                        int[] local = rotateLocal(x, z, room.sizeX, room.sizeZ, rotation);
                        writer.set(worldX0 + local[0], y, worldZ0 + local[1], state.rotate(vanillaRotation));
                        SimBuildQueue.touched(worldX0 + local[0], worldZ0 + local[1]);
                        placed++;
                    }
                }
                // Same iteration order as paste(): z fastest, then x, then y.
                if (++z >= room.sizeZ) {
                    z = 0;
                    if (++x >= room.sizeX) {
                        x = 0;
                        y++;
                    }
                }
            }
            return placed;
        }
    }

    public static int paste(ServerLevel level, RoomLibrary.Room room, int gridX, int gridZ, int rotation) {
        if (room == null) {
            throw new IllegalArgumentException("room is null");
        }
        Rotation vanillaRotation = toVanillaRotation(rotation);

        BlockPos origin = DungeonLayout.cellCenter(gridZ * DungeonLayout.GRID + gridX);
        int worldX0 = origin.getX() - RoomLibrary.TILE / 2 - marginOf(room);
        int worldZ0 = origin.getZ() - RoomLibrary.TILE / 2 - marginOf(room);

        int sizeX = room.sizeX;
        int sizeZ = room.sizeZ;
        int placed = 0;
        for (int y = room.minY; y <= room.maxY; y++) {
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    short paletteIdx = room.at(x, y, z);
                    if (paletteIdx < 0) {
                        continue; // never read at capture time - leave whatever is already there
                    }
                    BlockState state = resolve(room.palette.get(paletteIdx));
                    if (state == null) {
                        continue; // unresolved palette entry - already warned once in resolve()
                    }
                    int[] local = rotateLocal(x, z, sizeX, sizeZ, rotation);
                    BlockPos pos = new BlockPos(worldX0 + local[0],
                            SimAltitude.toWorld(y), worldZ0 + local[1]);
                    level.setBlock(pos, state.rotate(vanillaRotation), PLACE_FLAGS);
                    placed++;
                }
            }
        }
        return placed;
    }

    /**
     * Resolves one palette string to a block state, or null (and one warning) if it no longer exists.
     *
     * <p>Parses the FULL state, not just the block id. The library stores entries as
     * {@code minecraft:stone_brick_stairs[facing=north,half=bottom,...]}, so a stair comes back facing the way
     * it was captured rather than the way its default state happens to point - which is the difference between
     * a rebuilt room being usable and being subtly wrong everywhere a stair or door appears.
     *
     * <p>A bare id still works: entries captured before the palette held states parse straight to the default,
     * which is exactly what they meant.
     */
    private static BlockState resolve(String paletteEntry) {
        try {
            return net.minecraft.commands.arguments.blocks.BlockStateParser
                    .parseForBlock(BuiltInRegistries.BLOCK, paletteEntry, false)
                    .blockState();
        } catch (Exception e) {
            // One warning per distinct entry, not per block: a renamed block appears thousands of times in a
            // room and the log would be useless.
            if (WARNED_UNRESOLVED.add(paletteEntry)) {
                LOGGER.warn("Room library palette entry \"{}\" no longer resolves - skipping every block that "
                        + "used it (renamed or removed since capture?)", paletteEntry);
            }
            return null;
        }
    }

    private static Rotation toVanillaRotation(int degrees) {
        return switch (degrees) {
            case 0 -> Rotation.NONE;
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalArgumentException(
                    "rotation must be 0, 90, 180 or 270 degrees, got " + degrees);
        };
    }

    /**
     * Rotates one local (x, z) about the room's own centre, clockwise by {@code degrees} (0/90/180/270 - see
     * {@link #paste}). Plain ints, no Minecraft classes, so {@link #selfCheckRotation()} can call this directly
     * with no world.
     *
     * <p>Derived from the vanilla transform documented on the class, specialised to a pivot at the room's centre
     * instead of an arbitrary {@code BlockPos}, and worked through in centre-relative doubled coordinates
     * (X2 = 2x - (sizeX-1), so it stays exactly on the pivot with no fractions) to get back to plain array
     * indices for a rotated room whose dimensions swap on a 90/270 turn:
     * <pre>
     *   0:   (x, z)
     *   90:  (sizeZ-1-z, x)          - new bounds are sizeZ x sizeX
     *   180: (sizeX-1-x, sizeZ-1-z)
     *   270: (z, sizeX-1-x)          - new bounds are sizeZ x sizeX
     * </pre>
     * Composing the 90-degree case with itself twice reduces algebraically to the 180 case, and composing it
     * four times returns the identity - both checked by hand when this was derived, and both exercised again at
     * runtime by {@link #selfCheckRotation()}.
     *
     * @return {@code {newX, newZ}} in the rotated room's own coordinate space - on a 90/270 turn that space is
     *     {@code sizeZ} wide by {@code sizeX} deep instead of the original {@code sizeX} by {@code sizeZ}, which
     *     is exactly why {@link #paste} only ever adds this result to a fixed world origin rather than assuming
     *     the room's footprint keeps its original dimensions
     */
    static int[] rotateLocal(int x, int z, int sizeX, int sizeZ, int degrees) {
        return switch (degrees) {
            case 0 -> new int[]{x, z};
            case 90 -> new int[]{sizeZ - 1 - z, x};
            case 180 -> new int[]{sizeX - 1 - x, sizeZ - 1 - z};
            case 270 -> new int[]{z, sizeX - 1 - x};
            default -> throw new IllegalArgumentException(
                    "rotation must be 0, 90, 180 or 270 degrees, got " + degrees);
        };
    }

    /**
     * Needs no world: builds a synthetic 3x3 room in memory, marks its (0,0) corner with a distinctive palette
     * entry, and checks {@link #rotateLocal} sends that corner where a real 90-degree-at-a-time clockwise spin
     * must send it - NW -&gt; NE -&gt; SE -&gt; SW as rotation goes 0 -&gt; 90 -&gt; 180 -&gt; 270. That physical
     * expectation, not just "some formula", is what is being checked.
     *
     * @return true if all four rotations land the corner where expected
     */
    public static boolean selfCheckRotation() {
        int size = 3;
        RoomLibrary.Room room = new RoomLibrary.Room("selfcheck", size, size);
        String marker = "killer560smod:selfcheck_marker";
        int markerIdx = room.paletteFor(marker);
        room.blocks[room.index(0, room.minY, 0)] = (short) markerIdx;

        int[][] expected = {
                {0, 0}, // 0 deg:  NW stays NW
                {2, 0}, // 90 deg: NW -> NE
                {2, 2}, // 180 deg: NW -> SE
                {0, 2}, // 270 deg: NW -> SW
        };
        int[] degreesInOrder = {0, 90, 180, 270};

        boolean allOk = true;
        for (int i = 0; i < degreesInOrder.length; i++) {
            int degrees = degreesInOrder[i];
            // Cross-check via the actual room data, not just the raw formula: find where the marker landed by
            // scanning the rotated space, the same way paste() would place it.
            int foundX = -1;
            int foundZ = -1;
            for (int x = 0; x < room.sizeX && foundX < 0; x++) {
                for (int z = 0; z < room.sizeZ; z++) {
                    if (room.at(x, room.minY, z) == markerIdx) {
                        int[] rotated = rotateLocal(x, z, room.sizeX, room.sizeZ, degrees);
                        foundX = rotated[0];
                        foundZ = rotated[1];
                        break;
                    }
                }
            }
            boolean ok = foundX == expected[i][0] && foundZ == expected[i][1];
            if (!ok) {
                LOGGER.warn("selfCheckRotation: {} degrees landed the NW corner at ({},{}), expected ({},{})",
                        degrees, foundX, foundZ, expected[i][0], expected[i][1]);
            }
            allOk &= ok;
        }
        return allOk;
    }
}
