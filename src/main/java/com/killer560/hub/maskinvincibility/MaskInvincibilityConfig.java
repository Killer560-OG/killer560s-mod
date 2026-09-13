package com.killer560.hub.maskinvincibility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Mask Invincibility Timer settings - see {@link MaskInvincibilityFeature}. Ships disabled
 *  by default. */
public final class MaskInvincibilityConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-maskinvincibility.json");

    private static MaskInvincibilityConfig instance;

    private boolean enabled = false;
    private boolean announceInChat = true;
    private boolean showSpirit = true;
    private boolean showBonzo = true;
    private boolean showPhoenix = true;
    private boolean autoSwapEnabled = false;

    private MaskInvincibilityConfig() {
    }

    public static MaskInvincibilityConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new MaskInvincibilityConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            MaskInvincibilityConfig cfg = new MaskInvincibilityConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.announceInChat = !obj.has("announceInChat") || obj.get("announceInChat").getAsBoolean();
            cfg.showSpirit = !obj.has("showSpirit") || obj.get("showSpirit").getAsBoolean();
            cfg.showBonzo = !obj.has("showBonzo") || obj.get("showBonzo").getAsBoolean();
            cfg.showPhoenix = !obj.has("showPhoenix") || obj.get("showPhoenix").getAsBoolean();
            cfg.autoSwapEnabled = com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED
                    && obj.has("autoSwapEnabled") && obj.get("autoSwapEnabled").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new MaskInvincibilityConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("announceInChat", announceInChat);
            obj.addProperty("showSpirit", showSpirit);
            obj.addProperty("showBonzo", showBonzo);
            obj.addProperty("showPhoenix", showPhoenix);
            obj.addProperty("autoSwapEnabled", autoSwapEnabled);
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

    public boolean isAnnounceInChat() {
        return announceInChat;
    }

    public void setAnnounceInChat(boolean announceInChat) {
        this.announceInChat = announceInChat;
    }

    public boolean isShowSpirit() {
        return showSpirit;
    }

    public void setShowSpirit(boolean showSpirit) {
        this.showSpirit = showSpirit;
    }

    public boolean isShowBonzo() {
        return showBonzo;
    }

    public void setShowBonzo(boolean showBonzo) {
        this.showBonzo = showBonzo;
    }

    public boolean isShowPhoenix() {
        return showPhoenix;
    }

    public void setShowPhoenix(boolean showPhoenix) {
        this.showPhoenix = showPhoenix;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - automatically swaps
     *  your worn mask, a real automation. */
    public boolean isAutoSwapEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoSwapEnabled;
    }

    public void setAutoSwapEnabled(boolean autoSwapEnabled) {
        this.autoSwapEnabled = autoSwapEnabled;
    }
}
