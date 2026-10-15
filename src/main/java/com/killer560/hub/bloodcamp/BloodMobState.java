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
    /** Countdown sounds: each fires once per trip (see {@code BloodCampFeature#tickSounds}). */
    boolean startSoundDone = false;
    boolean killSoundDone = false;
    /** The exact position the trip's first move packet gave the stand (testkit comparison only). */
    Vec3 firstPacketExact;
    /** Game tick the kill timer went off for this trip (the box turned green), or -1. */
    long killTimerTick = -1L;
    /** Trigger Bot clicks this trip (the first plus any retries after a miss), the tick of the last, whether it was an
     *  attack (an entity under the crosshair in reach) or a swing on air, and the entity it attacked. */
    int clicks;
    long lastClickTick;
    boolean lastClickAttacked;
    int lastAttackedId = -1;
    /** Ticks after a click before the next retry may go (rolled per click: a human's re-click is not metronomic). */
    int retryGap;
    /** The mob took damage or died: the trip is done, the aura moves on and nothing is clicked again. */
    boolean confirmed;
    /** Out of retries (or out of time) without a hit. */
    boolean gaveUp;
    /** Where on the mob the aura aims, as a small fixed offset rolled per trip (fraction of the box). */
    float aimJitterX;
    float aimJitterY;
    float aimJitterZ;

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
        this.startSoundDone = false;
        this.killSoundDone = false;
        this.killTimerTick = -1L;
        this.clicks = 0;
        this.lastClickTick = 0L;
        this.lastClickAttacked = false;
        this.lastAttackedId = -1;
        this.retryGap = 0;
        this.confirmed = false;
        this.gaveUp = false;
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        this.aimJitterX = (r.nextFloat() * 2f - 1f) * 0.12f;
        this.aimJitterY = (r.nextFloat() * 2f - 1f) * 0.06f;
        this.aimJitterZ = (r.nextFloat() * 2f - 1f) * 0.12f;
    }
}
