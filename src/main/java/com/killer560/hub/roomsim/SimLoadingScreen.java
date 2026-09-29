package com.killer560.hub.roomsim;

import com.killer560.hub.gui.profit.ProfitPanels;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The sim's own loading screen, held up until the map is actually built.
 *
 * <p>killer560 (2026-09-28): "no room ever loaded. Make sure the room loads before I am actually put into the
 * workd. And make it a custom loading screen."
 *
 * <p>The first half was a real bug. Picking a room from the main menu called into the builder while there was
 * still no world, which took a branch that opened an empty sim and printed "run /simbuild room again once you
 * are in" - so the room was never placed and the message asking him to finish the job himself scrolled past in
 * chat. Building is now queued and runs the moment the world exists.
 *
 * <p>The second half is what makes that honest. A big room is hundreds of thousands of blocks, and the world
 * arrives before any of them are placed, so without this he would be standing in an empty flat world watching a
 * dungeon appear around him. This screen covers that gap: it goes up as the world opens and comes down when the
 * build reports finished, so the first thing he sees is the finished room.
 *
 * <p>It cannot be dismissed with Escape on purpose. Escaping out of it would drop him into exactly the
 * half-built world it exists to hide, which is the confusing state, not a useful escape hatch.
 */
public final class SimLoadingScreen extends Screen {

    private String what;
    private String detail = "";
    private int ticks;

    public SimLoadingScreen(String what) {
        super(Component.literal("Dungeon Sim"));
        this.what = what == null ? "" : what;
    }

    /** Updates the line under the title - the room being placed, or the block count as it lands. */
    public void progress(String line) {
        this.detail = line == null ? "" : line;
    }

    public void setWhat(String line) {
        this.what = line == null ? "" : line;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        // Pausing would stop the integrated server, which is the thread doing the building.
        return false;
    }

    @Override
    public void tick() {
        ticks++;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xFF0B0B0B);

        int panelW = Math.min(this.width - 40, 420);
        int panelH = 110;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, ProfitPanels.PANEL_BG);
        g.outline(panelX, panelY, panelW, panelH, ProfitPanels.BORDER);

        g.centeredText(this.font, "DUNGEON SIM", panelX + panelW / 2, panelY + 16, ProfitPanels.ACCENT);
        if (!what.isEmpty()) {
            g.centeredText(this.font, what, panelX + panelW / 2, panelY + 38, ProfitPanels.TEXT);
        }
        if (!detail.isEmpty()) {
            g.centeredText(this.font, detail, panelX + panelW / 2, panelY + 52, ProfitPanels.DIM);
        }

        // A bar that sweeps rather than fills. The builder cannot say how far along it is without counting the
        // work twice, and a progress bar that invents a percentage is worse than one that only says "working".
        int barX = panelX + 20;
        int barW = panelW - 40;
        int barY = panelY + panelH - 30;
        g.fill(barX, barY, barX + barW, barY + 6, 0xFF1A1A1A);
        int span = Math.max(24, barW / 4);
        int travel = barW + span;
        int head = (int) (((ticks * 3L) % travel) - span);
        int from = Math.max(barX, barX + head);
        int to = Math.min(barX + barW, barX + head + span);
        if (to > from) {
            g.fill(from, barY, to, barY + 6, ProfitPanels.ACCENT);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** Puts this screen up, replacing whatever is there. */
    public static SimLoadingScreen show(Minecraft client, String what) {
        SimLoadingScreen screen = new SimLoadingScreen(what);
        client.setScreen(screen);
        return screen;
    }

    /** Takes it down, but only if it is still the screen showing - never closes something else. */
    public static void dismiss(Minecraft client) {
        if (client.screen instanceof SimLoadingScreen) {
            client.setScreen(null);
        }
    }
}
