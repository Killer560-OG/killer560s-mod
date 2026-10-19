package com.killer560.hub.bazaaradvisor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Bazaar Advisor settings (killer560, 2026-10-19: "a legit bazaar flipper setting that just tells someone every
 * time they open the menu what to buy and how much"). Legit: it only reads the Bazaar and prints a line in chat, it never
 * clicks anything, so it is not gated on the cheat build. Ships off, like every new feature here.
 */
public final class BazaarAdvisorConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-bazaaradvisor.json");

    private static BazaarAdvisorConfig instance;

    private boolean enabled = false;
    /** The Bazaar pick: buy at the cheapest sell offer, sell into the best buy order; ranked by profit per unit times units sold per week. */
    private boolean bazaarMode = true;
    /** The NPC pick: buy at the cheapest sell offer, sell to the NPC; ranked by profit per unit. */
    private boolean npcMode = true;

    private BazaarAdvisorConfig() {
    }

    public static BazaarAdvisorConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new BazaarAdvisorConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            BazaarAdvisorConfig cfg = new BazaarAdvisorConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.bazaarMode = ConfigJson.getBool(obj, "bazaarMode", cfg.bazaarMode);
            cfg.npcMode = ConfigJson.getBool(obj, "npcMode", cfg.npcMode);
            instance = cfg;
        } catch (Exception e) {
            instance = new BazaarAdvisorConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("bazaarMode", bazaarMode);
            obj.addProperty("npcMode", npcMode);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isBazaarMode() {
        return bazaarMode;
    }

    public void setBazaarMode(boolean bazaarMode) {
        this.bazaarMode = bazaarMode;
    }

    public boolean isNpcMode() {
        return npcMode;
    }

    public void setNpcMode(boolean npcMode) {
        this.npcMode = npcMode;
    }
}
