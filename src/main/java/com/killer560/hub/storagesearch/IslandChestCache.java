package com.killer560.hub.storagesearch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contents of every island chest killer560 has actually opened - killer560 (2026-09-21): "add in island chests to
 * it. Make it so if the item is in a chest it will esp the chest and highlight the item inside of it."
 * <p>
 * Why opening is required: the server only ever sends a container's contents while its screen is open, so a chest
 * block entity sitting in a loaded (or chunk-cached) chunk carries an EMPTY inventory client-side. The chunk cache
 * is what lets this feature still know a chest is THERE once you have walked away (see
 * {@link IslandChestScanner}); this cache is what lets it know what is IN it. A chest you have never opened is
 * reported as "never opened" rather than silently missing from the results.
 * <p>
 * Keys embed the account+profile prefix and the world, exactly like {@code StorageOverlayCache}'s do, so one
 * account/profile/island can never show another one's chests.
 */
public final class IslandChestCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CACHE_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-storagesearch-chests.json");

    /** Hard cap so a long session of opening chests can never grow the file without bound. Oldest goes first. */
    private static final int MAX_CHESTS = 400;

    /** One remembered chest: where it is, what was in it, and when that snapshot was taken. */
    public record ChestEntry(String key, String world, BlockPos pos, String label, long updatedMs, String blob) {
    }

    private static IslandChestCache instance;

    /** Insertion-ordered so the oldest entry is the one trimmed when {@link #MAX_CHESTS} is hit. */
    private final Map<String, ChestEntry> chests = new LinkedHashMap<>();

    private IslandChestCache() {
    }

    public static IslandChestCache getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        IslandChestCache cache = new IslandChestCache();
        if (Files.exists(CACHE_PATH)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(CACHE_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                for (String key : root.keySet()) {
                    JsonObject obj = root.getAsJsonObject(key);
                    if (obj == null || !obj.has("b")) {
                        continue;
                    }
                    cache.chests.put(key, new ChestEntry(key,
                            obj.has("w") ? obj.get("w").getAsString() : "",
                            new BlockPos(obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt()),
                            obj.has("l") ? obj.get("l").getAsString() : "Chest",
                            obj.has("t") ? obj.get("t").getAsLong() : -1L,
                            obj.get("b").getAsString()));
                }
            } catch (Exception ignored) {
                cache.chests.clear();
            }
        }
        instance = cache;
    }

    public void save() {
        try {
            Files.createDirectories(CACHE_PATH.getParent());
            JsonObject root = new JsonObject();
            for (ChestEntry entry : chests.values()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("w", entry.world());
                obj.addProperty("x", entry.pos().getX());
                obj.addProperty("y", entry.pos().getY());
                obj.addProperty("z", entry.pos().getZ());
                obj.addProperty("l", entry.label());
                obj.addProperty("t", entry.updatedMs());
                obj.addProperty("b", entry.blob());
                root.add(entry.key(), obj);
            }
            Files.writeString(CACHE_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public static String keyFor(String accountProfilePrefix, String world, BlockPos pos) {
        return accountProfilePrefix + "|" + world + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** Stores this chest's contents, but only when they actually differ from what is already remembered (the
     *  capture re-runs every tick while the chest screen is open, so it self-heals a too-early scan the same way
     *  Storage Overlay's own capture does - without rewriting the file every tick once it has settled). */
    public void put(String key, String world, BlockPos pos, String label, List<ItemStack> contents) {
        ChestEntry existing = chests.get(key);
        if (existing != null && StorageSearchNbtCodec.sameContents(StorageSearchNbtCodec.decode(existing.blob()), contents)) {
            return;
        }
        String blob = StorageSearchNbtCodec.encode(contents);
        if (blob == null) {
            return;
        }
        chests.remove(key);
        chests.put(key, new ChestEntry(key, world, pos, label, System.currentTimeMillis(), blob));
        while (chests.size() > MAX_CHESTS) {
            String oldest = chests.keySet().iterator().next();
            chests.remove(oldest);
        }
        save();
    }

    /** Every remembered chest for this account/profile in this world. */
    public List<ChestEntry> entriesFor(String accountProfilePrefix, String world) {
        String prefix = accountProfilePrefix + "|" + world + "|";
        List<ChestEntry> out = new ArrayList<>();
        for (ChestEntry entry : chests.values()) {
            if (entry.key().startsWith(prefix)) {
                out.add(entry);
            }
        }
        return out;
    }

    public List<ItemStack> contentsOf(ChestEntry entry) {
        return StorageSearchNbtCodec.decode(entry.blob());
    }

    /** Drops remembered chests whose block is provably gone - see {@link IslandChestScanner#scanForMissing}. */
    public void forget(List<String> keys) {
        boolean changed = false;
        for (String key : keys) {
            changed |= chests.remove(key) != null;
        }
        if (changed) {
            save();
        }
    }

    public void clear() {
        if (!chests.isEmpty()) {
            chests.clear();
            save();
        }
    }

    public int size() {
        return chests.size();
    }
}
