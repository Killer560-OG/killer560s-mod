package com.killer560.hub.splittimers;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "Terminal Timers" settings - see {@link TerminalTimersFeature}. Ships disabled by default. */
public final class TerminalTimersConfig {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-terminaltimers.json");

    private static TerminalTimersConfig instance;

    private boolean enabled = false;
    // The reference "Terminal Times": client-side "<Terminal> solved in Xs" after each terminal you complete.
    private boolean solveTimes = true;
    // The reference "Terminal Splits": section/phase times appended to every "completed a terminal/device/lever! (n/m)"
    // line, plus a per-section summary when the core opens.
    private boolean splits = true;
    // killer560's own "bundle the simon says one into this as well" - the Simon Says "Whole device solved" message.
    private boolean simonSaysTime = true;
    // The end-of-P3 per-player count of terminals, devices and levers (featsweep-1108, item 2).
    private boolean breakdown = true;
    // Time Lost To Melody (melodylost-1118): what each P3 section lost to a Melody that was its last objective.
    private boolean melodyLostSplits = false;
    private boolean melodyLostHud = false;
    private boolean melodyLostPhase = false;
    private boolean melodyLostTotal = false;
    private boolean melodyLostParty = false;

    private TerminalTimersConfig() {
    }

    public static TerminalTimersConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new TerminalTimersConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            TerminalTimersConfig cfg = new TerminalTimersConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.solveTimes = ConfigJson.getBool(obj, "solveTimes", true);
            cfg.splits = ConfigJson.getBool(obj, "splits", true);
            cfg.simonSaysTime = ConfigJson.getBool(obj, "simonSaysTime", true);
            cfg.breakdown = ConfigJson.getBool(obj, "breakdown", true);
            cfg.melodyLostSplits = ConfigJson.getBool(obj, "melodyLostSplits", false);
            cfg.melodyLostHud = ConfigJson.getBool(obj, "melodyLostHud", false);
            cfg.melodyLostPhase = ConfigJson.getBool(obj, "melodyLostPhase", false);
            cfg.melodyLostTotal = ConfigJson.getBool(obj, "melodyLostTotal", false);
            cfg.melodyLostParty = ConfigJson.getBool(obj, "melodyLostParty", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new TerminalTimersConfig();
        }
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("solveTimes", solveTimes);
        obj.addProperty("splits", splits);
        obj.addProperty("simonSaysTime", simonSaysTime);
        obj.addProperty("breakdown", breakdown);
        obj.addProperty("melodyLostSplits", melodyLostSplits);
        obj.addProperty("melodyLostHud", melodyLostHud);
        obj.addProperty("melodyLostPhase", melodyLostPhase);
        obj.addProperty("melodyLostTotal", melodyLostTotal);
        obj.addProperty("melodyLostParty", melodyLostParty);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return enabled && com.killer560.hub.util.SkyblockGate.allows(); }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isSolveTimes() { return enabled && solveTimes && com.killer560.hub.util.SkyblockGate.allows(); }

    public boolean getSolveTimesRaw() { return solveTimes; }

    public void setSolveTimes(boolean solveTimes) { this.solveTimes = solveTimes; }

    public boolean isSplits() { return enabled && splits && com.killer560.hub.util.SkyblockGate.allows(); }

    public boolean getSplitsRaw() { return splits; }

    public void setSplits(boolean splits) { this.splits = splits; }

    public boolean isSimonSaysTime() { return enabled && simonSaysTime && com.killer560.hub.util.SkyblockGate.allows(); }

    public boolean getSimonSaysTimeRaw() { return simonSaysTime; }

    public void setSimonSaysTime(boolean simonSaysTime) { this.simonSaysTime = simonSaysTime; }

    public boolean isBreakdown() { return enabled && breakdown && com.killer560.hub.util.SkyblockGate.allows(); }

    public boolean getBreakdownRaw() { return breakdown; }

    public void setBreakdown(boolean breakdown) { this.breakdown = breakdown; }
    public boolean getMelodyLostSplitsRaw() { return melodyLostSplits; }

    public void setMelodyLostSplits(boolean v) { melodyLostSplits = v; }

    public boolean getMelodyLostHudRaw() { return melodyLostHud; }

    public void setMelodyLostHud(boolean v) { melodyLostHud = v; }

    public boolean getMelodyLostPhaseRaw() { return melodyLostPhase; }

    public void setMelodyLostPhase(boolean v) { melodyLostPhase = v; }

    public boolean getMelodyLostTotalRaw() { return melodyLostTotal; }

    public void setMelodyLostTotal(boolean v) { melodyLostTotal = v; }

    public boolean getMelodyLostPartyRaw() { return melodyLostParty; }

    public void setMelodyLostParty(boolean v) { melodyLostParty = v; }

    public boolean isMelodyLostParty() { return isEnabled() && melodyLostParty; }

    public boolean isMelodyLostSplits() { return isEnabled() && melodyLostSplits; }

    public boolean isMelodyLostHud() { return isEnabled() && melodyLostHud; }

    public boolean isMelodyLostPhase() { return isEnabled() && melodyLostPhase; }

    public boolean isMelodyLostTotal() { return isEnabled() && melodyLostTotal; }

    /** Any Time Lost To Melody output is on, so the tracker runs. */
    public boolean isMelodyLostActive() { return isEnabled() && (melodyLostSplits || melodyLostHud || melodyLostPhase || melodyLostTotal || melodyLostParty); }
}
