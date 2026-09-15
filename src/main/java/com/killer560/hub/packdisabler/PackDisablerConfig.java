package com.killer560.hub.packdisabler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "Pack Disabler" settings - see {@link PackDisablerFeature}. Ships disabled by default. */
public final class PackDisablerConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-packdisabler.json");

    public enum Mode {
        /** Decline optional packs (Hypixel's Skyblock pack is optional); pretend-load only packs the server marks required. */
        SMART("Smart"),
        /** Always answer DECLINED (a server that requires its pack may disconnect you). */
        DECLINE("Always Decline"),
        /** Always answer ACCEPTED -> DOWNLOADED -> SUCCESSFULLY_LOADED without downloading or applying anything. */
        FAKE_LOADED("Always Pretend Loaded");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        public Mode next() {
            Mode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    private static PackDisablerConfig instance;

    private boolean enabled = false;
    private boolean hypixelOnly = true;
    private Mode mode = Mode.SMART;
    private boolean chatNotice = true;

    private PackDisablerConfig() {
    }

    public static PackDisablerConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new PackDisablerConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            PackDisablerConfig cfg = new PackDisablerConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.hypixelOnly = !obj.has("hypixelOnly") || obj.get("hypixelOnly").getAsBoolean();
            cfg.chatNotice = !obj.has("chatNotice") || obj.get("chatNotice").getAsBoolean();
            if (obj.has("mode")) {
                try {
                    cfg.mode = Mode.valueOf(obj.get("mode").getAsString());
                } catch (IllegalArgumentException ignored) {
                    cfg.mode = Mode.SMART;
                }
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new PackDisablerConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("hypixelOnly", hypixelOnly);
            obj.addProperty("mode", mode.name());
            obj.addProperty("chatNotice", chatNotice);
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

    public boolean isHypixelOnly() {
        return hypixelOnly;
    }

    public void setHypixelOnly(boolean hypixelOnly) {
        this.hypixelOnly = hypixelOnly;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.SMART : mode;
    }

    public boolean isChatNotice() {
        return chatNotice;
    }

    public void setChatNotice(boolean chatNotice) {
        this.chatNotice = chatNotice;
    }
}
