package com.killer560.hub.fastleap;

import com.killer560.hub.compat.McCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Owner evidence for Fast Leap's stuck state (to-do item 68, killer560 2026-11-01: "i will try to leap manually click
 * someone then my game doesnt freeze but i cant move my mouse or walk or anything"). Its cause was never seen in a log, so
 * whenever anything that can hold his inputs or a leap has lasted more than {@link #STUCK_MS} - a leap menu open with no
 * heads in it, a leap queued or running, Fast Leap's or I4 Leap's input block, the use or movement keys held up - one INFO
 * line says what (DEBUG never reaches his log), and one more when it ends. One line per stuck spell, at most one every
 * {@link #MIN_GAP_MS}. Logging only: it reads state and changes nothing.
 */
public final class FastLeapStuckLog {

    static final long STUCK_MS = 1000L;
    static final long MIN_GAP_MS = 5000L;

    private static long since = 0L;
    private static boolean logged = false;
    private static long lastLogMs = 0L;
    private static int lines = 0;

    private FastLeapStuckLog() {
    }

    /** Lines written since the game started (testkit). */
    public static int lines() {
        return lines;
    }

    static void tick(Minecraft client) {
        if (client.player == null) {
            reset(client);
            return;
        }
        List<String> reasons = new ArrayList<>(4);
        Screen screen = McCompat.screen(client);
        AbstractContainerScreen<?> menu = LeapManager.leapMenuScreen(screen);
        int heads = 0;
        int items = 0;
        if (menu != null) {
            List<Slot> slots = menu.getMenu().slots;
            int n = Math.max(0, slots.size() - 36);
            for (int i = 0; i < n; i++) {
                ItemStack s = slots.get(i).getItem();
                if (!s.isEmpty()) {
                    items++;
                    if (s.is(Items.PLAYER_HEAD)) {
                        heads++;
                    }
                }
            }
            if (heads == 0) {
                reasons.add("leap menu with no heads");
            }
        }
        if (LeapManager.isBusy()) {
            reasons.add("leap busy");
        }
        if (LeapManager.blocksInput()) {
            reasons.add("Fast Leap blocks input");
        }
        if (I4LeapFeature.blocksInput()) {
            reasons.add("I4 Leap blocks input");
        }
        if (LeapManager.holdsKeys()) {
            reasons.add("keys held up");
        }
        long now = System.currentTimeMillis();
        if (reasons.isEmpty()) {
            reset(client);
            return;
        }
        if (since == 0L) {
            since = now;
            return;
        }
        if (logged || now - since <= STUCK_MS || now - lastLogMs < MIN_GAP_MS) {
            return;
        }
        logged = true;
        lastLogMs = now;
        lines++;
        var o = client.options;
        FastLeapFeature.LOGGER.info("[FastLeap] stuck for {} ms: {} | screen={} title=\"{}\" heads={} items={} containerMenu={}"
                        + " mouseGrabbed={} keysDown(fwd/back/left/right/jump/use/attack)={}/{}/{}/{}/{}/{}/{} | {}",
                now - since, reasons, screen == null ? "none" : screen.getClass().getSimpleName(),
                screen == null ? "" : screen.getTitle().getString(), heads, items,
                client.player.containerMenu == client.player.inventoryMenu ? "inventory"
                        : client.player.containerMenu.getClass().getSimpleName() + "#" + client.player.containerMenu.containerId,
                client.mouseHandler.isMouseGrabbed(), o.keyUp.isDown(), o.keyDown.isDown(), o.keyLeft.isDown(),
                o.keyRight.isDown(), o.keyJump.isDown(), o.keyUse.isDown(), o.keyAttack.isDown(), LeapManager.debugState());
    }

    private static void reset(Minecraft client) {
        if (logged) {
            lines++;
            FastLeapFeature.LOGGER.info("[FastLeap] stuck state ended after {} ms (screen={} mouseGrabbed={})",
                    System.currentTimeMillis() - since, McCompat.screen(client) == null ? "none"
                            : McCompat.screen(client).getClass().getSimpleName(), client.mouseHandler.isMouseGrabbed());
        }
        since = 0L;
        logged = false;
    }
}
