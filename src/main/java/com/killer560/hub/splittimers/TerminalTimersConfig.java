package com.killer560.hub.splittimers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "Terminal Timers" settings - see {@link TerminalTimersFeature}. Ships disabled by default. */
public final class TerminalTimersConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-terminaltimers.json");

    private static TerminalTimersConfig instance;

    private boolean enabled = false;
    // Odin "Terminal Times": client-side "<Terminal> solved in Xs" after each terminal you complete.
    private boolean solveTimes = true;
    // Odin "Terminal Splits": section/phase times appended to every "completed a terminal/device/lever! (n/m)"
    // line, plus a per-section summary when the core opens.
    private boolean splits = true;
    // killer560's own "bundle the simon says one into this as well" - the Simon Says "Whole device solved" message.
    private boolean simonSaysTime = true;

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
            instance = cfg;
        } catch (Exception e) {
            instance = new TerminalTimersConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("solveTimes", solveTimes);
            obj.addProperty("splits", splits);
            obj.addProperty("simonSaysTime", simonSaysTime);
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

    public boolean isSolveTimes() {
        return enabled && solveTimes && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getSolveTimesRaw() {
        return solveTimes;
    }

    public void setSolveTimes(boolean solveTimes) {
        this.solveTimes = solveTimes;
    }

    public boolean isSplits() {
        return enabled && splits && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getSplitsRaw() {
        return splits;
    }

    public void setSplits(boolean splits) {
        this.splits = splits;
    }

    public boolean isSimonSaysTime() {
        return enabled && simonSaysTime && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getSimonSaysTimeRaw() {
        return simonSaysTime;
    }

    public void setSimonSaysTime(boolean simonSaysTime) {
        this.simonSaysTime = simonSaysTime;
    }
}
