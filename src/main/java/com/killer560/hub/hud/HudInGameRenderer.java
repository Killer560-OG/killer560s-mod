package com.killer560.hub.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
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
            "tick_timers", "split_timers", "player_stats", "quiver_display", "livid_invuln_timer",
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
    }

    private static void draw(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        // Skyblock Only: none of these elements draw outside Skyblock / p3sim (the HUD editor previews them itself).
        // Menu check (2026-09-16): one gate here instead of "screen != null" inside every element, so chat never
        // hides these (killer560: "dont make it hide the gui if i open chat") and the HUD editor - which draws
        // each listed element itself - doesn't get a second copy from this layer underneath its boxes.
        if (client.player == null || McCompat.hudHidden(client) || HudVisibility.menuOpen()
                || !com.killer560.hub.util.SkyblockGate.allows()) {
            return;
        }
        // Past every gate that can stop this mod's HUD drawing, so this is the "the HUD is live" heartbeat the
        // editor's ten-second window is measured against - see HudSeen#markHudFrame for why it cannot be wall
        // time. One call per frame, not per element.
        HudSeen.markHudFrame();
        List<HudElement> elements = drawList();
        for (int i = 0; i < elements.size(); i++) {
            com.killer560.hub.hud.HudElementRegistry.drawAt(graphics, elements.get(i));
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
            for (HudElement element : com.killer560.hub.hud.HudElementRegistry.all()) {
                // Stat Bars' custom bars and readouts (2026-10-04) are drawn here too, by prefix rather than by name.
                if (UNDRAWN_ELEMENT_IDS.contains(element.id())
                        || com.killer560.hub.playerstats.StatElements.isStatElementId(element.id())) {
                    out.add(element);
                }
            }
            drawList = out;
            drawListVersion = version;
        }
        return drawList;
    }
}
