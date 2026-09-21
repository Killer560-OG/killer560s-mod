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
 *   <li>{@code wheelPets} - the ordered subset actually shown on the wheel, picked in {@link PetPickerScreen}.</li>
 *   <li>{@code knownPets} - every pet {@link PetsMenuScanner} has ever read off a real {@code /pets} screen,
 *   insertion-ordered, so the picker has something to choose from even before the wheel itself has been
 *   opened once. Both are keyed by the pet's own item uuid (see {@link PetEntry}), never by name+rarity.</li>
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
    public static final int MAX_SCALE_PCT = 150;
    public static final int DEFAULT_SCALE_PCT = 100;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-petwheel.json");

    private static PetWheelConfig instance;

    private boolean enabled = false;
    private int sliceCount = DEFAULT_SLICES;
    private int scalePercent = DEFAULT_SCALE_PCT;
    private InteractionMode mode = InteractionMode.HOLD_RELEASE;
    private int keyCode = KeyUtil.NONE;

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

    public static void load() {
        PetWheelConfig cfg = new PetWheelConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = root.has("enabled") && root.get("enabled").getAsBoolean();
                cfg.sliceCount = clampSlices(getInt(root, "sliceCount", DEFAULT_SLICES));
                cfg.scalePercent = clampScale(getInt(root, "scalePercent", DEFAULT_SCALE_PCT));
                cfg.mode = parseMode(root.has("mode") ? root.get("mode").getAsString() : null);
                cfg.keyCode = KeyUtil.sanitizeBind(getInt(root, "keyCode", KeyUtil.NONE));
                readPetList(root, "wheelPets", cfg.wheelPets);
                List<PetEntry> known = new ArrayList<>();
                readPetList(root, "knownPets", known);
                for (PetEntry p : known) {
                    cfg.knownPets.put(p.uuid(), p);
                }
            } catch (Exception ignored) {
                // Corrupt/hand-edited file: fall back to defaults rather than refuse to start.
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
            root.addProperty("mode", mode.name());
            root.addProperty("keyCode", keyCode);
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
