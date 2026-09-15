package com.killer560.hub.cheatutils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

/**
 * Persisted settings for every Cheat Utils feature (Wither ESP, Secret Aura, Auto GFS, Auto Ult, Auto
 * Chocolate Factory). Every feature is cheat-build only: each master getter is gated on
 * {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} (same pattern as
 * {@code SecretsConfig#isMasterEnabled} / {@code TerminalSolverConfig#isAutoTerminalsEnabled}), so a legit
 * jar can never run any of these even from a copied config file. Every master toggle defaults OFF. Every
 * setting is loaded from and saved to {@code killer560smod-cheatutils.json} so it survives a restart.
 */
public final class CheatUtilsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-cheatutils.json");
    private static final Random RANDOM = new Random();

    private static CheatUtilsConfig instance;

    // ---- Wither ESP (NoammAddons WitherESP.kt) ----
    /** Which F7/M7 boss phases the Wither ESP draws in. P3 by default (roadmap: "Wither ESP (P3 only)"). */
    public enum WitherPhaseFilter {
        ALL("P1-P4"), P1("P1 Maxor"), P2("P2 Storm"), P3("P3 Goldor"), P4("P4 Necron");

        public final String label;

        WitherPhaseFilter(String label) {
            this.label = label;
        }
    }

    public static final int DEFAULT_MAXOR_COLOR = 0xFF5804A4;   // Noamm: Color(88, 4, 164)
    public static final int DEFAULT_STORM_COLOR = 0xFF00D0FF;   // Noamm: Color(0, 208, 255)
    public static final int DEFAULT_GOLDOR_COLOR = 0xFFFFFFFF;  // Noamm: Color.WHITE
    public static final int DEFAULT_NECRON_COLOR = 0xFFFF0000;  // Noamm: Color.RED

    private boolean witherEspEnabled = false;
    private WitherPhaseFilter witherPhaseFilter = WitherPhaseFilter.P3;
    private int maxorColor = DEFAULT_MAXOR_COLOR;
    private int stormColor = DEFAULT_STORM_COLOR;
    private int goldorColor = DEFAULT_GOLDOR_COLOR;
    private int necronColor = DEFAULT_NECRON_COLOR;

    // ---- Secret Aura (QUOI SecretAura.kt) ----
    public static final double MIN_AURA_RANGE = 2.1;
    public static final double MAX_AURA_RANGE = 6.5;
    public static final double MAX_AURA_SKULL_RANGE = 4.7;
    public static final int MIN_AURA_COOLDOWN_MS = 100;
    public static final int MAX_AURA_COOLDOWN_MS = 2000;

    private boolean secretAuraEnabled = false;
    private boolean auraChests = true;
    private boolean auraLevers = true;
    private boolean auraEssence = true;
    private boolean auraBossLevers = false;
    private double auraRange = 6.2;       // QUOI default
    private double auraSkullRange = 4.7;  // QUOI default
    private int auraCooldownMs = 150;     // QUOI "Click delay" default
    private boolean auraSwing = false;    // QUOI "Swing hand" default off
    private boolean auraPauseWhileSneaking = true;
    /** Comma-separated; matched case-insensitively against the held item's Skyblock id and display name. */
    private String auraPauseHolding = "";

    // ---- Auto GFS (NoammAddons AutoGFS.kt + QUOI AutoGFS.kt) ----
    public static final int MIN_GFS_THRESHOLD_PERCENT = 5;
    public static final int MAX_GFS_THRESHOLD_PERCENT = 95;
    public static final int MIN_GFS_INTERVAL_SEC = 5;
    public static final int MAX_GFS_INTERVAL_SEC = 60;

    private boolean autoGfsEnabled = false;
    private boolean gfsPearls = false;
    private boolean gfsLeaps = false;
    private boolean gfsSuperbooms = false;
    private boolean gfsJerries = false;
    private int gfsThresholdPercent = 50; // QUOI "Amount" default
    private int gfsIntervalSec = 20;      // Noamm "Check Delay" default
    private boolean gfsSkipIfNone = true; // Noamm: `if (current == 0) return`

    // ---- Auto Ult (NoammAddons Abilities.kt, CHEAT block) ----
    private boolean autoUltEnabled = false;
    private boolean ultMaxorEnraged = true;
    private boolean ultGoldorFactory = true;
    /** "AUTO" = read own class from the tab list; otherwise a {@link com.killer560.hub.dungeonclass.DungeonClass} name. */
    private String ultClassOverride = "AUTO";

    // ---- Auto Chocolate Factory (QUOI ChocolateFactory.kt, from OdinLegacy) ----
    public static final int MIN_CF_DELAY_MS = 50;
    public static final int MAX_CF_DELAY_MS = 1500;
    public static final int MIN_CF_UPGRADE_DELAY_MS = 300;
    public static final int MAX_CF_UPGRADE_DELAY_MS = 2000;

    private boolean chocolateEnabled = false;
    private boolean cfClickCookie = true;
    private boolean cfAutoUpgrade = false;
    private boolean cfClaimStrays = false;
    private boolean cfAutoTimeTower = false;
    private int cfMinDelayMs = 150;       // QUOI "Delay" default
    private int cfMaxDelayMs = 250;
    private int cfUpgradeDelayMs = 500;   // QUOI "Upgrade delay" default

    private CheatUtilsConfig() {
    }

    public static CheatUtilsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        CheatUtilsConfig cfg = new CheatUtilsConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.witherEspEnabled = bool(o, "witherEspEnabled", false);
                cfg.witherPhaseFilter = parsePhase(str(o, "witherPhaseFilter", "P3"));
                cfg.maxorColor = integer(o, "maxorColor", DEFAULT_MAXOR_COLOR);
                cfg.stormColor = integer(o, "stormColor", DEFAULT_STORM_COLOR);
                cfg.goldorColor = integer(o, "goldorColor", DEFAULT_GOLDOR_COLOR);
                cfg.necronColor = integer(o, "necronColor", DEFAULT_NECRON_COLOR);

                cfg.secretAuraEnabled = bool(o, "secretAuraEnabled", false);
                cfg.auraChests = bool(o, "auraChests", true);
                cfg.auraLevers = bool(o, "auraLevers", true);
                cfg.auraEssence = bool(o, "auraEssence", true);
                cfg.auraBossLevers = bool(o, "auraBossLevers", false);
                cfg.setAuraRange(o.has("auraRange") ? o.get("auraRange").getAsDouble() : 6.2);
                cfg.setAuraSkullRange(o.has("auraSkullRange") ? o.get("auraSkullRange").getAsDouble() : 4.7);
                cfg.setAuraCooldownMs(integer(o, "auraCooldownMs", 150));
                cfg.auraSwing = bool(o, "auraSwing", false);
                cfg.auraPauseWhileSneaking = bool(o, "auraPauseWhileSneaking", true);
                cfg.auraPauseHolding = str(o, "auraPauseHolding", "");

                cfg.autoGfsEnabled = bool(o, "autoGfsEnabled", false);
                cfg.gfsPearls = bool(o, "gfsPearls", false);
                cfg.gfsLeaps = bool(o, "gfsLeaps", false);
                cfg.gfsSuperbooms = bool(o, "gfsSuperbooms", false);
                cfg.gfsJerries = bool(o, "gfsJerries", false);
                cfg.setGfsThresholdPercent(integer(o, "gfsThresholdPercent", 50));
                cfg.setGfsIntervalSec(integer(o, "gfsIntervalSec", 20));
                cfg.gfsSkipIfNone = bool(o, "gfsSkipIfNone", true);

                cfg.autoUltEnabled = bool(o, "autoUltEnabled", false);
                cfg.ultMaxorEnraged = bool(o, "ultMaxorEnraged", true);
                cfg.ultGoldorFactory = bool(o, "ultGoldorFactory", true);
                cfg.ultClassOverride = str(o, "ultClassOverride", "AUTO");

                cfg.chocolateEnabled = bool(o, "chocolateEnabled", false);
                cfg.cfClickCookie = bool(o, "cfClickCookie", true);
                cfg.cfAutoUpgrade = bool(o, "cfAutoUpgrade", false);
                cfg.cfClaimStrays = bool(o, "cfClaimStrays", false);
                cfg.cfAutoTimeTower = bool(o, "cfAutoTimeTower", false);
                cfg.cfMinDelayMs = clamp(integer(o, "cfMinDelayMs", 150), MIN_CF_DELAY_MS, MAX_CF_DELAY_MS);
                cfg.cfMaxDelayMs = Math.max(cfg.cfMinDelayMs, clamp(integer(o, "cfMaxDelayMs", 250), MIN_CF_DELAY_MS, MAX_CF_DELAY_MS));
                cfg.setCfUpgradeDelayMs(integer(o, "cfUpgradeDelayMs", 500));
            } catch (Exception e) {
                cfg = new CheatUtilsConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("witherEspEnabled", witherEspEnabled);
            o.addProperty("witherPhaseFilter", witherPhaseFilter.name());
            o.addProperty("maxorColor", maxorColor);
            o.addProperty("stormColor", stormColor);
            o.addProperty("goldorColor", goldorColor);
            o.addProperty("necronColor", necronColor);

            o.addProperty("secretAuraEnabled", secretAuraEnabled);
            o.addProperty("auraChests", auraChests);
            o.addProperty("auraLevers", auraLevers);
            o.addProperty("auraEssence", auraEssence);
            o.addProperty("auraBossLevers", auraBossLevers);
            o.addProperty("auraRange", auraRange);
            o.addProperty("auraSkullRange", auraSkullRange);
            o.addProperty("auraCooldownMs", auraCooldownMs);
            o.addProperty("auraSwing", auraSwing);
            o.addProperty("auraPauseWhileSneaking", auraPauseWhileSneaking);
            o.addProperty("auraPauseHolding", auraPauseHolding);

            o.addProperty("autoGfsEnabled", autoGfsEnabled);
            o.addProperty("gfsPearls", gfsPearls);
            o.addProperty("gfsLeaps", gfsLeaps);
            o.addProperty("gfsSuperbooms", gfsSuperbooms);
            o.addProperty("gfsJerries", gfsJerries);
            o.addProperty("gfsThresholdPercent", gfsThresholdPercent);
            o.addProperty("gfsIntervalSec", gfsIntervalSec);
            o.addProperty("gfsSkipIfNone", gfsSkipIfNone);

            o.addProperty("autoUltEnabled", autoUltEnabled);
            o.addProperty("ultMaxorEnraged", ultMaxorEnraged);
            o.addProperty("ultGoldorFactory", ultGoldorFactory);
            o.addProperty("ultClassOverride", ultClassOverride);

            o.addProperty("chocolateEnabled", chocolateEnabled);
            o.addProperty("cfClickCookie", cfClickCookie);
            o.addProperty("cfAutoUpgrade", cfAutoUpgrade);
            o.addProperty("cfClaimStrays", cfClaimStrays);
            o.addProperty("cfAutoTimeTower", cfAutoTimeTower);
            o.addProperty("cfMinDelayMs", cfMinDelayMs);
            o.addProperty("cfMaxDelayMs", cfMaxDelayMs);
            o.addProperty("cfUpgradeDelayMs", cfUpgradeDelayMs);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        return o.has(key) ? o.get(key).getAsBoolean() : def;
    }

    private static int integer(JsonObject o, String key, int def) {
        return o.has(key) ? o.get(key).getAsInt() : def;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) ? o.get(key).getAsString() : def;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static WitherPhaseFilter parsePhase(String s) {
        try {
            return WitherPhaseFilter.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return WitherPhaseFilter.P3;
        }
    }

    private static boolean cheat() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED;
    }

    // ---- Wither ESP ----
    public boolean isWitherEspEnabled() { return cheat() && witherEspEnabled; }
    public void setWitherEspEnabled(boolean v) { witherEspEnabled = v; }
    public WitherPhaseFilter getWitherPhaseFilter() { return witherPhaseFilter; }
    public void cycleWitherPhaseFilter() {
        WitherPhaseFilter[] all = WitherPhaseFilter.values();
        witherPhaseFilter = all[(witherPhaseFilter.ordinal() + 1) % all.length];
    }
    public int getMaxorColor() { return maxorColor; }
    public void setMaxorColor(int v) { maxorColor = v; }
    public int getStormColor() { return stormColor; }
    public void setStormColor(int v) { stormColor = v; }
    public int getGoldorColor() { return goldorColor; }
    public void setGoldorColor(int v) { goldorColor = v; }
    public int getNecronColor() { return necronColor; }
    public void setNecronColor(int v) { necronColor = v; }

    // ---- Secret Aura ----
    public boolean isSecretAuraEnabled() { return cheat() && secretAuraEnabled; }
    public void setSecretAuraEnabled(boolean v) { secretAuraEnabled = v; }
    public boolean isAuraChests() { return auraChests; }
    public void setAuraChests(boolean v) { auraChests = v; }
    public boolean isAuraLevers() { return auraLevers; }
    public void setAuraLevers(boolean v) { auraLevers = v; }
    public boolean isAuraEssence() { return auraEssence; }
    public void setAuraEssence(boolean v) { auraEssence = v; }
    public boolean isAuraBossLevers() { return auraBossLevers; }
    public void setAuraBossLevers(boolean v) { auraBossLevers = v; }
    public double getAuraRange() { return auraRange; }
    public void setAuraRange(double v) { auraRange = Math.max(MIN_AURA_RANGE, Math.min(MAX_AURA_RANGE, Math.round(v * 10.0) / 10.0)); }
    public double getAuraSkullRange() { return auraSkullRange; }
    public void setAuraSkullRange(double v) { auraSkullRange = Math.max(MIN_AURA_RANGE, Math.min(MAX_AURA_SKULL_RANGE, Math.round(v * 10.0) / 10.0)); }
    public int getAuraCooldownMs() { return auraCooldownMs; }
    public void setAuraCooldownMs(int v) { auraCooldownMs = clamp(v, MIN_AURA_COOLDOWN_MS, MAX_AURA_COOLDOWN_MS); }
    public boolean isAuraSwing() { return auraSwing; }
    public void setAuraSwing(boolean v) { auraSwing = v; }
    public boolean isAuraPauseWhileSneaking() { return auraPauseWhileSneaking; }
    public void setAuraPauseWhileSneaking(boolean v) { auraPauseWhileSneaking = v; }
    public String getAuraPauseHolding() { return auraPauseHolding; }
    public void setAuraPauseHolding(String v) { auraPauseHolding = v == null ? "" : v; }

    // ---- Auto GFS ----
    public boolean isAutoGfsEnabled() { return cheat() && autoGfsEnabled; }
    public void setAutoGfsEnabled(boolean v) { autoGfsEnabled = v; }
    public boolean isGfsPearls() { return gfsPearls; }
    public void setGfsPearls(boolean v) { gfsPearls = v; }
    public boolean isGfsLeaps() { return gfsLeaps; }
    public void setGfsLeaps(boolean v) { gfsLeaps = v; }
    public boolean isGfsSuperbooms() { return gfsSuperbooms; }
    public void setGfsSuperbooms(boolean v) { gfsSuperbooms = v; }
    public boolean isGfsJerries() { return gfsJerries; }
    public void setGfsJerries(boolean v) { gfsJerries = v; }
    public int getGfsThresholdPercent() { return gfsThresholdPercent; }
    public void setGfsThresholdPercent(int v) { gfsThresholdPercent = clamp(v, MIN_GFS_THRESHOLD_PERCENT, MAX_GFS_THRESHOLD_PERCENT); }
    public int getGfsIntervalSec() { return gfsIntervalSec; }
    public void setGfsIntervalSec(int v) { gfsIntervalSec = clamp(v, MIN_GFS_INTERVAL_SEC, MAX_GFS_INTERVAL_SEC); }
    public boolean isGfsSkipIfNone() { return gfsSkipIfNone; }
    public void setGfsSkipIfNone(boolean v) { gfsSkipIfNone = v; }

    // ---- Auto Ult ----
    public boolean isAutoUltEnabled() { return cheat() && autoUltEnabled; }
    public void setAutoUltEnabled(boolean v) { autoUltEnabled = v; }
    public boolean isUltMaxorEnraged() { return ultMaxorEnraged; }
    public void setUltMaxorEnraged(boolean v) { ultMaxorEnraged = v; }
    public boolean isUltGoldorFactory() { return ultGoldorFactory; }
    public void setUltGoldorFactory(boolean v) { ultGoldorFactory = v; }
    public String getUltClassOverride() { return ultClassOverride; }
    public void cycleUltClassOverride() {
        String[] order = {"AUTO", "HEALER", "TANK", "MAGE", "ARCHER", "BERSERKER"};
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equalsIgnoreCase(ultClassOverride)) {
                idx = i;
            }
        }
        ultClassOverride = order[(idx + 1) % order.length];
    }

    // ---- Auto Chocolate Factory ----
    public boolean isChocolateEnabled() { return cheat() && chocolateEnabled; }
    public void setChocolateEnabled(boolean v) { chocolateEnabled = v; }
    public boolean isCfClickCookie() { return cfClickCookie; }
    public void setCfClickCookie(boolean v) { cfClickCookie = v; }
    public boolean isCfAutoUpgrade() { return cfAutoUpgrade; }
    public void setCfAutoUpgrade(boolean v) { cfAutoUpgrade = v; }
    public boolean isCfClaimStrays() { return cfClaimStrays; }
    public void setCfClaimStrays(boolean v) { cfClaimStrays = v; }
    public boolean isCfAutoTimeTower() { return cfAutoTimeTower; }
    public void setCfAutoTimeTower(boolean v) { cfAutoTimeTower = v; }
    public int getCfMinDelayMs() { return cfMinDelayMs; }
    public void setCfMinDelayMs(int v) {
        cfMinDelayMs = clamp(v, MIN_CF_DELAY_MS, MAX_CF_DELAY_MS);
        if (cfMinDelayMs > cfMaxDelayMs) {
            cfMaxDelayMs = cfMinDelayMs;
        }
    }
    public int getCfMaxDelayMs() { return cfMaxDelayMs; }
    public void setCfMaxDelayMs(int v) {
        cfMaxDelayMs = clamp(v, MIN_CF_DELAY_MS, MAX_CF_DELAY_MS);
        if (cfMaxDelayMs < cfMinDelayMs) {
            cfMinDelayMs = cfMaxDelayMs;
        }
    }
    public int rollCfDelayMs() {
        return cfMinDelayMs >= cfMaxDelayMs ? cfMinDelayMs : cfMinDelayMs + RANDOM.nextInt(cfMaxDelayMs - cfMinDelayMs + 1);
    }
    public int getCfUpgradeDelayMs() { return cfUpgradeDelayMs; }
    public void setCfUpgradeDelayMs(int v) { cfUpgradeDelayMs = clamp(v, MIN_CF_UPGRADE_DELAY_MS, MAX_CF_UPGRADE_DELAY_MS); }
}
