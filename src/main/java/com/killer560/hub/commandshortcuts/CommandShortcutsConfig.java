package com.killer560.hub.commandshortcuts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.commandkeybinds.CommandKeybindsConfig;
import com.killer560.hub.util.ConfigJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * The command-shortcut half of Chat Keybinds: the built-in /f0../f7, /m1../m7 and Kuudra toggles, and his own Custom
 * shortcuts. Every shortcut defaults ON - they are client-side aliases that re-send the real Hypixel command (see
 * {@link CommandShortcutsFeature}) and never act in the world.
 * <p>
 * <b>Merged into Chat Keybinds (killer560, 2026-10-08: "Chat keybinds and command shortcuts should be combined into
 * just chat keybinds.").</b> This object no longer has a file of its own: it is a section ({@code "shortcuts"}) of
 * {@code killer560smod-commandkeybinds.json}, owned and saved by {@link CommandKeybindsConfig}, and its master switch IS
 * the Chat Keybinds switch. {@link #load()}, {@link #save()} and {@link #getInstance()} stay so every caller (the
 * feature, the profile reload list, the testkit) keeps working; they go through the merged config. The old
 * {@code killer560smod-commandshortcuts.json} is read once by {@link CommandKeybindsConfig#load()} and renamed to
 * {@code .migrated}.
 * <p>
 * <b>2026-09-27 regroup</b> (killer560: "clump them into groups like catacombs/mastermode as one toggle for each
 * floor. Same for kuudra."): one toggle per {@link CommandShortcutsFeature.Group}. A file from before it stored one
 * boolean per {@code Shortcut} under {@code "shortcut.<name>"}; a group with no {@code "group.<name>"} key reads its
 * old member keys and comes back on only if every one was on.
 */
public final class CommandShortcutsConfig {

    /** What he types, without the slash: Brigadier literal characters only (killer560, 2026-10-07). */
    public static final java.util.regex.Pattern CUSTOM_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,32}");
    public static final int MAX_CUSTOM_NAME = 32;
    /** The command a custom shortcut sends; Hypixel's chat box takes 256 characters, so nothing longer is useful. */
    public static final int MAX_CUSTOM_COMMAND = 256;
    public static final int MAX_CUSTOMS = 50;

    /**
     * One of his own shortcuts (the Custom section, killer560 2026-10-07): typing {@code /name} sends {@code command}
     * to the server. {@code name} is only ever set to a value {@link CommandShortcutsFeature#nameProblem} accepted
     * (or "" for a new row), so what is saved is always a legal literal. Since the merge a row can also carry a key
     * (stored like a Chat Keybinds bind, -1 for none) that sends the same command.
     */
    public static final class CustomShortcut {
        private String name;
        private String command;
        private boolean enabled;
        private int key;

        CustomShortcut(String name, String command, boolean enabled, int key) {
            this.name = name == null ? "" : name;
            this.command = command == null ? "" : command;
            this.enabled = enabled;
            this.key = key;
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

        /** The bound key or mouse code ({@link CommandKeybindsConfig#MOUSE_CODE_BASE} encoding), -1 for none. */
        public int key() {
            return key;
        }
    }

    private final EnumMap<CommandShortcutsFeature.Group, Boolean> groups =
            new EnumMap<>(CommandShortcutsFeature.Group.class);
    private final List<CustomShortcut> customs = new ArrayList<>();

    private CommandShortcutsConfig() {
        for (CommandShortcutsFeature.Group g : CommandShortcutsFeature.Group.values()) {
            groups.put(g, true);
        }
    }

    /** The shortcuts section of the live Chat Keybinds config. */
    public static CommandShortcutsConfig getInstance() {
        return CommandKeybindsConfig.getInstance().shortcuts();
    }

    /** Reloads the merged Chat Keybinds config (kept so the profile reload list and the testkit still reach it). */
    public static void load() {
        CommandKeybindsConfig.load();
    }

    /** Saves the merged Chat Keybinds config. */
    public void save() {
        CommandKeybindsConfig.getInstance().save();
    }

    /** All defaults: every group on, no custom rows. */
    public static CommandShortcutsConfig defaults() {
        return new CommandShortcutsConfig();
    }

    /**
     * Reads a shortcuts section: the merged file's {@code "shortcuts"} object, or a whole old
     * {@code killer560smod-commandshortcuts.json} (same keys; its {@code "enabled"} is read by the caller).
     */
    public static CommandShortcutsConfig readFrom(JsonObject obj) {
        CommandShortcutsConfig cfg = new CommandShortcutsConfig();
        if (obj == null) {
            return cfg;
        }
        try {
            for (CommandShortcutsFeature.Group g : CommandShortcutsFeature.Group.values()) {
                String groupKey = groupKey(g);
                if (obj.has(groupKey)) {
                    cfg.groups.put(g, ConfigJson.getBool(obj, groupKey, true));
                } else {
                    // No group key yet - fall back to this group's old per-shortcut keys (see class doc).
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
                    if (el == null || !el.isJsonObject() || cfg.customs.size() >= MAX_CUSTOMS) {
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
                    cfg.customs.add(new CustomShortcut(name, command, ConfigJson.getBool(o, "enabled", true),
                            CommandKeybindsConfig.sanitizeBind(ConfigJson.getInt(o, "key", -1))));
                }
            }
        } catch (Exception ignored) {
            // Unreadable section: keep whatever the readers managed, everything else stays at its default.
        }
        return cfg;
    }

    /** Writes this section's keys into {@code obj}. */
    public void writeTo(JsonObject obj) {
        for (CommandShortcutsFeature.Group g : CommandShortcutsFeature.Group.values()) {
            obj.addProperty(groupKey(g), groups.getOrDefault(g, true));
        }
        JsonArray arr = new JsonArray();
        for (CustomShortcut c : customs) {
            JsonObject o = new JsonObject();
            o.addProperty("name", c.name);
            o.addProperty("command", c.command);
            o.addProperty("enabled", c.enabled);
            o.addProperty("key", c.key);
            arr.add(o);
        }
        obj.add("custom", arr);
    }

    /** Switches every built-in group and every custom row off: what a migrated "Command Shortcuts: OFF" becomes once
     *  the one Chat Keybinds switch is on for its key binds. */
    public void switchAllOff() {
        for (CommandShortcutsFeature.Group g : CommandShortcutsFeature.Group.values()) {
            groups.put(g, false);
        }
        for (CustomShortcut c : customs) {
            c.enabled = false;
        }
    }

    private static String groupKey(CommandShortcutsFeature.Group g) {
        return "group." + g.name().toLowerCase(Locale.US);
    }

    /** Pre-2026-09-27 config key for an individual shortcut - read during migration, never written. */
    private static String legacyShortcutKey(CommandShortcutsFeature.Shortcut s) {
        return "shortcut." + s.name().toLowerCase(Locale.US);
    }

    /** The Chat Keybinds master switch, raw (not gated by {@code SkyblockGate}) - registration only cares whether the
     *  player wants the aliases to exist at all; {@link CommandShortcutsFeature} checks the Skyblock/p3sim gate at run
     *  time. */
    public boolean isEnabledRaw() {
        return CommandKeybindsConfig.getInstance().isEnabledRaw();
    }

    public void setEnabled(boolean enabled) {
        CommandKeybindsConfig.getInstance().setEnabled(enabled);
    }

    public boolean isGroupOn(CommandShortcutsFeature.Group g) {
        return g != null && Boolean.TRUE.equals(groups.get(g));
    }

    public void setGroupOn(CommandShortcutsFeature.Group g, boolean value) {
        if (g != null) {
            groups.put(g, value);
        }
    }

    /** Whether the given shortcut's owning {@link CommandShortcutsFeature.Group} toggle is on. */
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
        CustomShortcut c = new CustomShortcut("", "", true, -1);
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

    /** Binds a key or mouse code (-1 clears it) to this row: pressing it sends the row's command. */
    public void setCustomKey(CustomShortcut c, int key) {
        if (c != null) {
            c.key = CommandKeybindsConfig.sanitizeBind(key);
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
