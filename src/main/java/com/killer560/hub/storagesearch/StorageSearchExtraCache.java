package com.killer560.hub.storagesearch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cached contents of the Hypixel menus that are not Ender Chest pages or backpacks but still hold items
 *  killer560 wants to find - killer560 (2026-09-21): "make sure that it scans the wardrobe, equipment wardrobe, and
 *  my pets for items". Kept out of {@code StorageOverlayCache} on purpose: every key in that cache becomes a panel
 *  in the Storage Overlay grid, and a wardrobe page is not a storage he can open from the grid. Same
 *  account+profile-prefixed key scheme, same Base64 NBT blob (see {@link StorageSearchNbtCodec}). */
public final class StorageSearchExtraCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CACHE_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-storagesearch-extras.json");

    private static StorageSearchExtraCache instance;

    private record Page(String blob, long updatedMs) {
    }

    private final Map<String, Page> pages = new LinkedHashMap<>();

    private StorageSearchExtraCache() {
    }

    public static StorageSearchExtraCache getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        StorageSearchExtraCache cache = new StorageSearchExtraCache();
        if (Files.exists(CACHE_PATH)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(CACHE_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                for (String key : root.keySet()) {
                    JsonObject obj = root.getAsJsonObject(key);
                    if (obj != null && obj.has("b")) {
                        cache.pages.put(key, new Page(obj.get("b").getAsString(),
                                obj.has("t") ? obj.get("t").getAsLong() : -1L));
                    }
                }
            } catch (Exception ignored) {
                cache.pages.clear();
            }
        }
        instance = cache;
    }

    public void save() {
        try {
            Files.createDirectories(CACHE_PATH.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Page> entry : pages.entrySet()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("b", entry.getValue().blob());
                obj.addProperty("t", entry.getValue().updatedMs());
                root.add(entry.getKey(), obj);
            }
            Files.writeString(CACHE_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Only writes when the contents actually changed - this is called every tick while the menu is open. */
    public void put(String key, List<ItemStack> contents) {
        Page existing = pages.get(key);
        if (existing != null && StorageSearchNbtCodec.sameContents(StorageSearchNbtCodec.decode(existing.blob()), contents)) {
            return;
        }
        String blob = StorageSearchNbtCodec.encode(contents);
        if (blob == null) {
            return;
        }
        pages.put(key, new Page(blob, System.currentTimeMillis()));
        save();
    }

    public List<ItemStack> get(String key) {
        Page page = pages.get(key);
        return page == null ? null : StorageSearchNbtCodec.decode(page.blob());
    }

    public long updatedMs(String key) {
        Page page = pages.get(key);
        return page == null ? -1L : page.updatedMs();
    }

    public List<String> keysFor(String accountProfilePrefix) {
        List<String> out = new ArrayList<>();
        for (String key : pages.keySet()) {
            if (key.startsWith(accountProfilePrefix)) {
                out.add(key);
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    public void clear() {
        if (!pages.isEmpty()) {
            pages.clear();
            save();
        }
    }

    public int size() {
        return pages.size();
    }
}
