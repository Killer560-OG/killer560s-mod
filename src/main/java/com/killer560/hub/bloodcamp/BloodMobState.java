package com.killer560.hub.bloodcamp;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;

/** Per-blood-mob tracking state - real fields ported directly from Noamm's own confirmed
 *  {@code BloodCamp.kt} ({@code BloodEntity}), see {@link BloodCampFeature}'s class doc. */
final class BloodMobState {

    Vec3 startVec;
    long startedAtTick;
    boolean firstSpawn;
    final Deque<Vec3> deltaHistory = new ArrayDeque<>();
    Vec3 lastPosition;
    Vec3 endVector;
    /** Game tick of the most recent move packet for this mob - a gap means its trip ended (see
     *  {@link BloodCampFeature#onMoveEntity}). */
    long lastMoveTick;
    /** Debounce so Trigger Bot only ever clicks this one real mob once, no matter how many ticks it keeps
     *  being looked at after the click - killer560's own explicit request ("make sure it only clicks once
     *  not twice or any more than once"). */
    boolean triggerBotClicked = false;

    BloodMobState(Vec3 startVec, long startedAtTick, boolean firstSpawn) {
        restart(startVec, startedAtTick, firstSpawn);
    }

    /** Same entity, new trip - see {@link BloodCampFeature#onMoveEntity}. Everything derived from the previous
     *  trip has to go, including the "already clicked" debounce, or the mob is only ever hit once per run. */
    void restart(Vec3 startVec, long startedAtTick, boolean firstSpawn) {
        this.startVec = startVec;
        this.startedAtTick = startedAtTick;
        this.firstSpawn = firstSpawn;
        this.lastPosition = startVec;
        this.lastMoveTick = startedAtTick;
        this.deltaHistory.clear();
        this.endVector = null;
        this.triggerBotClicked = false;
    }
}
