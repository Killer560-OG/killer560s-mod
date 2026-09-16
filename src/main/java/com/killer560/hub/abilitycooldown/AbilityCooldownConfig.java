package com.killer560.hub.abilitycooldown;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Persisted Automatic Ability Cooldowns settings ({@code killer560smod-abilitycooldown.json}).
 * <p>
 * Ships OFF, like every other new feature. Reads go through {@link ConfigJson} per key so one malformed
 * value can never wipe the rest of the file (2026-09-15 persistence audit), and every GUI change calls
 * {@link #save()} at the call site (killer560's settings-persistence rule).
 * <p>
 * The per-ability on/off switches live in a nested {@code abilities} object keyed by
 * {@link ItemAbility#configKey()}, so adding, removing or renaming an entry in {@link ItemAbility} can
 * never shift anyone's saved toggles.
 */
public final class AbilityCooldownConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-abilitycooldown");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-abilitycooldown.json");

    /** SkyHanni's own window: a heard ability sound only starts YOUR timer if YOU clicked an item within
     *  this long, so another player's Hyperion next to you can't start your cooldown
     *  ({@code ItemAbilityCooldown.sound()}: {@code if (ping < 400.milliseconds) activate()}). */
    public static final int MIN_CLICK_WINDOW_MS = 100;
    public static final int MAX_CLICK_WINDOW_MS = 1000;
    public static final int MIN_MAGE_LEVEL = 0;
    public static final int MAX_MAGE_LEVEL = 50;

    private static AbilityCooldownConfig instance;

    // --- master ---
    private boolean enabled = false;

    // --- display ---
    private boolean dungeonOnly = true;
    private boolean textOnly = false;
    private boolean showWhenReady = false;
    private boolean colorByRemaining = true;
    private int maxLines = 6;

    // --- detection ---
    private boolean soundDetection = true;
    private boolean actionBarDetection = true;
    private int clickWindowMs = 400;

    // --- mage cooldown reduction (SkyHanni's formula, see AbilityCooldownState#multiplier) ---
    private boolean mageReduction = false;
    private boolean mageUniqueClass = true;
    private int mageClassLevel = 50;

    private final Map<ItemAbility, Boolean> abilityEnabled = new EnumMap<>(ItemAbility.class);

    private AbilityCooldownConfig() {
        for (ItemAbility a : ItemAbility.values()) {
            abilityEnabled.put(a, true);
        }
    }

    public static AbilityCooldownConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        AbilityCooldownConfig cfg = new AbilityCooldownConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(root, "enabled", cfg.enabled);
                cfg.dungeonOnly = ConfigJson.getBool(root, "dungeonOnly", cfg.dungeonOnly);
                cfg.textOnly = ConfigJson.getBool(root, "textOnly", cfg.textOnly);
                cfg.showWhenReady = ConfigJson.getBool(root, "showWhenReady", cfg.showWhenReady);
                cfg.colorByRemaining = ConfigJson.getBool(root, "colorByRemaining", cfg.colorByRemaining);
                cfg.maxLines = clampLines(ConfigJson.getInt(root, "maxLines", cfg.maxLines));
                cfg.soundDetection = ConfigJson.getBool(root, "soundDetection", cfg.soundDetection);
                cfg.actionBarDetection = ConfigJson.getBool(root, "actionBarDetection", cfg.actionBarDetection);
                cfg.clickWindowMs = clampClickWindow(ConfigJson.getInt(root, "clickWindowMs", cfg.clickWindowMs));
                cfg.mageReduction = ConfigJson.getBool(root, "mageReduction", cfg.mageReduction);
                cfg.mageUniqueClass = ConfigJson.getBool(root, "mageUniqueClass", cfg.mageUniqueClass);
                cfg.mageClassLevel = clampMageLevel(ConfigJson.getInt(root, "mageClassLevel", cfg.mageClassLevel));
                JsonObject abilities = ConfigJson.getObject(root, "abilities");
                if (abilities != null) {
                    for (ItemAbility a : ItemAbility.values()) {
                        cfg.abilityEnabled.put(a, ConfigJson.getBool(abilities, a.configKey(), true));
                    }
                }
            } catch (Exception e) {
                // Same rule as PosmsgConfig/AbilityTimersConfig: a genuine parse failure leaves the broken
                // file on disk untouched instead of instantly overwriting it with blank defaults.
                LOGGER.warn("[AbilityCooldown] Couldn't read {} - keeping the file, using defaults this session: {}",
                        CONFIG_PATH.getFileName(), e.toString());
                instance = new AbilityCooldownConfig();
                return;
            }
        }
        instance = cfg;
    }

    private static int clampLines(int v) {
        return Math.max(1, Math.min(12, v));
    }

    private static int clampClickWindow(int v) {
        return Math.max(MIN_CLICK_WINDOW_MS, Math.min(MAX_CLICK_WINDOW_MS, v));
    }

    private static int clampMageLevel(int v) {
        return Math.max(MIN_MAGE_LEVEL, Math.min(MAX_MAGE_LEVEL, v));
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("dungeonOnly", dungeonOnly);
            root.addProperty("textOnly", textOnly);
            root.addProperty("showWhenReady", showWhenReady);
            root.addProperty("colorByRemaining", colorByRemaining);
            root.addProperty("maxLines", maxLines);
            root.addProperty("soundDetection", soundDetection);
            root.addProperty("actionBarDetection", actionBarDetection);
            root.addProperty("clickWindowMs", clickWindowMs);
            root.addProperty("mageReduction", mageReduction);
            root.addProperty("mageUniqueClass", mageUniqueClass);
            root.addProperty("mageClassLevel", mageClassLevel);
            JsonObject abilities = new JsonObject();
            for (ItemAbility a : ItemAbility.values()) {
                abilities.addProperty(a.configKey(), isAbilityEnabled(a));
            }
            root.add("abilities", abilities);
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[AbilityCooldown] Couldn't save {}: {}", CONFIG_PATH.getFileName(), e.toString());
        }
    }

    /** Skyblock-gated: an item-ability cooldown table is Skyblock-specific, so "Skyblock Only" turns it off
     *  everywhere that isn't Hypixel Skyblock / p3sim. */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    /** The saved value with no gate applied - for the settings GUI. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isDungeonOnly() {
        return dungeonOnly;
    }

    public void setDungeonOnly(boolean v) {
        dungeonOnly = v;
    }

    public boolean isTextOnly() {
        return textOnly;
    }

    public void setTextOnly(boolean v) {
        textOnly = v;
    }

    public boolean isShowWhenReady() {
        return showWhenReady;
    }

    public void setShowWhenReady(boolean v) {
        showWhenReady = v;
    }

    public boolean isColorByRemaining() {
        return colorByRemaining;
    }

    public void setColorByRemaining(boolean v) {
        colorByRemaining = v;
    }

    public int getMaxLines() {
        return maxLines;
    }

    public void setMaxLines(int v) {
        maxLines = clampLines(v);
    }

    public boolean isSoundDetection() {
        return soundDetection;
    }

    public void setSoundDetection(boolean v) {
        soundDetection = v;
    }

    public boolean isActionBarDetection() {
        return actionBarDetection;
    }

    public void setActionBarDetection(boolean v) {
        actionBarDetection = v;
    }

    public int getClickWindowMs() {
        return clickWindowMs;
    }

    public void setClickWindowMs(int v) {
        clickWindowMs = clampClickWindow(v);
    }

    public boolean isMageReduction() {
        return mageReduction;
    }

    public void setMageReduction(boolean v) {
        mageReduction = v;
    }

    public boolean isMageUniqueClass() {
        return mageUniqueClass;
    }

    public void setMageUniqueClass(boolean v) {
        mageUniqueClass = v;
    }

    public int getMageClassLevel() {
        return mageClassLevel;
    }

    public void setMageClassLevel(int v) {
        mageClassLevel = clampMageLevel(v);
    }

    public boolean isAbilityEnabled(ItemAbility ability) {
        Boolean v = abilityEnabled.get(ability);
        return v == null || v;
    }

    public void setAbilityEnabled(ItemAbility ability, boolean v) {
        abilityEnabled.put(ability, v);
    }

    /** Whether this ability should be timed and drawn at all, given the master, the per-ability switch and
     *  the "Dungeon Abilities Only" filter. */
    public boolean shows(ItemAbility ability) {
        return isAbilityEnabled(ability) && (!dungeonOnly || ability.isDungeon());
    }
}
