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
 * for the room he is in, and the secrets count (the clock, keys and floor lines went on 2026-10-04 - see
 * {@link #compose}). It is an ordinary scoreboard on the integrated server, which is ours, so anything that
 * reads a Hypixel sidebar reads this one the same way.
 *
 * <p>The lines are TEAM PREFIXES on short holder names, not the holder names themselves, because a score
 * holder name cannot contain a space - the same trick the anticheat harness uses for the same reason.
 */
public final class SimSidebar {

    private static final String OBJECTIVE = "k560sim";

    /**
     * The holder names the sidebar used before 2026-10-04. A holder name is DRAWN after the team prefix, so these
     * showed up on every line as "k560l4" and the like - killer560: "there still shows a lot of stuff like time,
     * keys, k560l4". The sim world's scoreboard is saved with the world, so the old ones are cleared out once.
     */
    private static final String OLD_HOLDER = "k560l";

    /** Team name per line. Teams carry the visible text as a prefix. */
    private static final String TEAM = "k560t";

    /**
     * Line {@code i}'s holder: a colour code and a reset and nothing else, which draws as nothing - what Hypixel
     * does. {@code ScoreboardData.lineText} already drops an owner that is blank once formatting is stripped,
     * so the Custom Scoreboard and every sidebar reader see the prefix alone.
     */
    private static String holder(int i) {
        return "\u00a7" + "0123456789abcdef".charAt(i & 15) + "\u00a7r";
    }

    /** Top line of the sidebar and the tab list header. */
    public static final String INFO_TITLE = "killer560's personal testing sim";

    /** Said plainly, so a screenshot of the sim is never mistaken for a Hypixel run. */
    public static final String INFO_NOT_HYPIXEL = "Local practice world - not Hypixel";

    /** His Discord invite - the same one README.md, HomeMainTab and ChatCommandsFeature carry. */
    public static final String DISCORD = "discord.gg/hkQMF5fE84";

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

    /**
     * Takes the sim's header and footer back off the tab list. Called as the sim world unloads.
     *
     * <p>The header and footer themselves are sent by the integrated server as a tab-list packet, with the rest
     * of the Hypixel-shaped tab list, by {@link SimTabList}; this only makes sure they cannot follow him onto a
     * server that sends none of its own.
     */
    public static void clearTabInfo(Minecraft client) {
        if (client == null || client.gui == null) {
            return;
        }
        var tab = com.killer560.hub.compat.McCompat.tabList(client);
        tab.setHeader(null);
        tab.setFooter(null);
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
     * <p>killer560 (2026-10-04): "For the sim scoreboard there still shows a lot of stuff like time, keys,
     * k560l4 and stuff like that." So: who this world belongs to, the two things worth glancing at while
     * practising - secrets and the room he is in - and the Discord link. No clock, no keys/doors, no "not
     * Hypixel" line (the tab list footer still says that). The Catacombs line STAYS: the floor change it
     * produces in {@code DungeonState} is what starts the run timer, Score Calc's run and the Live Map's grid
     * reset (his 2026-10-04 log, the three lines right after "Dungeon floor changed"), and Hypixel shows it too.
     * {@code SkyblockGate} reads only the objective TITLE, which stays "SKYBLOCK".
     */
    private static List<String> compose(Minecraft client) {
        List<String> lines = new ArrayList<>();
        lines.add(INFO_TITLE);
        lines.add("");
        lines.add("The Catacombs (" + SimState.floorLabel() + ")");
        lines.add("Secrets: " + SimScore.secretsFound() + "/" + SimScore.secretsTotal());
        String room = SimState.currentRoomName();
        lines.add("Room: " + (room == null || room.isBlank() ? "Entrance" : room));
        lines.add("");
        lines.add(DISCORD);
        return lines;
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
                    Component.literal("SKYBLOCK"), ObjectiveCriteria.RenderType.INTEGER, true,
                    net.minecraft.network.chat.numbers.BlankFormat.INSTANCE);
        }
        // No red score numbers down the right - they are only the line order. Set every time, because an
        // objective saved with the world before this existed has none.
        objective.setNumberFormat(net.minecraft.network.chat.numbers.BlankFormat.INSTANCE);
        board.setDisplayObjective(DisplaySlot.SIDEBAR, objective);

        if (!built) {
            // The visible "k560l0".."k560l15" holders and their teams, from sidebars saved before 2026-10-04.
            for (int i = 0; i < 16; i++) {
                board.resetAllPlayerScores(net.minecraft.world.scores.ScoreHolder.forNameOnly(OLD_HOLDER + i));
                PlayerTeam old = board.getPlayerTeam(OLD_HOLDER + i);
                if (old != null) {
                    board.removePlayerTeam(old);
                }
            }
        }

        // Clear the lines that are no longer used before writing the new ones, or a shorter sidebar keeps the
        // tail of the longer one it replaced.
        for (int i = lines.size(); i < 16; i++) {
            board.resetSinglePlayerScore(net.minecraft.world.scores.ScoreHolder.forNameOnly(holder(i)), objective);
        }
        for (int i = 0; i < lines.size(); i++) {
            String holder = holder(i);
            PlayerTeam team = board.getPlayerTeam(TEAM + i);
            if (team == null) {
                team = board.addPlayerTeam(TEAM + i);
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
