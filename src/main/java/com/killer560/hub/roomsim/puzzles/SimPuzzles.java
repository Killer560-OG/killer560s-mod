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

    static {
        BUILDERS.put("blaze", SimBlazePuzzle::build);
        BUILDERS.put("creeper", SimCreeperPuzzle::build);
        BUILDERS.put("quiz", SimQuizPuzzle::build);
        BUILDERS.put("tictactoe", SimTicTacToePuzzle::build);
        BUILDERS.put("water", SimWaterPuzzle::build);
        BUILDERS.put("boulder", SimBoulderPuzzle::build);
        BUILDERS.put("teleportmaze", SimTeleportMazePuzzle::build);
        BUILDERS.put("icefill", SimIceFillPuzzle::build);
        RESETS.put("blaze", SimBlazePuzzle::reset);
        RESETS.put("creeper", SimCreeperPuzzle::reset);
        RESETS.put("quiz", SimQuizPuzzle::reset);
        RESETS.put("tictactoe", SimTicTacToePuzzle::reset);
        RESETS.put("water", SimWaterPuzzle::reset);
        RESETS.put("boulder", SimBoulderPuzzle::reset);
        RESETS.put("teleportmaze", SimTeleportMazePuzzle::reset);
        RESETS.put("icefill", SimIceFillPuzzle::reset);
    }

    private SimPuzzles() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("simpuzzle")
                        .then(ClientCommands.literal("reset").executes(ctx -> {
                            resetAll();
                            ModChat.send("Sim", ModChat.text("Puzzles reset"));
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
        com.killer560.hub.roomsim.SimArchitect.onPuzzleFail(puzzleName);
    }

    /** Clears every puzzle's state, for leaving the sim or restarting a run. */
    public static void resetAll() {
        for (Runnable r : RESETS.values()) {
            try {
                r.run();
            } catch (Throwable ignored) {
                // one puzzle failing to reset must not stop the others
            }
        }
    }
}
