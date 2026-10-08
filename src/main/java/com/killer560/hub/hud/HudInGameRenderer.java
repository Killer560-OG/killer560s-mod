package com.killer560.hub.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Set;
import com.killer560.hub.compat.McCompat;

/**
 * Real bug found and fixed (2026-09-15, spotted while wiring the Dungeon Alerts HUDs): only a handful of this mod's
 * {@link HudElement}s ever had an in-game draw path - each feature that shipped its own Gui mixin (Ability Timers,
 * Dungeon Info, Etherwarp, Posmsg, GIFs, RNG Meter) draws its own element. These were registered for the HUD editor
 * (so they could be moved/scaled there) but NOTHING drew them while playing, so they never showed up at all. Draws
 * exactly those elements through Fabric's own HUD API, at their HUD-editor position and scale. Each element's own
 * render() already gates on its feature being enabled / no screen open, so drawing them here every frame is safe.
 */
public final class HudInGameRenderer {

    // Set, not List: this is probed once per registered element per frame (2026-09-20, FPS pass).
    private static final Set<String> UNDRAWN_ELEMENT_IDS = Set.of(
            "tick_timers", "split_timers", "quiver_display", "livid_invuln_timer",
            "mask_invincibility", "simonsays_party_progress", "live_map", "real_time", "score_calculator",
            "wither_dragon_timers", "king_relic_timer",
            "ability_cooldowns", "lag_display",
            "blessings", "maxor_crystals",
            // Advanced Position (2026-09-21) - missing from this list at first, so it never drew in game.
            "advanced_position");

    private HudInGameRenderer() {
    }

    /** Call once, after every feature has registered its HUD elements. */
    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("killer560smod", "hud_elements_in_game"),
                (graphics, deltaTracker) -> draw(graphics));
        // Health and Mana Bars get their own layer, with vanilla's status bars (2026-10-07, killer560: "The custom
        // health bars should show even in my inventory"). It draws behind an open screen as vanilla's hearts and
        // hotbar do: the screen's dimmed background, panel and slots all go over it. It is NOT the addLast layer above:
        // with an in-game screen open vanilla defers its SUBTITLES layer into Screen.extractBackground, AFTER the dim
        // gradient (Gui.extractSubtitleOverlay / extractDeferredSubtitles, javap 26.1.2), and Fabric's addLast layers
        // come after SUBTITLES - drawn from there, a bar showed undimmed through the dim (testkit 441's first run).
        HudElementRegistry.attachElementAfter(VanillaHudElements.EXPERIENCE_LEVEL,
                Identifier.fromNamespaceAndPath("killer560smod", "stat_readouts"), (graphics, deltaTracker) -> drawStats(graphics));
    }

    /** Whether this mod's in-game HUD may draw at all now (the HUD editor previews every element itself). */
    private static boolean hudAllowed(Minecraft client) {
        return client.player != null && !McCompat.hudHidden(client) && !HudVisibility.editorOpen()
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    private static void drawStats(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!hudAllowed(client)) {
            return;
        }
        // Health and Mana Bars' Predefined layout reads values and vanilla rows that change between frames.
        com.killer560.hub.playerstats.StatLayout.newFrame();
        List<HudElement> elements = statList();
        HudTextCache.begin();
        try {
            for (int i = 0; i < elements.size(); i++) {
                com.killer560.hub.hud.HudElementRegistry.drawAt(graphics, elements.get(i));
            }
        } finally {
            HudTextCache.end();
        }
    }

    private static void draw(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        // Skyblock Only: none of these elements draw outside Skyblock / p3sim (the HUD editor previews them itself).
        // Menu check (2026-09-16): one gate here instead of "screen != null" inside every element, so chat never
        // hides these (killer560: "dont make it hide the gui if i open chat") and the HUD editor - which draws
        // each listed element itself - doesn't get a second copy from this layer underneath its boxes.
        if (!hudAllowed(client) || HudVisibility.menuOpen()) {
            return;
        }
        // Past every gate that can stop this mod's HUD drawing, so this is the "the HUD is live" heartbeat the
        // editor's ten-second window is measured against - see HudSeen#markHudFrame for why it cannot be wall
        // time. One call per frame, not per element.
        HudSeen.markHudFrame();
        List<HudElement> elements = drawList();
        HudTextCache.begin(); // these elements' String lines keep their visual order between frames
        try {
            for (int i = 0; i < elements.size(); i++) {
                com.killer560.hub.hud.HudElementRegistry.drawAt(graphics, elements.get(i));
            }
        } finally {
            HudTextCache.end();
        }
    }

    private static List<HudElement> drawList = List.of();
    private static int drawListVersion = -1;

    /** The registered elements this layer draws, in registry order. An element's id never changes, so the filter's
     *  answer only changes when the registry does; it used to be re-run over every element (~100, two string tests
     *  each) every frame, which was most of this layer's cost with every feature off (95-fps-bench, 2026-10-05). */
    private static List<HudElement> drawList() {
        int version = com.killer560.hub.hud.HudElementRegistry.version();
        if (version != drawListVersion) {
            List<HudElement> out = new java.util.ArrayList<>();
            List<HudElement> stats = new java.util.ArrayList<>();
            for (HudElement element : com.killer560.hub.hud.HudElementRegistry.all()) {
                if (UNDRAWN_ELEMENT_IDS.contains(element.id())) {
                    out.add(element);
                } else if (com.killer560.hub.playerstats.StatElements.isStatElementId(element.id())) {
                    // Stat Bars' bars and readouts (2026-10-04), by prefix: drawn by their own layer (drawStats).
                    stats.add(element);
                }
            }
            drawList = out;
            statList = stats;
            drawListVersion = version;
        }
        return drawList;
    }

    private static List<HudElement> statList = List.of();

    private static List<HudElement> statList() {
        drawList(); // refreshes both lists when the registry changed
        return statList;
    }
}
