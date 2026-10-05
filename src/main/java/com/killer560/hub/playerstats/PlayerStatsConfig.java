package com.killer560.hub.playerstats;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Stat Bars settings - see {@link PlayerStatsFeature}'s class doc for the real Odin-ported
 *  action-bar parsing this is built on. Ships disabled by default, same as every other new feature in
 *  this mod.
 *  <p>
 *  killer560 (2026-09-21): "For the player stats hud rename it to stat bars... it should hide the other
 *  bars. Allow for me to hide hunger, the armor bar, and the hearts. Add a toggle to unhide hearts in the
 *  rift." The five {@code hideVanilla*}/{@code showHeartsInRift} fields below back that - see
 *  {@link PlayerStatsFeature#registerVanillaSuppression()} for the actual hide mechanism.
 *  <p>
 *  <b>Persistence keys that must never change</b> (renaming "Player Stats" to "Stat Bars" only touches
 *  display strings): the JSON file name {@code killer560smod-playerstats.json}, and every key below. */
public final class PlayerStatsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-playerstats.json");

    private static PlayerStatsConfig instance;

    private boolean enabled = false;
    private boolean showHealth = true;
    private boolean showMana = true;
    private boolean showDefense = true;
    // --- Stat Bars: hide the vanilla HUD bars underneath ours (killer560, 2026-09-21) ---
    private boolean hideVanillaHearts = true;
    private boolean hideVanillaHunger = true;
    private boolean hideVanillaArmour = true;
    private boolean hideVanillaAir = true;
    /** Un-hides the vanilla heart bar while in The Rift, where hearts mean something different. */
    private boolean showHeartsInRift = true;
    // --- Real bug found and fixed (2026-09-27), killer560: "it didn't create the bars ... I should have
    // an option to hide or show the text and the bar when the bars are working as well." The HUD element
    // only ever drew text (see PlayerStatsFeature.StatsHudElement) - these two independently control
    // whether that text line and the new proportional bars each draw. Both default ON so, once fixed,
    // the feature actually looks like "Stat Bars" out of the box for anyone who already had it enabled. ---
    private boolean showText = true;
    private boolean showBar = true;
    // --- 2026-10-04, killer560: "make custom health, intel, vitality, defence, true defence, and other such
    // bars ... Also add an option to hide the text Hypixel has like 3000/3000 with the heart symbol ... Also
    // add an option to hide the enchanting bar and its level. But make options for custom text, custom bars
    // and whatnot all scalable." Each custom bar/text is its own HUD element (StatElements); its scale and
    // position live in HudConfig like every other element, its on/off and colour live here. ---
    /** Strips Hypixel's own stat segments (health, defence, mana, overflow mana, the extra n/n resource) from
     *  the action bar. Independent of {@link #enabled}. A file written before this key existed takes the old
     *  behaviour, where the stat line was hidden exactly when Stat Bars was on - see {@link #load()}. */
    private boolean hideHypixelStatText = false;
    /** Hides the vanilla experience bar and the level number above it. Independent of {@link #enabled}. */
    private boolean hideXpBar = false;
    /** Per-element on/off, keyed by {@link StatElements.Readout#key}. Absent = off. */
    private final java.util.Map<String, Boolean> readoutOn = new java.util.HashMap<>();
    /** Per-element ARGB colour, keyed by {@link StatElements.Readout#key}. Absent = the readout's default. */
    private final java.util.Map<String, Integer> readoutColor = new java.util.HashMap<>();
    public static final int MIN_BAR_WIDTH = 40;
    public static final int MAX_BAR_WIDTH = 300;
    public static final int MIN_BAR_HEIGHT = 2;
    public static final int MAX_BAR_HEIGHT = 20;
    public static final int DEFAULT_ABSORPTION_COLOR = 0xFFFFAA00;
    public static final int DEFAULT_BAR_BACKGROUND = 0xAA000000;
    private int barWidth = 100;
    private int barHeight = 8;
    /** Draws the number (e.g. "3,423/3,423") centred on each custom bar. */
    private boolean barShowValue = true;
    /** Colour of the part of the health bar past max health (absorption / overflow health). */
    private int absorptionColor = DEFAULT_ABSORPTION_COLOR;
    private int barBackground = DEFAULT_BAR_BACKGROUND;
    private boolean textShadow = true;

    private PlayerStatsConfig() {
    }

    public static PlayerStatsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new PlayerStatsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            PlayerStatsConfig cfg = new PlayerStatsConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showHealth = ConfigJson.getBool(obj, "showHealth", true);
            cfg.showMana = ConfigJson.getBool(obj, "showMana", true);
            cfg.showDefense = ConfigJson.getBool(obj, "showDefense", true);
            cfg.hideVanillaHearts = ConfigJson.getBool(obj, "hideVanillaHearts", true);
            cfg.hideVanillaHunger = ConfigJson.getBool(obj, "hideVanillaHunger", true);
            cfg.hideVanillaArmour = ConfigJson.getBool(obj, "hideVanillaArmour", true);
            cfg.hideVanillaAir = ConfigJson.getBool(obj, "hideVanillaAir", true);
            cfg.showHeartsInRift = ConfigJson.getBool(obj, "showHeartsInRift", true);
            cfg.showText = ConfigJson.getBool(obj, "showText", true);
            cfg.showBar = ConfigJson.getBool(obj, "showBar", true);
            // Before 2026-10-04 the stat line was hidden whenever Stat Bars was on, so an old file keeps that.
            cfg.hideHypixelStatText = ConfigJson.getBool(obj, "hideHypixelStatText", cfg.enabled);
            cfg.hideXpBar = ConfigJson.getBool(obj, "hideXpBar", false);
            if (obj.has("readouts") && obj.get("readouts").isJsonObject()) {
                for (var e : obj.getAsJsonObject("readouts").entrySet()) {
                    if (!e.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject r = e.getValue().getAsJsonObject();
                    if (r.has("on")) {
                        cfg.readoutOn.put(e.getKey(), r.get("on").getAsBoolean());
                    }
                    if (r.has("color")) {
                        cfg.readoutColor.put(e.getKey(), r.get("color").getAsInt());
                    }
                }
            }
            cfg.barWidth = clamp(obj.has("barWidth") ? obj.get("barWidth").getAsInt() : cfg.barWidth,
                    MIN_BAR_WIDTH, MAX_BAR_WIDTH);
            cfg.barHeight = clamp(obj.has("barHeight") ? obj.get("barHeight").getAsInt() : cfg.barHeight,
                    MIN_BAR_HEIGHT, MAX_BAR_HEIGHT);
            cfg.barShowValue = ConfigJson.getBool(obj, "barShowValue", cfg.barShowValue);
            cfg.absorptionColor = obj.has("absorptionColor") ? obj.get("absorptionColor").getAsInt() : cfg.absorptionColor;
            cfg.barBackground = obj.has("barBackground") ? obj.get("barBackground").getAsInt() : cfg.barBackground;
            cfg.textShadow = ConfigJson.getBool(obj, "textShadow", cfg.textShadow);
            instance = cfg;
        } catch (Exception e) {
            instance = new PlayerStatsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showHealth", showHealth);
            obj.addProperty("showMana", showMana);
            obj.addProperty("showDefense", showDefense);
            obj.addProperty("hideVanillaHearts", hideVanillaHearts);
            obj.addProperty("hideVanillaHunger", hideVanillaHunger);
            obj.addProperty("hideVanillaArmour", hideVanillaArmour);
            obj.addProperty("hideVanillaAir", hideVanillaAir);
            obj.addProperty("showHeartsInRift", showHeartsInRift);
            obj.addProperty("showText", showText);
            obj.addProperty("showBar", showBar);
            obj.addProperty("hideHypixelStatText", hideHypixelStatText);
            obj.addProperty("hideXpBar", hideXpBar);
            JsonObject readouts = new JsonObject();
            for (StatElements.Readout r : StatElements.Readout.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("on", isReadoutOn(r));
                o.addProperty("color", getReadoutColor(r));
                readouts.add(r.key, o);
            }
            obj.add("readouts", readouts);
            obj.addProperty("barWidth", barWidth);
            obj.addProperty("barHeight", barHeight);
            obj.addProperty("barShowValue", barShowValue);
            obj.addProperty("absorptionColor", absorptionColor);
            obj.addProperty("barBackground", barBackground);
            obj.addProperty("textShadow", textShadow);
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

    public boolean isShowHealth() {
        return showHealth;
    }

    public void setShowHealth(boolean showHealth) {
        this.showHealth = showHealth;
    }

    public boolean isShowMana() {
        return showMana;
    }

    public void setShowMana(boolean showMana) {
        this.showMana = showMana;
    }

    public boolean isShowDefense() {
        return showDefense;
    }

    public void setShowDefense(boolean showDefense) {
        this.showDefense = showDefense;
    }

    public boolean isHideVanillaHearts() {
        return hideVanillaHearts;
    }

    public void setHideVanillaHearts(boolean value) {
        this.hideVanillaHearts = value;
    }

    public boolean isHideVanillaHunger() {
        return hideVanillaHunger;
    }

    public void setHideVanillaHunger(boolean value) {
        this.hideVanillaHunger = value;
    }

    public boolean isHideVanillaArmour() {
        return hideVanillaArmour;
    }

    public void setHideVanillaArmour(boolean value) {
        this.hideVanillaArmour = value;
    }

    public boolean isHideVanillaAir() {
        return hideVanillaAir;
    }

    public void setHideVanillaAir(boolean value) {
        this.hideVanillaAir = value;
    }

    /** Whether the vanilla heart bar stays visible in The Rift even while {@link #isHideVanillaHearts()}. */
    public boolean isShowHeartsInRift() {
        return showHeartsInRift;
    }

    public void setShowHeartsInRift(boolean value) {
        this.showHeartsInRift = value;
    }

    /** Whether the "HP: x/y  MP: x/y  DEF: x" text line draws. */
    public boolean isShowText() {
        return showText;
    }

    public void setShowText(boolean value) {
        this.showText = value;
    }

    /** Whether the proportional health/mana bars draw. */
    public boolean isShowBar() {
        return showBar;
    }

    public void setShowBar(boolean value) {
        this.showBar = value;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Raw master toggle, without the Skyblock gate - for the settings screen. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public boolean isHideHypixelStatText() {
        return hideHypixelStatText;
    }

    public void setHideHypixelStatText(boolean value) {
        this.hideHypixelStatText = value;
    }

    public boolean isHideXpBar() {
        return hideXpBar;
    }

    public void setHideXpBar(boolean value) {
        this.hideXpBar = value;
    }

    public boolean isReadoutOn(StatElements.Readout r) {
        return readoutOn.getOrDefault(r.key, Boolean.FALSE);
    }

    public void setReadoutOn(StatElements.Readout r, boolean on) {
        readoutOn.put(r.key, on);
    }

    public int getReadoutColor(StatElements.Readout r) {
        Integer c = readoutColor.get(r.key);
        return c != null ? c : r.defaultColor;
    }

    public void setReadoutColor(StatElements.Readout r, int argb) {
        readoutColor.put(r.key, argb);
    }

    public int getBarWidth() {
        return barWidth;
    }

    public void setBarWidth(int v) {
        this.barWidth = clamp(v, MIN_BAR_WIDTH, MAX_BAR_WIDTH);
    }

    public int getBarHeight() {
        return barHeight;
    }

    public void setBarHeight(int v) {
        this.barHeight = clamp(v, MIN_BAR_HEIGHT, MAX_BAR_HEIGHT);
    }

    public boolean isBarShowValue() {
        return barShowValue;
    }

    public void setBarShowValue(boolean v) {
        this.barShowValue = v;
    }

    public int getAbsorptionColor() {
        return absorptionColor;
    }

    public void setAbsorptionColor(int argb) {
        this.absorptionColor = argb;
    }

    public int getBarBackground() {
        return barBackground;
    }

    public void setBarBackground(int argb) {
        this.barBackground = argb;
    }

    public boolean isTextShadow() {
        return textShadow;
    }

    public void setTextShadow(boolean v) {
        this.textShadow = v;
    }
}
