package com.killer560.hub.pricetooltip;

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

/**
 * Persisted Price Tooltip settings - see {@link PriceTooltipFeature}. Ships OFF.
 *
 * <p>The first load with no file of its own takes its on/off from the Item Browser's old "Show Item Value (Tooltip)"
 * switch (removed 2026-10-15, this feature replaces it), so anyone who had that on keeps price lines.
 */
public final class PriceTooltipConfig {

    /** When a stack's prices are multiplied by its count, or the Est. Item Value breakdown is listed. */
    public enum When {
        SHIFT("Hold Shift"), ALWAYS("Always"), NEVER("Never");

        public final String label;

        When(String label) {
            this.label = label;
        }
    }

    /** How Est. Item Value weighs what was applied to an item. */
    public enum ValueMode {
        /** SkyHelper-style application worth (a Recombobulator counts 80%, an Art of War 60%...), as Skyblocker shows it. */
        NETWORTH("Networth"),
        /** Everything at its full market price, as SkyHanni's Estimated Item Value shows it. */
        FULL("Full Price");

        public final String label;

        ValueMode(String label) {
            this.label = label;
        }
    }

    public enum NumberFormat {
        /** 5,900,000.0 - the format the other mods use. */
        EXACT("Exact"),
        /** 5.9M */
        SHORT("Short");

        public final String label;

        NumberFormat(String label) {
            this.label = label;
        }
    }

    public static final int MIN_REFRESH_MINUTES = 1;
    public static final int MAX_REFRESH_MINUTES = 30;
    public static final int[] AVERAGE_DAYS = {1, 3};

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-pricetooltip.json");

    private static PriceTooltipConfig instance;

    private boolean enabled = false;
    private boolean npcPrice = true;
    private boolean lowestBin = true;
    private boolean averageBin = true;
    private int averageDays = 3;
    private boolean bazaar = true;
    private boolean estimatedValue = true;
    private boolean obtained = true;
    private boolean museum = true;
    private When stackPrice = When.SHIFT;
    private When breakdown = When.SHIFT;
    private ValueMode valueMode = ValueMode.NETWORTH;
    private NumberFormat numberFormat = NumberFormat.EXACT;
    private int refreshMinutes = 5;

    private PriceTooltipConfig() {
    }

    public static PriceTooltipConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        PriceTooltipConfig cfg = new PriceTooltipConfig();
        if (!Files.exists(CONFIG_PATH)) {
            try {
                cfg.enabled = com.killer560.hub.itembrowser.ItemBrowserConfig.getInstance().isShowItemValue();
            } catch (Throwable ignored) {
                // Defaults stand.
            }
            instance = cfg;
            PriceTooltipFeature.invalidate();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            // Per-key reads (util/ConfigJson): one bad value must not reset the whole file to defaults.
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.npcPrice = ConfigJson.getBool(obj, "npcPrice", true);
            cfg.lowestBin = ConfigJson.getBool(obj, "lowestBin", true);
            cfg.averageBin = ConfigJson.getBool(obj, "averageBin", true);
            cfg.averageDays = clampDays(ConfigJson.getInt(obj, "averageDays", 3));
            cfg.bazaar = ConfigJson.getBool(obj, "bazaar", true);
            cfg.estimatedValue = ConfigJson.getBool(obj, "estimatedValue", true);
            cfg.obtained = ConfigJson.getBool(obj, "obtained", true);
            cfg.museum = ConfigJson.getBool(obj, "museum", true);
            cfg.stackPrice = ConfigJson.getEnum(obj, "stackPrice", When.class, When.SHIFT);
            cfg.breakdown = ConfigJson.getEnum(obj, "breakdown", When.class, When.SHIFT);
            cfg.valueMode = ConfigJson.getEnum(obj, "valueMode", ValueMode.class, ValueMode.NETWORTH);
            cfg.numberFormat = ConfigJson.getEnum(obj, "numberFormat", NumberFormat.class, NumberFormat.EXACT);
            cfg.refreshMinutes = clampMinutes(ConfigJson.getInt(obj, "refreshMinutes", 5));
        } catch (Exception e) {
            cfg = new PriceTooltipConfig();
        }
        instance = cfg;
        PriceTooltipFeature.invalidate();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("npcPrice", npcPrice);
            obj.addProperty("lowestBin", lowestBin);
            obj.addProperty("averageBin", averageBin);
            obj.addProperty("averageDays", averageDays);
            obj.addProperty("bazaar", bazaar);
            obj.addProperty("estimatedValue", estimatedValue);
            obj.addProperty("obtained", obtained);
            obj.addProperty("museum", museum);
            obj.addProperty("stackPrice", stackPrice.name());
            obj.addProperty("breakdown", breakdown.name());
            obj.addProperty("valueMode", valueMode.name());
            obj.addProperty("numberFormat", numberFormat.name());
            obj.addProperty("refreshMinutes", refreshMinutes);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        PriceTooltipFeature.invalidate();
    }

    private static int clampMinutes(int v) {
        return Math.max(MIN_REFRESH_MINUTES, Math.min(MAX_REFRESH_MINUTES, v));
    }

    private static int clampDays(int v) {
        return v <= 1 ? 1 : 3;
    }

    /** The switch as saved, for the settings menu. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    /** On, and allowed here by Skyblock Only. */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isNpcPrice() {
        return npcPrice;
    }

    public void setNpcPrice(boolean v) {
        npcPrice = v;
    }

    public boolean isLowestBin() {
        return lowestBin;
    }

    public void setLowestBin(boolean v) {
        lowestBin = v;
    }

    public boolean isAverageBin() {
        return averageBin;
    }

    public void setAverageBin(boolean v) {
        averageBin = v;
    }

    public int getAverageDays() {
        return averageDays;
    }

    public void setAverageDays(int v) {
        averageDays = clampDays(v);
    }

    public boolean isBazaar() {
        return bazaar;
    }

    public void setBazaar(boolean v) {
        bazaar = v;
    }

    public boolean isEstimatedValue() {
        return estimatedValue;
    }

    public void setEstimatedValue(boolean v) {
        estimatedValue = v;
    }

    public boolean isObtained() {
        return obtained;
    }

    public void setObtained(boolean v) {
        obtained = v;
    }

    public boolean isMuseum() {
        return museum;
    }

    public void setMuseum(boolean v) {
        museum = v;
    }

    public When getStackPrice() {
        return stackPrice;
    }

    public void setStackPrice(When v) {
        stackPrice = v == null ? When.SHIFT : v;
    }

    public When getBreakdown() {
        return breakdown;
    }

    public void setBreakdown(When v) {
        breakdown = v == null ? When.SHIFT : v;
    }

    public ValueMode getValueMode() {
        return valueMode;
    }

    public void setValueMode(ValueMode v) {
        valueMode = v == null ? ValueMode.NETWORTH : v;
    }

    public NumberFormat getNumberFormat() {
        return numberFormat;
    }

    public void setNumberFormat(NumberFormat v) {
        numberFormat = v == null ? NumberFormat.EXACT : v;
    }

    public int getRefreshMinutes() {
        return refreshMinutes;
    }

    public void setRefreshMinutes(int v) {
        refreshMinutes = clampMinutes(v);
    }
}
