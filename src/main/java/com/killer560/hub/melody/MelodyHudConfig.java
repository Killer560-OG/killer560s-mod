package com.killer560.hub.melody;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.partydata.PartyDataConfig;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Team Melody settings - see {@link MelodyTrackerFeature}. Two independent switches:
 * {@link #isHudEnabled()} (the HUD element itself) and {@link #isShareProgress()} (whether OUR read-only
 * tracker's own progress gets published at all, to the relay and/or Odin).
 * <p>
 * <b>Defaults.</b> The HUD ships OFF like every other new feature in this mod (killer560's "New tab until
 * confirmed" rule) - a player has to opt in even though the underlying read-only tracker itself always runs
 * (it is harmless, never clicks, and other features may want its data). "Share My Progress" instead follows
 * {@link PartyDataConfig}'s own precedent (2026-09-21, "Share Dungeon Data With Party"): sharing dungeon
 * facts we already worked out is only useful if the party-facing half is already on, so this defaults to
 * whatever {@link PartyDataConfig#isShareEnabled()} was the first time this config file is created - computed
 * once, never re-derived later (flipping the other setting off after that is the player's own later choice).
 */
public final class MelodyHudConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-teammelody.json");

    private static MelodyHudConfig instance;

    private boolean hudEnabled = false;
    private boolean shareProgress;

    private MelodyHudConfig() {
    }

    public static MelodyHudConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        boolean defaultShare = PartyDataConfig.getInstance().isShareEnabled();
        if (!Files.exists(CONFIG_PATH)) {
            MelodyHudConfig cfg = new MelodyHudConfig();
            cfg.shareProgress = defaultShare;
            instance = cfg;
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            MelodyHudConfig cfg = new MelodyHudConfig();
            cfg.hudEnabled = ConfigJson.getBool(obj, "hudEnabled", false);
            cfg.shareProgress = ConfigJson.getBool(obj, "shareProgress", defaultShare);
            instance = cfg;
        } catch (Exception e) {
            MelodyHudConfig cfg = new MelodyHudConfig();
            cfg.shareProgress = defaultShare;
            instance = cfg;
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("hudEnabled", hudEnabled);
            obj.addProperty("shareProgress", shareProgress);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isHudEnabled() {
        return hudEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isHudEnabledRaw() {
        return hudEnabled;
    }

    public void setHudEnabled(boolean hudEnabled) {
        this.hudEnabled = hudEnabled;
    }

    /** Publish OUR own read-only tracker's progress (relay + Odin, when its own toggle/connection allow it).
     *  Never affects what we RECEIVE from teammates - that's always shown once known. */
    public boolean isShareProgress() {
        return shareProgress;
    }

    public void setShareProgress(boolean shareProgress) {
        this.shareProgress = shareProgress;
    }
}
