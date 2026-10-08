package com.killer560.hub.commandshortcuts;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * Persisted Command Shortcuts settings (killer560's item 8.3: "/f7, /m7, /infernal, every cata and Kuudra
 * tier"). Unlike most toggle sets in this mod, every shortcut here defaults ON - these are pure client-side
 * chat aliases that just re-send the real Hypixel join command (see {@link CommandShortcutsFeature}), they
 * never act in the world, so there is nothing to hide behind an opt-in.
 * <p>
 * <b>2026-09-27 regroup</b> (killer560: "clump them into groups like catacombs/mastermode as one toggle
 * for each floor. Same for kuudra.") - the settings tab used to show one toggle per
 * {@link CommandShortcutsFeature.Shortcut} (20 rows total). Now there is one toggle per
 * {@link CommandShortcutsFeature.Group} instead - each Catacombs floor's toggle (F1-F7) covers BOTH that
 * floor's normal Catacombs shortcut AND its Master Mode shortcut together (F0 has no Master Mode, so its
 * group is just itself); Kuudra already had exactly one shortcut per tier, so its grouping is unchanged
 * in substance, just moved onto the same {@code Group}-keyed storage as everything else. The individual
 * {@code /f1}, {@code /m1}, etc. commands themselves are untouched - only how they're enabled/displayed
 * changed; see {@link CommandShortcutsFeature.Group#members()}.
 * <p>
 * <b>Migrating old configs.</b> Earlier versions of this file stored one boolean per {@code Shortcut}
 * under {@code "shortcut.<name>"}. On load, if the new {@code "group.<name>"} key isn't present yet (first
 * load after this update), that group instead reads its OLD member keys rather than just resetting to the
 * default-true: a group comes back on only if EVERY one of its old member shortcuts was on - so if
 * killer560 had deliberately switched off just the Master Mode half of a floor (e.g. a real collision with
 * another mod), that "off" is honoured for the whole floor instead of silently re-enabling it. A group
 * with no old keys present at all (a config from before this feature even existed) just gets the default,
 * same as every other setting in this mod.
 */
public final class CommandShortcutsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-commandshortcuts.json");

    private static CommandShortcutsConfig instance;

    /** What he types, without the slash: Brigadier literal characters only (killer560, 2026-10-07). */
    public static final java.util.regex.Pattern CUSTOM_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,32}");
    public static final int MAX_CUSTOM_NAME = 32;
    /** The command a custom shortcut sends; Hypixel's chat box takes 256 characters, so nothing longer is useful. */
    public static final int MAX_CUSTOM_COMMAND = 256;
    public static final int MAX_CUSTOMS = 50;

    /**
     * One of his own shortcuts (the Custom section, killer560 2026-10-07): typing {@code /name} sends {@code command}
     * to the server. {@code name} is only ever set to a value {@link CommandShortcutsFeature#nameProblem} accepted
     * (or "" for a new row), so what is saved is always a legal literal.
     */
    public static final class CustomShortcut {
        private String name;
        private String command;
        private boolean enabled;

        CustomShortcut(String name, String command, boolean enabled) {
            this.name = name == null ? "" : name;
            this.command = command == null ? "" : command;
            this.enabled = enabled;
        }

        public String name() {
            return name;
        }

        public String command() {
            return command;
        }

        public boolean isEnabled() {
            return enabled;
        }
    }

    private boolean enabled = true;
    private final EnumMap<CommandShortcutsFeature.Group, Boolean> groups =
            new EnumMap<>(CommandShortcutsFeature.Group.class);
    private final List<CustomShortcut> customs = new ArrayList<>();

    private CommandShortcutsConfig() {
        for (CommandShortcutsFeature.Group g : CommandShortcutsFeature.Group.values()) {
            groups.put(g, true);
        }
    }

    public static CommandShortcutsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new CommandShortcutsConfig();
            return;
        }
        CommandShortcutsConfig cfg = new CommandShortcutsConfig();
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", true);
            for (CommandShortcutsFeature.Group g : CommandShortcutsFeature.Group.values()) {
                String groupKey = groupKey(g);
                if (obj.has(groupKey)) {
                    cfg.groups.put(g, ConfigJson.getBool(obj, groupKey, true));
                } else {
                    // No group key yet - fall back to this group's old per-shortcut keys (see class doc)
                    // instead of resetting straight to the default.
                    boolean anyOldKey = false;
                    boolean allOn = true;
                    for (CommandShortcutsFeature.Shortcut s : g.members()) {
                        String oldKey = legacyShortcutKey(s);
                        if (obj.has(oldKey)) {
                            anyOldKey = true;
                            allOn &= ConfigJson.getBool(obj, oldKey, true);
                        }
                    }
                    cfg.groups.put(g, anyOldKey ? allOn : true);
                }
            }
            JsonArray arr = ConfigJson.getArray(obj, "custom");
            if (arr != null) {
                for (JsonElement el : arr) {
                    if (!el.isJsonObject() || cfg.customs.size() >= MAX_CUSTOMS) {
                        continue;
                    }
                    JsonObject o = el.getAsJsonObject();
                    String name = ConfigJson.getString(o, "name", "");
                    if (!name.isEmpty() && !CUSTOM_NAME.matcher(name).matches()) {
                        name = "";   // hand-edited into something no literal can be: keep the row, drop the name
                    }
                    String command = ConfigJson.getString(o, "command", "");
                    if (command.length() > MAX_CUSTOM_COMMAND) {
                        command = command.substring(0, MAX_CUSTOM_COMMAND);
                    }
                    cfg.customs.add(new CustomShortcut(name, command, ConfigJson.getBool(o, "enabled", true)));
                }
            }
        } catch (Exception ignored) {
            // Unreadable file: keep whatever the per-group readers managed, everything else stays at its default.
        }
        instance = cfg;
        // A profile load replaces the custom list: let the feature register any new ones in the live dispatcher.
        CommandShortcutsFeature.customsChanged();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            for (CommandShortcutsFeature.Group g : CommandShortcutsFeature.Group.values()) {
                obj.addProperty(groupKey(g), groups.getOrDefault(g, true));
            }
            JsonArray arr = new JsonArray();
            for (CustomShortcut c : customs) {
                JsonObject o = new JsonObject();
                o.addProperty("name", c.name);
                o.addProperty("command", c.command);
                o.addProperty("enabled", c.enabled);
                arr.add(o);
            }
            obj.add("custom", arr);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static String groupKey(CommandShortcutsFeature.Group g) {
        return "group." + g.name().toLowerCase(Locale.US);
    }

    /** Pre-2026-09-27 config key for an individual shortcut - read once during migration, never written
     *  again after the first {@link #save()} on the new {@code group.*} keys. */
    private static String legacyShortcutKey(CommandShortcutsFeature.Shortcut s) {
        return "shortcut." + s.name().toLowerCase(Locale.US);
    }

    /** Master switch. Raw (not gated by {@code SkyblockGate}) - registration only cares whether the player
     *  wants the aliases to exist at all; {@link CommandShortcutsFeature} checks the Skyblock/p3sim gate
     *  separately, at run time, so the settings tab always shows what is actually saved. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isGroupOn(CommandShortcutsFeature.Group g) {
        return g != null && Boolean.TRUE.equals(groups.get(g));
    }

    public void setGroupOn(CommandShortcutsFeature.Group g, boolean value) {
        if (g != null) {
            groups.put(g, value);
        }
    }

    /** Whether the given shortcut's owning {@link CommandShortcutsFeature.Group} toggle is on - every
     *  real command still checks this exact method, {@link CommandShortcutsFeature#register()} and
     *  {@code exec()} are otherwise unchanged by the regroup. */
    public boolean isOn(CommandShortcutsFeature.Shortcut s) {
        return isGroupOn(CommandShortcutsFeature.Group.forShortcut(s));
    }

    // ---- custom shortcuts -----------------------------------------------------------------------------------------

    /** His own shortcuts, in the order the tab lists them. Read-only view; change them through the setters below. */
    public List<CustomShortcut> customs() {
        return Collections.unmodifiableList(customs);
    }

    /** A new, empty, enabled row (it does nothing until it has a name and a command), or null at {@link #MAX_CUSTOMS}. */
    public CustomShortcut addCustom() {
        if (customs.size() >= MAX_CUSTOMS) {
            return null;
        }
        CustomShortcut c = new CustomShortcut("", "", true);
        customs.add(c);
        return c;
    }

    public void removeCustom(CustomShortcut c) {
        customs.remove(c);
    }

    /** Sets the name only when {@link CommandShortcutsFeature#nameProblem} has no objection; returns that objection. */
    public String setCustomName(CustomShortcut c, String name) {
        String n = name == null ? "" : name.trim();
        // An empty name is allowed (a row he is still filling in, or cleared): it simply registers nothing.
        String problem = n.isEmpty() ? null : CommandShortcutsFeature.nameProblem(n, c);
        if (problem == null && c != null) {
            c.name = n;
        }
        return problem;
    }

    public void setCustomCommand(CustomShortcut c, String command) {
        if (c == null) {
            return;
        }
        String s = command == null ? "" : command;
        c.command = s.length() > MAX_CUSTOM_COMMAND ? s.substring(0, MAX_CUSTOM_COMMAND) : s;
    }

    public void setCustomEnabled(CustomShortcut c, boolean on) {
        if (c != null) {
            c.enabled = on;
        }
    }

    /** The row whose name is exactly {@code name} (Brigadier literals are case-sensitive), or null. */
    public CustomShortcut customNamed(String name) {
        for (CustomShortcut c : customs) {
            if (!c.name.isEmpty() && c.name.equals(name)) {
                return c;
            }
        }
        return null;
    }
}
