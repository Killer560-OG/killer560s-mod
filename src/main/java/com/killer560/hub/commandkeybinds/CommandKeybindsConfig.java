package com.killer560.hub.commandkeybinds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persisted Chat Keybinds settings - see {@link CommandKeybindsFeature}. Ships disabled by default with no
 *  binds. The class/package names stay {@code commandkeybinds} (they're referenced from the client entrypoint
 *  and {@code ProfileManager}, which this agent doesn't own) - only the user-facing name changed to
 *  "Chat Keybinds" when killer560 asked for free-text binds instead of the 8 fixed menu commands (2026-09-20). */
public final class CommandKeybindsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-commandkeybinds.json");

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

        public Bind(int key, String text) {
            this.key = key;
            this.text = text == null ? "" : text;
        }
    }

    private boolean enabled = false;
    private final List<Bind> binds = new ArrayList<>();

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
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                JsonArray arr = ConfigJson.getArray(obj, "binds");
                if (arr != null) {
                    for (JsonElement el : arr) {
                        if (el == null || !el.isJsonObject()) {
                            continue;
                        }
                        JsonObject o = el.getAsJsonObject();
                        cfg.binds.add(new Bind(sanitizeBind(ConfigJson.getInt(o, "key", -1)),
                                ConfigJson.getString(o, "text", "")));
                    }
                } else {
                    migrateLegacyBinds(cfg, obj);
                }
            } catch (Exception e) {
                cfg = new CommandKeybindsConfig();
            }
        }
        instance = cfg;
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
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            JsonArray arr = new JsonArray();
            for (Bind bind : binds) {
                JsonObject o = new JsonObject();
                o.addProperty("key", bind.key);
                o.addProperty("text", bind.text);
                arr.add(o);
            }
            obj.add("binds", arr);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
