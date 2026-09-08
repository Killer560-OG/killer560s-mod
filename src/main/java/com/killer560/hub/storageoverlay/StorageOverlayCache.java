package com.killer560.hub.storageoverlay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Cached contents of every tracked storage unit (Ender Chest pages, backpacks), keyed by a
 *  composite string that already embeds the account + SkyBlock profile it belongs to (see
 *  {@link StorageOverlayFeature#storageKey}) - a lookup under the CURRENT account/profile's key can
 *  never return another account's or profile's data, since the keys themselves differ, which is what
 *  actually satisfies killer560's "don't show one account's stuff on another account or profile"
 *  requirement (simpler and more robust than juggling separate cache files per account).
 *  <p>
 *  Two layers: {@link #liveStacks} holds the real, full-fidelity {@link ItemStack} copies captured
 *  this session (correct custom textures/NBT, same approach as {@code ExperimentsFeature}'s own
 *  {@code superpairsIconCache}) but is never persisted - full {@code ItemStack} NBT round-tripping
 *  through a registry-aware codec was judged out of scope for this pass. {@link #persisted} is a
 *  simplified id/count/name/lore summary written to disk so contents survive a restart; on load it's
 *  rebuilt into plain {@link ItemStack}s (correct base item and name, but a custom-textured item like
 *  a player-skull skin will show its default texture until that storage is opened again and
 *  {@link #liveStacks} takes back over). */
public final class StorageOverlayCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CACHE_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-storageoverlay-cache.json");

    private static StorageOverlayCache instance;

    private final Map<String, List<CachedItem>> persisted = new HashMap<>();
    private final Map<String, List<ItemStack>> liveStacks = new HashMap<>();

    public record CachedItem(String itemId, int count, String name, String lore) {
    }

    private StorageOverlayCache() {
    }

    public static StorageOverlayCache getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        StorageOverlayCache cache = new StorageOverlayCache();
        if (Files.exists(CACHE_PATH)) {
            try {
                String json = Files.readString(CACHE_PATH, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                for (String key : obj.keySet()) {
                    JsonArray arr = obj.getAsJsonArray(key);
                    List<CachedItem> items = new ArrayList<>();
                    for (var el : arr) {
                        JsonObject item = el.getAsJsonObject();
                        items.add(new CachedItem(
                                item.get("itemId").getAsString(),
                                item.get("count").getAsInt(),
                                item.has("name") ? item.get("name").getAsString() : "",
                                item.has("lore") ? item.get("lore").getAsString() : ""));
                    }
                    cache.persisted.put(key, items);
                }
            } catch (Exception ignored) {
            }
        }
        instance = cache;
    }

    public void save() {
        try {
            Files.createDirectories(CACHE_PATH.getParent());
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, List<CachedItem>> entry : persisted.entrySet()) {
                JsonArray arr = new JsonArray();
                for (CachedItem item : entry.getValue()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("itemId", item.itemId());
                    o.addProperty("count", item.count());
                    o.addProperty("name", item.name());
                    o.addProperty("lore", item.lore());
                    arr.add(o);
                }
                obj.add(entry.getKey(), arr);
            }
            Files.writeString(CACHE_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Stores this session's real capture of a storage's contents (full fidelity for rendering) and
     *  the simplified persisted summary (survives a restart), then saves to disk immediately. */
    public void put(String key, List<ItemStack> stacks) {
        liveStacks.put(key, stacks.stream().map(ItemStack::copy).toList());
        List<CachedItem> summary = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            summary.add(new CachedItem(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(),
                    stack.getHoverName().getString(),
                    ""));
        }
        persisted.put(key, summary);
        save();
    }

    /** @return the real captured {@link ItemStack}s for this key if this session has ever seen it
     *  opened, otherwise plain stacks rebuilt from the persisted summary (correct item/count/name,
     *  generic texture for anything with custom NBT-driven skin data), or null if never known at all. */
    public List<ItemStack> get(String key) {
        List<ItemStack> live = liveStacks.get(key);
        if (live != null) {
            return live;
        }
        List<CachedItem> summary = persisted.get(key);
        if (summary == null) {
            return null;
        }
        List<ItemStack> rebuilt = new ArrayList<>(summary.size());
        for (CachedItem item : summary) {
            Identifier id = Identifier.tryParse(item.itemId());
            if (id == null) {
                continue;
            }
            BuiltInRegistries.ITEM.get(id).ifPresent(ref ->
                    rebuilt.add(new ItemStack(ref.value(), Math.max(1, item.count()))));
        }
        return rebuilt;
    }

    /** Every storage key currently known (persisted or live-only) whose composite key starts with
     *  {@code accountProfilePrefix} - used by the settings tab to list only the current account/
     *  profile's own storages for renaming. */
    public List<String> knownKeysFor(String accountProfilePrefix) {
        List<String> keys = new ArrayList<>();
        for (String key : persisted.keySet()) {
            if (key.startsWith(accountProfilePrefix) && !keys.contains(key)) {
                keys.add(key);
            }
        }
        for (String key : liveStacks.keySet()) {
            if (key.startsWith(accountProfilePrefix) && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }
}
