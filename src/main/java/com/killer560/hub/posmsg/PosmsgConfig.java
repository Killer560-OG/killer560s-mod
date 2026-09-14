package com.killer560.hub.posmsg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persisted list of {@link PosmsgEntry} waypoints, plus the master enable and the seeded set of
 *  built-in room presets - per killer560's request, this ships with one preset per named room
 *  (Simon Says, EE2, EE3, Outpour, Recor, Necron's Platform, P5), each starting UNCONFIGURED
 *  (x=y=z=0, {@code configured=false}) since real in-game coordinates for these rooms weren't
 *  available to seed here - see each preset's own comment below. Use "Set to my position" in the
 *  Posmsg tab (or manually type X/Y/Z) once, in-game, standing on the real spot; the preset then
 *  behaves exactly like a custom one from that point on. */
public final class PosmsgConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-posmsg.json");

    private static PosmsgConfig instance;

    private boolean enabled = true;
    private boolean presetsSeeded = false;
    private final List<PosmsgEntry> entries = new ArrayList<>();

    private PosmsgConfig() {
    }

    public static PosmsgConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        PosmsgConfig cfg = new PosmsgConfig();
        // Real bug found and fixed (2026-09-14, pre-testing bug-review pass): a genuine parse failure
        // here used to be silently swallowed and then this method unconditionally saved the freshly-
        // defaulted config right back over the corrupted file a moment later - permanently destroying
        // whatever was recoverable (custom waypoints, captured positions) before killer560 ever noticed.
        // Now skips the auto-save specifically when parsing actually failed, leaving the broken file on
        // disk untouched instead of instantly overwriting it with blank defaults.
        boolean parseFailed = false;
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                cfg.enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
                cfg.presetsSeeded = root.has("presetsSeeded") && root.get("presetsSeeded").getAsBoolean();
                if (root.has("entries")) {
                    JsonArray array = root.getAsJsonArray("entries");
                    for (var el : array) {
                        JsonObject obj = el.getAsJsonObject();
                        PosmsgEntry e = new PosmsgEntry();
                        e.id = getString(obj, "id", e.id);
                        e.name = getString(obj, "name", e.name);
                        e.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
                        e.x = obj.has("x") ? obj.get("x").getAsDouble() : 0;
                        e.y = obj.has("y") ? obj.get("y").getAsDouble() : 0;
                        e.z = obj.has("z") ? obj.get("z").getAsDouble() : 0;
                        e.radius = obj.has("radius") ? obj.get("radius").getAsDouble() : 3.0;
                        e.configured = obj.has("configured") && obj.get("configured").getAsBoolean();
                        e.showRadius = !obj.has("showRadius") || obj.get("showRadius").getAsBoolean();
                        e.showDisplay = !obj.has("showDisplay") || obj.get("showDisplay").getAsBoolean();
                        e.colorHex = getString(obj, "colorHex", e.colorHex);
                        e.showOnlyInsideRadius = obj.has("showOnlyInsideRadius") && obj.get("showOnlyInsideRadius").getAsBoolean();
                        e.onceOnlyPerRun = obj.has("onceOnlyPerRun") && obj.get("onceOnlyPerRun").getAsBoolean();
                        e.builtin = obj.has("builtin") && obj.get("builtin").getAsBoolean();
                        cfg.entries.add(e);
                    }
                }
            } catch (Exception e) {
                parseFailed = true;
                cfg = new PosmsgConfig();
            }
        }
        if (!cfg.presetsSeeded) {
            cfg.seedPresets();
            cfg.presetsSeeded = true;
        }
        instance = cfg;
        if (!parseFailed) {
            instance.save();
        }
    }

    /** Adds the built-in room presets exactly once (first-ever load) - never re-added after that, even
     *  if the player deletes one, since {@link #presetsSeeded} latches true forever once this runs. */
    private void seedPresets() {
        addPreset("Simon Says");
        // "For EE to make sure that it is the high one up by lever device" - killer560's own wording;
        // kept verbatim rather than guessing at Hypixel's internal room name for it.
        addPreset("EE2 (High - Lever Device)");
        // "for EE three make sure it is the low one"
        addPreset("EE3 (Low)");
        addPreset("Outpour");
        // Kept as "Recor" verbatim (killer560's own term) rather than guessing a canonical Hypixel
        // room name that couldn't be confirmed - rename it in the tab once you know which room it is.
        addPreset("Recor");
        addPreset("Necron's Platform");
        addPreset("P5");
        // Fast Leap's "Leap to Mel" preset (2026-09-13 request) - same unconfigured-until-you-set-it
        // treatment as every other preset above.
        addPreset("Mel");
    }

    private void addPreset(String name) {
        PosmsgEntry e = new PosmsgEntry();
        e.name = name;
        e.builtin = true;
        e.configured = false;
        entries.add(e);
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        return obj.has(key) ? obj.get(key).getAsString() : fallback;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("presetsSeeded", presetsSeeded);
            JsonArray array = new JsonArray();
            for (PosmsgEntry e : entries) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", e.id);
                obj.addProperty("name", e.name);
                obj.addProperty("enabled", e.enabled);
                obj.addProperty("x", e.x);
                obj.addProperty("y", e.y);
                obj.addProperty("z", e.z);
                obj.addProperty("radius", e.radius);
                obj.addProperty("configured", e.configured);
                obj.addProperty("showRadius", e.showRadius);
                obj.addProperty("showDisplay", e.showDisplay);
                obj.addProperty("colorHex", e.colorHex);
                obj.addProperty("showOnlyInsideRadius", e.showOnlyInsideRadius);
                obj.addProperty("onceOnlyPerRun", e.onceOnlyPerRun);
                obj.addProperty("builtin", e.builtin);
                array.add(obj);
            }
            root.add("entries", array);
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<PosmsgEntry> entries() {
        return entries;
    }

    public PosmsgEntry addNew() {
        PosmsgEntry e = new PosmsgEntry();
        e.name = "Waypoint " + (entries.size() + 1);
        entries.add(e);
        save();
        return e;
    }

    public void remove(String id) {
        entries.removeIf(e -> e.id.equals(id));
        save();
    }

    public PosmsgEntry byName(String name) {
        for (PosmsgEntry e : entries) {
            if (e.name.equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }
}
