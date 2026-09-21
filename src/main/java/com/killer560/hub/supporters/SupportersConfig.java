package com.killer560.hub.supporters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted settings for killer560's item 8.5 ("mod-wide custom IGNs for supporters"). Exactly one real
 * setting - "Toggle Custom Cosmetics" - written to {@code killer560smod-supporters.json} on every change so
 * it survives a restart, same load/save shape as this mod's other {@code XyzConfig} classes.
 * <p>
 * <b>Ships ON by default</b> - killer560's own explicit spec for this one feature (item 8.5: "a 'toggle
 * custom cosmetics' setting on by default"), the deliberate exception to this mod's usual "new features
 * default OFF" rule. See this feature's staging notes for why.
 * <p>
 * Who is a supporter, their display name and their scale are NOT settings - they come from the relay (see
 * {@code SUPPORTERS-CONTRACT.md}) and are cached separately in {@link SupportersCache}.
 */
public final class SupportersConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-supporters.json");

    private static SupportersConfig instance;
    private static volatile int version = 0;

    private boolean customCosmeticsEnabled = true;

    private SupportersConfig() {
    }

    public static SupportersConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static int version() {
        return version;
    }

    public static void load() {
        SupportersConfig cfg = new SupportersConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.customCosmeticsEnabled = ConfigJson.getBool(obj, "customCosmeticsEnabled", true);
            } catch (Exception e) {
                cfg = new SupportersConfig();
            }
        }
        instance = cfg;
        version++;
    }

    public void save() {
        version++;
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("customCosmeticsEnabled", customCosmeticsEnabled);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isCustomCosmeticsEnabled() {
        return customCosmeticsEnabled;
    }

    public void setCustomCosmeticsEnabled(boolean value) {
        this.customCosmeticsEnabled = value;
        version++;
    }
}
