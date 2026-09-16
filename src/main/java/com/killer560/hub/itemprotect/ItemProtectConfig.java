package com.killer560.hub.itemprotect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.loader.api.FabricLoader;

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
 * loses that one key instead of resetting every setting in the file. The two collections that matter most -
 * the locked-slot list and the protected-item list - are read element by element too, so a single bad entry
 * in a hand-edited file can't wipe someone's whole protected list.
 * <p>
 * {@link #save()} is called by every mutator's caller (the tab, and the in-inventory keybinds) so a lock or a
 * protect toggled mid-run survives a Minecraft restart - the mod's standing "every setting must persist" rule.
 */
public final class ItemProtectConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-itemprotect.json");

    /** Player {@code Inventory} container slots: 0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand. */
    public static final int INVENTORY_SLOTS = 41;

    /** How a locked slot is marked. */
    public enum LockStyle {
        OUTLINE("Outline"),
        ICON("Lock Icon"),
        BOTH("Outline + Icon");

        public final String label;

        LockStyle(String label) {
            this.label = label;
        }

        public LockStyle next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static ItemProtectConfig instance;

    // Master
    private boolean enabled = false;

    // Slot Lock
    private boolean slotLockEnabled = false;
    private int slotLockKey = KeyUtil.NONE;
    private LockStyle lockStyle = LockStyle.BOTH;
    private int lockColor = 0xFFFF5555;
    private final boolean[] lockedSlots = new boolean[INVENTORY_SLOTS];

    // Protect Item
    private boolean protectItemEnabled = false;
    private int protectKey = KeyUtil.NONE;
    private int peekKey = KeyUtil.NONE;
    private boolean useItemIdFallback = false;
    private int protectedColor = 0xFF55FFFF;
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

    private ItemProtectConfig() {
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
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);

            cfg.slotLockEnabled = ConfigJson.getBool(obj, "slotLockEnabled", false);
            cfg.slotLockKey = KeyUtil.sanitize(ConfigJson.getInt(obj, "slotLockKey", KeyUtil.NONE));
            cfg.lockStyle = ConfigJson.getEnum(obj, "lockStyle", LockStyle.class, LockStyle.BOTH);
            cfg.lockColor = ConfigJson.getInt(obj, "lockColor", 0xFFFF5555);
            JsonArray locked = ConfigJson.getArray(obj, "lockedSlots");
            if (locked != null) {
                for (JsonElement el : locked) {
                    // Per-element: one malformed index is skipped instead of dropping every lock.
                    try {
                        int idx = el.getAsInt();
                        if (idx >= 0 && idx < INVENTORY_SLOTS) {
                            cfg.lockedSlots[idx] = true;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            cfg.protectItemEnabled = ConfigJson.getBool(obj, "protectItemEnabled", false);
            cfg.protectKey = KeyUtil.sanitize(ConfigJson.getInt(obj, "protectKey", KeyUtil.NONE));
            cfg.peekKey = KeyUtil.sanitize(ConfigJson.getInt(obj, "peekKey", KeyUtil.NONE));
            cfg.useItemIdFallback = ConfigJson.getBool(obj, "useItemIdFallback", false);
            cfg.protectedColor = ConfigJson.getInt(obj, "protectedColor", 0xFF55FFFF);
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
        } catch (Exception ignored) {
            // Unreadable/not-an-object file: fall back to a fresh default config rather than throwing on startup.
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);

            obj.addProperty("slotLockEnabled", slotLockEnabled);
            obj.addProperty("slotLockKey", slotLockKey);
            obj.addProperty("lockStyle", lockStyle.name());
            obj.addProperty("lockColor", lockColor);
            JsonArray locked = new JsonArray();
            for (int i = 0; i < lockedSlots.length; i++) {
                if (lockedSlots[i]) {
                    locked.add(i);
                }
            }
            obj.add("lockedSlots", locked);

            obj.addProperty("protectItemEnabled", protectItemEnabled);
            obj.addProperty("protectKey", protectKey);
            obj.addProperty("peekKey", peekKey);
            obj.addProperty("useItemIdFallback", useItemIdFallback);
            obj.addProperty("protectedColor", protectedColor);
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

    // --- slot lock ---

    public boolean isSlotLockEnabled() {
        return isEnabled() && slotLockEnabled;
    }

    public boolean isSlotLockEnabledRaw() {
        return slotLockEnabled;
    }

    public void setSlotLockEnabled(boolean value) {
        this.slotLockEnabled = value;
    }

    public int getSlotLockKey() {
        return slotLockKey;
    }

    public void setSlotLockKey(int key) {
        this.slotLockKey = KeyUtil.sanitize(key);
    }

    public LockStyle getLockStyle() {
        return lockStyle;
    }

    public void setLockStyle(LockStyle style) {
        this.lockStyle = style == null ? LockStyle.BOTH : style;
    }

    public int getLockColor() {
        return lockColor;
    }

    public void setLockColor(int argb) {
        this.lockColor = argb;
    }

    public boolean isSlotLocked(int containerSlot) {
        return containerSlot >= 0 && containerSlot < lockedSlots.length && lockedSlots[containerSlot];
    }

    /** @return the new state, or {@code false} if the index isn't a real player-inventory slot. */
    public boolean toggleSlotLock(int containerSlot) {
        if (containerSlot < 0 || containerSlot >= lockedSlots.length) {
            return false;
        }
        lockedSlots[containerSlot] = !lockedSlots[containerSlot];
        return lockedSlots[containerSlot];
    }

    public void clearSlotLocks() {
        java.util.Arrays.fill(lockedSlots, false);
    }

    public int countLockedSlots() {
        int n = 0;
        for (boolean b : lockedSlots) {
            if (b) {
                n++;
            }
        }
        return n;
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
        this.protectKey = KeyUtil.sanitize(key);
    }

    public int getPeekKey() {
        return peekKey;
    }

    public void setPeekKey(int key) {
        this.peekKey = KeyUtil.sanitize(key);
    }

    public boolean isUseItemIdFallback() {
        return useItemIdFallback;
    }

    public void setUseItemIdFallback(boolean value) {
        this.useItemIdFallback = value;
    }

    public int getProtectedColor() {
        return protectedColor;
    }

    public void setProtectedColor(int argb) {
        this.protectedColor = argb;
    }

    public Set<String> getProtectedKeys() {
        return protectedKeys;
    }

    public boolean hasProtectedKey(String key) {
        return key != null && protectedKeys.contains(key);
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
