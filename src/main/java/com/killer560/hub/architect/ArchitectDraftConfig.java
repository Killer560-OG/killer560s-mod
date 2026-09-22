package com.killer560.hub.architect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Architect's First Draft settings - see {@link ArchitectDraftFeature}. Both off by default (new feature). */
public final class ArchitectDraftConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-architectdraft.json");

    private static ArchitectDraftConfig instance;

    /** On a puzzle fail, post a local clickable line that gets a First Draft from your sack. */
    private boolean clickMessage = false;
    /** Cheat build only: when the fail line names YOU, get it from the sack straight away. */
    private boolean autoGet = false;

    private ArchitectDraftConfig() {
    }

    public static ArchitectDraftConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ArchitectDraftConfig cfg = new ArchitectDraftConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.clickMessage = ConfigJson.getBool(o, "clickMessage", false);
                cfg.autoGet = ConfigJson.getBool(o, "autoGet", false);
            } catch (Exception ignored) {
                // unreadable file: defaults
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("clickMessage", clickMessage);
            o.addProperty("autoGet", autoGet);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isClickMessage() {
        return clickMessage && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isClickMessageRaw() {
        return clickMessage;
    }

    public void setClickMessage(boolean v) {
        clickMessage = v;
    }

    public boolean isAutoGet() {
        return autoGet && com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isAutoGetRaw() {
        return autoGet;
    }

    public void setAutoGet(boolean v) {
        autoGet = v;
    }
}
