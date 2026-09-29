package com.killer560.hub.petwheel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pet Wheel settings (killer560's item 8.2): a radial keybind menu of a chosen subset/order of his real pets,
 * off by default and living in the New tab until confirmed working. Not cheat-gated - killer560, 2026-09-21:
 * "it's just a reskin of the /pets menu, not real automation, so it ships identically in both builds."
 * <p>
 * Two lists are kept, both persisted:
 * <ul>
 *   <li>{@code wheelPets} - the ordered subset actually shown on the wheel, picked/rearranged in
 *   {@link PetWheelScreen}'s edit mode (opened by {@code gui.tab.PetWheelTab}'s "Edit Pets" button).</li>
 *   <li>{@code knownPets} - every pet {@link PetsMenuScanner} has ever read off a real {@code /pets} screen,
 *   insertion-ordered, so {@link PetsMenuScanner}'s passive scan (and {@link PetWheelEditor}'s right-click
 *   pick) both have somewhere to record what a pet's icon/name/level/tier looked like last. Both lists are
 *   keyed by the pet's own item uuid (see {@link PetEntry}), never by name+rarity.</li>
 * </ul>
 */
public final class PetWheelConfig {

    public enum InteractionMode {
        /** Hold the bind, drag over a slice, release to summon it - killer560's "hold-the-key-and-release". */
        HOLD_RELEASE,
        /** Press the bind to open the wheel, then click a slice - killer560's "press-the-key-then-click". */
        PRESS_CLICK
    }

    public static final int MIN_SLICES = 4;
    public static final int MAX_SLICES = 12;
    public static final int DEFAULT_SLICES = 8;
    public static final int MIN_SCALE_PCT = 60;
    /** Raised from 150 (killer560, 2026-09-27: "the scale should also be able to go a decent bit higher"). */
    public static final int MAX_SCALE_PCT = 400;
    public static final int DEFAULT_SCALE_PCT = 100;
    /** Icon Size: a second, independent multiplier on top of {@link #scalePercent} that only grows the pet
     *  picture itself (killer560: "make a way to have the pictures alot bigger as well") - the tile box and
     *  ring radius both grow to fit it (see {@code PetWheelScreen#layout}), so a huge icon setting can't make
     *  neighbouring slices overlap. */
    public static final int MIN_ICON_SCALE_PCT = 100;
    public static final int MAX_ICON_SCALE_PCT = 400;
    public static final int DEFAULT_ICON_SCALE_PCT = 100;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-petwheel.json");

    private static PetWheelConfig instance;

    private boolean enabled = false;
    private int sliceCount = DEFAULT_SLICES;
    private int scalePercent = DEFAULT_SCALE_PCT;
    private int iconScalePercent = DEFAULT_ICON_SCALE_PCT;
    private InteractionMode mode = InteractionMode.HOLD_RELEASE;
    private int keyCode = KeyUtil.NONE;
    /** killer560: "add an option to hide their level and an option to hide the pets name" - two separate
     *  toggles, independent of each other, affecting only the wheel's own slice label (never the Edit Pets
     *  lists, which still show the full "[Lvl N] Name" so picking the right pet stays unambiguous there). */
    private boolean hideLevel = false;
    private boolean hideName = false;

    /** Ordered subset shown on the wheel, keyed by uuid so identity survives a rename/relevel. */
    private final List<PetEntry> wheelPets = new ArrayList<>();
    /** Every pet ever scanned off a real /pets screen, insertion-ordered, keyed by uuid. */
    private final Map<String, PetEntry> knownPets = new LinkedHashMap<>();

    private PetWheelConfig() {
    }

    public static PetWheelConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** A boolean that tolerates a key of the wrong type, like {@code getInt} beside it. */
    private static boolean getBool(JsonObject o, String key, boolean fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** A string that tolerates a key of the wrong type. */
    private static String getString(JsonObject o, String key, String fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static void load() {
        PetWheelConfig cfg = new PetWheelConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                // Read through the same helpers the rest of the mod uses, so one key of the wrong type
                // cannot abandon the rest. This block used raw getAsBoolean/getAsString and the catch below
                // still published the half-filled object, so a single hand-edited key left everything after it
                // - including wheelPets and knownPets, which load last - silently at defaults, and the next
                // save wrote that loss to disk.
                cfg.enabled = getBool(root, "enabled", false);
                cfg.sliceCount = clampSlices(getInt(root, "sliceCount", DEFAULT_SLICES));
                cfg.scalePercent = clampScale(getInt(root, "scalePercent", DEFAULT_SCALE_PCT));
                cfg.iconScalePercent = clampIconScale(getInt(root, "iconScalePercent", DEFAULT_ICON_SCALE_PCT));
                cfg.mode = parseMode(getString(root, "mode", null));
                cfg.keyCode = KeyUtil.sanitizeBind(getInt(root, "keyCode", KeyUtil.NONE));
                cfg.hideLevel = getBool(root, "hideLevel", false);
                cfg.hideName = getBool(root, "hideName", false);
                readPetList(root, "wheelPets", cfg.wheelPets);
                List<PetEntry> known = new ArrayList<>();
                readPetList(root, "knownPets", known);
                for (PetEntry p : known) {
                    cfg.knownPets.put(p.uuid(), p);
                }
            } catch (Exception ignored) {
                // Corrupt file: defaults WHOLESALE, not whatever was filled in before the throw. Publishing a
                // half-populated object is worse than defaults, because the next save persists the loss.
                cfg = new PetWheelConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("sliceCount", sliceCount);
            root.addProperty("scalePercent", scalePercent);
            root.addProperty("iconScalePercent", iconScalePercent);
            root.addProperty("mode", mode.name());
            root.addProperty("keyCode", keyCode);
            root.addProperty("hideLevel", hideLevel);
            root.addProperty("hideName", hideName);
            root.add("wheelPets", petListToJson(wheelPets));
            root.add("knownPets", petListToJson(new ArrayList<>(knownPets.values())));
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ simple settings

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getSliceCount() {
        return sliceCount;
    }

    public void setSliceCount(int sliceCount) {
        this.sliceCount = clampSlices(sliceCount);
    }

    public int getScalePercent() {
        return scalePercent;
    }

    public void setScalePercent(int scalePercent) {
        this.scalePercent = clampScale(scalePercent);
    }

    public int getIconScalePercent() {
        return iconScalePercent;
    }

    public void setIconScalePercent(int iconScalePercent) {
        this.iconScalePercent = clampIconScale(iconScalePercent);
    }

    public boolean isHideLevel() {
        return hideLevel;
    }

    public void setHideLevel(boolean hideLevel) {
        this.hideLevel = hideLevel;
    }

    public boolean isHideName() {
        return hideName;
    }

    public void setHideName(boolean hideName) {
        this.hideName = hideName;
    }

    public InteractionMode getMode() {
        return mode;
    }

    public void setMode(InteractionMode mode) {
        this.mode = mode == null ? InteractionMode.HOLD_RELEASE : mode;
    }

    public void cycleMode() {
        mode = mode == InteractionMode.HOLD_RELEASE ? InteractionMode.PRESS_CLICK : InteractionMode.HOLD_RELEASE;
    }

    public int getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = KeyUtil.sanitizeBind(keyCode);
    }

    // ------------------------------------------------------------------ pet lists

    /** @return an unmodifiable, ordered snapshot of the wheel's pets. */
    public List<PetEntry> getWheelPets() {
        return List.copyOf(wheelPets);
    }

    /** @return an unmodifiable, insertion-ordered snapshot of every pet ever seen in a real /pets screen. */
    public List<PetEntry> getKnownPets() {
        return List.copyOf(knownPets.values());
    }

    public boolean isOnWheel(String uuid) {
        return wheelPets.stream().anyMatch(p -> p.uuid().equals(uuid));
    }

    /** Appends a known pet to the end of the wheel (no-op if already on it or unknown). */
    public void addToWheel(String uuid) {
        if (isOnWheel(uuid)) {
            return;
        }
        PetEntry known = knownPets.get(uuid);
        if (known != null) {
            wheelPets.add(known);
        }
    }

    public void removeFromWheel(String uuid) {
        wheelPets.removeIf(p -> p.uuid().equals(uuid));
    }

    /** @return true if the swap happened (both indices were valid neighbours-or-not within range). */
    public boolean moveInWheel(int index, int delta) {
        int target = index + delta;
        if (index < 0 || index >= wheelPets.size() || target < 0 || target >= wheelPets.size()) {
            return false;
        }
        PetEntry moved = wheelPets.remove(index);
        wheelPets.add(target, moved);
        return true;
    }

    /** killer560's reworked wheel-edit flow: left-click one slice then another (or drag one onto another)
     *  swaps their pets in place. Both indices must already hold a real pet - the wheel's one trailing empty
     *  "appendable" slot (see {@link PetWheelScreen}) is never a valid swap partner. @return true if it swapped. */
    public boolean swapInWheel(int indexA, int indexB) {
        if (indexA == indexB || indexA < 0 || indexB < 0 || indexA >= wheelPets.size() || indexB >= wheelPets.size()) {
            return false;
        }
        PetEntry a = wheelPets.get(indexA);
        wheelPets.set(indexA, wheelPets.get(indexB));
        wheelPets.set(indexB, a);
        return true;
    }

    /**
     * killer560's reworked wheel-edit flow: right-clicking a slot opens the real {@code /pets} menu and
     * whatever pet is clicked there replaces the pet in that slot. {@code index == wheelPets.size()} is the
     * wheel's one trailing empty slot (see {@link PetWheelScreen}'s edit-mode layout), which this appends to
     * rather than replacing; anything past that is out of range and ignored rather than leaving a gap.
     */
    public void replaceOrAppendWheelSlot(int index, PetEntry entry) {
        if (entry == null || index < 0 || index > wheelPets.size()) {
            return;
        }
        if (index == wheelPets.size()) {
            wheelPets.add(entry);
        } else {
            wheelPets.set(index, entry);
        }
    }

    /**
     * Merges a fresh scan of one pet into {@code knownPets} (see {@link PetEntry#mergedWith}), and refreshes
     * the matching wheel entry too so the wheel's cached name/level/tier/head stay current without needing
     * the wheel to be reopened after a level-up.
     *
     * @return true if anything actually changed (caller only needs to {@link #save()} then).
     */
    public boolean recordSeenPet(PetEntry fresh) {
        PetEntry existing = knownPets.get(fresh.uuid());
        PetEntry merged = existing == null ? fresh : existing.mergedWith(fresh);
        boolean changed = existing == null || !existing.equals(merged);
        knownPets.put(fresh.uuid(), merged);
        if (changed) {
            for (int i = 0; i < wheelPets.size(); i++) {
                if (wheelPets.get(i).uuid().equals(fresh.uuid())) {
                    wheelPets.set(i, merged);
                    break;
                }
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ helpers

    private static int clampSlices(int v) {
        return Math.max(MIN_SLICES, Math.min(MAX_SLICES, v));
    }

    private static int clampScale(int v) {
        return Math.max(MIN_SCALE_PCT, Math.min(MAX_SCALE_PCT, v));
    }

    private static int clampIconScale(int v) {
        return Math.max(MIN_ICON_SCALE_PCT, Math.min(MAX_ICON_SCALE_PCT, v));
    }

    private static InteractionMode parseMode(String raw) {
        if (raw == null) {
            return InteractionMode.HOLD_RELEASE;
        }
        try {
            return InteractionMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return InteractionMode.HOLD_RELEASE;
        }
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        try {
            return obj.has(key) ? obj.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void readPetList(JsonObject root, String key, List<PetEntry> out) {
        if (!root.has(key) || !root.get(key).isJsonArray()) {
            return;
        }
        for (var el : root.getAsJsonArray(key)) {
            try {
                JsonObject o = el.getAsJsonObject();
                String uuid = o.has("uuid") ? o.get("uuid").getAsString() : null;
                if (uuid == null || uuid.isBlank()) {
                    continue;
                }
                String name = o.has("name") ? o.get("name").getAsString() : null;
                String tier = o.has("tier") && !o.get("tier").isJsonNull() ? o.get("tier").getAsString() : null;
                int level = getInt(o, "level", -1);
                String skin = o.has("skinValue") && !o.get("skinValue").isJsonNull() ? o.get("skinValue").getAsString() : null;
                out.add(new PetEntry(uuid, name, tier, level, skin));
            } catch (RuntimeException ignored) {
                // One bad entry (hand-edited file) never blocks the rest of the list.
            }
        }
    }

    private static JsonArray petListToJson(List<PetEntry> pets) {
        JsonArray arr = new JsonArray();
        for (PetEntry p : pets) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", p.uuid());
            o.addProperty("name", p.name());
            if (p.tier() != null) {
                o.addProperty("tier", p.tier());
            }
            o.addProperty("level", p.level());
            if (p.skinValue() != null) {
                o.addProperty("skinValue", p.skinValue());
            }
            arr.add(o);
        }
        return arr;
    }
}
