package com.killer560.hub.commandprefix;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Command Prefixes settings: which names open the mod's main command, and the optional alternate command character. */
public final class CommandPrefixConfig {

    /** The main command names, in menu order; index i is {@link #names}[i]. */
    public static final String[] NAMES = {"killer560", "k", "k5", "k560"};
    public static final String[] PRESETS = {".", ",", "!", "-", "#"};

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-commandprefix.json");
    private static volatile CommandPrefixConfig instance;

    private final boolean[] names = {true, false, false, false};
    private boolean altOn;
    private String altChar = ".";

    private CommandPrefixConfig() {
    }

    public static CommandPrefixConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    private static final BoolFields<CommandPrefixConfig> BOOLS = new BoolFields<CommandPrefixConfig>()
            .add("killer560", true, c -> c.names[0], (c, v) -> c.names[0] = v)
            .add("k", false, c -> c.names[1], (c, v) -> c.names[1] = v)
            .add("k5", false, c -> c.names[2], (c, v) -> c.names[2] = v)
            .add("k560", false, c -> c.names[3], (c, v) -> c.names[3] = v)
            .add("altEnabled", false, c -> c.altOn, (c, v) -> c.altOn = v);

    public static synchronized void load() {
        CommandPrefixConfig c = new CommandPrefixConfig();
        JsonObject o = ConfigJson.read(CONFIG_PATH);
        if (o != null) {
            BOOLS.load(c, o);
            c.setAltChar(ConfigJson.getString(o, "altChar", "."));
        }
        instance = c;
    }

    public void save() {
        JsonObject o = new JsonObject();
        BOOLS.save(this, o);
        o.addProperty("altChar", altChar);
        ConfigJson.write(CONFIG_PATH, o);
    }

    public boolean isNameOn(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return names[i];
            }
        }
        return false;
    }

    public void setNameOn(String name, boolean on) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                names[i] = on;
            }
        }
    }

    public boolean isAltOn() {
        return altOn;
    }

    public void setAltOn(boolean on) {
        altOn = on;
    }

    public String altChar() {
        return altChar;
    }

    /** One non-space character other than "/" (which would make a plain command); anything else is refused. */
    public boolean setAltChar(String s) {
        if (s == null || s.isEmpty() || s.codePointCount(0, s.length()) != 1 || s.equals("/")
                || Character.isWhitespace(s.codePointAt(0))) {
            return false;
        }
        altChar = s;
        return true;
    }
}
