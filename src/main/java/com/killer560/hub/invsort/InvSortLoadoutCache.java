package com.killer560.hub.invsort;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.shared.SharedWriter;
import com.killer560.hub.storagesearch.StorageSearchNbtCodec;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * The contents of Hypixel's /loadouts page 1 as last seen (loadoutpick-1105, killer560 2026-11-05: the loadout picker
 * "doesnt show the actual loadout"), kept per account in memory and in {@code killer560smod-loadout-cache.json} so the
 * picker can draw them after a restart. A cache, not a setting: it stays out of profiles. Read off the render thread; the
 * file is written on {@link SharedWriter}, and only when the page changed.
 */
public final class InvSortLoadoutCache {

    private static final Logger LOGGER = ModLog.get("killer560smod-invsort");
    private static final Path FILE = ModPaths.config("killer560smod-loadout-cache.json");
    private static final java.util.regex.Pattern PAGE_ONE = java.util.regex.Pattern.compile("\\(1/\\d+\\) Loadout");
    private static final int PLAYER_SLOTS = 36;

    private static volatile List<ItemStack> items;
    private static volatile String itemsAccount;
    private static volatile String tried;

    private InvSortLoadoutCache() {
    }

    /** END_CLIENT_TICK, registered by {@link InventorySorterExecutor#register}: free unless /loadouts page 1 is up. */
    public static void tick(Minecraft client) {
        Screen screen = McCompat.screen(client);
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        Matcher m = PAGE_ONE.matcher(container.getTitle().getString());
        if (m.find()) {
            capture(container.getMenu().slots.stream().limit(Math.max(0, container.getMenu().slots.size() - PLAYER_SLOTS))
                    .map(s -> s.getItem()).toList());
        }
    }

    /** Remembers {@code contents} (the container part of page 1) for the signed-in account when it differs. Public for tests. */
    public static void capture(List<ItemStack> contents) {
        if (contents.stream().allMatch(ItemStack::isEmpty) || same(items, contents)) {
            return;
        }
        List<ItemStack> copy = new ArrayList<>(contents.size());
        for (ItemStack s : contents) {
            copy.add(s.copy());
        }
        String account = SharedRecords.accountId();
        items = copy;
        itemsAccount = account;
        SharedWriter.submit(() -> write(account, copy));
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

    private static synchronized void write(String account, List<ItemStack> contents) {
        try {
            JsonObject root = Files.exists(FILE)
                    ? JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
            String blob = StorageSearchNbtCodec.encodeOrThrow(contents);
            if (blob == null) {
                return;
            }
            root.addProperty(account, blob);
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[InvSort] Could not save the loadout cache", e);
        }
    }

    /** The cached page for the signed-in account, or null (none seen yet, or still reading the file: asks again next frame). */
    public static List<ItemStack> get() {
        String account = SharedRecords.accountId();
        if (items != null && account.equals(itemsAccount)) {
            return items;
        }
        if (!account.equals(tried)) {
            tried = account;
            SharedWriter.submit(() -> read(account));
        }
        return null;
    }

    private static synchronized void read(String account) {
        try {
            List<ItemStack> list = null;
            if (Files.exists(FILE)) {
                JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has(account)) {
                    list = StorageSearchNbtCodec.decodeOrThrow(root.get(account).getAsString());
                }
            }
            if (list != null && !list.isEmpty()) {
                items = list;
                itemsAccount = account;
            }
        } catch (Exception e) {
            LOGGER.warn("[InvSort] Could not read the loadout cache", e);
        }
    }

    /** Test hook: forget the in-memory copy so the next {@link #get} reads the file. */
    public static void forgetMemoryForTest() {
        items = null;
        itemsAccount = null;
        tried = null;
    }
}
