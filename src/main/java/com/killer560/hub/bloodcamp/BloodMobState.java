package com.killer560.hub.bloodcamp;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;

/** Per-blood-mob tracking state - real fields ported directly from Noamm's own confirmed
 *  {@code BloodCamp.kt} ({@code BloodEntity}), see {@link BloodCampFeature}'s class doc. */
final class BloodMobState {

    final Vec3 startVec;
    final long startedAtTick;
    final boolean firstSpawn;
    final Deque<Vec3> deltaHistory = new ArrayDeque<>();
    Vec3 lastPosition;
    Vec3 endVector;
    /** Debounce so Trigger Bot only ever clicks this one real mob once, no matter how many ticks it keeps
     *  being looked at after the click - killer560's own explicit request ("make sure it only clicks once
     *  not twice or any more than once"). */
    boolean triggerBotClicked = false;

    BloodMobState(Vec3 startVec, long startedAtTick, boolean firstSpawn) {
        this.startVec = startVec;
        this.startedAtTick = startedAtTick;
        this.firstSpawn = firstSpawn;
        this.lastPosition = startVec;
    }
}
