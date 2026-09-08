package com.killer560.hub.rngmeter;

import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
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

    private static String readSidebarText() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return "";
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(sidebar.getDisplayName().getString()).append('\n');
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
            sb.append(entry.display() != null ? entry.display().getString() : entry.owner()).append('\n');
        }
        return sb.toString();
    }
}
