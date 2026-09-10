package com.killer560.hub.rngmeter;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Locale;

/**
 * Best-effort detection of which RNG-meter category the player is currently in, read from the
 * Hypixel sidebar scoreboard. Category indices match {@link RngItemData#CATEGORY_NAMES}.
 *
 * <p>The keyword matches below are a first pass, not verified against a live session - Hypixel's
 * exact scoreboard wording per area hasn't been confirmed. If a category picks the wrong tab (or
 * none) in a given area, that keyword list needs adjusting to match what actually shows up.
 */
public final class LocationTracker {

    private LocationTracker() {
    }

    /** Returns a category index (0-5, matching {@link RngItemData#CATEGORY_NAMES}) or -1 if unknown. */
    public static int detectCategory() {
        String sidebar = readSidebarText();
        if (sidebar.isEmpty()) {
            return -1;
        }
        String lower = sidebar.toLowerCase(Locale.US);

        if (lower.contains("master mode")) {
            return 1; // Master Mode
        }
        if (lower.contains("catacombs") || lower.matches("(?s).*\\bf[1-7]\\b.*")) {
            return 0; // Dungeons
        }
        if (lower.contains("nucleus") || lower.contains("crystal hollows")) {
            return 3; // Crystal Nucleus
        }
        if (lower.contains("superpairs") || lower.contains("experimentation")) {
            return 4; // Experimentation Table
        }
        if (lower.contains("corpse") || lower.contains("glacite") || lower.contains("mineshaft")) {
            return 5; // Frozen Corpses
        }
        if (lower.contains("slayer")) {
            return 2; // Slayers
        }
        return -1;
    }

    // Real bug found and fixed (2026-09-09, round 22) - see DungeonState#readSidebarText's doc comment
    // for the full story: killer560's own real F7-clear log proved DisplaySlot.SIDEBAR WAS populated the
    // whole time (the round-13 "wrong slot" theory here was a red herring), but every line came back as
    // invisible-color-code garbage - Hypixel's real anti-scraping technique puts the actual visible text
    // on each entry's registered PlayerTeam prefix/suffix, not on entry.display()/owner() directly. This
    // class had the identical bug (same copied approach, per its own now-outdated doc note above) - fixed
    // the same way, confirmed against SkyHanni's own real, working ScoreboardCompatKt.getPlayerNames.
    private static String readSidebarText() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return "";
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            for (DisplaySlot slot : DisplaySlot.values()) {
                if (slot == DisplaySlot.SIDEBAR || slot == DisplaySlot.LIST || slot == DisplaySlot.BELOW_NAME) {
                    continue;
                }
                Objective candidate = scoreboard.getDisplayObjective(slot);
                if (candidate != null) {
                    sidebar = candidate;
                    break;
                }
            }
        }
        if (sidebar == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(sidebar.getDisplayName().getString()).append('\n');
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
            sb.append(realLineText(scoreboard, entry)).append('\n');
        }
        return sb.toString();
    }

    private static String realLineText(Scoreboard scoreboard, PlayerScoreEntry entry) {
        PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
        if (team == null) {
            return entry.display() != null ? entry.display().getString() : entry.owner();
        }
        StringBuilder line = new StringBuilder();
        Component prefix = team.getPlayerPrefix();
        if (prefix != null) {
            line.append(prefix.getString());
        }
        Component suffix = team.getPlayerSuffix();
        if (suffix != null) {
            line.append(suffix.getString());
        }
        return line.toString();
    }
}
