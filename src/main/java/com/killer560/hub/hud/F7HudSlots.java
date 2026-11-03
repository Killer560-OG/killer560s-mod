package com.killer560.hub.hud;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * Default spots for the F7/M7 boss HUDs (2026-11-02, killer560's screenshots at 1920x1080 GUI 2: the Storm line over
 * Maxor's "Active: 1/2", "Team Melody: waiting..." over the Split Timers, the P3 list over chat). Every one of them used
 * to default to x 10 in the left column, where the chat (y 410-500 there), the Split Timers (from y 240) and a dozen other
 * defaults already sit. They now default to one column on the RIGHT, under the status-effect icons and above the vanilla
 * sidebar (which is centered on the right edge, about y 200-340 at 540 GUI px high):
 * <pre>
 *   y 56   Tick Timers (up to 3 lines)
 *   y 98   Maxor's Crystals (P1) / P3 Status (P3) - never on screen together
 *   y 350  Team Melody (P3), under the sidebar: its teammate rows (and boards) can run 120 px tall
 * </pre>
 * Only defaults: a position the player saved is never moved (the HUD editor's own rule).
 */
public final class F7HudSlots {

    /** The column's box: its left edge is this far from the screen's right edge. */
    static final int COLUMN_FROM_RIGHT = 170;
    public static final int TICK_TIMERS = 56;
    public static final int MAXOR_CRYSTALS = 98;
    public static final int P3_STATUS = 98;
    public static final int TEAM_MELODY = 350;

    private F7HudSlots() {
    }

    /** The column's x on the current screen (GUI pixels); 10 when no window is up yet. */
    public static int x(int slotY) {
        Minecraft client = Minecraft.getInstance();
        Window window = client == null ? null : client.getWindow();
        if (window == null) {
            return 10;
        }
        return Math.max(10, window.getGuiScaledWidth() - COLUMN_FROM_RIGHT);
    }

    public static int y(int slotY) {
        return slotY;
    }
}
