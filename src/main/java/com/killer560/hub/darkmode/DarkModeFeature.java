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
 * as in NoammAddons. Two screens that are really HUD are tinted by their own last draw (killer560, 2026-11-09: "the dark
 * mode didnt apply to the mod hud ... also when i open chat"): the chat screen (chat lines, input, Chat Tabs, and the HUD
 * elements Fabric draws under a screen) and the HUD editor, with Tint HUD on. With an in-game screen open Fabric defers the layers after SUBTITLES into the screen's background
 * (after its dim, before its panel), so the Tint HUD fill still lands under the screen itself.
 */
public final class DarkModeFeature {

    /** Frames each layer filled, for the testkit (2950-2953). */
    public static int worldFills;
    public static int hudFills;

    /** Frames a screen was tinted, for the testkit. */
    public static int screenFills;

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
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT
                .register((c, screen, w, h) -> tintScreen(screen)));
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "dark_mode_hud"), (graphics, deltaTracker) -> {
                    DarkModeConfig cfg = DarkModeConfig.getInstance();
                    if (cfg.isEnabled() && cfg.isTintHud()) {
                        fill(graphics, cfg.getOpacity());
                        hudFills++;
                    }
                }));
    }

    /** Registered at CLIENT_STARTED so this screen listener runs after every mod's own (the fill lands on top of them).
     *  Which screens count is decided each frame, because a container's reskin is known only once its title is. */
    private static void tintScreen(net.minecraft.client.gui.screens.Screen screen) {
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register((s, graphics, mx, my, delta) -> {
            DarkModeConfig cfg = DarkModeConfig.getInstance();
            if (cfg.isEnabled() && cfg.isTintHud() && skinned(s)) {
                fill(graphics, cfg.getOpacity());
                screenFills++;
            }
        });
    }

    /** A screen the mod draws or skins: its own screens (menus, editors), the chat, and the AH / Bazaar reskins; any other
     *  vanilla screen stays as it is. */
    private static boolean skinned(net.minecraft.client.gui.screens.Screen s) {
        return s instanceof net.minecraft.client.gui.screens.ChatScreen
                || s.getClass().getName().startsWith("com.killer560.hub.")
                || com.killer560.hub.auction.ah.AhReskin.isHiding(s) || com.killer560.hub.bazaar.BazaarReskin.isHiding(s);
    }

    /** Black at {@code opacity} % over the whole GUI area. */
    private static void fill(GuiGraphicsExtractor graphics, int opacity) {
        int alpha = Math.round(DarkModeConfig.clampOpacity(opacity) * 255f / 100f);
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24);
    }
}
