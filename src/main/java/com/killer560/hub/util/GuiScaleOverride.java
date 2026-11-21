package com.killer560.hub.util;

import com.killer560.hub.compat.McCompat;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * A GUI scale for one open screen (the Terminal QoL scales, Melody's Harp Helper). Setting the window's real scale once on
 * open and putting it back once on close needs no mouse remapping: the mouse handler scales against whatever the window's
 * scale is, so clicks land where they look. The player's saved option is never touched. {@link #tick} is called every
 * client tick while the owner wants a scale (and once more after it stops): vanilla's {@code resizeDisplay} recomputes the
 * scale from the option on every window resize, which would otherwise drop the override mid-screen.
 */
public final class GuiScaleOverride {

    /** Pass as {@code wanted} for the largest scale that fits the window (what vanilla's Auto picks). */
    public static final int AUTO = 99;

    private GuiScaleOverride() {
    }

    /**
     * @param wanted  the scale to hold now, or 0 for none
     * @param applied whether the caller's last call held one
     * @return whether this call holds one (pass it back next tick)
     */
    public static boolean tick(int wanted, boolean applied) {
        Minecraft client = Minecraft.getInstance();
        Window window = client.getWindow();
        if (window == null || client.options == null) {
            return applied;
        }
        if (wanted > 0) {
            int target = window.calculateScale(wanted, client.isEnforceUnicode());
            if (window.getGuiScale() != target) {
                apply(client, window, target);
            }
            return true;
        }
        if (applied) {
            // Recomputed from the live option rather than remembered, so a scale the player changed in Video Settings
            // while the screen was open is still honored when it closes.
            int vanilla = window.calculateScale(client.options.guiScale().get(), client.isEnforceUnicode());
            if (window.getGuiScale() != vanilla) {
                apply(client, window, vanilla);
            }
        }
        return false;
    }

    private static void apply(Minecraft client, Window window, int scale) {
        window.setGuiScale(scale);
        Screen screen = McCompat.screen(client);
        if (screen != null) {
            screen.resize(window.getGuiScaledWidth(), window.getGuiScaledHeight());
        }
    }
}
