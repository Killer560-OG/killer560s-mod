package com.killer560.hub.updatecheck;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether to say, once per launch, that a newer release exists - see {@link UpdateCheckFeature}.
 *
 * <p>On by default, which is the one place this departs from "new features ship disabled". killer560 is opening
 * the mod up beyond friends (2026-09-28), and the failure it prevents is the one that costs him the most: a
 * stranger running a months-old jar, hitting something already fixed, and reporting it. A notice nobody has
 * switched on cannot do that.
 *
 * <p>It is still ONE check, at startup, and never again that session. The class it lives beside was deliberately
 * written click-only with "no background polling, matching his explicit requirement" - that rule was about the
 * mod quietly talking to the network over and over, and a single check on launch is not that. The toggle is here
 * so anyone who disagrees can have the old behaviour back.
 */
public final class UpdateCheckConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-updatecheck.json");

    private static UpdateCheckConfig instance;

    private boolean notifyOnStart = true;

    private UpdateCheckConfig() {
    }

    public static UpdateCheckConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new UpdateCheckConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            UpdateCheckConfig cfg = new UpdateCheckConfig();
            cfg.notifyOnStart = ConfigJson.getBool(obj, "notifyOnStart", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new UpdateCheckConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("notifyOnStart", notifyOnStart);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isNotifyOnStart() {
        return notifyOnStart;
    }

    public void setNotifyOnStart(boolean v) {
        this.notifyOnStart = v;
    }
}
