package com.killer560.hub.autosprint;

/**
 * Auto Sprint (killer560, 2026-11-01: "ADD AUTO SPRINT - this should be its own setting. Make sure it doesnt override
 * other movement things in the mod like ap3 and such only human movement", and "find a way to override other mods auto
 * sprint if they have it and we are using any type of our pathfinding").
 * <p>
 * <b>Our own Auto Sprint</b> ({@link #sprintFor}): while his own forward key is down (and he is not sneaking), the
 * sprint key reads as held, the same thing holding Ctrl does - a discrete key, never a velocity. It never touches a tick
 * where the mod's own movement owns the keys (AP3, Auto Routes, the pathfinder, Auto Clear's short walks, AP3's freeze):
 * those write their own sprint key and win.
 * <p>
 * <b>Other mods' auto sprint</b> ({@link #blocksSprintStart}): while one of the mod's own movers is driving and the key
 * it chose has sprint UP, no sprint may start on the local player, whoever asks. Other mods do it either by holding the
 * sprint key (already undone: the movers replace the whole key record at the end of {@code KeyboardInput.tick}), by
 * answering true where vanilla reads the sprint key in {@code LocalPlayer.aiStep} (Odin's Auto Sprint,
 * {@code @ModifyExpressionValue} on {@code Input.sprint()}), or by calling {@code setSprinting(true)} each tick. The
 * last two both end in {@code LivingEntity.setSprinting(true)}, which the mixin refuses then. A sprint already under
 * way is left alone (vanilla keeps it while moving forward, and the movers plan with that), so only starts are blocked;
 * the server sees nothing it would not have seen with the other mod uninstalled.
 */
public final class AutoSprintFeature {

    /** Testkit probes (1101-*-autosprint): ticks Auto Sprint added the key, sprint starts refused for a mover. */
    static int sprintTicks;
    static int startsBlocked;

    private AutoSprintFeature() {
    }

    /** True while one of the mod's own movers owns the keys this tick, or holds a session that may take them. */
    public static boolean modOwnsMovement() {
        return com.killer560.hub.ap3.Ap3FreezeState.isFrozen()
                || com.killer560.hub.ap3.Ap3Executor.isSessionActive()
                || com.killer560.hub.autoroutes.RouteExecutor.isSessionActive()
                || com.killer560.hub.pathfinding.AutoWalker.isSessionActive()
                || com.killer560.hub.pathfinding.ShortWalk.isActive();
    }

    /** True while a mover is actually driving the player this tick (the keys are the mod's, not his). */
    public static boolean modDriving() {
        return (com.killer560.hub.ap3.Ap3Executor.isSessionActive()
                        && (com.killer560.hub.ap3.Ap3Executor.isDriving() || com.killer560.hub.ap3.Ap3Executor.isInputOverridden()))
                || com.killer560.hub.autoroutes.RouteExecutor.isDriving()
                || (com.killer560.hub.pathfinding.AutoWalker.isSessionActive() && com.killer560.hub.pathfinding.AutoWalker.isDriving())
                || com.killer560.hub.pathfinding.ShortWalk.isDriving();
    }

    /** @return the sprint key for this tick's record: held when Auto Sprint is on and he is walking forward himself. */
    public static boolean sprintFor(boolean forward, boolean shift, boolean sprint) {
        if (sprint || !forward || shift || !AutoSprintConfig.getInstance().isEnabled() || modOwnsMovement()) {
            return sprint;
        }
        sprintTicks++;
        return true;
    }

    /** @return whether a sprint START on the local player must be refused: a mover drives with the sprint key up. */
    public static boolean blocksSprintStart(boolean drivenSprintKey) {
        if (drivenSprintKey || !modDriving()) {
            return false;
        }
        startsBlocked++;
        return true;
    }
}
