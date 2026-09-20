package com.killer560.hub.enchantcolors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "Enchant Colours" settings - see {@link EnchantColorsFeature}. Ships disabled by default.
 *  <p>
 *  Gated on {@link SkyblockGate} because this reads Hypixel's own lore text and Hypixel's
 *  {@code ExtraAttributes} NBT: outside Skyblock there is nothing for it to match, so it should follow
 *  "Skyblock Only" like every other lore-parsing feature. (Scrollable Tooltips deliberately is not - see
 *  {@code TooltipScrollConfig}.)
 *  <p>
 *  2026-09-20 rewrite (killer560: "should follow skyhanni where the color is based off of tier not enchant"):
 *  the old per-enchant override table ({@code colors}/{@code onlyConfigured}/{@code defaultColor}) is gone,
 *  replaced with one colour per SkyHanni tier (Poor/Good/Great/Perfect) plus the existing Ultimate colour.
 *  An old config file's now-unused keys are simply ignored by {@link ConfigJson}'s per-key reads - no
 *  migration needed, it just reseeds the new keys with defaults. */
public final class EnchantColorsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-enchantcolors.json");

    private static EnchantColorsConfig instance;

    private boolean enabled = false;
    /** killer560: "an option to colour only configured enchantments vs recolouring all" - now "known to the
     *  tier table" rather than "has an override", since there is no per-enchant override anymore. When on,
     *  an enchant {@link EnchantColorsDefaults#TIERS} has no entry for is left exactly as Hypixel sent it. */
    private boolean onlyKnownEnchants = true;
    /** Colour for a recognised-but-untiered enchant while {@link #onlyKnownEnchants} is off. In practice this
     *  should rarely fire - {@link EnchantColorsDefaults#TIERS} is sourced from SkyHanni's own repo data - but
     *  Hypixel adding a brand new enchant before this table is updated would otherwise go uncoloured. */
    private int unknownColor = EnchantColorsDefaults.UNKNOWN;
    private int poorColor = EnchantColorsDefaults.POOR;
    private int goodColor = EnchantColorsDefaults.GOOD;
    private int greatColor = EnchantColorsDefaults.GREAT;
    private int perfectColor = EnchantColorsDefaults.PERFECT;
    /** SkyHanni's own default (boldPerfectEnchant) - kept as a toggle rather than hardcoded, same as Ultimate. */
    private boolean perfectBold = false;
    private boolean ultimateEnabled = true;
    private int ultimateColor = EnchantColorsDefaults.ULTIMATE;
    /** SkyHanni always renders ultimates bold; kept as a toggle rather than hardcoded. */
    private boolean ultimateBold = true;

    private EnchantColorsConfig() {
    }

    public static EnchantColorsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new EnchantColorsConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            EnchantColorsConfig cfg = new EnchantColorsConfig();
            // Per-key reads (util/ConfigJson): one bad value must not reset the whole file to defaults.
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.onlyKnownEnchants = ConfigJson.getBool(obj, "onlyKnownEnchants", true);
            cfg.unknownColor = ConfigJson.getInt(obj, "unknownColor", EnchantColorsDefaults.UNKNOWN);
            cfg.poorColor = ConfigJson.getInt(obj, "poorColor", EnchantColorsDefaults.POOR);
            cfg.goodColor = ConfigJson.getInt(obj, "goodColor", EnchantColorsDefaults.GOOD);
            cfg.greatColor = ConfigJson.getInt(obj, "greatColor", EnchantColorsDefaults.GREAT);
            cfg.perfectColor = ConfigJson.getInt(obj, "perfectColor", EnchantColorsDefaults.PERFECT);
            cfg.perfectBold = ConfigJson.getBool(obj, "perfectBold", false);
            cfg.ultimateEnabled = ConfigJson.getBool(obj, "ultimateEnabled", true);
            cfg.ultimateColor = ConfigJson.getInt(obj, "ultimateColor", EnchantColorsDefaults.ULTIMATE);
            cfg.ultimateBold = ConfigJson.getBool(obj, "ultimateBold", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new EnchantColorsConfig();
        }
        EnchantColorsFeature.clearCache();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("onlyKnownEnchants", onlyKnownEnchants);
            obj.addProperty("unknownColor", unknownColor);
            obj.addProperty("poorColor", poorColor);
            obj.addProperty("goodColor", goodColor);
            obj.addProperty("greatColor", greatColor);
            obj.addProperty("perfectColor", perfectColor);
            obj.addProperty("perfectBold", perfectBold);
            obj.addProperty("ultimateEnabled", ultimateEnabled);
            obj.addProperty("ultimateColor", ultimateColor);
            obj.addProperty("ultimateBold", ultimateBold);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        // Every rendered tooltip is cached by lore identity, so an edit has to invalidate it or the new
        // colour wouldn't show until you hovered something else (SkyHanni hits the same problem and solves
        // it with a "configChanged" dirty flag on every colour option).
        EnchantColorsFeature.clearCache();
    }

    /** Real value, ignoring "Skyblock Only" - for the settings screen, which must show what is actually on. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isOnlyKnownEnchants() {
        return onlyKnownEnchants;
    }

    public void setOnlyKnownEnchants(boolean onlyKnownEnchants) {
        this.onlyKnownEnchants = onlyKnownEnchants;
    }

    public int getUnknownColor() {
        return unknownColor;
    }

    public void setUnknownColor(int unknownColor) {
        this.unknownColor = unknownColor;
    }

    public int getPoorColor() {
        return poorColor;
    }

    public void setPoorColor(int poorColor) {
        this.poorColor = poorColor;
    }

    public int getGoodColor() {
        return goodColor;
    }

    public void setGoodColor(int goodColor) {
        this.goodColor = goodColor;
    }

    public int getGreatColor() {
        return greatColor;
    }

    public void setGreatColor(int greatColor) {
        this.greatColor = greatColor;
    }

    public int getPerfectColor() {
        return perfectColor;
    }

    public void setPerfectColor(int perfectColor) {
        this.perfectColor = perfectColor;
    }

    public boolean isPerfectBold() {
        return perfectBold;
    }

    public void setPerfectBold(boolean perfectBold) {
        this.perfectBold = perfectBold;
    }

    public boolean isUltimateEnabled() {
        return ultimateEnabled;
    }

    public void setUltimateEnabled(boolean ultimateEnabled) {
        this.ultimateEnabled = ultimateEnabled;
    }

    public int getUltimateColor() {
        return ultimateColor;
    }

    public void setUltimateColor(int ultimateColor) {
        this.ultimateColor = ultimateColor;
    }

    public boolean isUltimateBold() {
        return ultimateBold;
    }

    public void setUltimateBold(boolean ultimateBold) {
        this.ultimateBold = ultimateBold;
    }
}
