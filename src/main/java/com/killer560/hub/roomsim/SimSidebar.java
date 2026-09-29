package com.killer560.hub.roomsim;

import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Catacombs sidebar, inside the sim.
 *
 * <p>killer560 (2026-09-29): "Make this feel like I am in dungeons." A real run has a sidebar telling you the
 * floor, the secrets, the keys and the room you are standing in, and the sim had nothing.
 *
 * <p>What this does NOT do is switch the mod's dungeon features on - I assumed it would and that was wrong.
 * {@code DungeonState.isInDungeon()} is already true inside the sim because {@code SimState.enter} calls
 * {@code setRoomSim(true)}, by a deliberate earlier decision ("they would every one of them sit out"). The
 * gates were open before this existed.
 *
 * <p>What it adds is the sidebar TEXT, which several things read rather than infer: {@code sidebarRoomName}
 * for the room he is in, the secrets count, and the run clock. And it makes the sim look like a run, which is
 * what he actually asked for. The integrated server is ours, so this is an ordinary scoreboard on it and
 * anything that reads a Hypixel sidebar reads this one the same way.
 *
 * <p>The lines are TEAM PREFIXES on short holder names, not the holder names themselves, because a score
 * holder name cannot contain a space - the same trick the anticheat harness uses for the same reason.
 */
public final class SimSidebar {

    private static final String OBJECTIVE = "k560sim";

    /** Holder names: short, no spaces, and stable so lines are rewritten rather than re-added. */
    private static final String HOLDER = "k560l";

    /** What the sidebar said last tick, so the scoreboard is only touched when something changed. */
    private static List<String> lastLines = List.of();

    private static boolean built;

    private SimSidebar() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("SimSidebar.tick", SimSidebar::tick));
    }

    /** Forgets everything, so the next sim session rebuilds rather than inheriting the last one's lines. */
    public static void reset() {
        built = false;
        lastLines = List.of();
    }

    private static void tick(Minecraft client) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        List<String> lines = compose(client);
        if (lines.equals(lastLines)) {
            return;
        }
        lastLines = lines;
        // On the server thread: the scoreboard belongs to the server, and touching it from the render thread
        // is the kind of cross-thread write that works until it does not.
        server.execute(() -> apply(server, lines));
    }

    /**
     * The sidebar text, top line first.
     *
     * <p>Shaped like Hypixel's, because that is what every reader in this mod was written against - the floor
     * line has to contain "The Catacombs (F7)" for {@code DungeonState} to detect the floor at all.
     */
    private static List<String> compose(Minecraft client) {
        List<String> lines = new ArrayList<>();
        lines.add("Practice Dungeon");
        lines.add("The Catacombs (" + SimState.floorLabel() + ")");
        lines.add("Secrets: " + SimScore.secretsFound() + "/" + SimScore.secretsTotal());
        lines.add(keysLine());
        lines.add("");
        String room = SimState.currentRoomName();
        lines.add("Room: " + (room == null || room.isBlank() ? "Entrance" : room));
        if (SimRun.isRunning()) {
            lines.add("Time: " + SimRun.elapsedText());
        } else if (SimRun.isArmed()) {
            int seconds = (SimRun.countdownTicks() + 19) / 20;
            lines.add("Starting in " + seconds);
        }
        return lines;
    }

    /**
     * Keys held and doors still shut.
     *
     * <p>Not Hypixel's exact glyph line. That reads wither-door / blood-door / key-count, and a Door here does
     * not record which kind it is - so two of those three markers would have been decoration that never
     * changed. Both numbers below are read from real state, which is worth more than a familiar shape that
     * lies.
     */
    private static String keysLine() {
        int doors = SimDoors.doorsRemaining();
        return String.format(Locale.US, "Keys: %dx   Doors: %d", SimDoors.keysHeld(), doors);
    }

    private static void apply(MinecraftServer server, List<String> lines) {
        ServerLevel level = server.overworld();
        if (level == null) {
            return;
        }
        Scoreboard board = server.getScoreboard();
        Objective objective = board.getObjective(OBJECTIVE);
        if (objective == null) {
            objective = board.addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY,
                    Component.literal("SKYBLOCK"), ObjectiveCriteria.RenderType.INTEGER, true, null);
        }
        board.setDisplayObjective(DisplaySlot.SIDEBAR, objective);

        // Clear the lines that are no longer used before writing the new ones, or a shorter sidebar keeps the
        // tail of the longer one it replaced.
        for (int i = lines.size(); i < 16; i++) {
            String holder = HOLDER + i;
            board.resetSinglePlayerScore(net.minecraft.world.scores.ScoreHolder.forNameOnly(holder), objective);
        }
        for (int i = 0; i < lines.size(); i++) {
            String holder = HOLDER + i;
            PlayerTeam team = board.getPlayerTeam(holder);
            if (team == null) {
                team = board.addPlayerTeam(holder);
            }
            team.setPlayerPrefix(Component.literal(lines.get(i)));
            if (!team.getPlayers().contains(holder)) {
                board.addPlayerToTeam(holder, team);
            }
            // Highest score at the top, which is how Hypixel orders it.
            board.getOrCreatePlayerScore(net.minecraft.world.scores.ScoreHolder.forNameOnly(holder), objective)
                    .set(lines.size() - i);
        }
        built = true;
    }

    public static boolean isBuilt() {
        return built;
    }
}
