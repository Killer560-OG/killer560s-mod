package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /simwhere} - says which room a block is in and what its coordinates are in every system this codebase
 * uses for one.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Half the sim's remaining work is of the form "the lever by the chest", "the structure with the head on it",
 * "the wooden wall across and up from it", "the very back of the green room". Each one is a real, specific block
 * that killer560 can see and I cannot, and every attempt to find them by decoding a capture and reasoning about
 * which of eighteen levers he means has either cost a round trip or produced a confident wrong answer. Mines has
 * no {@code dark_oak_door} in its capture at all; Pressure Plates has eighteen levers where he described two;
 * Leaves has ten heads around the one chest.
 *
 * <p>So rather than guess again: he looks at the block and the game tells him its coordinates, in the two
 * systems the sim's own code is written in.
 *
 * <ul>
 *   <li><b>Capture-local</b> is what a block is called inside {@code rooms/<Room>.json}, and what
 *       {@code SimRoomPuzzles.capturedPos} and {@code capturedBlocks} take. It is the one to quote for "put a
 *       chest here" or "delete this door".</li>
 *   <li><b>Database-relative</b> is what the room database's own secret coordinates are in, and what
 *       {@code SimRoomPuzzles.Anchor.world} takes. It is the one the real solvers are written in - Ice Path's
 *       maze at {@code (23-col, 67, 24-row)}, the Quiz's answer spots, Tic Tac Toe's cells.</li>
 * </ul>
 *
 * <p>Both are printed because which one a given job needs depends on whether it is reading the capture or the
 * database, and getting that wrong is exactly the mistake that makes a bound puzzle land in the wrong corner.
 *
 * <p>Dev tooling: it reads blocks and prints chat, writes nothing, and is gated on being inside the sim.
 */
public final class SimWhere {

    private SimWhere() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("simwhere")
                        .requires(src -> SimState.canAct(Minecraft.getInstance()))
                        .executes(ctx -> {
                            report(Minecraft.getInstance());
                            return 1;
                        })));
    }

    private static void report(Minecraft client) {
        if (!SimState.canAct(client)) {
            ModChat.send("Sim", ModChat.text("/simwhere only works inside the sim."));
            return;
        }
        BlockPos pos = lookedAt(client);
        if (pos == null) {
            pos = client.player.blockPosition().below();
            ModChat.send("Sim", ModChat.dim("Not looking at a block - using the one under your feet."));
        }
        SimRoomIndex.Placed room = roomAt(pos);
        if (room == null) {
            ModChat.send("Sim", ModChat.text("World "), ModChat.value(str(pos)),
                    ModChat.dim(" - not inside any placed room."));
            return;
        }
        RoomLibrary.Room lib = RoomLibrary.get(room.name());
        int[] local = lib == null ? null : captureLocal(room, lib, pos);
        // toRelativeCoord passes y straight through, and the y it is handed is a WORLD y - which in here is
        // the floor's own shifted one, so it printed things like "db-relative (53, -22, 15)" when the real
        // room's y is 94. Undoing the shift is the same SimAltitude.toCaptured every other read of a sim
        // coordinate goes through, and it is what makes the two printed systems differ only in x/z, which is
        // the whole point of printing both.
        RoomEntry.Pos rel = RoomDatabase.toRelativeCoord(pos, room.clayX(), room.clayZ(), room.rotation());
        rel.y = SimAltitude.toCaptured(pos.getY());

        ModChat.send("Sim", ModChat.value(room.name()),
                ModChat.dim(" (pasted " + room.pasteRotation() + ", database rotation " + room.rotation() + ")"));
        ModChat.send("Sim", ModChat.text("block "),
                ModChat.value(client.level.getBlockState(pos).getBlock().toString()));
        ModChat.send("Sim", ModChat.text("capture-local "),
                ModChat.value(local == null ? "?" : "(" + local[0] + ", " + local[1] + ", " + local[2] + ")"));
        ModChat.send("Sim", ModChat.text("db-relative   "),
                ModChat.value("(" + rel.x + ", " + rel.y + ", " + rel.z + ")"));
        // The room's own measured nudge, because it is part of what a SOLVER'S coordinate goes through -
        // PuzzleCoords adds it on top of the floor shift. Nearly always 0; Ice Fill's capture is a block low.
        int nudge = SimRoomPuzzles.dyFor(room.name());
        if (nudge != 0) {
            ModChat.send("Sim", ModChat.text("room nudge    "),
                    ModChat.value(nudge > 0 ? "+" + nudge : String.valueOf(nudge)),
                    ModChat.dim(" - this capture is measured off the bundled data, and solvers carry it"));
        }
        ModChat.send("Sim", ModChat.dim("world " + str(pos)));
    }

    /** The block the crosshair is on, out to a normal interaction reach, or null. */
    private static BlockPos lookedAt(Minecraft client) {
        Vec3 eye = client.player.getEyePosition(1f);
        Vec3 end = eye.add(client.player.getViewVector(1f).scale(6.0));
        HitResult hit = client.level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                ? block.getBlockPos().immutable() : null;
    }

    /**
     * The placed room whose pasted capture covers this column, or null.
     *
     * <p>By the room's own footprint - the same origin and size the paste and {@link #captureLocal} use - not by
     * nearest grid cell. The grid this used to ask is the live map's 11 x 11, whose odd cells are the CONNECTORS
     * between tiles; a block in a room's outer rows is as near the connector's center as the tile's, often nearer,
     * and a connector belongs to no 1x1 room. So a gate in a room's wall read "not inside any placed room" (Spider's
     * facade and Atlas' log wall, 2026-10-16). Where two captures' margins overlap, the room whose footprint center
     * is nearer wins.
     */
    public static SimRoomIndex.Placed roomAt(BlockPos pos) {
        SimRoomIndex.Placed best = null;
        long bestDist = Long.MAX_VALUE;
        for (SimRoomIndex.Placed placed : SimRoomIndex.placed()) {
            RoomLibrary.Room lib = RoomLibrary.get(placed.name());
            if (lib == null) {
                continue;
            }
            BlockPos origin = DungeonLayout.cellCenter(placed.gridZ() * DungeonLayout.GRID + placed.gridX());
            int x0 = origin.getX() - RoomLibrary.TILE / 2 - lib.margin;
            int z0 = origin.getZ() - RoomLibrary.TILE / 2 - lib.margin;
            boolean turned = placed.pasteRotation() == 90 || placed.pasteRotation() == 270;
            int ex = turned ? lib.sizeZ : lib.sizeX;
            int ez = turned ? lib.sizeX : lib.sizeZ;
            if (pos.getX() < x0 || pos.getX() >= x0 + ex || pos.getZ() < z0 || pos.getZ() >= z0 + ez) {
                continue;
            }
            long dx = 2L * (pos.getX() - x0) - (ex - 1);
            long dz = 2L * (pos.getZ() - z0) - (ez - 1);
            long dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = placed;
            }
        }
        return best;
    }

    /** The capture-local coordinates /simwhere prints for this block, or null outside every room - for tests. */
    public static int[] captureLocalAt(BlockPos pos) {
        SimRoomIndex.Placed p = roomAt(pos);
        RoomLibrary.Room lib = p == null ? null : RoomLibrary.get(p.name());
        return lib == null ? null : captureLocal(p, lib, pos);
    }

    /** {@link #roomAt}'s room name, or null - for tests. */
    public static String roomNameAt(BlockPos pos) {
        SimRoomIndex.Placed p = roomAt(pos);
        return p == null ? null : p.name();
    }

    /**
     * The inverse of the paste transform: a world position back to the coordinate it has in the capture file.
     *
     * <p>{@code SimRoomPuzzles.capturedPos} is the forward direction and this undoes it step for step - the
     * same room origin, the same {@code RoomPlacer.rotateLocal}, run backward by rotating by the complement.
     * A quarter turn's inverse is the turn that completes the circle, which is why 90 and 270 swap here.
     */
    private static int[] captureLocal(SimRoomIndex.Placed room, RoomLibrary.Room lib, BlockPos pos) {
        BlockPos origin = DungeonLayout.cellCenter(room.gridZ() * DungeonLayout.GRID + room.gridX());
        int worldX0 = origin.getX() - RoomLibrary.TILE / 2 - lib.margin;
        int worldZ0 = origin.getZ() - RoomLibrary.TILE / 2 - lib.margin;
        int lx = pos.getX() - worldX0;
        int lz = pos.getZ() - worldZ0;
        // Undo rotateLocal. Its 90 case maps (x,z) -> (sizeZ-1-z, x), so the inverse of 90 is 270 applied to
        // the ROTATED dimensions - hence the swapped sizes on the quarter turns.
        int[] back = switch (room.pasteRotation()) {
            case 0 -> new int[]{lx, lz};
            case 90 -> new int[]{lz, lib.sizeZ - 1 - lx};
            case 180 -> new int[]{lib.sizeX - 1 - lx, lib.sizeZ - 1 - lz};
            case 270 -> new int[]{lib.sizeX - 1 - lz, lx};
            default -> new int[]{lx, lz};
        };
        return new int[]{back[0], SimAltitude.toCaptured(pos.getY()), back[1]};
    }

    private static String str(BlockPos p) {
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }
}
