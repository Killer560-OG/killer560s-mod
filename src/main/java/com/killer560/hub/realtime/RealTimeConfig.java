package com.killer560.hub.realtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Real Time clock settings - see {@link RealTimeFeature}. Ships disabled by default. */
public final class RealTimeConfig {

    public static final int DEFAULT_TEXT_COLOR = 0xFFFFA040;

    public enum ZoneMode {
        SYSTEM, CUSTOM
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-realtime.json");

    private static RealTimeConfig instance;

    private boolean enabled = false;
    private boolean use24Hour = false;
    private boolean showSeconds = false;
    private ZoneMode zoneMode = ZoneMode.SYSTEM;
    /** Zone ID used in CUSTOM mode; blank means "not picked yet" and resolves to the system zone. */
    private String customZone = "";
    /** Region filter for the zone picker ("All", "America", "Europe", ..., "Other"). */
    private String regionFilter = RealTimeZones.ALL;
    private boolean showZoneAbbreviation = false;
    private boolean showLabel = false;
    private boolean textShadow = true;
    private int textColor = DEFAULT_TEXT_COLOR;

    private RealTimeConfig() {
    }

    public static RealTimeConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        RealTimeConfig cfg = new RealTimeConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.use24Hour = ConfigJson.getBool(obj, "use24Hour", cfg.use24Hour);
                cfg.showSeconds = ConfigJson.getBool(obj, "showSeconds", cfg.showSeconds);
                cfg.zoneMode = ConfigJson.getEnum(obj, "zoneMode", ZoneMode.class, cfg.zoneMode);
                cfg.customZone = ConfigJson.getString(obj, "customZone", cfg.customZone);
                cfg.regionFilter = ConfigJson.getString(obj, "regionFilter", cfg.regionFilter);
                cfg.showZoneAbbreviation = ConfigJson.getBool(obj, "showZoneAbbreviation", cfg.showZoneAbbreviation);
                cfg.showLabel = ConfigJson.getBool(obj, "showLabel", cfg.showLabel);
                cfg.textShadow = ConfigJson.getBool(obj, "textShadow", cfg.textShadow);
                cfg.textColor = ConfigJson.getInt(obj, "textColor", cfg.textColor);
            } catch (Exception ignored) {
                // Unparseable file: keep defaults for everything.
            }
        }
        if (cfg.customZone == null) {
            cfg.customZone = "";
        }
        if (cfg.regionFilter == null || !RealTimeZones.regions().contains(cfg.regionFilter)) {
            cfg.regionFilter = RealTimeZones.ALL;
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("use24Hour", use24Hour);
            obj.addProperty("showSeconds", showSeconds);
            obj.addProperty("zoneMode", zoneMode.name());
            obj.addProperty("customZone", customZone);
            obj.addProperty("regionFilter", regionFilter);
            obj.addProperty("showZoneAbbreviation", showZoneAbbreviation);
            obj.addProperty("showLabel", showLabel);
            obj.addProperty("textShadow", textShadow);
            obj.addProperty("textColor", textColor);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isUse24Hour() {
        return use24Hour;
    }

    public void setUse24Hour(boolean use24Hour) {
        this.use24Hour = use24Hour;
    }

    public boolean isShowSeconds() {
        return showSeconds;
    }

    public void setShowSeconds(boolean showSeconds) {
        this.showSeconds = showSeconds;
    }

    public ZoneMode getZoneMode() {
        return zoneMode;
    }

    public void setZoneMode(ZoneMode zoneMode) {
        this.zoneMode = zoneMode == null ? ZoneMode.SYSTEM : zoneMode;
    }

    public String getCustomZone() {
        return customZone;
    }

    public void setCustomZone(String customZone) {
        this.customZone = customZone == null ? "" : customZone;
    }

    public String getRegionFilter() {
        return regionFilter;
    }

    public void setRegionFilter(String regionFilter) {
        this.regionFilter = regionFilter == null ? RealTimeZones.ALL : regionFilter;
    }

    public boolean isShowZoneAbbreviation() {
        return showZoneAbbreviation;
    }

    public void setShowZoneAbbreviation(boolean showZoneAbbreviation) {
        this.showZoneAbbreviation = showZoneAbbreviation;
    }

    public boolean isShowLabel() {
        return showLabel;
    }

    public void setShowLabel(boolean showLabel) {
        this.showLabel = showLabel;
    }

    public boolean isTextShadow() {
        return textShadow;
    }

    public void setTextShadow(boolean textShadow) {
        this.textShadow = textShadow;
    }

    public int getTextColor() {
        return textColor;
    }

    public void setTextColor(int textColor) {
        this.textColor = textColor;
    }
}
