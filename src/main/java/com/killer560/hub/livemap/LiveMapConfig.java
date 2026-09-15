package com.killer560.hub.livemap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Live Map settings - see {@link LiveMapFeature}. Ships disabled by default. */
public final class LiveMapConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-livemap.json");

    private static LiveMapConfig instance;

    private boolean enabled = false;
    private boolean showTeammates = true;
    private boolean classRecolorTeammates = true;
    private int cellSize = 8;
    /** 0 Off, 1 Checkmarks, 2 Secrets, 3 Room Name, 4 Room Name + Secrets - NoammAddons' Checkmark Style. */
    private int roomLabels = 1;

    private LiveMapConfig() {
    }

    public static LiveMapConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new LiveMapConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            LiveMapConfig cfg = new LiveMapConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.showTeammates = !obj.has("showTeammates") || obj.get("showTeammates").getAsBoolean();
            cfg.classRecolorTeammates = !obj.has("classRecolorTeammates") || obj.get("classRecolorTeammates").getAsBoolean();
            cfg.cellSize = obj.has("cellSize") ? obj.get("cellSize").getAsInt() : 8;
            cfg.setRoomLabels(obj.has("roomLabels") ? obj.get("roomLabels").getAsInt() : 1);
            instance = cfg;
        } catch (Exception e) {
            instance = new LiveMapConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showTeammates", showTeammates);
            obj.addProperty("classRecolorTeammates", classRecolorTeammates);
            obj.addProperty("cellSize", cellSize);
            obj.addProperty("roomLabels", roomLabels);
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

    public boolean isShowTeammates() {
        return showTeammates;
    }

    public void setShowTeammates(boolean showTeammates) {
        this.showTeammates = showTeammates;
    }

    public boolean isClassRecolorTeammates() {
        return classRecolorTeammates;
    }

    public void setClassRecolorTeammates(boolean classRecolorTeammates) {
        this.classRecolorTeammates = classRecolorTeammates;
    }

    public int getCellSize() {
        return cellSize;
    }

    public void setCellSize(int cellSize) {
        this.cellSize = Math.max(4, Math.min(16, cellSize));
    }

    public static final String[] ROOM_LABEL_NAMES = {"Off", "Checkmarks", "Secrets", "Room Name", "Room Name + Secrets"};

    public int getRoomLabels() {
        return roomLabels;
    }

    public void setRoomLabels(int roomLabels) {
        this.roomLabels = roomLabels < 0 || roomLabels >= ROOM_LABEL_NAMES.length ? 1 : roomLabels;
    }
}
