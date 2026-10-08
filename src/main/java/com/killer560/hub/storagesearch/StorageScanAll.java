package com.killer560.hub.storagesearch;

import com.killer560.hub.storageoverlay.StorageOverlayCache;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import com.killer560.hub.storageoverlay.StoragePageSlots;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.killer560.hub.compat.McCompat;

/**
 * "Scan All" - killer560 (2026-09-21): "a button titled scan all that will go through and open all of my storage
 * pages, my wardrobe pages, pet pages, everything but island chests. Those just need to always be remembered."
 * <p>
 * A queue of visits, one at a time: every Ender Chest page ({@code /enderchest n}) and backpack ({@code /backpack n}),
 * then the wardrobe and pets menus page by page. Each visit opens the menu, waits for its contents to arrive, and
 * closes it - the existing captures (Storage Overlay's page cache, this feature's wardrobe/pets capture on close)
 * record it exactly as if you had opened it yourself. A wardrobe / pets page after the first is reached by reopening
 * the menu and clicking its "Next Page" arrow that many times. Opening any other screen, or closing one of its
 * menus yourself, ends it.
 * <p>
 * Which pages: killer560 (2026-10-07), "if I don't have a backpack in a spot or have an ender chest page unlocked it
 * shouldn't click there." It used to try all 9 Ender Chest pages and all 18 backpacks blind. It now opens Hypixel's
 * {@code /storage} menu first and reads its icons ({@link StoragePageSlots}): a "Locked Page" or an "Empty Backpack Slot
 * N" is never visited, only real pages are. If the Storage menu cannot be read, it falls back to the pages the Storage
 * Overlay already knows exist (from an earlier look at that menu or from opening them), and to none if it knows of none.
 */
public final class StorageScanAll {

    private record Visit(String command, int nextClicks, String label) {
    }

    private static final int OPEN_TIMEOUT_TICKS = 30;
    private static final int SETTLE_TICKS = 12;
    private static final int CLICK_GAP_TICKS = 8;
    private static final int MAX_EXTRA_PAGES = 9;
    private static final String STORAGE_COMMAND = "storage";
    private static final String STORAGE_TITLE = "Storage";

    private static final List<Visit> queue = new ArrayList<>();
    private static Visit current;
    private static int ticks;
    private static int clicksDone;
    private static boolean opened;
    private static int visited;
    private static boolean registered;

    /** What the last run decided, for the testkit and the chat summary: the page commands it queued, and how many
     *  locked pages / empty backpack slots it left alone. */
    private static final List<String> plannedPages = new ArrayList<>();
    private static int skippedLocked;
    private static int skippedEmpty;
    private static boolean planFromMenu;
    /** Ender Chest pages and backpacks only (the Storage Overlay's button), no wardrobe or pets. */
    private static boolean storageOnly;
    /** Pages (Ender Chest / backpack) of the plan finished so far, for the progress on the overlay's button. */
    private static int pagesDone;
    /** True once the plan exists (the Storage menu was read, or its fallback taken). */
    private static boolean planned;

    public static List<String> plannedPages() {
        return List.copyOf(plannedPages);
    }

    public static int skippedLocked() {
        return skippedLocked;
    }

    public static int skippedEmpty() {
        return skippedEmpty;
    }

    /** True when the last plan came from reading the Storage menu, false when it fell back to the overlay's cache. */
    public static boolean planFromMenu() {
        return planFromMenu;
    }

    private StorageScanAll() {
    }

    public static boolean isRunning() {
        return current != null || !queue.isEmpty();
    }

    /** Pages finished in the running (or last) scan's plan. */
    public static int pagesDone() {
        return pagesDone;
    }

    /** Pages in the running (or last) scan's plan, or -1 while the Storage menu has not been read yet. */
    public static int pagesTotal() {
        return planned ? plannedPages.size() : -1;
    }

    /** True when the running (or last) scan was the Storage Overlay's: storage pages only. */
    public static boolean isStorageOnly() {
        return storageOnly;
    }

    /** Storage Search's Scan All: every storage page, then the wardrobe and pets pages. */
    public static void start() {
        start(false);
    }

    /**
     * Starts a scan. {@code storageOnlyScan} is the Storage Overlay's "Scan All" button (killer560, 2026-10-07: "For
     * storage add a Scan All button somewhere that clicks in all the chests"): only the Ender Chest pages and backpacks
     * the Storage menu shows as real, no wardrobe or pets.
     */
    public static void start(boolean storageOnlyScan) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (!registered) {
            registered = true;
            // START: it opens and clicks through storage pages. See ActionGate's class doc.
            ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("StorageScanAll.tick", StorageScanAll::tick));
        }
        queue.clear();
        plannedPages.clear();
        skippedLocked = 0;
        skippedEmpty = 0;
        planFromMenu = false;
        planned = false;
        pagesDone = 0;
        storageOnly = storageOnlyScan;
        // The Storage menu first: its icons say which pages exist (see planPages), and only those are queued.
        queue.add(new Visit(STORAGE_COMMAND, 0, "Storage menu"));
        if (!storageOnlyScan) {
            // Wardrobe / pets: the extra pages only get queued once page 1 shows a Next Page arrow (see finishVisit()).
            queue.add(new Visit("wardrobe", 0, "Wardrobe page 1"));
            queue.add(new Visit("pets", 0, "Pets page 1"));
        }
        current = null;
        visited = 0;
        ModChat.send(StorageSearchFeature.CHAT_PREFIX, ModChat.text("Scan All started - "),
                ModChat.dim(storageOnlyScan
                        ? "opening every Ender Chest page and backpack. Press Scan All again, or Escape, to stop."
                        : "opening every storage page, wardrobe page and pet page. Open anything yourself to stop."));
        McCompat.setScreen(client, null);
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
            if (McCompat.screen(client) != null) {
                // Between two visits nothing of ours is open (finishVisit closes the menu at once), so this is a screen
                // he opened - Escape's pause menu, chat, his inventory. It used to wait here until he closed it again.
                stop("another screen opened");
                return;
            }
            current = queue.remove(0);
            ticks = 0;
            clicksDone = 0;
            opened = false;
            client.player.connection.sendCommand(current.command());
            return;
        }
        ticks++;
        boolean containerOpen = McCompat.screen(client) instanceof AbstractContainerScreen<?>;
        if (!opened) {
            if (containerOpen) {
                opened = true;
                ticks = 0;
            } else if (ticks > OPEN_TIMEOUT_TICKS) {
                if (STORAGE_COMMAND.equals(current.command())) {
                    planPages(null); // the Storage menu never opened: fall back to what the overlay knows
                } else if (current.command().startsWith("enderchest ") || current.command().startsWith("backpack ")) {
                    pagesDone++;
                }
                current = null; // this page doesn't exist - move on
                if (queue.isEmpty()) {
                    ModChat.send(StorageSearchFeature.CHAT_PREFIX, ModChat.good("Scan All done"),
                            ModChat.dim(String.format(Locale.ROOT, " - %d pages remembered.", visited)));
                }
            } else if (McCompat.screen(client) != null) {
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
        if (STORAGE_COMMAND.equals(done.command())) {
            planPages(captured ? storageMenuSlots(client) : null);
        } else if (done.command().startsWith("enderchest ") || done.command().startsWith("backpack ")) {
            pagesDone++;
        }
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

    /** The open screen's slots when it is Hypixel's Storage menu, else null. */
    private static List<Slot> storageMenuSlots(Minecraft client) {
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)
                || !STORAGE_TITLE.equals(screen.getTitle().getString())) {
            return null;
        }
        return screen.getMenu().slots;
    }

    /**
     * Queues the Ender Chest pages and backpacks to visit, ahead of the wardrobe and pets. From the Storage menu's own
     * icons when {@code slots} is that menu (and its icons have arrived): a real page is queued, a locked page or an
     * empty backpack slot is counted and skipped. Otherwise from the Storage Overlay's list of pages known to exist.
     */
    private static void planPages(List<Slot> slots) {
        List<Visit> pages = new ArrayList<>();
        boolean fromMenu = false;
        if (slots != null && slots.size() >= 45) {
            List<Visit> found = new ArrayList<>();
            int[] counts = new int[3]; // seen, locked, empty
            for (int page = 1; page <= 9; page++) {
                tally(slots.get(StoragePageSlots.enderChestSlot(page)), "enderchest " + page, "Ender Chest " + page,
                        found, counts);
            }
            for (int n = 1; n <= 18; n++) {
                tally(slots.get(StoragePageSlots.backpackSlot(n)), "backpack " + n, "Backpack " + n, found, counts);
            }
            // An all-blank menu means its icons had not arrived; that tells us nothing, so it is not a plan.
            if (counts[0] > 0) {
                pages = found;
                skippedLocked = counts[1];
                skippedEmpty = counts[2];
                fromMenu = true;
            }
        }
        if (!fromMenu) {
            pages = knownPages();
        }
        planFromMenu = fromMenu;
        planned = true;
        plannedPages.clear();
        for (Visit v : pages) {
            plannedPages.add(v.command());
        }
        queue.addAll(0, pages);
        ModChat.send(StorageSearchFeature.CHAT_PREFIX, ModChat.dim(fromMenu
                ? String.format(Locale.US, "%d page(s) to open; skipping %d locked page(s) and %d empty backpack slot(s).",
                        pages.size(), skippedLocked, skippedEmpty)
                : String.format(Locale.US, "Couldn't read the Storage menu - opening the %d page(s) already known.",
                        pages.size())));
    }

    private static void tally(Slot slot, String command, String label, List<Visit> found, int[] counts) {
        StoragePageSlots.Kind kind = StoragePageSlots.classify(slot.getItem());
        if (kind != StoragePageSlots.Kind.NONE) {
            counts[0]++;
        }
        if (kind == StoragePageSlots.Kind.PAGE) {
            found.add(new Visit(command, 0, label));
        } else if (kind == StoragePageSlots.Kind.LOCKED) {
            counts[1]++;
        } else if (kind == StoragePageSlots.Kind.EMPTY_BACKPACK) {
            counts[2]++;
        }
    }

    /** Ender Chest pages and backpacks the Storage Overlay already knows exist for this account and profile. */
    private static List<Visit> knownPages() {
        List<Visit> out = new ArrayList<>();
        try {
            String prefix = StorageOverlayFeature.accountProfilePrefix();
            List<String> keys = StorageOverlayCache.getInstance().knownKeysFor(prefix);
            for (int page = 1; page <= 9; page++) {
                if (keys.contains(prefix + "|enderchest_" + page)) {
                    out.add(new Visit("enderchest " + page, 0, "Ender Chest " + page));
                }
            }
            for (int n = 1; n <= 18; n++) {
                if (keys.contains(prefix + "|backpack_" + n)) {
                    out.add(new Visit("backpack " + n, 0, "Backpack " + n));
                }
            }
        } catch (RuntimeException e) {
            out.clear();
        }
        return out;
    }

    private static Slot findNextPage(Minecraft client) {
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
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
        if (next == null || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen) || client.gameMode == null) {
            return false;
        }
        client.gameMode.handleContainerInput(screen.getMenu().containerId, next.index, 0, ContainerInput.PICKUP, client.player);
        return true;
    }
}
