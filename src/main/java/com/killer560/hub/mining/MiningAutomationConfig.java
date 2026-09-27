package com.killer560.hub.mining;

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

/**
 * Settings for the Mining (WIP) tab's three requested AUTOMATION features - Auto Commissions, Auto Nucleus
 * Run and Auto Crystal (killer560, verbatim: "auto commissions auto nuc run ... auto crystal").
 * <p>
 * <b>NONE of these three are wired up.</b> Every one of them needs real Crystal Hollows / Dwarven Mines
 * terrain pathfinding (natural, non-grid caves - nothing like the dungeon's fixed 11x11 room grid {@code
 * ap3}/{@code livemap.autoclear} pathfind over) plus mob targeting (Auto Commissions), crystal/chest
 * detection (Auto Crystal) and a full mine-then-kill-then-loot loop (Auto Nucleus Run) that do not exist
 * anywhere in this codebase, and cannot be safely built inside this pass - see the class docs on
 * {@code mining.profit.MiningProfitTracker} (which IS wired) for what could be reused today. Rather than
 * ship a half-working clicker that misreads the terrain and starts sending discrete key presses at the
 * wrong thing (this mod's hard rule: no fractional/position movement, discrete keys only, abort on any
 * server correction - see {@code com.killer560.hub.ap3}), this only ships the OFF-by-default settings and
 * every getter is gated the same way a real one would be
 * ({@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} + {@link SkyblockGate#allows()}), so the
 * moment real automation is built the tabs, persistence and gating are already in place - only the actual
 * pathfinding/click logic (which would call {@link com.killer560.hub.util.ActionGate}) is missing.
 * <p>
 * Persisted so the toggles/settings survive restarts even though they do nothing yet, matching this
 * codebase's "every setting persists" rule.
 */
public final class MiningAutomationConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-mining-automation.json");

    private static MiningAutomationConfig instance;

    private boolean autoCommissionsEnabled = false;
    private boolean autoNucleusRunEnabled = false;
    private boolean autoCrystalEnabled = false;

    private MiningAutomationConfig() {
    }

    public static MiningAutomationConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new MiningAutomationConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            MiningAutomationConfig cfg = new MiningAutomationConfig();
            cfg.autoCommissionsEnabled = ConfigJson.getBool(obj, "autoCommissionsEnabled", false);
            cfg.autoNucleusRunEnabled = ConfigJson.getBool(obj, "autoNucleusRunEnabled", false);
            cfg.autoCrystalEnabled = ConfigJson.getBool(obj, "autoCrystalEnabled", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new MiningAutomationConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("autoCommissionsEnabled", autoCommissionsEnabled);
            obj.addProperty("autoNucleusRunEnabled", autoNucleusRunEnabled);
            obj.addProperty("autoCrystalEnabled", autoCrystalEnabled);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ---- Auto Commissions ----
    /** Gated exactly like a real automation getter would be, even though nothing reads it to act yet. */
    public boolean isAutoCommissionsEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoCommissionsEnabled && SkyblockGate.allows();
    }

    public boolean isAutoCommissionsEnabledRaw() {
        return autoCommissionsEnabled;
    }

    public void setAutoCommissionsEnabled(boolean v) {
        autoCommissionsEnabled = v;
    }

    // ---- Auto Nucleus Run ----
    public boolean isAutoNucleusRunEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoNucleusRunEnabled && SkyblockGate.allows();
    }

    public boolean isAutoNucleusRunEnabledRaw() {
        return autoNucleusRunEnabled;
    }

    public void setAutoNucleusRunEnabled(boolean v) {
        autoNucleusRunEnabled = v;
    }

    // ---- Auto Crystal ----
    public boolean isAutoCrystalEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoCrystalEnabled && SkyblockGate.allows();
    }

    public boolean isAutoCrystalEnabledRaw() {
        return autoCrystalEnabled;
    }

    public void setAutoCrystalEnabled(boolean v) {
        autoCrystalEnabled = v;
    }
}
