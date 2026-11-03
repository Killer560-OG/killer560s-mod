package com.killer560.hub.darkmode;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Dark Mode (killer560, 2026-11-02: "implement noamm's dark mode"; idea from NoammAddons' DarkMode, CC0): a black fill
 * over the whole scaled screen at Opacity %, drawn in the HUD pass.
 * <p>
 * Two Fabric HUD layers, never a {@code Gui}/{@code Hud} mixin (an {@code extractRenderState} inject crashed every 26.2
 * launch, see {@code hud/GuiOverlays}). With Tint HUD off the fill is the FIRST layer, under every vanilla and mod HUD
 * layer, so only the world darkens. With Tint HUD on it is the LAST layer, registered at {@code CLIENT_STARTED} so it
 * is added after every mod's own {@code addLast} layers (this mod's HUD elements included) and darkens them too.
 * <p>
 * Open screens are drawn after the whole HUD pass, so menus, containers and this mod's settings menu are never tinted,
 * as in NoammAddons. With an in-game screen open Fabric defers the layers after SUBTITLES into the screen's background
 * (after its dim, before its panel), so the Tint HUD fill still lands under the screen itself.
 */
public final class DarkModeFeature {

    /** Frames each layer filled, for the testkit (2950-2953). */
    public static int worldFills;
    public static int hudFills;

    private static boolean registered;

    private DarkModeFeature() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("killer560smod", "dark_mode_world"),
                (graphics, deltaTracker) -> {
                    DarkModeConfig cfg = DarkModeConfig.getInstance();
                    if (cfg.isEnabled() && !cfg.isTintHud()) {
                        fill(graphics, cfg.getOpacity());
                        worldFills++;
                    }
                });
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "dark_mode_hud"), (graphics, deltaTracker) -> {
                    DarkModeConfig cfg = DarkModeConfig.getInstance();
                    if (cfg.isEnabled() && cfg.isTintHud()) {
                        fill(graphics, cfg.getOpacity());
                        hudFills++;
                    }
                }));
    }

    /** Black at {@code opacity} % over the whole GUI area. */
    private static void fill(GuiGraphicsExtractor graphics, int opacity) {
        int alpha = Math.round(DarkModeConfig.clampOpacity(opacity) * 255f / 100f);
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24);
    }
}
