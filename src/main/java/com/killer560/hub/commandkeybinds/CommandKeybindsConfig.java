package com.killer560.hub.commandkeybinds;

import com.killer560.hub.commandshortcuts.CommandShortcutsConfig;
import com.killer560.hub.commandshortcuts.CommandShortcutsFeature;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Persisted Chat Keybinds settings - see {@link CommandKeybindsFeature}. The class/package names stay
 * {@code commandkeybinds} (the client entrypoint, {@code ProfileManager} and the testkit name them); the user-facing
 * name is "Chat Keybinds" since killer560 asked for free-text binds instead of the 8 fixed menu commands (2026-09-20).
 * <p>
 * <b>One feature since 2026-10-08</b> (killer560: "Chat keybinds and command shortcuts should be combined into just
 * chat keybinds."). This file now also holds the Command Shortcuts settings, as its {@code "shortcuts"} object
 * ({@link CommandShortcutsConfig}), and there is ONE master switch, {@code "enabled"}, defaulting ON (the shortcuts
 * always defaulted on; with no binds the key half does nothing). Each bind has its own on/off, like a custom shortcut.
 * <p>
 * <b>Migration.</b> The file is "merged" once it has a {@code "shortcuts"} object. Before that, its {@code "enabled"}
 * was Chat Keybinds' own switch (default off), and Command Shortcuts lived in {@code killer560smod-commandshortcuts.json}
 * (switch default on). Both are read; the merged switch is on if either half was; a half that was off while the other
 * was on has its rows switched off one by one (binds, or every built-in group and custom shortcut), so nothing starts
 * or stops doing anything. Then the merged file is written and the old shortcuts file is renamed to {@code .migrated}
 * (kept, never deleted; the profile list ignores it). An old shortcuts file found beside an already merged file (a
 * profile from before the merge applied over it) is taken as the newer word on the shortcuts and migrated the same way.
 */
public final class CommandKeybindsConfig {

    private static final Logger LOGGER = ModLog.get("killer560smod-commandkeybinds");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-commandkeybinds.json");
    /** Command Shortcuts' own file before the merge: read once, then renamed to {@code .migrated}. */
    private static final Path OLD_SHORTCUTS_PATH =
            ModPaths.config("killer560smod-commandshortcuts.json");

    /** Mouse buttons are stored as {@code MOUSE_CODE_BASE - button} (left -100, right -101, middle -102, ...)
     *  so one int field keeps holding a whole bind - killer560 (2026-09-20): "make all of the keybind things
     *  compatible with mouse buttons and middle mouse buttons". {@code -1} is still "Not Set". */
    public static final int MOUSE_CODE_BASE = -100;
    public static final int MAX_MOUSE_BUTTON = 7;

    private static CommandKeybindsConfig instance;

    /** One key/mouse bind and the exact line it sends. A leading "/" makes it a command, anything else is
     *  sent as a normal chat message. */
    public static final class Bind {
        public int key;
        public String text;
        /** Its own on/off (since the merge, 2026-10-08); a bind from before it was on whenever the feature was. */
        public boolean enabled;

        public Bind(int key, String text) {
            this(key, text, true);
        }

        public Bind(int key, String text, boolean enabled) {
            this.key = key;
            this.text = text == null ? "" : text;
            this.enabled = enabled;
        }
    }

    private boolean enabled = true;
    private final List<Bind> binds = new ArrayList<>();
    private CommandShortcutsConfig shortcuts = CommandShortcutsConfig.defaults();

    private CommandKeybindsConfig() {
    }

    public static CommandKeybindsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** True for a stored mouse-button bind (see {@link #MOUSE_CODE_BASE}). */
    public static boolean isMouseCode(int code) {
        return code <= MOUSE_CODE_BASE && code >= MOUSE_CODE_BASE - MAX_MOUSE_BUTTON;
    }

    /** GLFW mouse button number for a stored mouse code. */
    public static int mouseButton(int code) {
        return MOUSE_CODE_BASE - code;
    }

    /** Stored code for a GLFW mouse button number. */
    public static int codeForMouseButton(int button) {
        return MOUSE_CODE_BASE - button;
    }

    /** Like {@code KeyUtil.sanitize}, but keeps mouse-button codes (which KeyUtil rejects, since it only
     *  ever expected keyboard codes). */
    public static int sanitizeBind(int code) {
        return isMouseCode(code) ? code : com.killer560.hub.util.KeyUtil.sanitize(code);
    }

    public static void load() {
        CommandKeybindsConfig cfg = new CommandKeybindsConfig();
        JsonObject kb = readJson(CONFIG_PATH);
        JsonObject oldShortcuts = readJson(OLD_SHORTCUTS_PATH);
        boolean merged = kb != null && kb.has("shortcuts") && kb.get("shortcuts").isJsonObject();

        // Was each half doing anything? An unmerged file's "enabled" is the old Chat Keybinds switch (default off).
        boolean keysActive = kb != null && ConfigJson.getBool(kb, "enabled", merged);
        boolean shortcutsActive;
        if (oldShortcuts != null) {
            shortcutsActive = ConfigJson.getBool(oldShortcuts, "enabled", true);
        } else if (merged) {
            shortcutsActive = keysActive;   // one switch already
        } else {
            shortcutsActive = true;         // Command Shortcuts' default
        }
        cfg.enabled = merged && oldShortcuts == null ? keysActive : (keysActive || shortcutsActive);

        if (kb != null) {
            try {
                JsonArray arr = ConfigJson.getArray(kb, "binds");
                if (arr != null) {
                    for (JsonElement el : arr) {
                        if (el == null || !el.isJsonObject()) {
                            continue;
                        }
                        JsonObject o = el.getAsJsonObject();
                        cfg.binds.add(new Bind(sanitizeBind(ConfigJson.getInt(o, "key", -1)),
                                ConfigJson.getString(o, "text", ""), ConfigJson.getBool(o, "enabled", true)));
                    }
                } else {
                    migrateLegacyBinds(cfg, kb);
                }
            } catch (Exception e) {
                cfg.binds.clear();
            }
        }
        if (oldShortcuts != null) {
            cfg.shortcuts = CommandShortcutsConfig.readFrom(oldShortcuts);
        } else if (merged) {
            cfg.shortcuts = CommandShortcutsConfig.readFrom(kb.getAsJsonObject("shortcuts"));
        }

        boolean migrating = oldShortcuts != null || (kb != null && !merged);
        if (migrating && cfg.enabled) {
            // One switch now covers both halves: keep the half that was off doing nothing.
            if (!keysActive) {
                for (Bind b : cfg.binds) {
                    b.enabled = false;
                }
            }
            if (!shortcutsActive) {
                cfg.shortcuts.switchAllOff();
            }
        }
        instance = cfg;
        if (migrating) {
            if (cfg.write() && oldShortcuts != null) {
                try {
                    Files.move(OLD_SHORTCUTS_PATH, OLD_SHORTCUTS_PATH.resolveSibling(
                            OLD_SHORTCUTS_PATH.getFileName() + ".migrated"), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    LOGGER.warn("[ChatKeybinds] could not rename the migrated {}", OLD_SHORTCUTS_PATH, e);
                }
            }
            LOGGER.info("[ChatKeybinds] merged Command Shortcuts into Chat Keybinds: {} bind(s), {} custom shortcut(s), "
                    + "switch {}", cfg.binds.size(), cfg.shortcuts.customs().size(), cfg.enabled ? "on" : "off");
        }
        // A profile load replaces the custom list: let the feature register any new ones in the live dispatcher.
        CommandShortcutsFeature.customsChanged();
    }

    private static JsonObject readJson(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** One-time migration of the original 8 fixed menu binds ("petsKey" ... "potionBagKey") into the free-text
     *  list, so the keys killer560 already had keep doing the same thing after the Chat Keybinds rewrite
     *  instead of being orphaned. Unset keys are skipped, so an untouched config still starts empty. */
    private static void migrateLegacyBinds(CommandKeybindsConfig cfg, JsonObject obj) {
        addLegacy(cfg, obj, "petsKey", "/pets");
        addLegacy(cfg, obj, "storageKey", "/storage");
        addLegacy(cfg, obj, "armorKey", "/armor");
        addLegacy(cfg, obj, "equipmentKey", "/equipment");
        addLegacy(cfg, obj, "loadoutsKey", "/loadout");
        addLegacy(cfg, obj, "statsKey", "/stats");
        addLegacy(cfg, obj, "dungeonHubKey", "/warp dungeon_hub");
        addLegacy(cfg, obj, "potionBagKey", "/potionbag");
    }

    private static void addLegacy(CommandKeybindsConfig cfg, JsonObject obj, String field, String command) {
        int key = sanitizeBind(ConfigJson.getInt(obj, field, -1));
        if (key != -1) {
            cfg.binds.add(new Bind(key, command));
        }
    }

    public void save() {
        write();
    }

    private boolean write() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            JsonArray arr = new JsonArray();
            for (Bind bind : binds) {
                JsonObject o = new JsonObject();
                o.addProperty("key", bind.key);
                o.addProperty("text", bind.text);
                o.addProperty("enabled", bind.enabled);
                arr.add(o);
            }
            obj.add("binds", arr);
            JsonObject sc = new JsonObject();
            shortcuts.writeTo(sc);
            obj.add("shortcuts", sc);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Master switch, gated by {@code SkyblockGate} (what the key half checks before sending). */
    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** The command-shortcut section (built-in toggles and custom shortcuts). */
    public CommandShortcutsConfig shortcuts() {
        return shortcuts;
    }

    /** Live list - call {@link #save()} after editing a row in place. */
    public List<Bind> binds() {
        return binds;
    }

    public void addBind() {
        binds.add(new Bind(-1, ""));
    }

    public void removeBind(Bind bind) {
        binds.remove(bind);
    }
}
