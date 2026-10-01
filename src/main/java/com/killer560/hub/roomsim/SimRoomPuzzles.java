package com.killer560.hub.roomsim;

import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.roomsim.puzzles.SimBlazePuzzle;
import com.killer560.hub.roomsim.puzzles.SimBoulderPuzzle;
import com.killer560.hub.roomsim.puzzles.SimCreeperPuzzle;
import com.killer560.hub.roomsim.puzzles.SimIceFillPuzzle;
import com.killer560.hub.roomsim.puzzles.SimIcePathPuzzle;
import com.killer560.hub.roomsim.puzzles.SimQuizPuzzle;
import com.killer560.hub.roomsim.puzzles.SimTeleportMazePuzzle;
import com.killer560.hub.roomsim.puzzles.SimTicTacToePuzzle;
import com.killer560.hub.roomsim.puzzles.SimWaterPuzzle;
import com.killer560.hub.util.ModLog;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Arms the puzzle rooms of a floor the sim just built.
 *
 * <p>killer560 (2026-09-30): "none of the puzzles do anything. They are all rooms that exist but they have
 * none of the actual attributes of the puzzle."
 *
 * <p>He was right and the reason was structural. The classes in {@code roomsim/puzzles} were only ever
 * reachable from {@code /simpuzzle <name>}, which builds a standalone arena four blocks in front of him, and
 * from {@code SimPuzzles.buildAt} which the gametests call. Nothing in the floor-building path -
 * {@code SimBuilder.build}, {@code SimFloorGen}, {@code SimBuildQueue} - ever built or armed one, so a
 * generated floor pasted the captured puzzle-room geometry and attached no rules to it. Several of those
 * classes even say in a comment that their wiring was left undone. This is that wiring.
 *
 * <h2>The furniture is already in the room - measured, not assumed</h2>
 *
 * Before any of this was written the eleven {@code PUZZLE} rooms' captures were decoded and each real solver's
 * own room-relative coordinate table was mapped into them at all four rotations. The answer decides the design
 * per puzzle, and it is not the same answer for all of them:
 *
 * <ul>
 *   <li><b>Teleport Maze</b> - all 30 of {@code TeleportMazeSolverFeature.PADS} land on an
 *       {@code end_portal_frame} in the capture (30/30 at rotation 0, 0-1/30 at the other three).</li>
 *   <li><b>Water Board</b> - all 7 of {@code WaterSolverFeature}'s levers land on a {@code lever} (7/7 at
 *       rotation 270).</li>
 *   <li><b>Creeper Beams</b> - all 22 lanterns of {@code creeper-beams-solutions.json} land on a
 *       {@code sea_lantern} or a {@code prismarine} (22/22 at rotation 0 - 13 of them were already
 *       {@code prismarine}, i.e. that room was captured part-solved, which is why arming resets them).</li>
 *   <li><b>Tic Tac Toe</b> - 8 of the 9 board cells at relative {@code x=8, y 70..72, z 15..17} hold a
 *       {@code stone_button} (rotation 180). The marks themselves are map ITEM FRAMES, which are entities and
 *       so can never be in a capture; the ninth button is missing because that cell was already played.</li>
 *   <li><b>Boulder</b> - the push buttons are there (a clean row of seven at relative {@code z=10},
 *       {@code x} 6/9/12/15/18/21/24, which is {@code BoulderSolverFeature}'s own {@code X_VALUES}) and that
 *       row is what identifies rotation 270. The boulder ARRANGEMENT, however, is not one of the eight
 *       bundled ones - the closest is 10 of 42 tiles away - so there is no known solution for it and the
 *       puzzle has to write a bundled pattern into the room's own grid before it can be solvable.</li>
 *   <li><b>Ice Fill</b> - every pattern of all three bundled floors lands entirely on ice, at rotation 270
 *       and <b>one block lower</b> than the solver's y. That capture sits a block below the reference the
 *       bundled data was measured in; it is the only one of the eleven that does, so the nudge is searched
 *       for rather than hard-coded, and logged when it is used.</li>
 *   <li><b>Quiz</b> and <b>Three Weirdos</b> - Oruo and the three weirdos are ARMOUR STANDS and the answer
 *       spots are plain floor. There is no puzzle furniture to bind to, so the labelled chests are created,
 *       but at {@code QuizSolverFeature}'s own real answer coordinates rather than in a line of three.</li>
 *   <li><b>Higher/Lower Blaze</b> - blazes are entities. Nothing about their placement is bundled anywhere in
 *       this repo, so the positions stay this mod's own invention; what changes is that they are now spawned
 *       in an air pocket found by scanning the real room instead of wherever he happened to be standing.</li>
 *   <li><b>Ice Path</b> - the whole puzzle is in the capture. At database rotation 180 all 289 board cells
 *       have {@code packed_ice} under them, the border ring is 65 of 65 {@code polished_andesite}, and the
 *       three cells that ring is missing are exactly {@code IcePathSolverFeature}'s exit columns, so the ring
 *       is what identifies the room (33, 5 and 4 of 65 at the other three rotations). Arming it writes no
 *       blocks at all: it reads the maze out of the room and spawns the one thing a block capture cannot
 *       hold, the silverfish.</li>
 * </ul>
 *
 * <h2>Rotation</h2>
 *
 * Every coordinate goes through {@link RoomDatabase#toRealCoord} with the clay corner from
 * {@link SimSecrets#clayCorner(RoomLibrary.Room, int, int, int, int)} - the same two calls
 * {@link SimSecrets} and {@link SimRoomIndex} already use, not a second transform. That matters for the
 * reason {@code docs/SIM.md} records: the rotation a coordinate has to be turned by is the PASTE rotation
 * plus the capture's own turn ({@link RoomCaptureRotation}), and those differ for 88 of his 122 identifiable
 * rooms. A puzzle that used the paste rotation alone would work only in rooms that happen to be pasted at 0
 * AND captured canonically.
 *
 * <p>Because a capture's turn is RECOVERED rather than recorded, and {@code docs/SIM.md} puts that recovery
 * at 120 of 135 rooms, every bind also CHECKS itself: {@link #bestAnchor} scores the puzzle's own furniture
 * at all four rotations and takes the winner, preferring the recorded one on a tie. That is cheap - a few
 * dozen block reads per puzzle - and it is the difference between a puzzle that is wrong silently and one
 * that says so in the log.
 *
 * <h2>Threading</h2>
 *
 * {@link #armFloor} runs on the integrated server thread, from {@code SimBuilder}'s {@code whenDone} block
 * after the rooms are pasted and the doorways carved - exactly where {@link SimSecrets#place} runs, and for
 * the same reason: anything written before the paste reaches that cell is simply pasted over. It reads and
 * writes blocks directly, as {@code SimSecrets} does. Nothing here touches the client; the two chat lines go
 * through {@code Minecraft.execute}.
 */
public final class SimRoomPuzzles {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    private SimRoomPuzzles() {
    }

    /**
     * One placed puzzle room, in the shape a bind needs.
     *
     * @param pasteRotation the rotation the room was physically pasted at - decides the FOOTPRINT
     * @param dbRotation the paste rotation plus the capture's own turn - decides which corner the database's
     *                   relative coordinates are measured from, and which way they run
     */
    public record Placement(RoomLibrary.Room room, int gridX, int gridZ, int pasteRotation, int dbRotation) {

        /** The anchor this placement was recorded with. */
        public Anchor anchor() {
            return anchorAt(dbRotation, 0);
        }

        /** The anchor for a different database rotation and vertical nudge, for {@link #bestAnchor}. */
        public Anchor anchorAt(int rotation, int dy) {
            int[] clay = SimSecrets.clayCorner(room, gridX, gridZ, pasteRotation, rotation);
            return new Anchor(room.name, clay[0], clay[1], rotation, dy);
        }
    }

    /**
     * Where a room's relative coordinates land in the world.
     *
     * @param dy a vertical correction, used only where a capture has been MEASURED to sit off the reference
     *           the bundled data was taken in - see the class doc on Ice Fill. 0 everywhere else.
     */
    public record Anchor(String room, int clayX, int clayZ, int rotation, int dy) {

        /** A room-relative block in the world the floor was built into. */
        public BlockPos world(int rx, int ry, int rz) {
            RoomEntry.Pos rel = new RoomEntry.Pos();
            rel.x = rx;
            rel.y = ry + dy;
            rel.z = rz;
            BlockPos real = RoomDatabase.toRealCoord(rel, clayX, clayZ, rotation);
            // toRealCoord passes y through untouched, so the floor's own shift has to be added here - the
            // same SimAltitude.toWorld every other read of a captured coordinate goes through.
            return new BlockPos(real.getX(), SimAltitude.toWorld(real.getY()), real.getZ());
        }

        public BlockPos world(int[] rel) {
            return world(rel[0], rel[1], rel[2]);
        }
    }

    /** A predicate for "is one of these blocks", for the self-checks below. */
    public static Predicate<BlockState> is(Block... blocks) {
        return state -> {
            for (Block b : blocks) {
                if (state.is(b)) {
                    return true;
                }
            }
            return false;
        };
    }

    /** How many of {@code rels} hold a matching block at this anchor. */
    public static int hits(ServerLevel level, Anchor anchor, List<int[]> rels, Predicate<BlockState> ok) {
        return hits(level, anchor, rels, (lv, pos) -> ok.test(lv.getBlockState(pos)));
    }

    /**
     * The same count, for a test that has to look at more than the one block.
     *
     * <p>Quiz needs this: its three answer spots are plain floor, and "is it solid" cannot tell rotation 0
     * from rotation 180 in that capture - both put smooth stone under all three. "Is it solid AND is there
     * standing room above it" separates them 3 to 1, because at the wrong turn two of the three spots are
     * buried inside the room's own terracotta.
     */
    public static int hits(ServerLevel level, Anchor anchor, List<int[]> rels,
                           java.util.function.BiPredicate<ServerLevel, BlockPos> ok) {
        int found = 0;
        for (int[] rel : rels) {
            if (ok.test(level, anchor.world(rel))) {
                found++;
            }
        }
        return found;
    }

    /**
     * The anchor that actually puts this puzzle's furniture where the puzzle expects it.
     *
     * <p>Scores every rotation (and every offered vertical nudge) against the room as it now stands in the
     * world and takes the winner, preferring the recorded rotation on a tie so a correct capture is never
     * second-guessed. Returns null when nothing scores above {@code need} - which is a room that does not
     * contain this puzzle's furniture at all, and is reported rather than armed wrongly.
     *
     * @param dyCandidates vertical nudges to try; pass {@code {0}} unless a capture has been measured off
     * @param need the minimum number of {@code rels} that must land, so a room with a couple of coincidental
     *             matches is not mistaken for the real thing
     */
    public static Anchor bestAnchor(ServerLevel level, Placement p, List<int[]> rels,
                                    Predicate<BlockState> ok, int[] dyCandidates, int need) {
        return bestAnchor(level, p, rels, (lv, pos) -> ok.test(lv.getBlockState(pos)), dyCandidates, need);
    }

    /** {@link #bestAnchor} for a test that has to look at more than the one block - see {@link #hits}. */
    public static Anchor bestAnchor(ServerLevel level, Placement p, List<int[]> rels,
                                    java.util.function.BiPredicate<ServerLevel, BlockPos> ok,
                                    int[] dyCandidates, int need) {
        Anchor recorded = p.anchor();
        int recordedScore = hits(level, recorded, rels, ok);
        Anchor best = recorded;
        int bestScore = recordedScore;
        for (int rotation : new int[]{0, 90, 180, 270}) {
            for (int dy : dyCandidates) {
                if (rotation == recorded.rotation() && dy == 0) {
                    continue;
                }
                Anchor candidate = p.anchorAt(rotation, dy);
                int score = hits(level, candidate, rels, ok);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        if (bestScore < need) {
            LOGGER.warn("Sim puzzle {}: none of the four rotations puts its furniture in the room - best {} of"
                            + " {} expected block(s). Not armed; this capture does not hold the puzzle.",
                    p.room().name, bestScore, rels.size());
            return null;
        }
        if (best.rotation() != recorded.rotation() || best.dy() != 0) {
            // Said out loud, because it means the recovered capture turn disagreed with the furniture. The
            // furniture is the harder evidence (it is the thing being bound to) but a disagreement is worth
            // seeing: docs/SIM.md puts RoomCaptureRotation at 120 of 135 rooms, so this is expected to fire
            // on a handful and never on most.
            LOGGER.warn("Sim puzzle {}: bound at database rotation {} dy {} ({} of {} land), not the recorded"
                            + " rotation {} ({} land)",
                    p.room().name, best.rotation(), best.dy(), bestScore, rels.size(),
                    recorded.rotation(), recordedScore);
        } else {
            LOGGER.info("Sim puzzle {}: bound at database rotation {} - {} of {} expected block(s) present",
                    p.room().name, best.rotation(), bestScore, rels.size());
        }
        return best;
    }

    /**
     * Arms every puzzle room on the floor that has just been built.
     *
     * <p>Server thread only, after the rooms are pasted and the doorways carved.
     *
     * @return how many puzzle rooms were armed
     */
    public static int armFloor(ServerLevel level) {
        if (level == null) {
            return 0;
        }
        // Whatever the LAST floor armed is gone with it, and a stale click index is a click on some unrelated
        // block counting as a move in a puzzle that is not on this floor. Dropped BEFORE anything is armed and
        // through forget() rather than reset(): reset() would put the previous floor's blocks back, at absolute
        // positions the new floor has just been built over, punching holes in it - and it reads the client,
        // which this method is not on.
        for (String name : com.killer560.hub.roomsim.puzzles.SimPuzzles.names()) {
            com.killer560.hub.roomsim.puzzles.SimPuzzles.forget(name);
        }
        int armed = 0;
        int seen = 0;
        // One of each puzzle CLASS, because each one is a singleton holding one arena's worth of static state.
        // A floor never gets two rooms with the same name, and the generator already excludes the
        // Higher/Lower Blaze pair, but a second bind would silently replace the first rather than fail.
        Set<String> tried = new HashSet<>();
        StringBuilder names = new StringBuilder();
        for (SimRoomIndex.Placed placed : SimRoomIndex.placed()) {
            RoomLibrary.Room room = RoomLibrary.get(placed.name());
            if (room == null) {
                continue;
            }
            String key = placed.name().toLowerCase(Locale.ROOT);
            Placement p = new Placement(room, placed.gridX(), placed.gridZ(),
                    placed.pasteRotation(), placed.rotation());
            // The puzzle CLASS this room belongs to, by the name SimPuzzles registers it under, or null when
            // the room is not one of the eleven the generator treats as a puzzle.
            String puzzle = switch (key) {
                case "water board" -> "water";
                case "teleport maze" -> "teleportmaze";
                case "tic tac toe" -> "tictactoe";
                case "creeper beams" -> "creeper";
                case "boulder" -> "boulder";
                case "ice fill" -> "icefill";
                // Oruo's trivia and the three weirdos are different mechanics that share a shape (one
                // question, three things to pick between), and SimQuizPuzzle's own class doc says it stands
                // in for both. Kept together here rather than pretending there are two classes.
                case "quiz", "three weirdos" -> "quiz";
                case "higher blaze", "lower blaze" -> "blaze";
                case "ice path" -> "icepath";
                default -> null;
            };
            if (puzzle == null) {
                continue;
            }
            seen++;
            if (!tried.add(puzzle)) {
                LOGGER.warn("Sim puzzles: {} is the second {} room on this floor - only one can be armed, "
                        + "because the puzzle holds one arena's worth of state", placed.name(), puzzle);
                continue;
            }
            // WRAPPED, because this runs inside the build's completion callback. A bind that throws there
            // takes the rest of the callback with it - the secret-chest audit, the mimic, the map publish and
            // the call that takes the loading screen down - so one puzzle failing would leave him staring at
            // "Generating F7" forever with a finished floor behind it. None of this has been in front of a
            // running client yet, which is exactly when a guard like this earns its place.
            boolean ok;
            try {
                ok = bind(level, puzzle, p, key);
            } catch (Throwable t) {
                LOGGER.error("Sim puzzles: binding {} in {} threw - the room is left as scenery and the rest "
                        + "of the floor is unaffected", puzzle, placed.name(), t);
                ok = false;
            }
            if (ok) {
                armed++;
                names.append(names.isEmpty() ? "" : ", ").append(placed.name());
            } else {
                LOGGER.warn("Sim puzzles: {} was NOT armed", placed.name());
            }
        }
        // Loud either way. "Nothing happened" was the whole complaint, so a floor whose puzzle rooms went
        // unarmed has to say which and why rather than look identical to one that worked.
        if (seen == 0) {
            LOGGER.info("Sim puzzles: no puzzle room on this floor");
        } else {
            LOGGER.info("Sim puzzles: {} of {} puzzle room(s) armed{}", armed, seen,
                    armed == 0 ? "" : " - " + names);
            final int a = armed;
            final int s = seen;
            final String list = names.toString();
            net.minecraft.client.Minecraft.getInstance().execute(() ->
                    com.killer560.hub.util.ModChat.send("Sim",
                            com.killer560.hub.util.ModChat.text("Puzzles armed: "),
                            com.killer560.hub.util.ModChat.value(a + " of " + s),
                            com.killer560.hub.util.ModChat.dim(list.isEmpty() ? "" : " (" + list + ")")));
        }
        return armed;
    }

    /**
     * Every world position in a placed room whose CAPTURE holds one of these blocks.
     *
     * <p>Reads the room data rather than the world, and that is the point: by the time a puzzle is armed
     * {@link SimSecrets} has already put this floor's secret chests in, so a world scan for chests cannot tell
     * a secret chest from one the room was captured with. The capture can, because secrets are not captured.
     *
     * <p>Uses the PASTE transform ({@code RoomPlacer.rotateLocal} and the same anchor {@code PasteJob} uses),
     * not the database one - a capture-local coordinate is not a database-relative coordinate, and the two
     * differ by the capture's own turn. Nothing is guessed here: this is exactly where the paste put the block.
     */
    public static List<BlockPos> capturedBlocks(Placement p, Block... blocks) {
        RoomLibrary.Room room = p.room();
        List<BlockPos> out = new ArrayList<>();
        Set<Integer> wanted = new HashSet<>();
        for (int i = 0; i < room.palette.size(); i++) {
            String id = room.palette.get(i);
            int bracket = id.indexOf('[');
            String bare = bracket < 0 ? id : id.substring(0, bracket);
            for (Block b : blocks) {
                if (bare.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString())) {
                    wanted.add(i);
                }
            }
        }
        if (wanted.isEmpty()) {
            return out;
        }
        BlockPos origin = com.killer560.hub.livemap.DungeonLayout.cellCenter(
                p.gridZ() * com.killer560.hub.livemap.DungeonLayout.GRID + p.gridX());
        int worldX0 = origin.getX() - RoomLibrary.TILE / 2 - room.margin;
        int worldZ0 = origin.getZ() - RoomLibrary.TILE / 2 - room.margin;
        for (int y = room.minY; y <= room.maxY; y++) {
            for (int x = 0; x < room.sizeX; x++) {
                for (int z = 0; z < room.sizeZ; z++) {
                    if (!wanted.contains((int) room.at(x, y, z))) {
                        continue;
                    }
                    int[] local = RoomPlacer.rotateLocal(x, z, room.sizeX, room.sizeZ, p.pasteRotation());
                    out.add(new BlockPos(worldX0 + local[0], SimAltitude.toWorld(y), worldZ0 + local[1]));
                }
            }
        }
        return out;
    }

    /**
     * Where ONE capture-local coordinate ended up in the world.
     *
     * <p>{@link #capturedBlocks} answers "where are this room's chests", which is the usual question. This
     * answers "where is the block I measured at capture-local (x, y, z)", which is what a puzzle needs when it
     * has to WRITE something at a spot found by decoding the capture rather than by searching the world. Same
     * transform, same anchor, same {@code RoomPlacer.rotateLocal} - so it lands exactly where the paste put
     * that cell, at any rotation, with nothing guessed.
     *
     * <p>Capture-local, NOT database-relative: the two differ by the capture's own turn, and a coordinate read
     * out of a capture file is the former.
     */
    public static BlockPos capturedPos(Placement p, int cx, int cy, int cz) {
        RoomLibrary.Room room = p.room();
        BlockPos origin = com.killer560.hub.livemap.DungeonLayout.cellCenter(
                p.gridZ() * com.killer560.hub.livemap.DungeonLayout.GRID + p.gridX());
        int worldX0 = origin.getX() - RoomLibrary.TILE / 2 - room.margin;
        int worldZ0 = origin.getZ() - RoomLibrary.TILE / 2 - room.margin;
        int[] local = RoomPlacer.rotateLocal(cx, cz, room.sizeX, room.sizeZ, p.pasteRotation());
        return new BlockPos(worldX0 + local[0], SimAltitude.toWorld(cy), worldZ0 + local[1]);
    }

    /**
     * One bind, picked by puzzle name.
     *
     * <p>Split out of {@link #armFloor} only so every puzzle goes through the same guard there. A switch
     * inside a try reads as though the try is about the switch; it is about the nine methods it calls.
     */
    private static boolean bind(ServerLevel level, String puzzle, Placement p, String key) {
        return switch (puzzle) {
            case "water" -> SimWaterPuzzle.bindAt(level, p);
            case "teleportmaze" -> SimTeleportMazePuzzle.bindAt(level, p);
            case "tictactoe" -> SimTicTacToePuzzle.bindAt(level, p);
            case "creeper" -> SimCreeperPuzzle.bindAt(level, p);
            case "boulder" -> SimBoulderPuzzle.bindAt(level, p);
            case "icefill" -> SimIceFillPuzzle.bindAt(level, p);
            case "icepath" -> SimIcePathPuzzle.bindAt(level, p);
            case "quiz" -> SimQuizPuzzle.bindAt(level, p);
            case "blaze" -> SimBlazePuzzle.bindAt(level, p, key.startsWith("higher"));
            default -> false;
        };
    }

}
