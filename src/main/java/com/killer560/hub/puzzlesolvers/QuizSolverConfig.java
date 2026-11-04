package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;

import java.nio.file.Path;

/** Persisted Quiz Solver settings - see {@link QuizSolverFeature}'s class doc for the real Odin-ported
 *  trivia-answer database this is built on. Ships disabled by default, same as every other new feature
 *  in this mod. */
public final class QuizSolverConfig extends SolverConfigBase {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-quizsolver.json");

    private static QuizSolverConfig instance;

    /** Block Wrong Answers: once the solver knows the answer, his click on a wrong answer's button is dropped. */
    private boolean blockWrongAnswers = false;
    /** Sneaking lets a blocked wrong answer through (killer560, 2026-11-01: "a shift to override option"). */
    private boolean sneakOverride = true;
    /** The Quiz Timer HUD: how long until the current question can be answered (killer560, 2026-11-01). */
    private boolean showTimer = false;

    private QuizSolverConfig() {
    }

    public static QuizSolverConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        QuizSolverConfig cfg = new QuizSolverConfig();
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.blockWrongAnswers = ConfigJson.getBool(obj, "blockWrongAnswers", false);
            cfg.sneakOverride = ConfigJson.getBool(obj, "sneakOverride", true);
            cfg.showTimer = ConfigJson.getBool(obj, "showTimer", false);
        }
        instance = cfg;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("blockWrongAnswers", blockWrongAnswers);
        obj.addProperty("sneakOverride", sneakOverride);
        obj.addProperty("showTimer", showTimer);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isBlockWrongAnswers() { return blockWrongAnswers; }

    public void setBlockWrongAnswers(boolean blockWrongAnswers) { this.blockWrongAnswers = blockWrongAnswers; }

    public boolean isSneakOverride() { return sneakOverride; }

    public void setSneakOverride(boolean sneakOverride) { this.sneakOverride = sneakOverride; }

    public boolean isShowTimer() { return showTimer; }

    public void setShowTimer(boolean showTimer) { this.showTimer = showTimer; }

}
