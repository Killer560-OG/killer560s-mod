package com.killer560.hub.pathfinding;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which SkyHanni island graph applies right now.
 * <p>
 * Island: the tab list's "Area: X" line, the same read {@code routes/SkyblockArea} and {@code DungeonInfoFeature}
 * already use (this feature keeps its own copy because {@code SkyblockArea.tick} only runs while Waypoint Routes is
 * on, and {@code scoreboard/ScoreboardData} only refreshes while the Custom Scoreboard is on).
 * <p>
 * Sub-area: the scoreboard's area line, needed for one special case SkyHanni has too - the Dwarven Mines and the
 * Glacite Tunnels are one island to Hypixel but two separate graph files, and SkyHanni's {@code data/IslandGraphs.kt}
 * notes that this switch "HAS TO be the scoreboard", not the graph areas.
 */
public final class IslandDetector {

    /** SkyHanni's own glacite area names (IslandGraphs.kt "glacitetunnels" repo pattern). */
    private static final Pattern GLACITE = Pattern.compile(
            "Glacite Tunnels|Dwarven Base Camp|Great Glacite Lake|Fossil Research Center");
    private static final Pattern TAB_AREA = Pattern.compile("^(?:Area|Dungeon):\\s*(.+)$");
    /** Same sidebar area line shape as {@code scoreboard/ScoreboardPattern.SKYBLOCK_AREA}, on stripped text. */
    private static final Pattern SIDEBAR_AREA = Pattern.compile("^\\s*[⏣ф]\\s*(.+)$");

    /** Tab-list island name -> graph file name. Names from SkyHanni-REPO {@code constants/misc/IslandType.json}. */
    private static final Map<String, String> ISLANDS = Map.ofEntries(
            Map.entry("hub", "HUB"),
            Map.entry("the farming islands", "THE_FARMING_ISLANDS"),
            Map.entry("the barn", "THE_FARMING_ISLANDS"),
            Map.entry("gold mine", "GOLD_MINES"),
            Map.entry("gold mines", "GOLD_MINES"),
            Map.entry("deep caverns", "DEEP_CAVERNS"),
            Map.entry("dwarven mines", "DWARVEN_MINES"),
            Map.entry("glacite tunnels", "GLACITE_TUNNELS"),
            Map.entry("crystal hollows", "CRYSTAL_HOLLOWS"),
            Map.entry("spider's den", "SPIDER_DEN"),
            Map.entry("spiders den", "SPIDER_DEN"),
            Map.entry("crimson isle", "CRIMSON_ISLE"),
            Map.entry("the end", "THE_END"),
            Map.entry("the park", "THE_PARK"),
            Map.entry("galatea", "GALATEA"),
            Map.entry("moonglade marsh", "GALATEA"),
            Map.entry("torrhus canyon", "TORRHUS_CANYON"),
            Map.entry("critter safari", "SAFARI"),
            Map.entry("safari", "SAFARI"),
            Map.entry("backwater bayou", "BACKWATER_BAYOU"),
            Map.entry("lotus atoll", "LOTUS_ATOLL"),
            Map.entry("jerry's workshop", "WINTER"),
            Map.entry("jerrys workshop", "WINTER"),
            Map.entry("the rift", "THE_RIFT"),
            Map.entry("rift dimension", "THE_RIFT"),
            Map.entry("dungeon hub", "DUNGEON_HUB"));

    private static String islandName = "";
    private static String graphIsland = null;
    private static String scoreboardArea = "";
    private static int ticks = 0;

    private IslandDetector() {
    }

    /** Call once per client tick; the real reads happen 4x per second. */
    public static void tick(Minecraft client) {
        if (client.level == null || client.getConnection() == null) {
            islandName = "";
            graphIsland = null;
            scoreboardArea = "";
            return;
        }
        if (ticks++ % 5 != 0) {
            return;
        }
        islandName = readTabArea(client);
        scoreboardArea = readScoreboardArea(client);
        String island = ISLANDS.get(islandName.toLowerCase(Locale.ROOT));
        if ("DWARVEN_MINES".equals(island) && GLACITE.matcher(scoreboardArea).find()) {
            island = "GLACITE_TUNNELS";
        }
        graphIsland = island;
    }

    /** Graph file name for the island the player is on, or null when unknown / no graph exists. */
    public static String graphIsland() {
        return graphIsland;
    }

    /** Human island name from the tab list ("Hub"), or "". */
    public static String islandName() {
        return islandName;
    }

    /** Current scoreboard area ("Village"), or "". */
    public static String scoreboardArea() {
        return scoreboardArea;
    }

    /** The island a fairy-soul count in Hypixel's menu belongs to - Glacite souls are counted under Dwarven Mines. */
    public static String soulMenuGroup(String island) {
        return "GLACITE_TUNNELS".equals(island) ? "DWARVEN_MINES" : island;
    }

    /** Graph files whose fairy souls Hypixel counts under {@code menuGroup}. */
    public static List<String> graphsForSoulGroup(String menuGroup) {
        if ("DWARVEN_MINES".equals(menuGroup)) {
            return List.of("DWARVEN_MINES", "GLACITE_TUNNELS");
        }
        return List.of(menuGroup);
    }

    /** Island name as shown in Hypixel's own menus -> graph/menu group name, or null. */
    public static String byDisplayName(String name) {
        if (name == null) {
            return null;
        }
        return ISLANDS.get(name.trim().toLowerCase(Locale.ROOT));
    }

    private static String readTabArea(Minecraft client) {
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String plain = ChatFormatting.stripFormatting(display.getString());
            if (plain == null) {
                continue;
            }
            Matcher m = TAB_AREA.matcher(plain.trim());
            if (m.matches()) {
                return m.group(1).trim();
            }
        }
        return "";
    }

    private static String readScoreboardArea(Minecraft client) {
        for (String line : sidebarLines(client)) {
            Matcher m = SIDEBAR_AREA.matcher(line);
            if (m.matches()) {
                return m.group(1).trim();
            }
        }
        return "";
    }

    /** Plain (colour-stripped) sidebar lines, highest score first. */
    private static List<String> sidebarLines(Minecraft client) {
        List<String> out = new ArrayList<>();
        if (client.level == null) {
            return out;
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective objective = com.killer560.hub.scoreboard.ScoreboardData.sidebarObjective(client);
        if (objective == null) {
            return out;
        }
        List<PlayerScoreEntry> entries = new ArrayList<>();
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
            if (!entry.isHidden()) {
                entries.add(entry);
            }
        }
        entries.sort((a, b) -> Integer.compare(b.value(), a.value()));
        for (PlayerScoreEntry entry : entries) {
            String text;
            if (entry.display() != null) {
                text = entry.display().getString();
            } else {
                PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
                text = team == null ? entry.owner()
                        : team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString();
            }
            String plain = ChatFormatting.stripFormatting(text);
            out.add(plain == null ? text : plain);
        }
        return out;
    }
}
