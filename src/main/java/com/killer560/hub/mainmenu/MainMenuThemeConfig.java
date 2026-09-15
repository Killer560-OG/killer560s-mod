package com.killer560.hub.mainmenu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "Themed Main Menu" toggle (2026-09-15). Default ON - killer560 explicitly asked for the
 *  black+orange title screen redesign; switching it off restores the vanilla panorama, grey buttons and
 *  white/yellow text. Not gated by SkyblockGate: the title screen is never "in Skyblock". */
public final class MainMenuThemeConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-mainmenu.json");

    private static volatile MainMenuThemeConfig instance;

    private boolean enabled = true;
    private boolean particles = true;

    private MainMenuThemeConfig() {
    }

    public static MainMenuThemeConfig getInstance() {
        MainMenuThemeConfig cfg = instance;
        if (cfg == null) {
            load();
            cfg = instance;
        }
        return cfg;
    }

    public static void load() {
        MainMenuThemeConfig cfg = new MainMenuThemeConfig();
        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.particles = ConfigJson.getBool(obj, "particles", cfg.particles);
            }
        } catch (Exception ignored) {
            // Unparseable file: keep defaults for this session (per-key reads above handle single bad keys).
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("particles", particles);
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

    /** Drifting ember particles on the themed background (no UI toggle yet; hand-editable). */
    public boolean isParticles() {
        return particles;
    }

    public void setParticles(boolean particles) {
        this.particles = particles;
    }
}
