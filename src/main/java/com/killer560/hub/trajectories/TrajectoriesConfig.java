package com.killer560.hub.trajectories;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Trajectories settings - see {@link TrajectoriesFeature}'s class doc for the real
 *  QUOI-ported projectile simulation this is built on. Ships disabled by default, same as every other
 *  new feature in this mod. */
public final class TrajectoriesConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-trajectories.json");

    private static TrajectoriesConfig instance;

    private boolean enabled = false;
    private boolean showBows = false;
    private boolean showPearls = true;
    private int range = 30;

    private TrajectoriesConfig() {
    }

    public static TrajectoriesConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new TrajectoriesConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            TrajectoriesConfig cfg = new TrajectoriesConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.showBows = obj.has("showBows") && obj.get("showBows").getAsBoolean();
            cfg.showPearls = !obj.has("showPearls") || obj.get("showPearls").getAsBoolean();
            cfg.range = obj.has("range") ? obj.get("range").getAsInt() : 30;
            instance = cfg;
        } catch (Exception e) {
            instance = new TrajectoriesConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showBows", showBows);
            obj.addProperty("showPearls", showPearls);
            obj.addProperty("range", range);
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

    public boolean isShowBows() {
        return showBows;
    }

    public void setShowBows(boolean showBows) {
        this.showBows = showBows;
    }

    public boolean isShowPearls() {
        return showPearls;
    }

    public void setShowPearls(boolean showPearls) {
        this.showPearls = showPearls;
    }

    public int getRange() {
        return range;
    }

    public void setRange(int range) {
        this.range = Math.max(5, Math.min(120, range));
    }
}
