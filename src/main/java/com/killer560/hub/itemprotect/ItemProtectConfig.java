package com.killer560.hub.itemprotect;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Persisted Item Protection settings - see {@link ItemProtectFeature}'s class doc for what each sub-feature
 * actually blocks. Everything here ships OFF, same as every other new feature in this mod.
 * <p>
 * Read with {@link ConfigJson}'s per-key readers (2026-09-15 persistence audit): one malformed value only
 * loses that one key instead of resetting every setting in the file. The protected-item list is read element by
 * element too, so a single bad entry in a hand-edited file can't wipe someone's whole protected list.
 * <p>
 * <b>Slot Lock is gone</b> (killer560, 2026-10-08: "Slot lock and protect item are essentially doing the same thing
 * but one is for slots and one is for UUID, so remove the slot lock because protect item does everything you need").
 * A file written before that still carries {@code slotLockEnabled} / {@code lockedSlots}; {@link #load} reads them
 * ONCE, from the old file's own keys (docs/LESSONS.md: a migration decides from the old keys, never from the new
 * fields), and turns them into {@link #pendingSlotMigration} - the slot indices whose items still have to be looked
 * at. {@link ItemProtectFeature} carries each locked slot's item over by its Skyblock UUID the first time the
 * inventory is readable, then clears the list. Lock In Place is switched on with it, so a migrated item keeps the
 * "cannot be moved at all" behaviour its slot had.
 */
public final class ItemProtectConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-itemprotect.json");

    /** Player {@code Inventory} container slots: 0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand. */
    public static final int INVENTORY_SLOTS = 41;

    /** Default star colour: gold, the colour a "protected" star reads as at a glance. */
    public static final int DEFAULT_STAR_COLOR = 0xFFFFAA00;

    /** Mouse buttons are stored as {@code MOUSE_CODE_BASE - button} (left -100, right -101, middle -102, ...)
     *  so one int field keeps holding a whole bind - killer560 (2026-09-20): "make all of the keybind things
     *  compatible with mouse buttons and middle mouse buttons". Same encoding as {@code CommandKeybindsConfig}. */
    public static final int MOUSE_CODE_BASE = -100;
    public static final int MAX_MOUSE_BUTTON = 7;

    private static ItemProtectConfig instance;

    // Master
    private boolean enabled = false;

    // Protect Item
    private boolean protectItemEnabled = false;
    private int protectKey = KeyUtil.NONE;
    private boolean useItemIdFallback = false;
    /** Colour of the small star drawn in the top-right corner of every protected item's slot. */
    private int protectedColor = DEFAULT_STAR_COLOR;
    /** What Slot Lock used to do, per item instead of per slot: a protected item cannot be clicked at all - not
     *  picked up, shift-moved, number-key swapped or thrown - in any menu, your own inventory included. */
    private boolean lockInPlace = false;
    /** Skyblock item UUIDs (or item ids, with the fallback on) added by hovering + the protect key. */
    private final Set<String> protectedKeys = new LinkedHashSet<>();
    /** Plain display-name fragments typed in the settings tab, matched case-insensitively. */
    private final List<String> protectedNames = new ArrayList<>();

    // Starred
    private boolean protectStarredEnabled = false;

    // Hotbar drops
    private boolean preventHotbarDropEnabled = false;
    private boolean blockEveryHotbarDrop = false;
    private boolean confirmToForce = true;

    // Feedback
    private boolean blockSound = true;

    /** Old Slot Lock indices still to be carried over to protected UUIDs (empty once done). */
    private final List<Integer> pendingSlotMigration = new ArrayList<>();

    private ItemProtectConfig() {
    }

    public static boolean isMouseCode(int code) {
        return code <= MOUSE_CODE_BASE && code >= MOUSE_CODE_BASE - MAX_MOUSE_BUTTON;
    }

    public static int mouseButton(int code) {
        return MOUSE_CODE_BASE - code;
    }

    public static int codeForMouseButton(int button) {
        return MOUSE_CODE_BASE - button;
    }

    /** Like {@link KeyUtil#sanitize}, but keeps mouse-button codes (KeyUtil only accepts keyboard codes). */
    public static int sanitizeBind(int code) {
        return isMouseCode(code) ? code : KeyUtil.sanitize(code);
    }

    /** True for any code this mod will actually poll or match - a keyboard code or a mouse button. */
    public static boolean isBoundCode(int code) {
        return isMouseCode(code) || KeyUtil.isValidKey(code);
    }

    public static ItemProtectConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ItemProtectConfig();
            return;
        }
        ItemProtectConfig cfg = new ItemProtectConfig();
        boolean migrated = false;
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);

            cfg.protectItemEnabled = ConfigJson.getBool(obj, "protectItemEnabled", false);
            cfg.protectKey = sanitizeBind(ConfigJson.getInt(obj, "protectKey", KeyUtil.NONE));
            cfg.useItemIdFallback = ConfigJson.getBool(obj, "useItemIdFallback", false);
            cfg.protectedColor = ConfigJson.getInt(obj, "protectedColor", DEFAULT_STAR_COLOR);
            cfg.lockInPlace = ConfigJson.getBool(obj, "lockInPlace", false);
            JsonArray keys = ConfigJson.getArray(obj, "protectedKeys");
            if (keys != null) {
                for (JsonElement el : keys) {
                    try {
                        String s = el.getAsString();
                        if (s != null && !s.isBlank()) {
                            cfg.protectedKeys.add(s.trim());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            JsonArray names = ConfigJson.getArray(obj, "protectedNames");
            if (names != null) {
                for (JsonElement el : names) {
                    try {
                        String s = el.getAsString();
                        if (s != null && !s.isBlank() && !cfg.protectedNames.contains(s.trim())) {
                            cfg.protectedNames.add(s.trim());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            cfg.protectStarredEnabled = ConfigJson.getBool(obj, "protectStarredEnabled", false);

            cfg.preventHotbarDropEnabled = ConfigJson.getBool(obj, "preventHotbarDropEnabled", false);
            cfg.blockEveryHotbarDrop = ConfigJson.getBool(obj, "blockEveryHotbarDrop", false);
            cfg.confirmToForce = ConfigJson.getBool(obj, "confirmToForce", true);

            cfg.blockSound = ConfigJson.getBool(obj, "blockSound", true);

            readIndices(ConfigJson.getArray(obj, "pendingSlotMigration"), cfg.pendingSlotMigration);

            // ---- Slot Lock migration: decided from the OLD file's keys only ----
            if (obj.has("slotLockEnabled") || obj.has("lockedSlots")) {
                migrated = true;
                boolean wasOn = ConfigJson.getBool(obj, "slotLockEnabled", false);
                List<Integer> locked = new ArrayList<>();
                readIndices(ConfigJson.getArray(obj, "lockedSlots"), locked);
                if (wasOn && !locked.isEmpty()) {
                    for (Integer idx : locked) {
                        if (!cfg.pendingSlotMigration.contains(idx)) {
                            cfg.pendingSlotMigration.add(idx);
                        }
                    }
                    // A slot that could not move at all becomes an item that cannot move at all.
                    cfg.protectItemEnabled = true;
                    cfg.lockInPlace = true;
                }
            }
        } catch (Exception ignored) {
            // Unreadable/not-an-object file: fall back to a fresh default config rather than throwing on startup.
        }
        instance = cfg;
        if (migrated) {
            // Rewrite without the old keys, so the migration above runs exactly once.
            cfg.save();
        }
    }

    private static void readIndices(JsonArray arr, List<Integer> out) {
        if (arr == null) {
            return;
        }
        for (JsonElement el : arr) {
            // Per-element: one malformed index is skipped instead of dropping every entry.
            try {
                int idx = el.getAsInt();
                if (idx >= 0 && idx < INVENTORY_SLOTS && !out.contains(idx)) {
                    out.add(idx);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);

            obj.addProperty("protectItemEnabled", protectItemEnabled);
            obj.addProperty("protectKey", protectKey);
            obj.addProperty("useItemIdFallback", useItemIdFallback);
            obj.addProperty("protectedColor", protectedColor);
            obj.addProperty("lockInPlace", lockInPlace);
            JsonArray keys = new JsonArray();
            for (String key : protectedKeys) {
                keys.add(key);
            }
            obj.add("protectedKeys", keys);
            JsonArray names = new JsonArray();
            for (String name : protectedNames) {
                names.add(name);
            }
            obj.add("protectedNames", names);

            obj.addProperty("protectStarredEnabled", protectStarredEnabled);

            obj.addProperty("preventHotbarDropEnabled", preventHotbarDropEnabled);
            obj.addProperty("blockEveryHotbarDrop", blockEveryHotbarDrop);
            obj.addProperty("confirmToForce", confirmToForce);

            obj.addProperty("blockSound", blockSound);

            if (!pendingSlotMigration.isEmpty()) {
                JsonArray pending = new JsonArray();
                for (Integer idx : pendingSlotMigration) {
                    pending.add(idx);
                }
                obj.add("pendingSlotMigration", pending);
            }

            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // --- master ---

    /** Master toggle, gated by "Skyblock Only" like every other feature's own enabled getter. */
    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** The raw saved value, ignoring the Skyblock gate - for the settings tab's own display. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // --- protect item ---

    public boolean isProtectItemEnabled() {
        return isEnabled() && protectItemEnabled;
    }

    public boolean isProtectItemEnabledRaw() {
        return protectItemEnabled;
    }

    public void setProtectItemEnabled(boolean value) {
        this.protectItemEnabled = value;
    }

    public int getProtectKey() {
        return protectKey;
    }

    public void setProtectKey(int key) {
        this.protectKey = sanitizeBind(key);
    }

    public boolean isUseItemIdFallback() {
        return useItemIdFallback;
    }

    public void setUseItemIdFallback(boolean value) {
        this.useItemIdFallback = value;
    }

    /** The star colour (the key keeps its old name, {@code protectedColor}, so a saved colour carries over). */
    public int getProtectedColor() {
        return protectedColor;
    }

    public void setProtectedColor(int argb) {
        this.protectedColor = argb;
    }

    public boolean isLockInPlace() {
        return lockInPlace;
    }

    public void setLockInPlace(boolean value) {
        this.lockInPlace = value;
    }

    public Set<String> getProtectedKeys() {
        return protectedKeys;
    }

    public boolean hasProtectedKey(String key) {
        return key != null && protectedKeys.contains(key);
    }

    /** Adds a key without toggling (the Slot Lock migration). @return true if it was not there yet. */
    public boolean addProtectedKey(String key) {
        return key != null && !key.isBlank() && protectedKeys.add(key);
    }

    /** @return true if the key was added, false if it was already there and got removed. */
    public boolean toggleProtectedKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (protectedKeys.remove(key)) {
            return false;
        }
        protectedKeys.add(key);
        return true;
    }

    public List<String> getProtectedNames() {
        return protectedNames;
    }

    public void addProtectedName(String name) {
        if (name == null) {
            return;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        for (String existing : protectedNames) {
            if (existing.equalsIgnoreCase(trimmed)) {
                return;
            }
        }
        protectedNames.add(trimmed);
    }

    public void removeProtectedName(String name) {
        protectedNames.removeIf(s -> s.equalsIgnoreCase(name == null ? "" : name.trim()));
    }

    public boolean nameMatchesProtected(String displayName) {
        if (displayName == null || displayName.isEmpty() || protectedNames.isEmpty()) {
            return false;
        }
        String haystack = displayName.toLowerCase(Locale.ROOT);
        for (String needle : protectedNames) {
            if (haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // --- slot lock migration ---

    /** Old locked slot indices still waiting to be carried over; a copy. */
    public List<Integer> getPendingSlotMigration() {
        return new ArrayList<>(pendingSlotMigration);
    }

    public void clearPendingSlotMigration() {
        pendingSlotMigration.clear();
    }

    // --- starred ---

    public boolean isProtectStarredEnabled() {
        return isEnabled() && protectStarredEnabled;
    }

    public boolean isProtectStarredEnabledRaw() {
        return protectStarredEnabled;
    }

    public void setProtectStarredEnabled(boolean value) {
        this.protectStarredEnabled = value;
    }

    // --- hotbar drops ---

    public boolean isPreventHotbarDropEnabled() {
        return isEnabled() && preventHotbarDropEnabled;
    }

    public boolean isPreventHotbarDropEnabledRaw() {
        return preventHotbarDropEnabled;
    }

    public void setPreventHotbarDropEnabled(boolean value) {
        this.preventHotbarDropEnabled = value;
    }

    public boolean isBlockEveryHotbarDrop() {
        return blockEveryHotbarDrop;
    }

    public void setBlockEveryHotbarDrop(boolean value) {
        this.blockEveryHotbarDrop = value;
    }

    public boolean isConfirmToForce() {
        return confirmToForce;
    }

    public void setConfirmToForce(boolean value) {
        this.confirmToForce = value;
    }

    // --- feedback ---

    public boolean isBlockSound() {
        return blockSound;
    }

    public void setBlockSound(boolean value) {
        this.blockSound = value;
    }
}
