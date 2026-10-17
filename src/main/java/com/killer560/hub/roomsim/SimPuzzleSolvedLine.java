package com.killer560.hub.roomsim;

import com.killer560.hub.roomsim.puzzles.SimPuzzles;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Hypixel's "PUZZLE SOLVED!" chat line, sent by the sim's server the tick a sim puzzle is solved, as Hypixel sends it the
 * moment it registers the solve (killer560, 2026-10-16: "make sure my mod registers when a puzzle is done as fast as
 * possible"). The mod reacts to it ({@code AutoPuzzleActivity.solvedByHim}), so the sim must say it, or that path is never
 * exercised here.
 *
 * <p>Only Tic Tac Toe's wording is from a real log ("PUZZLE SOLVED! 9878981 tied Tic Tac Toe! Good job!", Mod Only Test
 * 22:28:37); every other puzzle's middle part here is a stand-in ("completed Ice Path"). The mod matches the shape alone -
 * anchored prefix, the player as a real name, "! Good job!" - so a stand-in reads the same as Hypixel's own words.
 */
public final class SimPuzzleSolvedLine {

    private static final List<String> ROOMS = List.of("Tic Tac Toe", "Ice Path", "Ice Fill", "Creeper Beams",
            "Three Weirdos", "Quiz", "Water Board", "Teleport Maze", "Boulder", "Higher Blaze", "Lower Blaze");

    private static final Set<String> said = new HashSet<>();
    private static Object level;

    private SimPuzzleSolvedLine() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SimPuzzleSolvedLine::tick);
    }

    private static void tick(Minecraft client) {
        if (client.level != level) {
            level = client.level;
            said.clear();
        }
        if (client.player == null || !SimState.isActive()) {
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        for (String room : ROOMS) {
            // The blaze rooms share one puzzle class: isComplete(name) says which arena is solved.
            boolean done = room.endsWith("Blaze") ? SimPuzzles.isComplete(room) : SimPuzzles.isRoomComplete(room);
            if (!done) {
                said.remove(room); // reset (a fail, an Architect's First Draft): say it again when solved again
                continue;
            }
            if (!said.add(room)) {
                continue;
            }
            UUID who = client.player.getUUID();
            String name = client.player.getGameProfile().name();
            String line = "PUZZLE SOLVED! " + name + " " + ("Tic Tac Toe".equals(room) ? "tied Tic Tac Toe"
                    : "completed " + room) + "! Good job!";
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(who);
                if (sp != null) {
                    sp.sendSystemMessage(Component.literal(line));
                }
            });
        }
    }
}
