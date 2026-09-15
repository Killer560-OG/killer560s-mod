package com.killer560.hub.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Real bug found and fixed (2026-09-15, spotted while wiring the Dungeon Alerts HUDs): only a handful of this mod's
 * {@link HudElement}s ever had an in-game draw path - each feature that shipped its own Gui mixin (Ability Timers,
 * Dungeon Info, Etherwarp, Posmsg, GIFs, RNG Meter) draws its own element. These were registered for the HUD editor
 * (so they could be moved/scaled there) but NOTHING drew them while playing, so they never showed up at all. Draws
 * exactly those elements through Fabric's own HUD API, at their HUD-editor position and scale. Each element's own
 * render() already gates on its feature being enabled / no screen open, so drawing them here every frame is safe.
 */
public final class HudInGameRenderer {

    private static final List<String> UNDRAWN_ELEMENT_IDS = List.of(
            "tick_timers", "split_timers", "player_stats", "quiver_display", "livid_invuln_timer",
            "mask_invincibility", "simonsays_party_progress", "live_map", "real_time");

    private HudInGameRenderer() {
    }

    /** Call once, after every feature has registered its HUD elements. */
    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("killer560smod", "hud_elements_in_game"),
                (graphics, deltaTracker) -> draw(graphics));
    }

    private static void draw(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui) {
            return;
        }
        for (HudElement element : com.killer560.hub.hud.HudElementRegistry.all()) {
            if (!UNDRAWN_ELEMENT_IDS.contains(element.id())) {
                continue;
            }
            int[] pos = com.killer560.hub.hud.HudElementRegistry.resolvePosition(element);
            float scale = com.killer560.hub.hud.HudElementRegistry.resolveScale(element);
            graphics.pose().pushMatrix();
            try {
                graphics.pose().translate(pos[0], pos[1]);
                graphics.pose().scale(scale, scale);
                element.render(graphics, 0, 0);
            } catch (RuntimeException e) {
                // One broken element must never take down the whole HUD frame.
            } finally {
                graphics.pose().popMatrix();
            }
        }
    }
}
