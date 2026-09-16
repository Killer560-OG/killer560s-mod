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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persisted list of {@link PosmsgEntry} waypoints, plus the master enable and the seeded set of
 *  built-in room presets (Simon Says, EE2, EE3, Outcore, Recore, Necron's Platform, P5).
 *  <p>
 *  2026-09-16 (killer560's test round): the presets now ship with his real in-game coordinates
 *  ({@link #PRESETS}) and {@code configured=true}, so a fresh install works with no "Set To My Position"
 *  step; "Outpour"/"Recor" became "Outcore"/"Recore" (with {@link #migrateLegacyEntries} carrying an
 *  existing config's coordinates across under the new name rather than duplicating the row); and the
 *  "Mel" preset is gone - Melody is a terminal, not a fixed spot, so its callout moved to the Terminal
 *  Solver ("Send Mel Coords On Open"), which fires when the Melody terminal actually opens.
 *  <p>
 *  Earlier (same day): entries gained a {@code message} (the plain line typed into party chat, "at hee2")
 *  and lost {@code showDisplay}/{@code showOnlyInsideRadius}, which only ever controlled the top-left HUD
 *  list that has since been replaced by a real in-world ring. Unknown keys are simply ignored when an
 *  older config is read - nothing else in the file changes, so downgrading isn't destructive either. */
public final class PosmsgConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-posmsg.json");

    private static PosmsgConfig instance;

    private boolean enabled = true;
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
                            e.enabled = ConfigJson.getBool(obj, "enabled", false);
                            e.x = ConfigJson.getDouble(obj, "x", 0);
                            e.y = ConfigJson.getDouble(obj, "y", 0);
                            e.z = ConfigJson.getDouble(obj, "z", 0);
                            // Deliberately NOT clamped to the slider's 1-10 range: "/killer560 posmsg add"
                            // accepts any radius, and a hand-typed 12 must survive a restart unchanged.
                            e.radius = ConfigJson.getDouble(obj, "radius", 3.0);
                            e.configured = ConfigJson.getBool(obj, "configured", false);
                            e.showRadius = ConfigJson.getBool(obj, "showRadius", true);
                            e.thickness = ConfigJson.getDouble(obj, "thickness", 2.0);
                            e.colorHex = ConfigJson.getString(obj, "colorHex", e.colorHex);
                            e.onceOnlyPerRun = ConfigJson.getBool(obj, "onceOnlyPerRun", false);
                            e.builtin = ConfigJson.getBool(obj, "builtin", false);
                            e.textScale = PosmsgEntry.clampTextScale(ConfigJson.getDouble(obj, "textScale", 1.0));
                            e.textHeightOffset = PosmsgEntry.clampTextHeight(
                                    ConfigJson.getDouble(obj, "textHeightOffset", 0.0));
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
        cfg.migrateLegacyEntries();
        cfg.seedMissingPresets();
        cfg.fillBlankPresetMessages();
        instance = cfg;
        if (!parseFailed) {
            instance.save();
        }
    }

    /** One shipped preset: its list label, the exact line it types into party chat (killer560's own
     *  "at hee2" style), and the real boss-arena coordinates + trigger radius from his live config. */
    private record Preset(String name, String message, double x, double y, double z, double radius) {
    }

    /** The built-in presets, in list order. Coordinates are killer560's own (2026-09-16), copied from the
     *  config he tuned in real runs rather than guessed, so a fresh install fires in the right places.
     *  Note Simon Says' X/Z are an un-snapped standing position (the others sit on half-block centres) -
     *  kept exactly as he had it. */
    private static final Map<String, Preset> PRESETS = new LinkedHashMap<>();

    static {
        put(new Preset("Simon Says", "at ss", 107.86779359798764, 120.0, 93.91732371769773, 3.0));
        // "For EE to make sure that it is the high one up by lever device" - killer560's own wording;
        // kept verbatim rather than guessing at Hypixel's internal room name for it.
        put(new Preset("EE2 (High - Lever Device)", "at ee2", 60.5, 132.0, 139.0, 3.0));
        // "for EE three make sure it is the low one"
        put(new Preset("EE3 (Low)", "at ee3", 2.5, 109.0, 105.5, 3.0));
        // Renamed from "Outpour"/"Recor" (killer560, 2026-09-16) - see migrateLegacyEntries.
        put(new Preset("Outcore", "at outcore", 54.5, 115.0, 50.5, 1.5));
        put(new Preset("Recore", "at recore", 54.5, 115.0, 58.5, 4.0));
        put(new Preset("Necron's Platform", "at necron's platform", 54.5, 65.0, 76.5, 8.0));
        put(new Preset("P5", "at p5", 54.5, 5.0, 76.5, 3.0));
    }

    private static void put(Preset p) {
        PRESETS.put(p.name(), p);
    }

    /** Old preset name -&gt; new preset name. A config saved before the rename keeps its coordinates,
     *  colour, radius and enabled state under the new label instead of getting a second, blank row. */
    private static final Map<String, String> RENAMED_PRESETS = Map.of(
            "Outpour", "Outcore",
            "Recor", "Recore");

    /** Old default message -&gt; new default message, applied only when the player never customised it.
     *  A hand-edited message ("at op!") is theirs and stays. */
    private static final Map<String, String> RENAMED_MESSAGES = Map.of(
            "at outpour", "at outcore",
            "at recor", "at recore");

    /** Preset names that no longer exist and get dropped on load - only the {@code builtin} copy, never a
     *  custom waypoint the player happened to give the same name. "Mel" moved to the Terminal Solver
     *  (killer560, 2026-09-16: "It should have an option to also send coords on open mel"). */
    private static final Set<String> REMOVED_PRESETS = Set.of("Mel");

    /** Applies the 2026-09-16 rename/removal to a config written by an older build. Idempotent - a
     *  config that's already current passes through untouched. */
    private void migrateLegacyEntries() {
        entries.removeIf(e -> e.builtin && REMOVED_PRESETS.contains(e.name));
        Set<String> presentNames = new HashSet<>();
        for (PosmsgEntry e : entries) {
            if (e.builtin) {
                presentNames.add(e.name);
            }
        }
        for (PosmsgEntry e : entries) {
            if (!e.builtin) {
                continue;
            }
            String newName = RENAMED_PRESETS.get(e.name);
            if (newName == null) {
                continue;
            }
            if (presentNames.contains(newName)) {
                // Both the old and the new row exist (a config that was hand-edited, or round-tripped
                // through a build that already knew the new name). The new one wins; mark the old one
                // for removal rather than leaving a duplicate preset in the list.
                e.name = null;
                continue;
            }
            e.name = newName;
            presentNames.add(newName);
            String migratedMessage = e.message == null ? null
                    : RENAMED_MESSAGES.get(e.message.trim().toLowerCase(java.util.Locale.ROOT));
            if (migratedMessage != null) {
                e.message = migratedMessage;
            }
        }
        entries.removeIf(e -> e.name == null);
    }

    /** Adds any shipped preset that isn't in the list yet, by name, and gives an already-present preset
     *  the shipped coordinates if the player never set their own ({@code configured=false}). Presets
     *  can't be deleted from the tab, so "missing" only ever means a brand-new install or a preset added
     *  in a later build - a preset the player has positioned, recoloured or renamed is never touched. */
    private void seedMissingPresets() {
        Map<String, PosmsgEntry> byName = new LinkedHashMap<>();
        for (PosmsgEntry e : entries) {
            if (e.builtin) {
                byName.putIfAbsent(e.name, e);
            }
        }
        int insertAt = 0;
        for (Preset preset : PRESETS.values()) {
            PosmsgEntry existing = byName.get(preset.name());
            if (existing == null) {
                PosmsgEntry e = new PosmsgEntry();
                e.name = preset.name();
                e.message = preset.message();
                e.builtin = true;
                applyPresetPosition(e, preset);
                // Keep presets at the top of the list, in shipped order, ahead of custom waypoints.
                entries.add(Math.min(insertAt, entries.size()), e);
                byName.put(e.name, e);
            } else if (!existing.configured) {
                // Seeded by an older build as x=y=z=0 "set it yourself" placeholders; the real
                // coordinates exist now, so hand them over. Only when nothing was ever set.
                applyPresetPosition(existing, preset);
            }
            insertAt++;
        }
    }

    private static void applyPresetPosition(PosmsgEntry e, Preset preset) {
        e.x = preset.x();
        e.y = preset.y();
        e.z = preset.z();
        e.radius = preset.radius();
        e.configured = true;
    }

    /** Presets seeded before the message field existed have a blank message, which would make them type
     *  their full list label ("EE2 (High - Lever Device)") into party chat. Fill those in once. */
    private void fillBlankPresetMessages() {
        for (PosmsgEntry e : entries) {
            if (e.builtin && (e.message == null || e.message.isBlank())) {
                Preset preset = PRESETS.get(e.name);
                if (preset != null) {
                    e.message = preset.message();
                }
            }
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            // Still written for builds that latch on it; this build seeds by name instead (see
            // seedMissingPresets) so the flag is no longer read.
            root.addProperty("presetsSeeded", true);
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
                obj.addProperty("textScale", e.textScale);
                obj.addProperty("textHeightOffset", e.textHeightOffset);
                array.add(obj);
            }
            root.add("entries", array);
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Master switch AND the Skyblock gate - what the feature/renderer check. The GUI's button text
     *  must use {@link #isEnabledRaw()} instead, or toggling off-Skyblock would read OFF and flip the
     *  stored value to ON no matter what it was. */
    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** The stored master switch, ignoring the Skyblock gate. */
    public boolean isEnabledRaw() {
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
