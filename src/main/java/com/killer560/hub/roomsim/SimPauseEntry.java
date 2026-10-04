package com.killer560.hub.roomsim;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
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

    /** Vanilla's pause-menu button width. */
    private static final int WIDTH = 204;

    /** Vanilla's row spacing in that column. */
    private static final int GAP = 4;

    private SimPauseEntry() {
    }

    /**
     * The y just under the lowest button in the centre column, or the old bottom-of-screen spot when the
     * menu has no buttons (F3+Esc opens it without them).
     */
    private static int belowVanillaColumn(Screen screen, AbstractWidget own) {
        int centre = screen.width / 2;
        int bottom = -1;
        for (AbstractWidget w : Screens.getWidgets(screen)) {
            // Buttons only: a text label another mod parks at the bottom of the screen must not drag this down.
            if (w == own || !w.visible || !(w instanceof Button)) {
                continue;
            }
            if (w.getX() <= centre && centre < w.getX() + w.getWidth()) {
                bottom = Math.max(bottom, w.getY() + w.getHeight());
            }
        }
        return bottom < 0 ? screen.height - 46 : bottom + GAP;
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(SimPauseEntry::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof PauseScreen) || !SimState.canAct(client)) {
            return;
        }
        // DIRECTLY UNDER THE LAST VANILLA BUTTON, at vanilla's own width and spacing.
        //
        // killer560 (2026-10-04): it "sits alone at the very bottom of the Game Menu screen, far below the
        // vanilla button column". It was pinned to screen.height - 46 whatever the column did. The column's
        // positions are read off the screen's own widgets now, so it lands 4 px under Save and Quit (or
        // whatever is lowest in the centre column) and moves with it at any GUI scale.
        int width = WIDTH;
        int x = screen.width / 2 - width / 2;
        int y = belowVanillaColumn(screen, ownerScreen.get() == screen ? ownButton.get() : null);

        AbstractWidget existing = ownerScreen.get() == screen ? ownButton.get() : null;
        if (existing != null && Screens.getWidgets(screen).contains(existing)) {
            existing.setX(x);
            existing.setY(y);
            return;
        }

        // A vanilla Button, not the mod's amber SettingsButtonWidget, so it looks like the rest of the menu.
        // Button.builder/bounds/build checked identical in the 26.1.2 and 26.2 jars with javap.
        Button button = Button.builder(
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
