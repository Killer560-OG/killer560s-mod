package com.killer560.hub.bugreport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Bug Report settings - see {@link BugReportFeature}. Follows the exact
 *  getInstance/load/save shape of {@code autojoinskyblock.AutoJoinSkyblockConfig} (every setting must
 *  survive a restart per the project's standing rule). Only one setting exists so far: whether
 *  {@code latest.log} goes into the zip at all - a stranger reporting a bug they can reproduce without a
 *  log (e.g. "the GUI button is in the wrong place") shouldn't be forced to hand over a log every time. */
public final class BugReportConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-bugreport.json");

    private static BugReportConfig instance;

    private boolean includeLog = true;

    private BugReportConfig() {
    }

    public static BugReportConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new BugReportConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            BugReportConfig cfg = new BugReportConfig();
            cfg.includeLog = ConfigJson.getBool(obj, "includeLog", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new BugReportConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("includeLog", includeLog);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isIncludeLog() {
        return includeLog;
    }

    public void setIncludeLog(boolean includeLog) {
        this.includeLog = includeLog;
    }
}
