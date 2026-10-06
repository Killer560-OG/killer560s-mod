package com.killer560.hub.roomsim.puzzles;

import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.util.ModChat;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * One place to build and reset the sim's puzzles, and the command that does it.
 *
 * <p>Each puzzle owns its own rules and its own arena; this only knows their names. That split is deliberate -
 * adding a puzzle should mean writing one file and adding one line here, not touching a switch in five places.
 *
 * <p>{@code /simpuzzle <name>} builds one where the player is standing, which is how a puzzle gets practised in
 * isolation before it ever appears in a generated map.
 */
public final class SimPuzzles {

    /** Name to builder. Order is the order they are listed to him. */
    private static final Map<String, BiConsumer<Minecraft, BlockPos>> BUILDERS = new LinkedHashMap<>();
    private static final Map<String, Runnable> RESETS = new LinkedHashMap<>();
    /** Name to "drop the bookkeeping, touch nothing in the world" - see each puzzle's {@code forget()}. */
    private static final Map<String, Runnable> FORGETS = new LinkedHashMap<>();
    /** Name to "is it solved", for the tab list's puzzle rows. */
    private static final Map<String, java.util.function.BooleanSupplier> COMPLETE = new LinkedHashMap<>();

    static {
        BUILDERS.put("blaze", SimBlazePuzzle::build);
        BUILDERS.put("creeper", SimCreeperPuzzle::build);
        BUILDERS.put("quiz", SimQuizPuzzle::build);
        BUILDERS.put("tictactoe", SimTicTacToePuzzle::build);
        BUILDERS.put("water", SimWaterPuzzle::build);
        BUILDERS.put("boulder", SimBoulderPuzzle::build);
        BUILDERS.put("teleportmaze", SimTeleportMazePuzzle::build);
        BUILDERS.put("icefill", SimIceFillPuzzle::build);
        BUILDERS.put("icepath", SimIcePathPuzzle::build);
        RESETS.put("blaze", SimBlazePuzzle::reset);
        RESETS.put("creeper", SimCreeperPuzzle::reset);
        RESETS.put("quiz", SimQuizPuzzle::reset);
        RESETS.put("tictactoe", SimTicTacToePuzzle::reset);
        RESETS.put("water", SimWaterPuzzle::reset);
        RESETS.put("boulder", SimBoulderPuzzle::reset);
        RESETS.put("teleportmaze", SimTeleportMazePuzzle::reset);
        RESETS.put("icefill", SimIceFillPuzzle::reset);
        RESETS.put("icepath", SimIcePathPuzzle::reset);
        FORGETS.put("blaze", SimBlazePuzzle::forget);
        FORGETS.put("creeper", SimCreeperPuzzle::forget);
        FORGETS.put("quiz", SimQuizPuzzle::forget);
        FORGETS.put("tictactoe", SimTicTacToePuzzle::forget);
        FORGETS.put("water", SimWaterPuzzle::forget);
        FORGETS.put("boulder", SimBoulderPuzzle::forget);
        FORGETS.put("teleportmaze", SimTeleportMazePuzzle::forget);
        FORGETS.put("icefill", SimIceFillPuzzle::forget);
        FORGETS.put("icepath", SimIcePathPuzzle::forget);
        COMPLETE.put("blaze", SimBlazePuzzle::isComplete);
        COMPLETE.put("creeper", SimCreeperPuzzle::isComplete);
        COMPLETE.put("quiz", SimQuizPuzzle::isComplete);
        COMPLETE.put("tictactoe", SimTicTacToePuzzle::isComplete);
        COMPLETE.put("water", SimWaterPuzzle::isComplete);
        COMPLETE.put("boulder", SimBoulderPuzzle::isComplete);
        COMPLETE.put("teleportmaze", SimTeleportMazePuzzle::isComplete);
        COMPLETE.put("icefill", SimIceFillPuzzle::isComplete);
        COMPLETE.put("icepath", SimIcePathPuzzle::isComplete);
    }

    /**
     * THE "puzzle solved" signal, one per puzzle - what the tab list reads and what a test should read.
     *
     * <p>Takes either a {@link #names()} key ({@code "icepath"}) or the ROOM's name as the live map gives it
     * ({@code "Ice Path"}, {@code "Lower Blaze"}). The two blaze rooms share one puzzle class, so a blaze room name
     * is only complete when the armed arena is THAT room and it is complete - "Higher Blaze" never reads true off a
     * solved Lower Blaze. False for an unknown name, and never throws.
     */
    public static boolean isComplete(String rawName) {
        if (rawName == null) {
            return false;
        }
        String name = rawName.toLowerCase(Locale.ROOT);
        String key = COMPLETE.containsKey(name) ? name : com.killer560.hub.roomsim.SimRoomPuzzles.puzzleKey(rawName);
        java.util.function.BooleanSupplier s = key == null ? null : COMPLETE.get(key);
        try {
            if (s == null || !s.getAsBoolean()) {
                return false;
            }
            if (name.equals("higher blaze") || name.equals("lower blaze")) {
                return rawName.equalsIgnoreCase(SimBlazePuzzle.boundRoom());
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether the puzzle in the room of this NAME is solved - the per-room signal tests read by reflection, so a
     * scenario never has to know which class runs which room or what each one counts as solved.
     *
     * <ul>
     *   <li>Boulder: the boxes leave a way from the near row to the far one ({@code SimBoulderPuzzle.isComplete}).
     *       Its reward chest being opened is {@code SimBoulderPuzzle.isRewardChestOpened}, separately.</li>
     *   <li>Three Weirdos / Quiz: the right chest or answer, in THAT room - both run on {@code SimQuizPuzzle}, so
     *       its plain {@code isComplete} cannot say which.</li>
     *   <li>Water Board: every gate click and every timed click made; Tic Tac Toe: a game finished without the AI
     *       winning; Teleport Maze: the end pad reached; the rest: their own {@code isComplete}.</li>
     * </ul>
     *
     * @return false for an unknown room name, or a room whose puzzle is not solved
     */
    public static boolean isRoomComplete(String roomName) {
        if (roomName == null) {
            return false;
        }
        try {
            return switch (roomName.toLowerCase(Locale.ROOT)) {
                case "three weirdos" -> SimQuizPuzzle.isWeirdosComplete();
                case "quiz" -> SimQuizPuzzle.isQuizComplete();
                case "boulder" -> SimBoulderPuzzle.isComplete();
                case "water board" -> SimWaterPuzzle.isComplete();
                case "tic tac toe" -> SimTicTacToePuzzle.isComplete();
                case "teleport maze" -> SimTeleportMazePuzzle.isComplete();
                case "creeper beams" -> SimCreeperPuzzle.isComplete();
                case "higher blaze", "lower blaze" -> SimBlazePuzzle.isComplete();
                case "ice fill" -> SimIceFillPuzzle.isComplete();
                case "ice path" -> SimIcePathPuzzle.isComplete();
                default -> false;
            };
        } catch (Throwable t) {
            return false;
        }
    }

    private SimPuzzles() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("simpuzzle")
                        .then(ClientCommands.literal("reset").executes(ctx -> {
                            int n = resetForPlayer();
                            ModChat.send("Sim", n > 0 ? ModChat.text("Puzzles reset: " + n)
                                    : ModChat.dim("Nothing to reset - only failed puzzles (and Boulder) can be reset"));
                            return 1;
                        }))
                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    build(Minecraft.getInstance(),
                                            StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                }))
                        .executes(ctx -> {
                            ModChat.send("Sim", ModChat.dim("/simpuzzle " + String.join("|", BUILDERS.keySet())
                                    + "  |  /simpuzzle reset"));
                            return 1;
                        })));
    }

    /** Every puzzle's name, in the order they are offered. */
    public static java.util.List<String> names() {
        return java.util.List.copyOf(BUILDERS.keySet());
    }

    /**
     * Builds one puzzle at a chosen spot, for tests.
     *
     * <p>{@link #build} puts it four blocks in front of the player, which is right for him and useless for a
     * scenario that needs to know exactly where to look. This is the same builder with the origin handed in.
     *
     * @return false when there is no puzzle by that name
     */
    public static boolean buildAt(Minecraft client, String rawName, BlockPos origin) {
        BiConsumer<Minecraft, BlockPos> builder = BUILDERS.get(rawName.toLowerCase(Locale.ROOT));
        if (builder == null) {
            return false;
        }
        builder.accept(client, origin);
        return true;
    }

    private static void build(Minecraft client, String rawName) {
        if (!SimState.canAct(client)) {
            ModChat.send("Sim", ModChat.text("Puzzles only build inside the sim."));
            return;
        }
        String name = rawName.toLowerCase(Locale.ROOT);
        BiConsumer<Minecraft, BlockPos> builder = BUILDERS.get(name);
        if (builder == null) {
            ModChat.send("Sim", ModChat.text("No puzzle called " + rawName + " - "),
                    ModChat.dim(String.join(", ", BUILDERS.keySet())));
            return;
        }
        // Built where he is standing, a few blocks ahead, so it appears in front rather than on top of him.
        BlockPos origin = client.player.blockPosition()
                .relative(client.player.getDirection(), 4);
        builder.accept(client, origin);
        ModChat.send("Sim", ModChat.text("Built the "), ModChat.value(name), ModChat.text(" puzzle"));
    }

    /**
     * A puzzle just failed.
     *
     * <p>killer560 asked that the Architect's First Draft feature he already has - the one that fetches a draft
     * from his sack when a puzzle fails - work in here too. On Hypixel it triggers off the server's own
     * "PUZZLE FAIL!" broadcast, which no local world sends, so the sim tells it directly instead of faking a
     * chat line. The SETTINGS are the same ones; only where the draft comes from differs, because there is no
     * sack to pull from in a world this mod made.
     */
    public static void reportFail(String puzzleName) {
        reportFail(puzzleName, null);
    }

    /**
     * The same, for a puzzle that knows which ROOM it is bound to.
     *
     * <p>On Hypixel a failed puzzle also turns its room red on the dungeon map, which killer560 asked for in
     * here ("the map image for the room should turn red to show that I failed the puzzle"). The map cannot
     * work that out for itself in a sim - there is no map item for it to read - so the puzzle says so. Room
     * name rather than puzzle name because the two are not always the same: Three Weirdos is run by
     * {@code SimQuizPuzzle}, and it is the room that gets painted.
     */
    public static void reportFail(String puzzleName, String roomName) {
        if (roomName != null) {
            com.killer560.hub.roomsim.SimRoomState.markFailed(roomName);
        }
        com.killer560.hub.roomsim.SimArchitect.onPuzzleFail(puzzleName);
    }

    /**
     * Drops one puzzle's bookkeeping by name, WITHOUT touching the world.
     *
     * <p>Needed because {@link com.killer560.hub.roomsim.SimRoomPuzzles} arms only the puzzles a floor actually
     * holds and has to drop the last floor's state for the ones it does not - without also dropping what it has
     * just armed, which is what {@link #resetAll} would do, and without writing anything, because those blocks
     * belonged to a floor that has already been built over. See any puzzle's own {@code forget()}.
     *
     * @return false when there is no puzzle by that name
     */
    public static boolean forget(String rawName) {
        Runnable r = FORGETS.get(rawName.toLowerCase(Locale.ROOT));
        if (r == null) {
            return false;
        }
        try {
            r.run();
        } catch (Throwable ignored) {
            // same reason as resetAll
        }
        return true;
    }

    /**
     * Resets one puzzle by name - blocks put back, progress cleared. The client-side counterpart of
     * {@link #forget(String)}, for a puzzle whose arena is still standing.
     *
     * @return false when there is no puzzle by that name
     */
    public static boolean reset(String rawName) {
        Runnable r = RESETS.get(rawName.toLowerCase(Locale.ROOT));
        if (r == null) {
            return false;
        }
        try {
            r.run();
        } catch (Throwable ignored) {
            // same reason as resetAll: a puzzle failing to reset must not take the caller down with it
        }
        return true;
    }

    /**
     * The player's reset (an Architect's First Draft or {@code /simpuzzle reset}), by killer560's rules
     * (2026-10-06): "you shouldnt be able to reset completed puzzles only failed ones", Water Board can never be
     * reset, and Boulder can be reset whether or not it failed. Failed rooms come from {@link
     * com.killer560.hub.roomsim.SimRoomState}, which is what paints them red.
     *
     * @return how many puzzles were reset (0 = nothing to reset, so a draft is not used up)
     */
    public static int resetForPlayer() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (String room : com.killer560.hub.roomsim.SimRoomState.failedRooms()) {
            String key = com.killer560.hub.roomsim.SimRoomPuzzles.puzzleKey(room);
            if (key != null && !key.equals("water")) {
                keys.add(key);
                com.killer560.hub.roomsim.SimRoomState.clearRoom(room);
            }
        }
        keys.add("boulder");
        int n = 0;
        for (String key : keys) {
            if (key.equals("boulder") && !BUILT_BOULDER.getAsBoolean()) {
                continue;
            }
            if (reset(key)) {
                n++;
            }
        }
        return n;
    }

    /** Whether a Boulder arena is up to reset. */
    private static final java.util.function.BooleanSupplier BUILT_BOULDER = SimBoulderPuzzle::isBuilt;

    /** Clears every puzzle's state, for leaving the sim or restarting a run. */
    public static void resetAll() {
        // An Architect's First Draft comes through here, and the whole point of one is that the room stops
        // being failed - so the red square goes with the reset.
        com.killer560.hub.roomsim.SimRoomState.clear();
        for (Runnable r : RESETS.values()) {
            try {
                r.run();
            } catch (Throwable ignored) {
                // one puzzle failing to reset must not stop the others
            }
        }
    }
}
