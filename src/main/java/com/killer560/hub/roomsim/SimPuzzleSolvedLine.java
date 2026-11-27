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
 * <p>Only two wordings are from real logs (156 of his logs, checked 2026-11-26): "PUZZLE SOLVED! Killer560 tied Tic Tac
 * Toe! Good job!" and "PUZZLE SOLVED! Xolty wasn't fooled by Eveleth! Good job!" (Three Weirdos, the NPC at the correct
 * chest). Every other puzzle's middle part here is an UNVERIFIED stand-in ("completed Ice Path") until a real line is
 * logged. The mod matches the shape alone - anchored prefix, the player as a real name, "! Good job!".
 * Blaze (Higher and Lower) gets no line: Hypixel sends none.
 */
public final class SimPuzzleSolvedLine {

    private static final List<String> ROOMS = List.of("Tic Tac Toe", "Ice Path", "Ice Fill", "Creeper Beams",
            "Three Weirdos", "Quiz", "Water Board", "Teleport Maze", "Boulder");

    private static final Set<String> said = new HashSet<>();
    private static Object level;

    private SimPuzzleSolvedLine() {
    }

    public static void register() { ClientTickEvents.END_CLIENT_TICK.register(SimPuzzleSolvedLine::tick); }

    private static void tick(Minecraft client) {
        if (client.level != level) {
            level = client.level;
            said.clear();
        }
        if (client.player == null || !SimState.isActive()) return;
        var server = client.getSingleplayerServer();
        if (server == null) return;
        for (String room : ROOMS) {
            if (!SimPuzzles.isRoomComplete(room)) {
                said.remove(room); // reset (a fail, an Architect's First Draft): say it again when solved again
                continue;
            }
            if (!said.add(room)) continue;
            UUID who = client.player.getUUID();
            String name = solverName(room, client.player.getGameProfile().name());
            String what = "Tic Tac Toe".equals(room) ? "tied Tic Tac Toe" : "Three Weirdos".equals(room)
                    ? "wasn't fooled by " + com.killer560.hub.roomsim.puzzles.SimQuizPuzzle.correctWeirdoName()
                    : "completed " + room;
            String line = "PUZZLE SOLVED! " + name + " " + what + "! Good job!";
            server.execute(() -> {
                if (!com.killer560.hub.roomsim.SimCoop.guestSpots().isEmpty()) {
                    // Co-op (coopsim-p5): the whole party reads it, as on Hypixel.
                    com.killer560.hub.roomsim.SimCoop.broadcast(server, Component.literal(line));
                    return;
                }
                ServerPlayer sp = server.getPlayerList().getPlayer(who);
                if (sp != null) {
                    sp.sendSystemMessage(Component.literal(line));
                }
            });
        }
    }
    /** Who solved {@code room}: him if he is in it, else a co-op guest standing in it (coopsim-p5), else him. */
    private static String solverName(String room, String self) {
        if (room.equals(SimState.currentRoomName())) return self;
        for (SimCoop.Spot g : SimCoop.guestSpots()) {
            if (room.equals(SimState.roomNameAt((int) Math.floor(g.x()), (int) Math.floor(g.z())))) return g.name();
        }
        return self;
    }
}
