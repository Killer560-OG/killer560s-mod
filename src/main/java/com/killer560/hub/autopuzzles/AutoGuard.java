package com.killer560.hub.autopuzzles;

import com.killer560.hub.util.ModChat;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

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

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");

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
            if (!clearedThisLevel) {
                LOGGER.info("[AutoGuard] {}: {} seen empty in this world - its data is safe to act on from now, tick={}",
                        autoName, solverName, com.killer560.hub.util.SessionRecorderSink.tick());
            }
            clearedThisLevel = true;
            staleLogged = false;
        }
    }

    /**
     * Whether {@link #observe} can still change anything this level. Once the solver has been seen empty, a further
     * observe only re-clears {@code staleLogged}, which is already false, so a caller may skip building the solver
     * state it would pass (a list copy per auto per tick, with every auto off: fps-auto-1015).
     */
    boolean observing() {
        return !clearedThisLevel;
    }

    void levelChanged() {
        LOGGER.debug("[AutoGuard] {}: world changed - waiting to see {} empty again (was cleared={})", autoName,
                solverName, clearedThisLevel);
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
            LOGGER.info("[AutoGuard] {}: not acting - {} is off (warned in chat once this room visit) tick={}", autoName,
                    solverName, com.killer560.hub.util.SessionRecorderSink.tick());
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
