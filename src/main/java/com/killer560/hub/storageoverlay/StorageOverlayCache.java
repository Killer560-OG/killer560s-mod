package com.killer560.hub.storageoverlay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cached contents of every tracked storage unit (Ender Chest pages, backpacks), keyed by a
 *  composite string that already embeds the account + SkyBlock profile it belongs to (see
 *  {@link StorageOverlayFeature#storageKey}) - a lookup under the CURRENT account/profile's key can
 *  never return another account's or profile's data, since the keys themselves differ, which is what
 *  actually satisfies killer560's "don't show one account's stuff on another account or profile"
 *  requirement (simpler and more robust than juggling separate cache files per account).
 *  <p>
 *  Real full-fidelity {@link ItemStack} NBT persistence (correct custom textures/components survive
 *  a restart), not a simplified id/count/name summary - ported directly from NoammAddons'
 *  {@code NBTInventory.kt} per killer560's explicit "really similar to noamm's" request: each stack
 *  encodes via {@code ItemStack.CODEC} + {@code DataComponentPatch.CODEC} through {@link NbtOps},
 *  written compressed via {@link NbtIo}, then Base64'd so it can sit as a plain string in this mod's
 *  existing JSON config style rather than needing a second file format. */
public final class StorageOverlayCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-storageoverlay");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CACHE_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-storageoverlay-cache.json");

    private static StorageOverlayCache instance;

    /** Composite key -> Base64'd compressed NBT blob (see class doc). */
    private final Map<String, String> encoded = new HashMap<>();
    /** Composite keys the "Storage" overview screen has shown exist (a real icon, not one of the
     *  "empty slot" placeholder items) but whose real contents haven't actually been opened/logged
     *  yet - ported from NoammAddons' own {@code saveOverview}, which discovers backpacks this way
     *  before you ever open them. A key moves out of this set the moment {@link #put} gives it real
     *  contents. */
    private final Set<String> knownOnly = new HashSet<>();

    private StorageOverlayCache() {
    }

    public static StorageOverlayCache getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    private static final String KNOWN_ONLY_KEY = "__known_only__";

    public static void load() {
        StorageOverlayCache cache = new StorageOverlayCache();
        if (Files.exists(CACHE_PATH)) {
            try {
                String json = Files.readString(CACHE_PATH, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                for (String key : obj.keySet()) {
                    if (key.equals(KNOWN_ONLY_KEY)) {
                        for (var el : obj.getAsJsonArray(key)) {
                            cache.knownOnly.add(el.getAsString());
                        }
                    } else {
                        cache.encoded.put(key, obj.get(key).getAsString());
                    }
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
            for (Map.Entry<String, String> entry : encoded.entrySet()) {
                obj.addProperty(entry.getKey(), entry.getValue());
            }
            var knownArray = new com.google.gson.JsonArray();
            for (String key : knownOnly) {
                knownArray.add(key);
            }
            obj.add(KNOWN_ONLY_KEY, knownArray);
            Files.writeString(CACHE_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Registers a storage as known to exist (a real icon on the "Storage" overview screen) without
     *  real contents yet - a no-op if we already have real contents for this key, or if it was
     *  already marked (this is called every frame while the overview is open, same self-heal reason
     *  as {@code captureIfChanged} - the set-membership check keeps that from writing to disk on
     *  every single frame once it's already known). */
    public void markKnown(String key) {
        if (!encoded.containsKey(key) && knownOnly.add(key)) {
            save();
        }
    }

    /** Per NoammAddons' own overview cleanup: the overview later showing this page as one of the
     *  "empty slot" placeholder items means it was removed (e.g. a backpack slot cleared) - drop it
     *  from the known-but-unopened set. Never touches a key that already has real contents. */
    public void unmarkKnown(String key) {
        if (knownOnly.remove(key)) {
            save();
        }
    }

    /** Encodes and stores this storage's real contents, then saves to disk immediately. */
    public void put(String key, List<ItemStack> stacks) {
        RegistryAccess registryAccess = registryAccess();
        if (registryAccess == null) {
            return;
        }
        HolderLookup.Provider provider = registryAccess;
        var ops = provider.createSerializationContext(NbtOps.INSTANCE);
        ListTag list = new ListTag();
        for (ItemStack stack : stacks) {
            CompoundTag tag = new CompoundTag();
            if (stack != null && !stack.isEmpty()) {
                Tag encodedStack = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
                if (encodedStack instanceof CompoundTag stackTag) {
                    tag = stackTag;
                }
            }
            list.add(tag);
        }
        try {
            CompoundTag root = new CompoundTag();
            root.put("i", list);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, baos);
            encoded.put(key, Base64.getEncoder().encodeToString(baos.toByteArray()));
            knownOnly.remove(key);
            save();
        } catch (Exception e) {
            LOGGER.error("Failed to encode storage \"{}\"", key, e);
        }
    }

    /** @return the real stacks for this key, or null if never captured. */
    public List<ItemStack> get(String key) {
        String blob = encoded.get(key);
        if (blob == null) {
            return null;
        }
        RegistryAccess registryAccess = registryAccess();
        if (registryAccess == null) {
            return null;
        }
        try {
            HolderLookup.Provider provider = registryAccess;
            var ops = provider.createSerializationContext(NbtOps.INSTANCE);
            byte[] bytes = Base64.getDecoder().decode(blob);
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            ListTag list = root.getListOrEmpty("i");
            List<ItemStack> result = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompoundOrEmpty(i);
                if (tag.isEmpty()) {
                    result.add(ItemStack.EMPTY);
                    continue;
                }
                result.add(ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY));
            }
            return result;
        } catch (Exception e) {
            LOGGER.error("Failed to decode storage \"{}\"", key, e);
            return null;
        }
    }

    /** Every storage key currently known whose composite key starts with {@code accountProfilePrefix}
     *  - used both to list/rename the current account/profile's own storages in the settings tab and
     *  to lay out the 3-column overlay grid. */
    public List<String> knownKeysFor(String accountProfilePrefix) {
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (String key : encoded.keySet()) {
            if (key.startsWith(accountProfilePrefix)) {
                keys.add(key);
            }
        }
        for (String key : knownOnly) {
            if (key.startsWith(accountProfilePrefix)) {
                keys.add(key);
            }
        }
        return new ArrayList<>(keys);
    }

    /** Whether this key has real logged contents (as opposed to only being known-to-exist via the
     *  overview screen). */
    public boolean hasContents(String key) {
        return encoded.containsKey(key);
    }

    private static RegistryAccess registryAccess() {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            return client.level.registryAccess();
        }
        return client.getConnection() != null ? client.getConnection().registryAccess() : null;
    }
}
