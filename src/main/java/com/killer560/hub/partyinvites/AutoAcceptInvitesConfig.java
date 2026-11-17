package com.killer560.hub.partyinvites;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Auto Accept Invites' two settings: the switch (off by default) and its mode (All Invites by default). */
public final class AutoAcceptInvitesConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-partyinvites.json");

    private static AutoAcceptInvitesConfig instance;

    private boolean enabled = false;
    private AutoAcceptRule.Mode mode = AutoAcceptRule.Mode.ALL_INVITES;

    private AutoAcceptInvitesConfig() {
    }

    public static AutoAcceptInvitesConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        AutoAcceptInvitesConfig cfg = new AutoAcceptInvitesConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.mode = ConfigJson.getEnum(obj, "mode", AutoAcceptRule.Mode.class, AutoAcceptRule.Mode.ALL_INVITES);
            } catch (Exception ignored) {
                // a broken file loads the defaults
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("mode", mode.name());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Gated like every other feature getter - off outside Skyblock while "Skyblock Only" is on. */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    /** The real saved value, for the settings screen. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public AutoAcceptRule.Mode getMode() {
        return mode;
    }

    public void setMode(AutoAcceptRule.Mode mode) {
        this.mode = mode == null ? AutoAcceptRule.Mode.ALL_INVITES : mode;
    }
}
