package com.killer560.hub.darkmode;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Dark Mode (killer560, 2026-11-02: "implement noamm's dark mode"; idea from NoammAddons' DarkMode, CC0): ONE black fill
 * at Opacity % over the whole GUI area, drawn once at the very end of the GUI frame by {@code mixin/DarkModeGuiMixin}
 * (after the HUD, the open screen and its tooltips, toasts and the debug overlay). So the world, HUD, chat, inventory,
 * every container and menu (also the title screen, with no world loaded) and this mod's screens darken at one even
 * level, and opening a screen never changes it.
 * <p>
 * Before 2026-11-10 there were up to three fills (a world layer, a HUD layer and a per-screen tint on the chat and the
 * mod's own screens), so the chat screen was darkened twice and vanilla screens not at all (killer560: "whenever I open
 * chat the whole screens color changes to get darker ... that dark mode should affect literally everything"). Tint HUD
 * is gone with them.
 */
public final class DarkModeFeature {

    /** Frames filled, for the testkit (2950-2953). */
    public static int frameFills;

    private DarkModeFeature() {
    }

    /** Called once per GUI frame by the mixin. */
    public static void frameFill(GuiGraphicsExtractor graphics) {
        DarkModeConfig cfg = DarkModeConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        int alpha = Math.round(cfg.getOpacity() * 255f / 100f);
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24);
        frameFills++;
    }
}
