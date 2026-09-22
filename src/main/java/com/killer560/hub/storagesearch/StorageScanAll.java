package com.killer560.hub.storagesearch;

import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Scan All" - killer560 (2026-09-21): "a button titled scan all that will go through and open all of my storage
 * pages, my wardrobe pages, pet pages, everything but island chests. Those just need to always be remembered."
 * <p>
 * A queue of visits, one at a time: every Ender Chest page ({@code /enderchest n}) and backpack ({@code /backpack n}),
 * then the wardrobe and pets menus page by page. Each visit opens the menu, waits for its contents to arrive, and
 * closes it - the existing captures (Storage Overlay's page cache, this feature's wardrobe/pets capture on close)
 * record it exactly as if you had opened it yourself. A wardrobe / pets page after the first is reached by reopening
 * the menu and clicking its "Next Page" arrow that many times. A page that doesn't exist (a backpack slot you don't
 * have) simply never opens and is skipped after a short wait. Opening any other screen, or closing one of its
 * menus yourself, ends it.
 */
public final class StorageScanAll {

    private record Visit(String command, int nextClicks, String label) {
    }

    private static final int OPEN_TIMEOUT_TICKS = 30;
    private static final int SETTLE_TICKS = 12;
    private static final int CLICK_GAP_TICKS = 8;
    private static final int MAX_EXTRA_PAGES = 9;

    private static final List<Visit> queue = new ArrayList<>();
    private static Visit current;
    private static int ticks;
    private static int clicksDone;
    private static boolean opened;
    private static int visited;
    private static boolean registered;

    private StorageScanAll() {
    }

    public static boolean isRunning() {
        return current != null || !queue.isEmpty();
    }

    public static void start() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (!registered) {
            registered = true;
            ClientTickEvents.END_CLIENT_TICK.register(StorageScanAll::tick);
        }
        queue.clear();
        for (int i = 1; i <= 9; i++) {
            queue.add(new Visit("enderchest " + i, 0, "Ender Chest " + i));
        }
        for (int i = 1; i <= 18; i++) {
            queue.add(new Visit("backpack " + i, 0, "Backpack " + i));
        }
        // Wardrobe / pets: the extra pages only get queued once page 1 shows a Next Page arrow (see finishVisit()).
        queue.add(new Visit("wardrobe", 0, "Wardrobe page 1"));
        queue.add(new Visit("pets", 0, "Pets page 1"));
        current = null;
        visited = 0;
        ModChat.send(StorageSearchFeature.CHAT_PREFIX, ModChat.text("Scan All started - "),
                ModChat.dim("opening every storage page, wardrobe page and pet page. Open anything yourself to stop."));
        client.setScreen(null);
    }

    public static void stop(String why) {
        if (!isRunning()) {
            return;
        }
        queue.clear();
        current = null;
        ModChat.send(StorageSearchFeature.CHAT_PREFIX, ModChat.bad("Scan All stopped"), ModChat.dim(" - " + why));
    }

    private static void tick(Minecraft client) {
        if (!isRunning()) {
            return;
        }
        if (client.player == null || client.level == null) {
            queue.clear();
            current = null;
            return;
        }
        if (current == null) {
            if (client.screen != null) {
                return; // wait for the previous menu to finish closing
            }
            current = queue.remove(0);
            ticks = 0;
            clicksDone = 0;
            opened = false;
            client.player.connection.sendCommand(current.command());
            return;
        }
        ticks++;
        boolean containerOpen = client.screen instanceof AbstractContainerScreen<?>;
        if (!opened) {
            if (containerOpen) {
                opened = true;
                ticks = 0;
            } else if (ticks > OPEN_TIMEOUT_TICKS) {
                current = null; // this page doesn't exist - move on
            } else if (client.screen != null) {
                stop("another screen opened");
            }
            return;
        }
        if (!containerOpen) {
            stop("the menu was closed");
            return;
        }
        if (clicksDone < current.nextClicks()) {
            if (ticks >= CLICK_GAP_TICKS) {
                if (!clickNextPage(client)) {
                    finishVisit(client, false); // fewer pages than expected
                    return;
                }
                clicksDone++;
                ticks = 0;
            }
            return;
        }
        if (ticks >= SETTLE_TICKS) {
            finishVisit(client, true);
        }
    }

    private static void finishVisit(Minecraft client, boolean captured) {
        Visit done = current;
        boolean hasNext = captured && findNextPage(client) != null;
        client.player.closeContainer(); // the close is what records wardrobe / pets pages
        if (captured) {
            visited++;
        }
        // Page 1 of the wardrobe / pets with a Next Page arrow: queue page 2 (and so on, one page at a time).
        if (hasNext && (done.command().equals("wardrobe") || done.command().equals("pets"))
                && done.nextClicks() < MAX_EXTRA_PAGES) {
            String name = done.command().equals("wardrobe") ? "Wardrobe" : "Pets";
            queue.add(0, new Visit(done.command(), done.nextClicks() + 1, name + " page " + (done.nextClicks() + 2)));
        }
        current = null;
        if (queue.isEmpty()) {
            ModChat.send(StorageSearchFeature.CHAT_PREFIX, ModChat.good("Scan All done"),
                    ModChat.dim(String.format(Locale.US, " - %d pages remembered.", visited)));
        }
    }

    private static Slot findNextPage(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            return null;
        }
        List<Slot> slots = screen.getMenu().slots;
        int top = Math.max(0, slots.size() - 36);
        for (int i = 0; i < top; i++) {
            Slot s = slots.get(i);
            if (!s.getItem().isEmpty() && s.getItem().getHoverName().getString().toLowerCase(Locale.ROOT).contains("next page")) {
                return s;
            }
        }
        return null;
    }

    private static boolean clickNextPage(Minecraft client) {
        Slot next = findNextPage(client);
        if (next == null || !(client.screen instanceof AbstractContainerScreen<?> screen) || client.gameMode == null) {
            return false;
        }
        client.gameMode.handleContainerInput(screen.getMenu().containerId, next.index, 0, ContainerInput.PICKUP, client.player);
        return true;
    }
}
