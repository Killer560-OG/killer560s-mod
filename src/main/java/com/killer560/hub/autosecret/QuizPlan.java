package com.killer560.hub.autosecret;

import com.killer560.hub.autopuzzles.AutoPuzzlesConfig;
import com.killer560.hub.puzzlesolvers.QuizSolverConfig;
import com.killer560.hub.puzzlesolvers.QuizState;

/**
 * Auto Secret's run state for the Quiz (killer560, 2026-10-17): "have it open quiz as its first goal if the setting is
 * on. Then after that go do ice fill then come back and answer it, then go secret a room then come back and answer it
 * till it finishes". The deciding is {@code AutoSecretFeature.decideQuiz}; this holds what a run has tried.
 */
final class QuizPlan {

    /** Tries at starting it (a walk into the room, a wait for his first line) before it is left to the rest of the run. */
    static final int MAX_START_TRIES = 2;

    private static int startTries;
    private static boolean gaveUp;

    private QuizPlan() {
    }

    static void reset() {
        startTries = 0;
        gaveUp = false;
    }

    /** Quiz First is on and Auto Quiz (with its solver) can answer. */
    static boolean wanted() {
        return AutoSecretConfig.getInstance().isQuizFirst() && AutoPuzzlesConfig.getInstance().isAutoQuizEnabled()
                && QuizSolverConfig.getInstance().isEnabled();
    }

    /** This run is working the Quiz now: wanted, not given up, and Oruo has begun and is not done. */
    static boolean running() {
        return wanted() && !gaveUp && QuizState.inProgress();
    }

    static boolean gaveUp() {
        return gaveUp;
    }

    /** {@code why} names the cause at the call site; the callers log it themselves. */
    static void giveUp(String why) {
        gaveUp = true;
    }

    /** One more try at starting it; false when the tries are used up. */
    static boolean tryStart() {
        return ++startTries <= MAX_START_TRIES;
    }

    /** A trip to the Quiz failed: {@code giveUp} after the usual retries. */
    static void tripFailed(boolean giveUpNow) {
        if (giveUpNow) {
            giveUp("could not get to the Quiz");
        }
    }
}
