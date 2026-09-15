package com.killer560.hub.autopuzzles;

import com.killer560.hub.util.ModChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-auto safety bookkeeping shared by the Auto Puzzles ports:
 * <ul>
 *   <li>"seen empty in this world" (same review fix as Auto Quiz/Weirdos): the solvers keep static state, so an auto
 *   never acts in a level until its solver has been observed with NO data at least once in that level - anything it
 *   reports afterwards was produced in this instance, never a previous room/run.</li>
 *   <li>one chat warning per room visit when the auto is on but its solver is off.</li>
 * </ul>
 */
final class AutoGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autopuzzles");

    private final String autoName;
    private final String solverName;
    private boolean clearedThisLevel = false;
    private boolean solverOffWarned = false;
    private boolean staleLogged = false;

    AutoGuard(String autoName, String solverName) {
        this.autoName = autoName;
        this.solverName = solverName;
    }

    /** Call every tick, before any gating. */
    void observe(boolean solverEmpty) {
        if (solverEmpty) {
            clearedThisLevel = true;
            staleLogged = false;
        }
    }

    void levelChanged() {
        clearedThisLevel = false;
        solverOffWarned = false;
        staleLogged = false;
    }

    void leftRoom() {
        solverOffWarned = false;
    }

    /** @return true if the solver is on; otherwise warns once per room visit. */
    boolean solverOn(boolean solverEnabled) {
        if (solverEnabled) {
            return true;
        }
        if (!solverOffWarned) {
            solverOffWarned = true;
            LOGGER.info("[AutoPuzzles] {}: in room but {} is OFF - the auto needs it", autoName, solverName);
            ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text(autoName + " needs "), ModChat.value(solverName),
                    ModChat.text(" turned on."));
        }
        return false;
    }

    /** @return true if the solver's current data was produced in this level (safe to act on). */
    boolean fresh() {
        if (clearedThisLevel) {
            return true;
        }
        if (!staleLogged) {
            staleLogged = true;
            LOGGER.info("[AutoPuzzles] {}: solver data predates this world - not acting until {} resets", autoName, solverName);
        }
        return false;
    }
}
