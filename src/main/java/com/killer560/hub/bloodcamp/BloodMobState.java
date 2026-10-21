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
    /** The Kill Sound fires once per trip (see {@code BloodCampFeature#hitSound}). */
    boolean killSoundDone = false;
    /** The kill timer (box green) has been reached this trip - recorded once for the testkit. */
    boolean killableSeen = false;
    /** The exact position the trip's first move packet gave the stand (testkit comparison only). */
    Vec3 firstPacketExact;
    /** Trigger Bot clicks this trip (the first plus any retries after a miss), the tick of the last, whether it was an
     *  attack (an entity under the crosshair in reach) or a swing on air, and the entity it attacked. */
    int clicks;
    long lastClickTick;
    boolean lastClickAttacked;
    int lastAttackedId = -1;
    /** Ticks after a click before the next retry may go (rolled per click: a human's re-click is not metronomic). */
    int retryGap;
    /** The real mob that stood on this trip's landing spot when it landed (a blood mob, see BloodCampFeature), or -1. */
    int mobId = -1;
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

    /** One-line summary for the log (pure read). */
    String brief() {
        return String.format(java.util.Locale.ROOT, "start=%.2f,%.2f,%.2f end=%s firstSpawn=%b startedAt=%d clicks=%d "
                        + "confirmed=%b gaveUp=%b mob=%d", startVec.x, startVec.y, startVec.z,
                endVector == null ? "none" : String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", endVector.x,
                        endVector.y, endVector.z), firstSpawn, startedAtTick, clicks, confirmed, gaveUp, mobId);
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
        this.killSoundDone = false;
        this.killableSeen = false;
        this.clicks = 0;
        this.lastClickTick = 0L;
        this.lastClickAttacked = false;
        this.lastAttackedId = -1;
        this.retryGap = 0;
        this.confirmed = false;
        this.mobId = -1;
        this.gaveUp = false;
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        this.aimJitterX = (r.nextFloat() * 2f - 1f) * 0.12f;
        this.aimJitterY = (r.nextFloat() * 2f - 1f) * 0.06f;
        this.aimJitterZ = (r.nextFloat() * 2f - 1f) * 0.12f;
    }
}
