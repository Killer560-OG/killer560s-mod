package com.killer560.hub.invsort;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.loadoutkeybinds.LoadoutKeybindsFeature;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.shared.SharedWriter;
import com.killer560.hub.storagesearch.StorageSearchNbtCodec;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * The contents of Hypixel's /loadouts pages as last seen (loadoutpick-1105, killer560 2026-11-05: the loadout picker
 * "doesnt show the actual loadout"; loadout1-1105: every page, for the picker's Next Page), kept per account in memory and
 * in {@code killer560smod-loadout-cache.json} (page 1 under the account, page N under {@code account#N}, the page count
 * under {@code account#pages}) so the picker can draw them after a restart. A cache, not a setting: it stays out of
 * profiles. Read off the render thread; the file is written on {@link SharedWriter}, and only when a page changed.
 * <p>
 * A page is never taken while the cursor holds an item (loadout1-1105): a click on a loadout, by him or by Loadout
 * Keybinds' swap, puts that loadout's item on the cursor on the client until Hypixel answers, and the swap closes the menu
 * before it does, so the page used to be saved with the clicked loadout's slot empty (his loadout 1 showed no icon).
 */
public final class InvSortLoadoutCache {

    private static final Logger LOGGER = ModLog.get("killer560smod-invsort");
    private static final Path FILE = ModPaths.config("killer560smod-loadout-cache.json");
    private static final int PLAYER_SLOTS = 36;

    /** The pages of {@link #itemsAccount} (page number -> container slots); replaced whole, never changed in place. */
    private static volatile Map<Integer, List<ItemStack>> pages;
    private static volatile int total;
    private static volatile String itemsAccount;
    private static volatile String tried;

    private static Component titleSeen;
    private static int titlePage;
    private static int titleOf;

    private InvSortLoadoutCache() {
    }

    /** END_CLIENT_TICK, registered by {@link InventorySorterExecutor#register}: free unless a /loadouts page is up. */
    public static void tick(Minecraft client) {
        Screen screen = McCompat.screen(client);
        if (!(screen instanceof AbstractContainerScreen<?> container) || !container.getMenu().getCarried().isEmpty()) {
            return;
        }
        Component title = container.getTitle();
        if (title != titleSeen) { // every container screen reaches here each tick: read the title once per title object
            Matcher m = LoadoutKeybindsFeature.LOADOUT_TITLE.matcher(title.getString());
            titlePage = m.find() ? Integer.parseInt(m.group(1)) : 0;
            titleOf = titlePage > 0 ? Integer.parseInt(m.group(2)) : 0;
            titleSeen = title;
        }
        if (titlePage > 0) {
            capture(titlePage, titleOf,
                    container.getMenu().slots.stream().limit(Math.max(0, container.getMenu().slots.size() - PLAYER_SLOTS))
                            .map(s -> s.getItem()).toList());
        }
    }

    /** Page 1 of {@code contents}; public for tests. */
    public static void capture(List<ItemStack> contents) {
        capture(1, Math.max(1, total), contents);
    }

    /** Remembers {@code contents} (the container part of page {@code page} of {@code of}) for the signed-in account when it
     *  differs. Public for tests. */
    public static void capture(int page, int of, List<ItemStack> contents) {
        String account = SharedRecords.accountId();
        Map<Integer, List<ItemStack>> now = account.equals(itemsAccount) && pages != null ? pages : Map.of();
        if (contents.stream().allMatch(ItemStack::isEmpty) || (same(now.get(page), contents) && of == total)) {
            return;
        }
        List<ItemStack> copy = new ArrayList<>(contents.size());
        for (ItemStack s : contents) {
            copy.add(s.copy());
        }
        Map<Integer, List<ItemStack>> next = new HashMap<>(now);
        next.put(page, copy);
        next.keySet().removeIf(p -> p > of);
        pages = Map.copyOf(next);
        total = of;
        itemsAccount = account;
        SharedWriter.submit(() -> write(account, page, of, copy));
    }

    private static boolean same(List<ItemStack> a, List<ItemStack> b) {
        if (a == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String key(String account, int page) {
        return page == 1 ? account : account + "#" + page;
    }

    private static synchronized void write(String account, int page, int of, List<ItemStack> contents) {
        try {
            JsonObject root = Files.exists(FILE)
                    ? JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
            String blob = StorageSearchNbtCodec.encodeOrThrow(contents);
            if (blob == null) {
                return;
            }
            root.addProperty(key(account, page), blob);
            root.addProperty(account + "#pages", of);
            for (int p = of + 1; p <= InventoryLayout.MAX_LOADOUT / 12; p++) {
                root.remove(key(account, p));
            }
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[InvSort] Could not save the loadout cache", e);
        }
    }

    /** Page 1 of the cache; see {@link #get(int)}. */
    public static List<ItemStack> get() {
        return get(1);
    }

    /** The cached page {@code page} for the signed-in account, or null (not seen yet, or still reading the file: asks again
     *  next frame). */
    public static List<ItemStack> get(int page) {
        String account = SharedRecords.accountId();
        if (pages != null && account.equals(itemsAccount)) {
            return pages.get(page);
        }
        if (!account.equals(tried)) {
            tried = account;
            SharedWriter.submit(() -> read(account));
        }
        return null;
    }

    /** How many /loadouts pages Hypixel said there are (the "M" of "(N/M) Loadouts"), 0 before any was seen. */
    public static int pages() {
        return pages != null && SharedRecords.accountId().equals(itemsAccount) ? total : 0;
    }

    private static synchronized void read(String account) {
        try {
            if (!Files.exists(FILE)) {
                return;
            }
            JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            int of = root.has(account + "#pages") ? root.get(account + "#pages").getAsInt() : root.has(account) ? 1 : 0;
            Map<Integer, List<ItemStack>> read = new HashMap<>();
            for (int p = 1; p <= Math.min(of, InventoryLayout.MAX_LOADOUT / 12); p++) {
                List<ItemStack> list = root.has(key(account, p))
                        ? StorageSearchNbtCodec.decodeOrThrow(root.get(key(account, p)).getAsString()) : null;
                if (list != null && !list.isEmpty()) {
                    read.put(p, list);
                }
            }
            if (!read.isEmpty() && !account.equals(itemsAccount)) { // a page captured meanwhile is newer
                pages = Map.copyOf(read);
                total = of;
                itemsAccount = account;
            }
        } catch (Exception e) {
            LOGGER.warn("[InvSort] Could not read the loadout cache", e);
        }
    }

    /** Test hook: forget the in-memory copy so the next {@link #get} reads the file. */
    public static void forgetMemoryForTest() {
        pages = null;
        total = 0;
        itemsAccount = null;
        tried = null;
    }
}
