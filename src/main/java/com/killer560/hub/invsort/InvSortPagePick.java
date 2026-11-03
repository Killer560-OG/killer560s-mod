package com.killer560.hub.invsort;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.slf4j.Logger;

/**
 * Put Others In's page pick (killer560, 2026-11-02: "'Put Others In' should open the storage and let him pick the page").
 * The layouts menu closes and {@code /storage} is sent - the same command he would type. He opens a page from there
 * as he always does (a click on its icon, or on its panel in the Storage Overlay), and the first Ender Chest page or
 * backpack that opens is the layout's page: it is saved, the page is closed and the layouts menu comes back with the
 * layout selected. Nothing here clicks a slot or sends anything but that one command: every click on the storage
 * menu is his own.
 * <p>
 * Closing the storage (Escape) with no page opened, or another menu opening, cancels and changes nothing.
 */
final class InvSortPagePick {

    private static final Logger LOGGER = ModLog.get("killer560smod-invsort");
    /** Ticks to wait for /storage (or a page) to open before giving up. */
    private static final int OPEN_TIMEOUT_TICKS = 100;
    /** Ticks with no screen, after the storage was up, before that counts as closed (a page swap can pass one). */
    private static final int CLOSED_TICKS = 20;
    private static final String OVERVIEW_TITLE = "Storage";

    private static String layoutName;
    private static int ticks;
    private static boolean sawStorage;
    private static int closedFor;

    private InvSortPagePick() {
    }

    static boolean isActive() {
        return layoutName != null;
    }

    /** The layout being picked for, or null. For tests. */
    static String pickingFor() {
        return layoutName;
    }

    /** Starts the pick for {@code name}. @return why it could not start, or null. */
    static String begin(String name) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.connection == null) {
            return "Not in a world.";
        }
        if (InventorySorterExecutor.isRunning()) {
            return "Wait for the sort to finish first.";
        }
        layoutName = name;
        ticks = 0;
        sawStorage = false;
        closedFor = 0;
        client.setScreenAndShow(null);
        client.player.connection.sendCommand("storage");
        LOGGER.info("[InvSort] Put Others In: picking a page for {} (/storage)", name);
        ModChat.send(InventorySorterCommands.FEATURE, ModChat.text("Open the page to put "), ModChat.value(name),
                ModChat.text("'s other items in (an Ender Chest page or a backpack). Escape cancels."));
        return null;
    }

    /** END_CLIENT_TICK, registered by {@link InventorySorterExecutor#register}. */
    static void tick(Minecraft client) {
        if (layoutName == null) {
            return;
        }
        ticks++;
        if (client.player == null) {
            layoutName = null;
            return;
        }
        Screen screen = McCompat.screen(client);
        if (screen instanceof AbstractContainerScreen<?> acs) {
            String title = acs.getTitle().getString();
            String key = StorageOverlayFeature.storageKeyForTitle(title);
            if (key != null) {
                String page = key.substring(key.lastIndexOf('|') + 1);
                if (InventoryLayoutStore.DUMP_PAGE.matcher(page).matches()) {
                    finish(client, page);
                    return;
                }
            }
            if (title.equals(OVERVIEW_TITLE)) {
                sawStorage = true;
                closedFor = 0;
                return;
            }
            if (sawStorage || ticks > OPEN_TIMEOUT_TICKS) {
                cancel(client, "another menu opened", false);
            }
            return;
        }
        if (screen == null) {
            if (sawStorage ? ++closedFor > CLOSED_TICKS : ticks > OPEN_TIMEOUT_TICKS) {
                cancel(client, sawStorage ? "no page was opened" : "/storage did not open", true);
            }
            return;
        }
        // Chat or any other screen.
        if (sawStorage || ticks > OPEN_TIMEOUT_TICKS) {
            cancel(client, "another screen opened", false);
        }
    }

    private static void finish(Minecraft client, String page) {
        String name = layoutName;
        layoutName = null;
        InventoryLayout layout = InventoryLayoutStore.getInstance().get(name);
        String message;
        boolean bad = false;
        if (layout == null) {
            message = "The layout " + name + " is gone.";
            bad = true;
        } else {
            String error = InventoryLayoutStore.getInstance().save(layout.withStorage(layout.fetchFromStorage(), page));
            message = error != null ? error : "Put Others In: " + InvSortStorage.label(page) + " for " + layout.name() + ".";
            bad = error != null;
        }
        LOGGER.info("[InvSort] Put Others In picked {} for {}", page, name);
        client.player.closeContainer();
        reopen(client, name, message, bad);
    }

    /** Ends the pick with nothing changed; {@code reopen} brings the layouts menu back (not over another menu). */
    private static void cancel(Minecraft client, String why, boolean reopen) {
        String name = layoutName;
        layoutName = null;
        LOGGER.info("[InvSort] Put Others In pick for {} cancelled: {}", name, why);
        ModChat.send(InventorySorterCommands.FEATURE, ModChat.dim("No page picked (" + why + ") - Put Others In unchanged."));
        if (reopen) {
            reopen(client, name, "No page picked - Put Others In unchanged.", false);
        }
    }

    private static void reopen(Minecraft client, String name, String message, boolean bad) {
        InventorySorterScreen menu = new InventorySorterScreen(null);
        client.setScreenAndShow(menu);
        menu.afterPick(name, message, bad);
    }
}
