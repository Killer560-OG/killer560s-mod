package com.killer560.hub.gui;

import com.killer560.hub.gui.tab.BaseTab;

import java.util.HashMap;
import java.util.Map;

/**
 * Card descriptions for the feature tabs whose master toggle has no tooltip to borrow (or that have no master toggle),
 * keyed by the tab class's simple name. One short line each, read off the feature's own code; see {@link FeatureCards}.
 */
final class FeatureDescriptions {

    private FeatureDescriptions() {
    }

    private static final Map<String, String> BY_TAB = new HashMap<>();

    static {
        // Filled from the feature sources, 2026-10-12 (Option A menu): only tabs whose master toggle has no tooltip, or
        // that have no master toggle at all.
        BY_TAB.put("HomeMainTab", "Update check, Discord, Skyblock Only, and the HUD editor, HUD Scale and Auto Scale.");
        BY_TAB.put("WitherDoorsTab", "Highlights the closest wither door, green once you hold a key.");
        BY_TAB.put("SimSettingsTab", "Dungeon Sim settings: Dungeon Breaker charges each secret gives back.");
        BY_TAB.put("SimKeybindsTab", "Next Room and Previous Room keys for route practice in the Dungeon Sim.");
        BY_TAB.put("AutoSimonSaysTab", "Trigger Bot, Auto Start and Auto Solve for the Simon Says device.");
        BY_TAB.put("HidePlayersTab", "Stops other players rendering: past a distance, in a dungeon or in the boss room.");
        BY_TAB.put("DvdTab", "Bouncing DVD-screensaver boxes of text or a GIF on your screen; several at once.");
        BY_TAB.put("CosmeticsTab", "Your custom name, player size and held-item look, and the global cosmetics switch.");
        BY_TAB.put("WindowLayoutTab", "Tiles several Minecraft windows side by side on one monitor.");
        BY_TAB.put("AutoExperimentsTab", "Plays the Experimentation Table: opens the games, solves them, claims the rewards.");
        BY_TAB.put("ProfilesTab", "Save, switch and share whole sets of settings.");
        // Tabs of several independent switches (no master one, so their first toggle's tooltip would describe only it).
        BY_TAB.put("SolverHighlightsTab", "The look every puzzle solver's highlight shares: through walls, waypoint style.");
        BY_TAB.put("DungeonAlertsTab", "Titles and sounds for a Shadow Assassin, a room cleared and its secrets done.");
        BY_TAB.put("MaxorTab", "F7/M7 Maxor crystals: highlights, place and respawn timers, unplaced-crystal alert.");
        BY_TAB.put("P3NavTab", "F7/M7 Phase 3: highlights the next gate, the terminals and the devices.");
        BY_TAB.put("F7SpotsTab", "Your saved F7/M7 walk-to waypoints and aim spots in the boss arena.");
        BY_TAB.put("ObjectHiderTab", "Hides fire, fog, particles, falling blocks and other screen and world clutter.");
        BY_TAB.put("DisplayOptionsTab", "Borderless fullscreen, the themed main menu, Pack Disabler and Fullbright.");
        BY_TAB.put("ThornTab", "F4/M4 Thorn: Spirit Bear HUD and ESP, stun spots and overkill messages.");
    }

    static String get(BaseTab tab) {
        return BY_TAB.get(tab.getClass().getSimpleName());
    }

    /** Every tab id with a fixed description (read by the testkit). */
    static Map<String, String> all() {
        return java.util.Collections.unmodifiableMap(BY_TAB);
    }
}
