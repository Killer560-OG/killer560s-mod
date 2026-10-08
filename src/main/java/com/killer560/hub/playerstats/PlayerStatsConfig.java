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
    // --- Stat Bars: hide the vanilla HUD bars underneath ours (killer560, 2026-09-21) ---
    private boolean hideVanillaHearts = true;
    private boolean hideVanillaHunger = true;
    private boolean hideVanillaArmour = true;
    private boolean hideVanillaAir = true;
    /** Un-hides the vanilla heart bar while in The Rift, where hearts mean something different. */
    private boolean showHeartsInRift = true;
    // Classic Display (the one-line "HP / MP / DEF" element, HUD id "player_stats", and its five keys showHealth,
    // showMana, showDefense, showText, showBar) was removed on 2026-10-07 - killer560: "remove the classic display
    // option, that is not needed." Those keys are no longer read or written, except once by migrateClassic().
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
    /** Per-bar length and thickness set by dragging the bar's edges in the HUD editor (2026-10-07, killer560: "make it
     *  so it is draggable to resize ... the same as a normal Chrome window"), keyed by {@link StatElements.Readout#key}.
     *  Absent = the shared Bar Width / Bar Height below, which the tab's sliders set for every bar at once. */
    private final java.util.Map<String, Integer> readoutWidth = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> readoutHeight = new java.util.HashMap<>();
    public static final int MIN_BAR_WIDTH = 40;
    public static final int MAX_BAR_WIDTH = 300;
    public static final int MIN_BAR_HEIGHT = 2;
    /** 30 since 2026-10-07 (was 20): a bar showing its number is at least 14 thick, which left 20 almost no range. */
    public static final int MAX_BAR_HEIGHT = 30;
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
    /**
     * Layout: Predefined (true) or Custom (false) - killer560, 2026-10-07: "a setting that is predefined spots or fully
     * custom ... look at how Skyblocker has their snap-to-area kind of set up and use that." Predefined puts every
     * readout in one of {@link StatLayout.Area}'s areas around the hotbar, laid out by {@link StatLayout}; Custom is
     * each readout's own HUD-editor position. The two never share a value: switching back to Custom finds every
     * readout's saved position exactly as it was, because Predefined never writes one.
     * <p>
     * Default: Predefined for a fresh config, and for an existing file that has no readout on (it draws nothing
     * today, so nothing moves). An existing file that already draws a readout keeps Custom, so an update never moves
     * a bar he can see. Decided in {@link #load()} from the OLD file's keys (docs/LESSONS.md, the migration lesson).
     */
    private boolean predefinedLayout = true;
    /** Per readout: its area ({@link StatLayout.Area#key}) and its place in that area, keyed by Readout key. Absent =
     *  {@link StatLayout#defaultArea} and the readout's enum order. */
    private final java.util.Map<String, String> readoutArea = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> readoutOrder = new java.util.HashMap<>();
    /** The one own scale every readout draws at under the Predefined layout (2026-10-07, killer560: "They are different
     *  scales when you use the predefined snap"); times the global HUD scale like any own scale. Custom keeps each
     *  readout's own HUD-editor scale, untouched by this. */
    private float predefinedScale = 1.0f;
    public static final float MIN_PREDEFINED_SCALE = 0.5f;
    public static final float MAX_PREDEFINED_SCALE = 3.0f;
    /** Bumped by every change that can move or resize a readout in the Predefined layout (and by every load), so
     *  {@link StatLayout} knows its cached layout is stale. */
    private static int layoutVersion;

    private PlayerStatsConfig() {
    }

    static int layoutVersion() {
        return layoutVersion;
    }

    private static void layoutChanged() {
        layoutVersion++;
    }

    public static PlayerStatsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        layoutChanged();
        if (!Files.exists(CONFIG_PATH)) {
            instance = new PlayerStatsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            PlayerStatsConfig cfg = new PlayerStatsConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.hideVanillaHearts = ConfigJson.getBool(obj, "hideVanillaHearts", true);
            cfg.hideVanillaHunger = ConfigJson.getBool(obj, "hideVanillaHunger", true);
            cfg.hideVanillaArmour = ConfigJson.getBool(obj, "hideVanillaArmour", true);
            cfg.hideVanillaAir = ConfigJson.getBool(obj, "hideVanillaAir", true);
            cfg.showHeartsInRift = ConfigJson.getBool(obj, "showHeartsInRift", true);
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
                    int w = ConfigJson.getInt(r, "width", -1);
                    if (w > 0) {
                        cfg.readoutWidth.put(e.getKey(), clamp(w, MIN_BAR_WIDTH, MAX_BAR_WIDTH));
                    }
                    int h = ConfigJson.getInt(r, "height", -1);
                    if (h > 0) {
                        cfg.readoutHeight.put(e.getKey(), clamp(h, MIN_BAR_HEIGHT, MAX_BAR_HEIGHT));
                    }
                    if (r.has("area") && r.get("area").isJsonPrimitive()
                            && StatLayout.Area.byKey(r.get("area").getAsString()) != null) {
                        cfg.readoutArea.put(e.getKey(), r.get("area").getAsString());
                    }
                    if (r.has("order") && r.get("order").isJsonPrimitive()) {
                        cfg.readoutOrder.put(e.getKey(), ConfigJson.getInt(r, "order", 0));
                    }
                }
            }
            // Layout (2026-10-07). A file from before the setting decides from ITS OWN keys: a readout switched on
            // there is drawing at its own position today, and stays Custom so nothing he can see moves.
            boolean oldFileDrawsReadouts = false;
            if (!obj.has("layout") && obj.has("readouts") && obj.get("readouts").isJsonObject()) {
                for (var e : obj.getAsJsonObject("readouts").entrySet()) {
                    if (e.getValue().isJsonObject() && ConfigJson.getBool(e.getValue().getAsJsonObject(), "on", false)) {
                        oldFileDrawsReadouts = true;
                    }
                }
            }
            cfg.predefinedLayout = obj.has("layout")
                    ? !"custom".equals(ConfigJson.getString(obj, "layout", "predefined"))
                    : !oldFileDrawsReadouts;
            cfg.barWidth = clamp(obj.has("barWidth") ? obj.get("barWidth").getAsInt() : cfg.barWidth,
                    MIN_BAR_WIDTH, MAX_BAR_WIDTH);
            cfg.barHeight = clamp(obj.has("barHeight") ? obj.get("barHeight").getAsInt() : cfg.barHeight,
                    MIN_BAR_HEIGHT, MAX_BAR_HEIGHT);
            cfg.barShowValue = ConfigJson.getBool(obj, "barShowValue", cfg.barShowValue);
            cfg.absorptionColor = obj.has("absorptionColor") ? obj.get("absorptionColor").getAsInt() : cfg.absorptionColor;
            cfg.barBackground = obj.has("barBackground") ? obj.get("barBackground").getAsInt() : cfg.barBackground;
            cfg.textShadow = ConfigJson.getBool(obj, "textShadow", cfg.textShadow);
            cfg.predefinedScale = clampScale(obj.has("predefinedScale") && obj.get("predefinedScale").isJsonPrimitive()
                    ? obj.get("predefinedScale").getAsFloat() : cfg.predefinedScale);
            instance = cfg;
            if (cfg.migrateClassic(obj)) {
                cfg.save();
            }
        } catch (Exception e) {
            instance = new PlayerStatsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("hideVanillaHearts", hideVanillaHearts);
            obj.addProperty("hideVanillaHunger", hideVanillaHunger);
            obj.addProperty("hideVanillaArmour", hideVanillaArmour);
            obj.addProperty("hideVanillaAir", hideVanillaAir);
            obj.addProperty("showHeartsInRift", showHeartsInRift);
            obj.addProperty("hideHypixelStatText", hideHypixelStatText);
            obj.addProperty("hideXpBar", hideXpBar);
            JsonObject readouts = new JsonObject();
            for (StatElements.Readout r : StatElements.Readout.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("on", isReadoutOn(r));
                o.addProperty("color", getReadoutColor(r));
                Integer w = readoutWidth.get(r.key);
                if (w != null) {
                    o.addProperty("width", w);
                }
                Integer h = readoutHeight.get(r.key);
                if (h != null) {
                    o.addProperty("height", h);
                }
                String area = readoutArea.get(r.key);
                if (area != null) {
                    o.addProperty("area", area);
                }
                Integer order = readoutOrder.get(r.key);
                if (order != null) {
                    o.addProperty("order", order);
                }
                readouts.add(r.key, o);
            }
            obj.add("readouts", readouts);
            obj.addProperty("layout", predefinedLayout ? "predefined" : "custom");
            obj.addProperty("barWidth", barWidth);
            obj.addProperty("barHeight", barHeight);
            obj.addProperty("barShowValue", barShowValue);
            obj.addProperty("absorptionColor", absorptionColor);
            obj.addProperty("barBackground", barBackground);
            obj.addProperty("textShadow", textShadow);
            obj.addProperty("predefinedScale", predefinedScale);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        layoutChanged();
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
        layoutChanged();
    }

    public int getReadoutColor(StatElements.Readout r) {
        Integer c = readoutColor.get(r.key);
        return c != null ? c : r.defaultColor;
    }

    public void setReadoutColor(StatElements.Readout r, int argb) {
        readoutColor.put(r.key, argb);
    }

    /** The shared bar length (the tab's Bar Width slider). */
    public int getBarWidth() {
        return barWidth;
    }

    /** Sets every bar's length: the shared value, and any bar resized on its own in the HUD editor goes back to it,
     *  so the slider always does what it says. */
    public void setBarWidth(int v) {
        this.barWidth = clamp(v, MIN_BAR_WIDTH, MAX_BAR_WIDTH);
        readoutWidth.clear();
        layoutChanged();
    }

    /** The shared bar thickness (the tab's Bar Height slider). */
    public int getBarHeight() {
        return barHeight;
    }

    /** Sets every bar's thickness; see {@link #setBarWidth}. */
    public void setBarHeight(int v) {
        this.barHeight = clamp(v, MIN_BAR_HEIGHT, MAX_BAR_HEIGHT);
        readoutHeight.clear();
        layoutChanged();
    }

    /** This bar's length: its own HUD-editor size, else the shared Bar Width. */
    public int getBarWidth(StatElements.Readout r) {
        Integer w = readoutWidth.get(r.key);
        return w != null ? w : barWidth;
    }

    /** Sets one bar's length (the HUD editor's edge drag), clamped to the slider's range. */
    public void setBarWidth(StatElements.Readout r, int v) {
        readoutWidth.put(r.key, clamp(v, MIN_BAR_WIDTH, MAX_BAR_WIDTH));
        layoutChanged();
    }

    /** This bar's thickness: its own HUD-editor size, else the shared Bar Height. */
    public int getBarHeight(StatElements.Readout r) {
        Integer h = readoutHeight.get(r.key);
        return h != null ? h : barHeight;
    }

    public void setBarHeight(StatElements.Readout r, int v) {
        readoutHeight.put(r.key, clamp(v, MIN_BAR_HEIGHT, MAX_BAR_HEIGHT));
        layoutChanged();
    }

    /**
     * One-time move off Classic Display (removed 2026-10-07). It drew whenever Stat Bars was on and either of its Show
     * Text / Show Bar was (both defaulted on), so anyone who had Stat Bars on and none of the custom readouts would
     * otherwise update to an empty HUD. For exactly them, the readouts that draw what Classic Display drew are switched
     * on - its bars as the Health / Mana Bar, its text as the Health / Mana / Defence Text - and placed where Classic
     * Display was, at its scale, one under another. Anyone already using a custom readout keeps exactly what they had.
     * The file is saved straight after (by {@link #load()}), without the classic keys, so this runs once.
     *
     * @return whether the file still carried Classic Display's keys (so it is re-saved without them)
     */
    private boolean migrateClassic(JsonObject obj) {
        if (!obj.has("showText") && !obj.has("showBar")) {
            return false;
        }
        boolean showText = ConfigJson.getBool(obj, "showText", true);
        boolean showBar = ConfigJson.getBool(obj, "showBar", true);
        boolean health = ConfigJson.getBool(obj, "showHealth", true);
        boolean mana = ConfigJson.getBool(obj, "showMana", true);
        boolean defence = ConfigJson.getBool(obj, "showDefense", true);
        boolean anyReadout = false;
        for (StatElements.Readout r : StatElements.Readout.values()) {
            anyReadout |= isReadoutOn(r);
        }
        if (!enabled || anyReadout || !(showText || showBar)) {
            return true;
        }
        java.util.List<StatElements.Readout> on = new java.util.ArrayList<>();
        if (showBar && health) {
            on.add(StatElements.Readout.HEALTH_BAR);
        }
        if (showBar && mana) {
            on.add(StatElements.Readout.MANA_BAR);
        }
        if (showText && health) {
            on.add(StatElements.Readout.HEALTH_TEXT);
        }
        if (showText && mana) {
            on.add(StatElements.Readout.MANA_TEXT);
        }
        if (showText && defence) {
            on.add(StatElements.Readout.DEFENCE_TEXT);
        }
        com.killer560.hub.hud.HudConfig hud = com.killer560.hub.hud.HudConfig.getInstance();
        boolean placed = hud.hasPosition(CLASSIC_HUD_ID);
        int[] at = hud.getPosition(CLASSIC_HUD_ID, 0, 0);
        float scale = hud.getScale(CLASSIC_HUD_ID, 1.0f);
        int y = at[1];
        if (!on.isEmpty()) {
            // Classic Display drew at its own place, so its replacements do too: Custom (see predefinedLayout).
            predefinedLayout = false;
        }
        for (StatElements.Readout r : on) {
            readoutOn.put(r.key, true);
            if (placed && !hud.hasPosition(r.hudId)) {
                hud.setPosition(r.hudId, at[0], y);
                hud.setScale(r.hudId, scale);
                y += Math.round((r.bar ? Math.max(barHeight, 9) : 9) * scale) + 2;
            }
        }
        if (placed) {
            hud.save();
        }
        return true;
    }

    /** The removed Classic Display's HUD id, read only by {@link #migrateClassic}. */
    static final String CLASSIC_HUD_ID = "player_stats";

    public boolean isBarShowValue() {
        return barShowValue;
    }

    public void setBarShowValue(boolean v) {
        this.barShowValue = v;
        layoutChanged();
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

    /** Layout: true = Predefined (areas around the hotbar, {@link StatLayout}), false = Custom (own positions). */
    public boolean isPredefinedLayout() {
        return predefinedLayout;
    }

    public void setPredefinedLayout(boolean predefined) {
        this.predefinedLayout = predefined;
        layoutChanged();
    }

    /** The own scale every readout shares under the Predefined layout. */
    public float getPredefinedScale() {
        return predefinedScale;
    }

    public void setPredefinedScale(float scale) {
        this.predefinedScale = clampScale(scale);
        layoutChanged();
    }

    private static float clampScale(float v) {
        if (!Float.isFinite(v)) {
            return 1.0f;
        }
        // Two decimals, so a slider or a scroll never leaves 1.0000001 behind.
        return Math.round(Math.max(MIN_PREDEFINED_SCALE, Math.min(MAX_PREDEFINED_SCALE, v)) * 100f) / 100f;
    }

    /** The area {@code r} sits in under the Predefined layout. */
    public StatLayout.Area getArea(StatElements.Readout r) {
        String key = readoutArea.get(r.key);
        StatLayout.Area a = key == null ? null : StatLayout.Area.byKey(key);
        return a != null ? a : StatLayout.defaultArea(r);
    }

    /** Puts {@code r} at the end of {@code area}. */
    public void setArea(StatElements.Readout r, StatLayout.Area area) {
        moveTo(r, area, null);
    }

    /** {@code r}'s place in its area: lower first (left in a row, nearest the hotbar's bottom in a side column). */
    public int getOrder(StatElements.Readout r) {
        Integer o = readoutOrder.get(r.key);
        return o != null ? o : r.ordinal();
    }

    public void setOrder(StatElements.Readout r, int order) {
        readoutOrder.put(r.key, order);
        layoutChanged();
    }

    /**
     * Moves {@code r} into {@code area}, just before {@code before} (another readout already in that area), or at its
     * end when {@code before} is null or not there; every readout of the area, and of the one {@code r} left, is then
     * numbered 0, 1, 2... in that order, so the order is explicit from then on. Not saved.
     */
    public void moveTo(StatElements.Readout r, StatLayout.Area area, StatElements.Readout before) {
        StatLayout.Area from = getArea(r);
        readoutArea.put(r.key, area.key);
        java.util.List<StatElements.Readout> list = StatLayout.inOrder(this, area);
        list.remove(r);
        int at = before == null ? -1 : list.indexOf(before);
        list.add(at < 0 ? list.size() : at, r);
        for (int i = 0; i < list.size(); i++) {
            readoutOrder.put(list.get(i).key, i);
            readoutArea.put(list.get(i).key, area.key);
        }
        if (from != area) {
            java.util.List<StatElements.Readout> old = StatLayout.inOrder(this, from);
            for (int i = 0; i < old.size(); i++) {
                readoutOrder.put(old.get(i).key, i);
                readoutArea.put(old.get(i).key, from.key);
            }
        }
        layoutChanged();
    }
}
