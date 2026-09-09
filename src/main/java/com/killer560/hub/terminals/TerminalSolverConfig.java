package com.killer560.hub.terminals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Terminal Solver settings - see {@link TerminalSolverFeature}. Ships disabled by default,
 *  same as every other new feature added going forward per killer560's standing instruction. Each
 *  terminal type has its own toggle so a type that isn't reliable yet can be turned off without
 *  disabling the whole feature. */
public final class TerminalSolverConfig {

    public static final float MIN_SCALE = 0.5f;
    // Bumped 0.5-2.0 -> 0.5-5.0 per killer560's explicit "let me put the scale up to a max of 500%"
    // request (2026-09-09).
    public static final float MAX_SCALE = 5.0f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-terminalsolver.json");

    private static TerminalSolverConfig instance;

    private boolean enabled = false;
    private float scale = 1.0f;
    private boolean panesEnabled = true;
    private boolean rubixEnabled = true;
    private boolean numbersEnabled = true;
    private boolean startsWithEnabled = true;
    private boolean selectEnabled = true;
    private boolean customGuiEnabled = false;

    private TerminalSolverConfig() {
    }

    public static TerminalSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new TerminalSolverConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            TerminalSolverConfig cfg = new TerminalSolverConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.scale = obj.has("scale") ? clampScale(obj.get("scale").getAsFloat()) : 1.0f;
            cfg.panesEnabled = !obj.has("panesEnabled") || obj.get("panesEnabled").getAsBoolean();
            cfg.rubixEnabled = !obj.has("rubixEnabled") || obj.get("rubixEnabled").getAsBoolean();
            cfg.numbersEnabled = !obj.has("numbersEnabled") || obj.get("numbersEnabled").getAsBoolean();
            cfg.startsWithEnabled = !obj.has("startsWithEnabled") || obj.get("startsWithEnabled").getAsBoolean();
            cfg.selectEnabled = !obj.has("selectEnabled") || obj.get("selectEnabled").getAsBoolean();
            cfg.customGuiEnabled = obj.has("customGuiEnabled") && obj.get("customGuiEnabled").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new TerminalSolverConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("scale", scale);
            obj.addProperty("panesEnabled", panesEnabled);
            obj.addProperty("rubixEnabled", rubixEnabled);
            obj.addProperty("numbersEnabled", numbersEnabled);
            obj.addProperty("startsWithEnabled", startsWithEnabled);
            obj.addProperty("selectEnabled", selectEnabled);
            obj.addProperty("customGuiEnabled", customGuiEnabled);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static float clampScale(float value) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = clampScale(scale);
    }

    public boolean isPanesEnabled() {
        return panesEnabled;
    }

    public void setPanesEnabled(boolean panesEnabled) {
        this.panesEnabled = panesEnabled;
    }

    public boolean isRubixEnabled() {
        return rubixEnabled;
    }

    public void setRubixEnabled(boolean rubixEnabled) {
        this.rubixEnabled = rubixEnabled;
    }

    public boolean isNumbersEnabled() {
        return numbersEnabled;
    }

    public void setNumbersEnabled(boolean numbersEnabled) {
        this.numbersEnabled = numbersEnabled;
    }

    public boolean isStartsWithEnabled() {
        return startsWithEnabled;
    }

    public void setStartsWithEnabled(boolean startsWithEnabled) {
        this.startsWithEnabled = startsWithEnabled;
    }

    public boolean isSelectEnabled() {
        return selectEnabled;
    }

    public void setSelectEnabled(boolean selectEnabled) {
        this.selectEnabled = selectEnabled;
    }

    public boolean isCustomGuiEnabled() {
        return customGuiEnabled;
    }

    public void setCustomGuiEnabled(boolean customGuiEnabled) {
        this.customGuiEnabled = customGuiEnabled;
    }
}
