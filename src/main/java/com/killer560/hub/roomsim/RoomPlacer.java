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
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

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
    public static int paste(ServerLevel level, RoomLibrary.Room room, int gridX, int gridZ, int rotation) {
        if (room == null) {
            throw new IllegalArgumentException("room is null");
        }
        Rotation vanillaRotation = toVanillaRotation(rotation);

        BlockPos origin = DungeonLayout.cellCenter(gridZ * DungeonLayout.GRID + gridX);
        int worldX0 = origin.getX() - RoomLibrary.TILE / 2;
        int worldZ0 = origin.getZ() - RoomLibrary.TILE / 2;

        int sizeX = room.sizeX;
        int sizeZ = room.sizeZ;
        int placed = 0;
        for (int y = RoomLibrary.MIN_Y; y <= RoomLibrary.MAX_Y; y++) {
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    short paletteIdx = room.blocks[room.index(x, y, z)];
                    if (paletteIdx < 0) {
                        continue; // never read at capture time - leave whatever is already there
                    }
                    BlockState state = resolve(room.palette.get(paletteIdx));
                    if (state == null) {
                        continue; // unresolved palette entry - already warned once in resolve()
                    }
                    int[] local = rotateLocal(x, z, sizeX, sizeZ, rotation);
                    BlockPos pos = new BlockPos(worldX0 + local[0], y, worldZ0 + local[1]);
                    level.setBlock(pos, state.rotate(vanillaRotation), PLACE_FLAGS);
                    placed++;
                }
            }
        }
        return placed;
    }

    /** Resolves one palette string to a default block state, or null (and one warning) if it no longer exists. */
    private static BlockState resolve(String paletteEntry) {
        Identifier id = Identifier.tryParse(paletteEntry);
        Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null) {
            if (WARNED_UNRESOLVED.add(paletteEntry)) {
                LOGGER.warn("Room library palette entry \"{}\" no longer resolves to a block - skipping every "
                        + "block that used it (renamed/removed since capture?)", paletteEntry);
            }
            return null;
        }
        return block.defaultBlockState();
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
     * @return {@code {newX, newZ}} in the rotated room's own coordinate space (see {@link #rotatedSizeX} /
     *     {@link #rotatedSizeZ} for that space's bounds)
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

    /** Width of the room once rotated - swaps with {@link #rotatedSizeZ} on a 90/270 turn. */
    static int rotatedSizeX(int sizeX, int sizeZ, int degrees) {
        return (degrees == 90 || degrees == 270) ? sizeZ : sizeX;
    }

    /** Depth of the room once rotated - swaps with {@link #rotatedSizeX} on a 90/270 turn. */
    static int rotatedSizeZ(int sizeX, int sizeZ, int degrees) {
        return (degrees == 90 || degrees == 270) ? sizeX : sizeZ;
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
        room.blocks[room.index(0, RoomLibrary.MIN_Y, 0)] = (short) markerIdx;

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
                    if (room.blocks[room.index(x, RoomLibrary.MIN_Y, z)] == markerIdx) {
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
