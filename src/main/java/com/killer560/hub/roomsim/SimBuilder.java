package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.util.ModChat;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Turns a map code into rooms on the ground.
 *
 * <p>The orchestration only: {@link RoomPlacer} does the block writing and {@link MapCode} does the decoding.
 * This decides what goes where and reports what could not be built.
 *
 * <p><b>A missing room is named, not skipped silently.</b> The library fills up as rooms are captured, so an
 * early sim will be mostly gaps - and a builder that quietly left holes would look exactly like a builder that
 * was broken. Every room it could not find is counted and listed.
 */
public final class SimBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-roomsim");

    private SimBuilder() {
    }

    /**
     * {@code /simbuild flat} and {@code /simbuild code <code>}.
     *
     * <p>Two entry points because they answer different questions: "does any of this work at all" and "does this
     * specific map rebuild". The first needs nothing captured, which is the only reason the sim is testable
     * today.
     */
    public static void register() {
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> dispatcher.register(
                        net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("simbuild")
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                                        .literal("flat")
                                        .executes(ctx -> {
                                            buildFlatTest(Minecraft.getInstance());
                                            return 1;
                                        }))
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                                        .literal("code")
                                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                                                .argument("code", com.mojang.brigadier.arguments.StringArgumentType
                                                        .greedyString())
                                                .executes(ctx -> {
                                                    build(Minecraft.getInstance(),
                                                            com.mojang.brigadier.arguments.StringArgumentType
                                                                    .getString(ctx, "code"));
                                                    return 1;
                                                })))
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                                        .literal("run")
                                        .executes(ctx -> {
                                            // No entrance door on the map yet, so nothing to hold shut - the
                                            // countdown still runs, which is the half that is testable today.
                                            SimRun.begin(Minecraft.getInstance(), null);
                                            return 1;
                                        }))
                                .executes(ctx -> {
                                    ModChat.send("Sim", ModChat.dim(
                                            "/simbuild flat  |  /simbuild code <code>  |  /simbuild run"));
                                    return 1;
                                })));
    }

    /**
     * Builds the whole map from a code.
     *
     * <p>Runs on the server thread. Pasting a room is tens of thousands of block writes and doing that from the
     * client thread would freeze the game rather than merely be slow.
     */
    public static void build(Minecraft client, String code) {
        MapCode.Decoded decoded = MapCode.decode(code);
        if (decoded == null) {
            ModChat.send("Sim", ModChat.text("That map code is not valid."));
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            int placed = 0;
            int missing = 0;
            StringBuilder missingNames = new StringBuilder();
            for (int cell = 0; cell < decoded.cellRoom().length; cell++) {
                int nameIndex = decoded.cellRoom()[cell];
                if (nameIndex < 0) {
                    continue;
                }
                String name = decoded.nameTable()[nameIndex];
                RoomLibrary.Room room = RoomLibrary.get(name);
                if (room == null) {
                    missing++;
                    if (missingNames.indexOf(name) < 0) {
                        missingNames.append(missingNames.isEmpty() ? "" : ", ").append(name);
                    }
                    continue;
                }
                int gx = cell % DungeonLayout.GRID;
                int gz = cell / DungeonLayout.GRID;
                // Rotation is not in the code yet - the grid says which cell, not which way round. Until it is,
                // everything goes in unrotated, which is wrong for real maps and fine for the flat test room.
                placed += RoomPlacer.paste(level, room, gx, gz, 0);
            }
            final int p = placed;
            final int m = missing;
            final String names = missingNames.toString();
            client.execute(() -> {
                ModChat.send("Sim", ModChat.text("Built "), ModChat.value(String.valueOf(p)),
                        ModChat.text(" blocks."));
                if (m > 0) {
                    ModChat.send("Sim", ModChat.dim(m + " cell(s) had no captured room: " + names));
                }
            });
            LOGGER.info("Sim build: {} blocks placed, {} cells missing a room", p, m);
        });
    }

    /**
     * Drops the synthetic test room into the middle of the grid.
     *
     * <p>What makes the sim testable before anything has been scanned. Placed at the centre cell so there is
     * room around it, and reported with its coordinates so he can find it rather than hunt.
     */
    public static void buildFlatTest(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null) {
            ModChat.send("Sim", ModChat.text("Not in a local world."));
            return;
        }
        RoomLibrary.Room room = FlatTestRoom.ensure();
        int centre = DungeonLayout.GRID / 2;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            int placed = RoomPlacer.paste(level, room, centre, centre, 0);
            var origin = DungeonLayout.cellCenter(centre * DungeonLayout.GRID + centre);
            client.execute(() -> ModChat.send("Sim",
                    ModChat.text("Flat test room built ("), ModChat.value(String.valueOf(placed)),
                    ModChat.text(" blocks) at "),
                    ModChat.value(String.format(Locale.US, "%d %d %d",
                            origin.getX(), origin.getY(), origin.getZ()))));
        });
    }
}
