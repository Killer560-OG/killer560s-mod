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
    // QUOI PuzzleSolvers bow setting: slider("Shoot cooldown", 500L, 250L, 1000L, 50L).
    // Miss cooldown removed (killer560, 2026-09-27: "For auto puzzles remove the miss cooldown.") - every shot is
    // now paced by the Shoot cooldown alone, hit or miss (see AutoBeams/AutoBlaze/AutoIcePath).
    public static final int SHOOT_CD_MIN = 250;
    public static final int SHOOT_CD_MAX = 1000;
    public static final int COOLDOWN_STEP_MS = 50;
    // QUOI IceFillSolver: slider("Delay", 2, 1, 10, 1, unit = "t").
    public static final int ICE_FILL_DELAY_MIN = 1;
    public static final int ICE_FILL_DELAY_MAX = 10;

    private static AutoPuzzlesConfig instance;

    // killer560, 2026-09-27: "Add an overall toggle to this section as well." One master switch for the whole
    // Auto Puzzles section, on top of (not instead of) each individual auto's own toggle - see #cheat below.
    private boolean autoPuzzlesMasterEnabled = false;

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

    // killer560, 2026-09-27: "have an option for auto secret" (Higher/Lower Blaze) - pathfinds to the room's known
    // secret once the blazes are all dead, only when Auto Blaze is also on. See AutoBlaze.
    private boolean autoBlazeSecretEnabled = false;
    // killer560, 2026-09-27: Auto Tic Tac Toe "walk towards that chest and get close enough to aura it as an
    // option as well" - only when Auto Tic Tac Toe is also on. See AutoTicTacToe.
    private boolean ticTacToeAuraChestEnabled = false;
    /**
     * "an overall toggle for that whole section" (killer560, 2026-09-27) - one switch over every piece of
     * WALKING the auto puzzles do: Water Board's start-area warp, Boulder's trip above the chest, Tic Tac Toe's
     * room spot and chest trip, Higher/Lower's walk to the secret, and the walk out of a finished room.
     * <p>
     * Enforced in one place, {@link AutoPuzzleUtil#pathIfMapOn}, because every one of those goes through it.
     * Defaults ON so turning the setting on for the first time does not silently change what the puzzles
     * already did; off, the solvers and their clicking still work and nothing moves you.
     */
    private boolean autoPuzzlePathingEnabled = true;
    /**
     * Tic Tac Toe only: walk out of the room once the board is finished (killer560, 2026-09-27, "then have it
     * walk out of the room once it is done"). Off by default - it is the one auto-puzzle walk with no
     * hand-verified destination, since it aims at whatever door the live map says is nearest rather than a
     * measured exit, so it wants one real run before being trusted.
     */
    private boolean ticTacToeWalkOutEnabled = false;

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
            cfg.autoPuzzlesMasterEnabled = ConfigJson.getBool(obj, "autoPuzzlesMasterEnabled", false);
            cfg.autoQuizEnabled = ConfigJson.getBool(obj, "autoQuizEnabled", false);
            cfg.quizDelayMs = clampDelay(ConfigJson.getInt(obj, "quizDelayMs", cfg.quizDelayMs));
            cfg.autoWeirdosEnabled = ConfigJson.getBool(obj, "autoWeirdosEnabled", false);
            cfg.weirdosDelayMs = clampDelay(ConfigJson.getInt(obj, "weirdosDelayMs", cfg.weirdosDelayMs));
            cfg.weirdosTalkToNpcs = ConfigJson.getBool(obj, "weirdosTalkToNpcs", false);
            cfg.autoBlazeEnabled = ConfigJson.getBool(obj, "autoBlazeEnabled", false);
            cfg.autoBeamsEnabled = ConfigJson.getBool(obj, "autoBeamsEnabled", false);
            cfg.autoIcePathEnabled = ConfigJson.getBool(obj, "autoIcePathEnabled", false);
            cfg.shootCooldownMs = clamp(ConfigJson.getInt(obj, "shootCooldownMs", cfg.shootCooldownMs), SHOOT_CD_MIN, SHOOT_CD_MAX);
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
            cfg.autoBlazeSecretEnabled = ConfigJson.getBool(obj, "autoBlazeSecretEnabled", false);
            cfg.ticTacToeAuraChestEnabled = ConfigJson.getBool(obj, "ticTacToeAuraChestEnabled", false);
            cfg.autoPuzzlePathingEnabled = ConfigJson.getBool(obj, "autoPuzzlePathingEnabled", true);
            cfg.ticTacToeWalkOutEnabled = ConfigJson.getBool(obj, "ticTacToeWalkOutEnabled", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new AutoPuzzlesConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("autoPuzzlesMasterEnabled", autoPuzzlesMasterEnabled);
            obj.addProperty("autoQuizEnabled", autoQuizEnabled);
            obj.addProperty("quizDelayMs", quizDelayMs);
            obj.addProperty("autoWeirdosEnabled", autoWeirdosEnabled);
            obj.addProperty("weirdosDelayMs", weirdosDelayMs);
            obj.addProperty("weirdosTalkToNpcs", weirdosTalkToNpcs);
            obj.addProperty("autoBlazeEnabled", autoBlazeEnabled);
            obj.addProperty("autoBeamsEnabled", autoBeamsEnabled);
            obj.addProperty("autoIcePathEnabled", autoIcePathEnabled);
            obj.addProperty("shootCooldownMs", shootCooldownMs);
            obj.addProperty("etherwarpReposition", etherwarpReposition);
            obj.addProperty("autoBoulderEnabled", autoBoulderEnabled);
            obj.addProperty("boulderDelayMs", boulderDelayMs);
            obj.addProperty("autoWaterEnabled", autoWaterEnabled);
            obj.addProperty("autoTicTacToeEnabled", autoTicTacToeEnabled);
            obj.addProperty("autoTeleportMazeEnabled", autoTeleportMazeEnabled);
            obj.addProperty("autoIceFillEnabled", autoIceFillEnabled);
            obj.addProperty("iceFillDelayTicks", iceFillDelayTicks);
            obj.addProperty("iceFillAdaptive", iceFillAdaptive);
            obj.addProperty("autoBlazeSecretEnabled", autoBlazeSecretEnabled);
            obj.addProperty("ticTacToeAuraChestEnabled", ticTacToeAuraChestEnabled);
            obj.addProperty("autoPuzzlePathingEnabled", autoPuzzlePathingEnabled);
            obj.addProperty("ticTacToeWalkOutEnabled", ticTacToeWalkOutEnabled);
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

    /** Not static any more - needs this instance's own {@link #autoPuzzlesMasterEnabled} (item 6, 2026-09-27). */
    private boolean cheat(boolean raw) {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoPuzzlesMasterEnabled && raw
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    // ---- master toggle ----

    /** "Add an overall toggle to this section as well" (killer560, 2026-09-27) - every individual auto below is
     *  additionally gated on this being on, same {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED}
     *  gate as the rest of the section. */
    public boolean isAutoPuzzlesMasterEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoPuzzlesMasterEnabled
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean getAutoPuzzlesMasterEnabledRaw() {
        return autoPuzzlesMasterEnabled;
    }

    public void setAutoPuzzlesMasterEnabled(boolean enabled) {
        this.autoPuzzlesMasterEnabled = enabled;
    }

    // ---- Quiz / Three Weirdos ----

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} and the master toggle - real automation. */
    public boolean isAutoQuizEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoPuzzlesMasterEnabled && autoQuizEnabled
                && com.killer560.hub.util.SkyblockGate.allows();
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

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} and the master toggle - real automation. */
    public boolean isAutoWeirdosEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoPuzzlesMasterEnabled && autoWeirdosEnabled
                && com.killer560.hub.util.SkyblockGate.allows();
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

    /** One switch over every walk the auto puzzles do. See the field. */
    public boolean isAutoPuzzlePathingEnabled() {
        return autoPuzzlePathingEnabled;
    }

    public void setAutoPuzzlePathingEnabled(boolean enabled) {
        this.autoPuzzlePathingEnabled = enabled;
    }

    /** Walk out of the Tic Tac Toe room when the board is done - only meaningful with Auto Tic Tac Toe on. */
    public boolean isTicTacToeWalkOutEnabled() {
        return isAutoTicTacToeEnabled() && ticTacToeWalkOutEnabled;
    }

    public boolean getTicTacToeWalkOutEnabledRaw() {
        return ticTacToeWalkOutEnabled;
    }

    public void setTicTacToeWalkOutEnabled(boolean enabled) {
        this.ticTacToeWalkOutEnabled = enabled;
    }

    /** "have an option for auto secret" (killer560, 2026-09-27) - only meaningful with Auto Blaze itself on. */
    public boolean isAutoBlazeSecretEnabled() {
        return isAutoBlazeEnabled() && autoBlazeSecretEnabled;
    }

    public boolean getAutoBlazeSecretEnabledRaw() {
        return autoBlazeSecretEnabled;
    }

    public void setAutoBlazeSecretEnabled(boolean enabled) {
        this.autoBlazeSecretEnabled = enabled;
    }

    public int getShootCooldownMs() {
        return shootCooldownMs;
    }

    public void setShootCooldownMs(int ms) {
        this.shootCooldownMs = clamp(ms, SHOOT_CD_MIN, SHOOT_CD_MAX);
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

    /** Repurposed (killer560, 2026-09-27 redo - see {@code AutoBoulder}'s class doc): no longer a gap between
     *  floor-button clicks (Auto Boulder doesn't click the floor any more), now how long it waits at the standing
     *  spot against the oak logs before it auras the chest ("run up against the oak logs... and wait there a
     *  second"). Same persisted field/slider so the setting isn't silently reset for anyone who had tuned it. */
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

    /** "walk towards that chest and get close enough to aura it as an option as well" (killer560, 2026-09-27) -
     *  only meaningful with Auto Tic Tac Toe itself on. */
    public boolean isTicTacToeAuraChestEnabled() {
        return isAutoTicTacToeEnabled() && ticTacToeAuraChestEnabled;
    }

    public boolean getTicTacToeAuraChestEnabledRaw() {
        return ticTacToeAuraChestEnabled;
    }

    public void setTicTacToeAuraChestEnabled(boolean enabled) {
        this.ticTacToeAuraChestEnabled = enabled;
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
