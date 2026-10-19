package com.killer560.hub.chocolatefactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Chocolate Factory helper settings (the legit, SkyHanni-style one) - see {@link ChocolateFactoryHelper}.
 *  The master switch ships off, like every new feature; its parts default on so turning it on shows everything. */
public final class ChocolateFactoryConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-chocolatefactory.json");

    private static ChocolateFactoryConfig instance;

    private boolean enabled = false;
    private boolean highlightBestUpgrade = true;
    private boolean highlightStrays = true;
    private boolean strayAlert = true;
    private boolean timeTowerReminder = true;
    private boolean infoLine = true;
    /** Hoppity's Hunt egg timers HUD (legit, read-only) - see {@code egg/HoppityEggs}. */
    private boolean eggHud = true;
    /** Hoppity egg tracker: counts eggs, rabbits and duplicates per hunt (off by default). */
    private boolean eggTracker = false;
    /** The duplicate-rabbit chocolate values line on that HUD. Since 2026-10-19 that line is part of Egg Extra Info and
     *  this has no menu row or effect; it is still read and written so a saved file keeps its value. */
    private boolean eggDupeValues = true;
    /** Egg Extra Info (killer560, 2026-10-19): the HUD's lines past the six egg timers - the hunt's progress, next egg,
     *  Egglocator, tracker, session and duplicate values. Off, so a fresh config shows just the six timers. */
    private boolean eggExtraInfo = false;
    /** Egglocator Tracer Line: the green line from the crosshair to the Egglocator's guess. The guess box and label are
     *  drawn either way. */
    private boolean eggLocatorLine = true;
    /** Egg ESP: boxes the hidden Chocolate Eggs through walls. Cheat build only, ships off. */
    private boolean eggEsp = false;
    /** Egg ESP leaves out the meals already found since their last spawn. */
    private boolean eggEspHideFound = true;
    /** Chocolate per second as last read in the factory menu, for the duplicate values while the menu is closed. */
    private double lastCps = 0;

    private ChocolateFactoryConfig() {
    }

    public static ChocolateFactoryConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ChocolateFactoryConfig cfg = new ChocolateFactoryConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", false);
                cfg.highlightBestUpgrade = ConfigJson.getBool(o, "highlightBestUpgrade", true);
                cfg.highlightStrays = ConfigJson.getBool(o, "highlightStrays", true);
                cfg.strayAlert = ConfigJson.getBool(o, "strayAlert", true);
                cfg.timeTowerReminder = ConfigJson.getBool(o, "timeTowerReminder", true);
                cfg.infoLine = ConfigJson.getBool(o, "infoLine", true);
                cfg.eggHud = ConfigJson.getBool(o, "eggHud", true);
                cfg.eggTracker = ConfigJson.getBool(o, "eggTracker", false);
                cfg.eggDupeValues = ConfigJson.getBool(o, "eggDupeValues", true);
                // No fallback to eggDupeValues (on in every saved file): he asked for the six timers alone by default.
                cfg.eggExtraInfo = ConfigJson.getBool(o, "eggExtraInfo", false);
                cfg.eggLocatorLine = ConfigJson.getBool(o, "eggLocatorLine", true);
                cfg.eggEsp = ConfigJson.getBool(o, "eggEsp", false);
                cfg.eggEspHideFound = ConfigJson.getBool(o, "eggEspHideFound", true);
                if (o.has("lastCps") && o.get("lastCps").isJsonPrimitive()) {
                    double v = o.get("lastCps").getAsDouble();
                    cfg.lastCps = Double.isFinite(v) && v > 0 ? v : 0;
                }
            } catch (Exception e) {
                cfg = new ChocolateFactoryConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("highlightBestUpgrade", highlightBestUpgrade);
            o.addProperty("highlightStrays", highlightStrays);
            o.addProperty("strayAlert", strayAlert);
            o.addProperty("timeTowerReminder", timeTowerReminder);
            o.addProperty("infoLine", infoLine);
            o.addProperty("eggHud", eggHud);
            o.addProperty("eggTracker", eggTracker);
            o.addProperty("eggDupeValues", eggDupeValues);
            o.addProperty("eggExtraInfo", eggExtraInfo);
            o.addProperty("eggLocatorLine", eggLocatorLine);
            o.addProperty("eggEsp", eggEsp);
            o.addProperty("eggEspHideFound", eggEspHideFound);
            o.addProperty("lastCps", lastCps);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Raw switch (the tab's toggle); {@link #active()} also applies the Skyblock Only gate. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean active() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isHighlightBestUpgrade() {
        return highlightBestUpgrade;
    }

    public void setHighlightBestUpgrade(boolean v) {
        highlightBestUpgrade = v;
    }

    public boolean isHighlightStrays() {
        return highlightStrays;
    }

    public void setHighlightStrays(boolean v) {
        highlightStrays = v;
    }

    public boolean isStrayAlert() {
        return strayAlert;
    }

    public void setStrayAlert(boolean v) {
        strayAlert = v;
    }

    public boolean isTimeTowerReminder() {
        return timeTowerReminder;
    }

    public void setTimeTowerReminder(boolean v) {
        timeTowerReminder = v;
    }

    public boolean isInfoLine() {
        return infoLine;
    }

    public void setInfoLine(boolean v) {
        infoLine = v;
    }

    public boolean isEggTracker() {
        return eggTracker;
    }

    public void setEggTracker(boolean v) {
        eggTracker = v;
    }

    public boolean isEggHud() {
        return eggHud;
    }

    public void setEggHud(boolean v) {
        eggHud = v;
    }

    public boolean isEggDupeValues() {
        return eggDupeValues;
    }

    public void setEggDupeValues(boolean v) {
        eggDupeValues = v;
    }

    public boolean isEggExtraInfo() {
        return eggExtraInfo;
    }

    public void setEggExtraInfo(boolean v) {
        eggExtraInfo = v;
    }

    public boolean isEggLocatorLine() {
        return eggLocatorLine;
    }

    public void setEggLocatorLine(boolean v) {
        eggLocatorLine = v;
    }

    /** The raw Egg ESP switch; the ESP itself also needs a cheat build ({@code egg/EggEsp}). */
    public boolean isEggEsp() {
        return eggEsp;
    }

    public void setEggEsp(boolean v) {
        eggEsp = v;
    }

    public boolean isEggEspHideFound() {
        return eggEspHideFound;
    }

    public void setEggEspHideFound(boolean v) {
        eggEspHideFound = v;
    }

    public double getLastCps() {
        return lastCps;
    }

    public void setLastCps(double v) {
        lastCps = Double.isFinite(v) && v > 0 ? v : 0;
    }
}
