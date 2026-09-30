package com.killer560.hub.roomsim;

import com.killer560.hub.gui.SettingsButtonWidget;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.ref.WeakReference;
import com.killer560.hub.compat.McCompat;

/**
 * "Change Room" on the pause screen, while you are in the sim.
 *
 * <p>killer560 (2026-09-28): "Make it so if i press esacpe while in teh sim room there is an option to change
 * room." The alternative was quitting to the title screen and going back in through the menu, which is a long
 * way round for the thing he will do most often - trying the same route in the next room along.
 *
 * <p>Only inside the sim. On a real server this screen is untouched, which matters because the pause screen is
 * one of the few places a stray button would be noticed immediately.
 */
public final class SimPauseEntry {

    private static WeakReference<Screen> ownerScreen = new WeakReference<>(null);
    private static WeakReference<AbstractWidget> ownButton = new WeakReference<>(null);

    private SimPauseEntry() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(SimPauseEntry::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof PauseScreen) || !SimState.canAct(client)) {
            return;
        }
        // Under the button column, clear of it. The pause screen's own rows are laid out by a layout this has
        // no business joining, so this sits below rather than trying to become one of them.
        int width = 204;
        int x = screen.width / 2 - width / 2;
        int y = screen.height - 46;

        AbstractWidget existing = ownerScreen.get() == screen ? ownButton.get() : null;
        if (existing != null && Screens.getWidgets(screen).contains(existing)) {
            existing.setX(x);
            existing.setY(y);
            return;
        }

        SettingsButtonWidget button = SettingsButtonWidget.builder(
                        Component.literal("Change Room"), btn -> {
                            // Straight to the picker. It opens the world it needs, so there is nothing to tear
                            // down here - and leaving the sim first would drop him to the title screen, which
                            // is the long way round this button exists to avoid.
                            McCompat.setScreen(client, new SimMenuScreen(screen));
                        })
                .bounds(x, y, width, 20)
                .build();

        Screens.getWidgets(screen).add(button);
        ownerScreen = new WeakReference<>(screen);
        ownButton = new WeakReference<>(button);
    }
}
