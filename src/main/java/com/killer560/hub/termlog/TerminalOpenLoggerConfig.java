package com.killer560.hub.termlog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Terminal Open Logger settings - see {@link TerminalOpenLogger}. Both default ON: killer560 (2026-10-07)
 *  "The term log should always be running once I enter boss" - it only records inside an F7/M7 boss, sends nothing,
 *  and the setting stays as an override. The per-attempt chat line is its own toggle. */
public final class TerminalOpenLoggerConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-termlog.json");

    private static TerminalOpenLoggerConfig instance;

    private boolean enabled = true;
    private boolean chatLines = true;

    private TerminalOpenLoggerConfig() {
    }

    public static TerminalOpenLoggerConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        TerminalOpenLoggerConfig cfg = new TerminalOpenLoggerConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", true);
                cfg.chatLines = ConfigJson.getBool(obj, "chatLines", true);
            } catch (Exception e) {
                cfg = new TerminalOpenLoggerConfig();
            }
        }
        instance = cfg;
        TerminalOpenLogger.onEnabledChanged(cfg.enabled);
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("chatLines", chatLines);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // A failed save keeps the in-memory value; the next change tries again.
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        TerminalOpenLogger.onEnabledChanged(enabled);
    }

    public boolean isChatLines() {
        return chatLines;
    }

    public void setChatLines(boolean chatLines) {
        this.chatLines = chatLines;
    }
}
