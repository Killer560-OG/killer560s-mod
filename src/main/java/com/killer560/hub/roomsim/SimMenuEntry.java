package com.killer560.hub.roomsim;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.profit.ProfitPanels;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import java.lang.ref.WeakReference;

/**
 * "Dungeon Sim" entry point on the vanilla Multiplayer screen. Killer560: "It should be its own option to join
 * under multiplayer on the main menu. It should all be client side." This class only has to get the player from
 * that screen to the sim - the sim world itself (loading a captured {@link RoomLibrary} / {@link MapCode} layout
 * into a real local world) is being built elsewhere in this package, not here.
 *
 * <p><b>No mixin.</b> {@code JoinMultiplayerScreen} lays its own buttons out through a private
 * {@code HeaderAndFooterLayout} (javap 26.1.2: header 33px / footer 60px, both row-based {@code LinearLayout}s
 * built in {@code init()}) that nothing outside the class can add a row to without one. {@link Screens#getWidgets}
 * - the same {@code ScreenEvents.AFTER_INIT} + Fabric {@code ButtonList} technique
 * {@code mainmenu.MainMenuTitleLayout} already uses on the title screen - hands back a live view over the
 * screen's renderables/children/narratables list that a plain {@code List.add} wires into all three at once, so
 * this button needs no layout row: it sits in the screen's untouched top-right corner, clear of both the
 * centred header title and every footer button (all of which javap confirms live in the bottom 60px).
 *
 * <p>{@code ScreenEvents.AFTER_INIT} fires again on every resize / GUI-scale change, not just the first open
 * (javap 26.1.2: {@code Screen.resize(II)} only calls {@code repositionElements()}, never {@code clearWidgets()},
 * so a widget added outside the layout survives resize untouched and would otherwise stay pinned at its old
 * corner forever). {@link #onScreenInit} tracks its own button by identity and repositions it on that second and
 * later call instead of adding a duplicate.
 */
public final class SimMenuEntry {

    private static final int BUTTON_W = 110;
    private static final int BUTTON_H = 18;
    private static final int MARGIN = 4;

    private static WeakReference<Screen> ownerScreen = new WeakReference<>(null);
    private static WeakReference<AbstractWidget> ownButton = new WeakReference<>(null);

    private SimMenuEntry() {
    }

    /** Call once from the client entrypoint, the same way every other {@code ScreenEvents.AFTER_INIT} feature
     *  in this codebase is registered (e.g. {@code RoomRecorderFeature.register()}). */
    public static void register() {
        ScreenEvents.AFTER_INIT.register(SimMenuEntry::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof JoinMultiplayerScreen)) {
            return;
        }
        int x = screen.width - MARGIN - BUTTON_W;
        int y = MARGIN;

        AbstractWidget existing = ownerScreen.get() == screen ? ownButton.get() : null;
        if (existing != null && Screens.getWidgets(screen).contains(existing)) {
            // Same screen instance as last time (a resize/scale change, not a fresh open) - move it, don't
            // add a second one.
            existing.setX(x);
            existing.setY(y);
            return;
        }

        SettingsButtonWidget button = SettingsButtonWidget.builder(Component.literal("Dungeon Sim"), btn -> {
            // Opens the chooser rather than a world: killer560 asked that clicking this "open a new menu that
            // says load previous run create new map or load a room". Nothing is loaded until he has picked,
            // and SimState stays off until a world actually arrives.
            client.setScreen(new SimMenuScreen(screen));
        }).bounds(x, y, BUTTON_W, BUTTON_H).build();

        Screens.getWidgets(screen).add(button);
        ownerScreen = new WeakReference<>(screen);
        ownButton = new WeakReference<>(button);
    }

    /**
     * Stands in for the real sim world until it exists. Every way out of this screen - the Back button and
     * Escape alike - runs through {@link #onClose()}, and {@link #onClose()} is the one place that calls
     * {@link SimState#leave()}. That flag is what every sim-only ability gates on, and there is no world here
     * to leave it attached to: {@link SimState}'s own javadoc calls a session that stays flagged active outside
     * a sim world the one outcome that must be impossible, not just unlikely.
     */
    private static final class SimPlaceholderScreen extends Screen {

        private final Screen parent;

        private SimPlaceholderScreen(Screen parent) {
            super(Component.literal("Dungeon Sim"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), btn -> onClose())
                    .bounds(this.width / 2 - 50, this.height - 30, 100, 20)
                    .build());
        }

        @Override
        public void onClose() {
            SimState.leave();
            this.minecraft.setScreen(parent);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            g.fill(0, 0, this.width, this.height, 0xCC000000);
            int panelW = Math.min(this.width - 20, 360);
            int panelH = 128;
            int panelX = (this.width - panelW) / 2;
            int panelY = (this.height - panelH) / 2;
            g.fill(panelX, panelY, panelX + panelW, panelY + panelH, ProfitPanels.PANEL_BG);
            g.outline(panelX, panelY, panelW, panelH, ProfitPanels.BORDER);
            g.centeredText(this.font, "Dungeon Sim isn't built yet", panelX + panelW / 2, panelY + 12, ProfitPanels.ACCENT);

            String[] lines = {
                    "The sim world builder hasn't landed.",
                    "",
                    "Once it has, this button will:",
                    "- Decode a captured room layout (RoomLibrary / MapCode)",
                    "- Build a real local singleplayer world from it",
                    "- Hand you off into that world as the sim session",
            };
            int ly = panelY + 30;
            for (String line : lines) {
                g.text(this.font, line, panelX + 12, ly, ProfitPanels.TEXT, false);
                ly += 12;
            }
            super.extractRenderState(g, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
