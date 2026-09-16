package com.killer560.hub.posmsg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
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
 *  available to seed here. Use "Set To My Position" in the Posmsg tab once, in-game, standing on the
 *  real spot; the preset then behaves exactly like a custom one from that point on.
 *  <p>
 *  2026-09-16: entries gained a {@code message} (the plain line typed into party chat, "at hee2") and
 *  lost {@code showDisplay}/{@code showOnlyInsideRadius}, which only ever controlled the top-left HUD
 *  list that has since been replaced by a real in-world ring. Those two keys are simply ignored when an
 *  older config is read - nothing else in the file changes, so downgrading isn't destructive either. */
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
                cfg.enabled = ConfigJson.getBool(root, "enabled", true);
                cfg.presetsSeeded = ConfigJson.getBool(root, "presetsSeeded", false);
                JsonArray array = ConfigJson.getArray(root, "entries");
                if (array != null) {
                    for (var el : array) {
                        try {
                            if (el == null || !el.isJsonObject()) {
                                continue;
                            }
                            JsonObject obj = el.getAsJsonObject();
                            PosmsgEntry e = new PosmsgEntry();
                            e.id = ConfigJson.getString(obj, "id", e.id);
                            e.name = ConfigJson.getString(obj, "name", e.name);
                            e.message = ConfigJson.getString(obj, "message", "");
                            e.enabled = ConfigJson.getBool(obj, "enabled", true);
                            e.x = ConfigJson.getDouble(obj, "x", 0);
                            e.y = ConfigJson.getDouble(obj, "y", 0);
                            e.z = ConfigJson.getDouble(obj, "z", 0);
                            e.radius = ConfigJson.getDouble(obj, "radius", 3.0);
                            e.configured = ConfigJson.getBool(obj, "configured", false);
                            e.showRadius = ConfigJson.getBool(obj, "showRadius", true);
                            e.thickness = ConfigJson.getDouble(obj, "thickness", 2.0);
                            e.colorHex = ConfigJson.getString(obj, "colorHex", e.colorHex);
                            e.onceOnlyPerRun = ConfigJson.getBool(obj, "onceOnlyPerRun", false);
                            e.builtin = ConfigJson.getBool(obj, "builtin", false);
                            cfg.entries.add(e);
                        } catch (Exception ignored) {
                            // Skip just this malformed waypoint; the rest of the list still loads.
                        }
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
        cfg.fillBlankPresetMessages();
        instance = cfg;
        if (!parseFailed) {
            instance.save();
        }
    }

    /** Adds the built-in room presets exactly once (first-ever load) - never re-added after that, even
     *  if the player deletes one, since {@link #presetsSeeded} latches true forever once this runs. */
    private void seedPresets() {
        for (var preset : PRESET_MESSAGES.entrySet()) {
            PosmsgEntry e = new PosmsgEntry();
            e.name = preset.getKey();
            e.message = preset.getValue();
            e.builtin = true;
            e.configured = false;
            entries.add(e);
        }
    }

    /** Preset room -&gt; the line it types in party chat, in killer560's own "at hee2" style. Kept as a
     *  map so {@link #fillBlankPresetMessages()} can also backfill configs saved before waypoints had a
     *  message field at all (they used to send a machine-readable coordinate payload instead). */
    private static final java.util.LinkedHashMap<String, String> PRESET_MESSAGES = new java.util.LinkedHashMap<>();

    static {
        PRESET_MESSAGES.put("Simon Says", "at ss");
        // "For EE to make sure that it is the high one up by lever device" - killer560's own wording;
        // kept verbatim rather than guessing at Hypixel's internal room name for it.
        PRESET_MESSAGES.put("EE2 (High - Lever Device)", "at ee2");
        // "for EE three make sure it is the low one"
        PRESET_MESSAGES.put("EE3 (Low)", "at ee3");
        PRESET_MESSAGES.put("Outpour", "at outpour");
        // Kept as "Recor" verbatim (killer560's own term) rather than guessing a canonical Hypixel
        // room name that couldn't be confirmed - rename it in the tab once you know which room it is.
        PRESET_MESSAGES.put("Recor", "at recor");
        PRESET_MESSAGES.put("Necron's Platform", "at necron's platform");
        PRESET_MESSAGES.put("P5", "at p5");
        // Fast Leap's "Leap to Mel" preset (2026-09-13 request) - same unconfigured-until-you-set-it
        // treatment as every other preset above.
        PRESET_MESSAGES.put("Mel", "at mel");
    }

    /** Presets seeded before the message field existed have a blank message, which would make them type
     *  their full list label ("EE2 (High - Lever Device)") into party chat. Fill those in once. */
    private void fillBlankPresetMessages() {
        for (PosmsgEntry e : entries) {
            if (e.builtin && (e.message == null || e.message.isBlank())) {
                String preset = PRESET_MESSAGES.get(e.name);
                if (preset != null) {
                    e.message = preset;
                }
            }
        }
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
                obj.addProperty("message", e.message == null ? "" : e.message);
                obj.addProperty("enabled", e.enabled);
                obj.addProperty("x", e.x);
                obj.addProperty("y", e.y);
                obj.addProperty("z", e.z);
                obj.addProperty("radius", e.radius);
                obj.addProperty("configured", e.configured);
                obj.addProperty("showRadius", e.showRadius);
                obj.addProperty("thickness", e.thickness);
                obj.addProperty("colorHex", e.colorHex);
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
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
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
        e.message = "";
        entries.add(e);
        save();
        return e;
    }

    public void remove(String id) {
        entries.removeIf(e -> e.id.equals(id));
        save();
    }

    /** Looks a waypoint up by its list label, or failing that by the message it sends - so
     *  {@code /killer560 posmsg send at hee2} works as well as the full preset name. */
    public PosmsgEntry byName(String name) {
        for (PosmsgEntry e : entries) {
            if (e.name.equalsIgnoreCase(name)) {
                return e;
            }
        }
        for (PosmsgEntry e : entries) {
            if (e.sendText().equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }
}
