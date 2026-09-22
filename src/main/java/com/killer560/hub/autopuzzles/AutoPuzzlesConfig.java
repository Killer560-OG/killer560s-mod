package com.killer560.hub.autopuzzles;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted "Auto Puzzles" settings - see {@link AutoPuzzlesFeature}. Real automation (clicks puzzle blocks / NPCs,
 * shoots, teleports for you), so every enable getter is gated on
 * {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} + {@link com.killer560.hub.util.SkyblockGate} and
 * everything ships OFF.
 */
public final class AutoPuzzlesConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-autopuzzles.json");

    public static final int MAX_DELAY_MS = 2000;
    public static final int DELAY_STEP_MS = 50;
    // QUOI PuzzleSolvers bow settings: slider("Shoot cooldown", 500L, 250L, 1000L, 50L) / ("Miss cooldown", 550L, 300L, 1050L, 50L).
    public static final int SHOOT_CD_MIN = 250;
    public static final int SHOOT_CD_MAX = 1000;
    public static final int MISS_CD_MIN = 300;
    public static final int MISS_CD_MAX = 1050;
    public static final int COOLDOWN_STEP_MS = 50;
    // QUOI IceFillSolver: slider("Delay", 2, 1, 10, 1, unit = "t").
    public static final int ICE_FILL_DELAY_MIN = 1;
    public static final int ICE_FILL_DELAY_MAX = 10;

    private static AutoPuzzlesConfig instance;

    private boolean autoQuizEnabled = false;
    private int quizDelayMs = 250;
    private boolean autoWeirdosEnabled = false;
    private int weirdosDelayMs = 250;
    // QUOI's ThreeWeirdos auto also right-clicks the 3 "CLICK" NPC stands so they say their lines.
    private boolean weirdosTalkToNpcs = false;

    private boolean autoBlazeEnabled = false;
    private boolean autoBeamsEnabled = false;
    private boolean autoIcePathEnabled = false;
    private int shootCooldownMs = 500;
    private int missCooldownMs = 550;
    // QUOI Repositionable (etherwarp to a standing spot) - one opt-in toggle for every auto that uses it.
    private boolean etherwarpReposition = false;

    private boolean autoBoulderEnabled = false;
    private int boulderDelayMs = 500;
    private boolean autoWaterEnabled = false;
    private boolean autoTicTacToeEnabled = false;
    private boolean autoTeleportMazeEnabled = false;
    private boolean autoIceFillEnabled = false;
    private int iceFillDelayTicks = 2;
    /** Adaptive Ice Fill (killer560, 2026-09-21: "an option for adaptive which means it will never be able to fail by
     *  adjusting to server lag"): each hop waits for the server to confirm the tile you are on. Off by default. */
    private boolean iceFillAdaptive = false;

    private AutoPuzzlesConfig() {
    }

    public static AutoPuzzlesConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new AutoPuzzlesConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            AutoPuzzlesConfig cfg = new AutoPuzzlesConfig();
            cfg.autoQuizEnabled = ConfigJson.getBool(obj, "autoQuizEnabled", false);
            cfg.quizDelayMs = clampDelay(ConfigJson.getInt(obj, "quizDelayMs", cfg.quizDelayMs));
            cfg.autoWeirdosEnabled = ConfigJson.getBool(obj, "autoWeirdosEnabled", false);
            cfg.weirdosDelayMs = clampDelay(ConfigJson.getInt(obj, "weirdosDelayMs", cfg.weirdosDelayMs));
            cfg.weirdosTalkToNpcs = ConfigJson.getBool(obj, "weirdosTalkToNpcs", false);
            cfg.autoBlazeEnabled = ConfigJson.getBool(obj, "autoBlazeEnabled", false);
            cfg.autoBeamsEnabled = ConfigJson.getBool(obj, "autoBeamsEnabled", false);
            cfg.autoIcePathEnabled = ConfigJson.getBool(obj, "autoIcePathEnabled", false);
            cfg.shootCooldownMs = clamp(ConfigJson.getInt(obj, "shootCooldownMs", cfg.shootCooldownMs), SHOOT_CD_MIN, SHOOT_CD_MAX);
            cfg.missCooldownMs = clamp(ConfigJson.getInt(obj, "missCooldownMs", cfg.missCooldownMs), MISS_CD_MIN, MISS_CD_MAX);
            cfg.etherwarpReposition = ConfigJson.getBool(obj, "etherwarpReposition", false);
            cfg.autoBoulderEnabled = ConfigJson.getBool(obj, "autoBoulderEnabled", false);
            cfg.boulderDelayMs = clampDelay(ConfigJson.getInt(obj, "boulderDelayMs", cfg.boulderDelayMs));
            cfg.autoWaterEnabled = ConfigJson.getBool(obj, "autoWaterEnabled", false);
            cfg.autoTicTacToeEnabled = ConfigJson.getBool(obj, "autoTicTacToeEnabled", false);
            cfg.autoTeleportMazeEnabled = ConfigJson.getBool(obj, "autoTeleportMazeEnabled", false);
            cfg.autoIceFillEnabled = ConfigJson.getBool(obj, "autoIceFillEnabled", false);
            cfg.iceFillAdaptive = ConfigJson.getBool(obj, "iceFillAdaptive", false);
            cfg.iceFillDelayTicks = clamp(ConfigJson.getInt(obj, "iceFillDelayTicks", cfg.iceFillDelayTicks),
                    ICE_FILL_DELAY_MIN, ICE_FILL_DELAY_MAX);
            instance = cfg;
        } catch (Exception e) {
            instance = new AutoPuzzlesConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("autoQuizEnabled", autoQuizEnabled);
            obj.addProperty("quizDelayMs", quizDelayMs);
            obj.addProperty("autoWeirdosEnabled", autoWeirdosEnabled);
            obj.addProperty("weirdosDelayMs", weirdosDelayMs);
            obj.addProperty("weirdosTalkToNpcs", weirdosTalkToNpcs);
            obj.addProperty("autoBlazeEnabled", autoBlazeEnabled);
            obj.addProperty("autoBeamsEnabled", autoBeamsEnabled);
            obj.addProperty("autoIcePathEnabled", autoIcePathEnabled);
            obj.addProperty("shootCooldownMs", shootCooldownMs);
            obj.addProperty("missCooldownMs", missCooldownMs);
            obj.addProperty("etherwarpReposition", etherwarpReposition);
            obj.addProperty("autoBoulderEnabled", autoBoulderEnabled);
            obj.addProperty("boulderDelayMs", boulderDelayMs);
            obj.addProperty("autoWaterEnabled", autoWaterEnabled);
            obj.addProperty("autoTicTacToeEnabled", autoTicTacToeEnabled);
            obj.addProperty("autoTeleportMazeEnabled", autoTeleportMazeEnabled);
            obj.addProperty("autoIceFillEnabled", autoIceFillEnabled);
            obj.addProperty("iceFillDelayTicks", iceFillDelayTicks);
            obj.addProperty("iceFillAdaptive", iceFillAdaptive);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clampDelay(int ms) {
        return clamp(ms, 0, MAX_DELAY_MS);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean cheat(boolean raw) {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && raw && com.killer560.hub.util.SkyblockGate.allows();
    }

    // ---- Quiz / Three Weirdos ----

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - real automation. */
    public boolean isAutoQuizEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoQuizEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setAutoQuizEnabled(boolean enabled) {
        this.autoQuizEnabled = enabled;
    }

    public int getQuizDelayMs() {
        return quizDelayMs;
    }

    public void setQuizDelayMs(int ms) {
        this.quizDelayMs = clampDelay(ms);
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - real automation. */
    public boolean isAutoWeirdosEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoWeirdosEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setAutoWeirdosEnabled(boolean enabled) {
        this.autoWeirdosEnabled = enabled;
    }

    public int getWeirdosDelayMs() {
        return weirdosDelayMs;
    }

    public void setWeirdosDelayMs(int ms) {
        this.weirdosDelayMs = clampDelay(ms);
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} and Auto Three Weirdos itself. */
    public boolean isWeirdosTalkToNpcs() {
        return isAutoWeirdosEnabled() && weirdosTalkToNpcs;
    }

    public boolean getWeirdosTalkToNpcsRaw() {
        return weirdosTalkToNpcs;
    }

    public void setWeirdosTalkToNpcs(boolean enabled) {
        this.weirdosTalkToNpcs = enabled;
    }

    // ---- bow puzzles (Blaze / Creeper Beams / Ice Path) ----

    public boolean isAutoBlazeEnabled() {
        return cheat(autoBlazeEnabled);
    }

    public void setAutoBlazeEnabled(boolean enabled) {
        this.autoBlazeEnabled = enabled;
    }

    public boolean isAutoBeamsEnabled() {
        return cheat(autoBeamsEnabled);
    }

    public void setAutoBeamsEnabled(boolean enabled) {
        this.autoBeamsEnabled = enabled;
    }

    public boolean isAutoIcePathEnabled() {
        return cheat(autoIcePathEnabled);
    }

    public void setAutoIcePathEnabled(boolean enabled) {
        this.autoIcePathEnabled = enabled;
    }

    public int getShootCooldownMs() {
        return shootCooldownMs;
    }

    public void setShootCooldownMs(int ms) {
        this.shootCooldownMs = clamp(ms, SHOOT_CD_MIN, SHOOT_CD_MAX);
    }

    public int getMissCooldownMs() {
        return missCooldownMs;
    }

    public void setMissCooldownMs(int ms) {
        this.missCooldownMs = clamp(ms, MISS_CD_MIN, MISS_CD_MAX);
    }

    /** Etherwarp repositioning (hotbar swap + sneak + AOTV) - cheat-gated; only used by an enabled auto. */
    public boolean isEtherwarpReposition() {
        return cheat(etherwarpReposition);
    }

    public boolean getEtherwarpRepositionRaw() {
        return etherwarpReposition;
    }

    public void setEtherwarpReposition(boolean enabled) {
        this.etherwarpReposition = enabled;
    }

    // ---- click puzzles (Boulder / Water Board / Tic Tac Toe) ----

    public boolean isAutoBoulderEnabled() {
        return cheat(autoBoulderEnabled);
    }

    public void setAutoBoulderEnabled(boolean enabled) {
        this.autoBoulderEnabled = enabled;
    }

    public int getBoulderDelayMs() {
        return boulderDelayMs;
    }

    public void setBoulderDelayMs(int ms) {
        this.boulderDelayMs = clampDelay(ms);
    }

    public boolean isAutoWaterEnabled() {
        return cheat(autoWaterEnabled);
    }

    public void setAutoWaterEnabled(boolean enabled) {
        this.autoWaterEnabled = enabled;
    }

    public boolean isAutoTicTacToeEnabled() {
        return cheat(autoTicTacToeEnabled);
    }

    public void setAutoTicTacToeEnabled(boolean enabled) {
        this.autoTicTacToeEnabled = enabled;
    }

    // ---- movement puzzles (Teleport Maze / Ice Fill) ----

    public boolean isAutoTeleportMazeEnabled() {
        return cheat(autoTeleportMazeEnabled);
    }

    public void setAutoTeleportMazeEnabled(boolean enabled) {
        this.autoTeleportMazeEnabled = enabled;
    }

    public boolean isAutoIceFillEnabled() {
        return cheat(autoIceFillEnabled);
    }

    public void setAutoIceFillEnabled(boolean enabled) {
        this.autoIceFillEnabled = enabled;
    }

    public boolean isIceFillAdaptive() {
        return iceFillAdaptive;
    }

    public void setIceFillAdaptive(boolean v) {
        iceFillAdaptive = v;
    }

    public int getIceFillDelayTicks() {
        return iceFillDelayTicks;
    }

    public void setIceFillDelayTicks(int ticks) {
        this.iceFillDelayTicks = clamp(ticks, ICE_FILL_DELAY_MIN, ICE_FILL_DELAY_MAX);
    }
}
