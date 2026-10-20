package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ServerLag;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * A short walk over the loaded blocks for an automation that otherwise etherwarps (Auto Clear, killer560 2026-10-19:
 * "make sure auto clear knows it can run and jump and stuff if needed if that is somehow faster"). CHEAT BUILD ONLY -
 * its one caller is behind {@code BuildVariant.CHEAT_FEATURES_ENABLED}.
 * <p>
 * Nothing new about the movement: the way is {@link LocalPlanner}'s A* (steps, jumps his real jump clears, drops), the
 * line ahead is {@link WalkTerrain#walkable}, the keys are {@link WalkPhysics}'s one-move-ahead choice of the nine key
 * combinations (sprint only with forward) braked to stop at the end, and the jump rule is {@link AutoWalker}'s (an
 * obstacle in front taller than his step and no taller than his jump). The keys reach the game the way Auto Walk Path's
 * do: {@code PathWalkInputMixin} rewrites the {@code Input} record {@code KeyboardInput#tick} built, so the physical
 * keys are never touched and nothing writes a position or a velocity. The caller turns the body (it owns the camera
 * hold); this class only says which yaw.
 * <p>
 * One walk at a time (one input mixin). Progress is judged on his real position every tick; no part of it waits a
 * fixed time. A walk that stops getting closer for {@link #STUCK_TICKS} (plus any server stall) ends as STUCK so the
 * caller can etherwarp instead.
 */
public final class ShortWalk {

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-pathfinding");

    /** Ticks with no progress of {@link #PROGRESS} blocks before the walk is called stuck. A failsafe, not a wait. */
    static final int STUCK_TICKS = 16;
    static final double PROGRESS = 0.25;
    /** A route point counts as passed within this, horizontally. */
    static final double PASSED = 0.55;
    /** Arrived: this close to the last point horizontally, within a block and a half of its height. */
    static final double ARRIVED = 0.45;
    /** Further than this off the planned line (horizontally) and the walk is off its route. */
    static final double OFF_ROUTE = 2.5;
    /** Points ahead looked at for the farthest straight-line carrot. */
    static final int CARROT_LOOKAHEAD = 6;
    /** Ticks the ground push takes to reach most of its top speed from a standstill (estimate only). */
    static final int START_TICKS = 2;
    /** Extra ticks a jump up a step costs over flat ground (estimate only). */
    static final int JUMP_TICKS = 3;

    /** A planned walk and its cost estimate. */
    public record Plan(List<Vec3> points, BlockPos goal, double length, double lengthToDone, int jumps,
                       double topSpeed, int ticks) {
        public String describe() {
            return String.format(Locale.US, "%.1f blocks on foot (%.1f until in reach), %d jump(s), top %.2f b/t",
                    length, lengthToDone, jumps, topSpeed);
        }
    }

    public enum Status { WALKING, ARRIVED, STUCK, OFF_ROUTE }

    private static Plan active;
    private static String owner = "";
    private static int next;
    private static int lastCombo;
    private static double best;
    private static int sinceProgress;
    private static long lagMark;
    private static boolean mixinApplied;

    private static boolean wantForward;
    private static boolean wantBack;
    private static boolean wantLeft;
    private static boolean wantRight;
    private static boolean wantJump;
    private static boolean wantSprint;

    /** Testkit counters. */
    private static int walksStarted;
    private static int jumpsPressed;

    private ShortWalk() {
    }

    // ------------------------------------------------------------------ planning

    /**
     * Plans a walk from where he stands to feet block {@code goalFeet} and estimates its ticks: {@link #START_TICKS},
     * the length up to the first point where {@code doneAt} accepts his feet (the whole length when null or never) at
     * his real sprinting top speed on this ground ({@link WalkPhysics}), and {@link #JUMP_TICKS} for each step up before
     * that point.
     * @return null when there is no way on foot within {@link LocalPlanner#RADIUS}
     */
    public static Plan plan(Level level, LocalPlayer player, BlockPos goalFeet, Predicate<Vec3> doneAt) {
        double jumpH = WalkTerrain.jumpHeight(player);
        BlockPos start = BlockPos.containing(player.getX(), player.getY() + 0.01, player.getZ());
        List<BlockPos> cells = LocalPlanner.plan(level, start, List.of(goalFeet), jumpH);
        if (cells.isEmpty()) {
            return null;
        }
        List<BlockPos> withGoal = new ArrayList<>(cells);
        if (!withGoal.get(withGoal.size() - 1).equals(goalFeet) && LocalPlanner.cell(level, goalFeet)) {
            withGoal.add(goalFeet);   // LocalPlanner's goal test takes a neighbour of it; finish on the block itself
        }
        List<Vec3> points = LocalPlanner.toPoints(withGoal);
        points.remove(0);   // his own block
        if (points.isEmpty()) {
            return null;
        }
        WalkPhysics.Model model = WalkPhysics.read(player);
        double k = model.friction * WalkPhysics.FRICTION_SCALE;
        double push = model.walkAttr * WalkPhysics.SPRINT_MULTIPLIER
                * (WalkPhysics.GROUND_CONSTANT / (model.friction * model.friction * model.friction));
        double top = push / (1.0 - k);   // the steady move a tick: v = (v + push) * k, moved v + push
        double length = 0;
        double toDone = -1;
        int jumps = 0;
        Vec3 at = player.position();
        if (doneAt != null && doneAt.test(at)) {
            toDone = 0;
        }
        for (Vec3 p : points) {
            length += Math.hypot(p.x - at.x, p.z - at.z);
            if (toDone < 0 && p.y - at.y > player.maxUpStep() + 0.01) {
                jumps++;
            }
            at = p;
            if (toDone < 0 && doneAt != null && doneAt.test(p)) {
                toDone = length;
            }
        }
        if (toDone < 0) {
            toDone = length;
        }
        int ticks = START_TICKS + (int) Math.ceil(toDone / Math.max(0.05, top)) + JUMP_TICKS * jumps;
        return new Plan(List.copyOf(points), goalFeet, length, toDone, jumps, top, ticks);
    }

    // ------------------------------------------------------------------ the walk

    public static void start(Plan plan, String who) {
        active = plan;
        owner = who;
        next = 0;
        lastCombo = 0;
        best = Double.MAX_VALUE;
        sinceProgress = 0;
        lagMark = ServerLag.mark();
        clearKeys();
        walksStarted++;
        LOGGER.info("[ShortWalk] {}: walking to {} - {}", who, plan.goal().toShortString(), plan.describe());
    }

    /** Ends the walk (any reason); the keys go up on the next input tick. */
    public static void stop(String why) {
        if (active != null) {
            LOGGER.info("[ShortWalk] {}: walk to {} ended - {}", owner, active.goal().toShortString(), why);
        }
        active = null;
        clearKeys();
    }

    public static boolean isActive() {
        return active != null;
    }

    /** The mixin asks: is a walk driving the keys right now. */
    public static boolean isDriving() {
        return active != null;
    }

    public static void onMixinApplied() {
        mixinApplied = true;
    }

    /** Whether the input mixin has ever run (without it a walk would press nothing, so callers do not choose one). */
    public static boolean mixinApplied() {
        return mixinApplied;
    }

    /**
     * One START_CLIENT_TICK of the walk: picks the carrot, has {@code turnBody} turn the body to face it, then sets the
     * keys for the move the same tick's input will make.
     */
    public static Status tick(Level level, LocalPlayer player, java.util.function.Consumer<Float> turnBody) {
        Plan plan = active;
        if (plan == null) {
            return Status.ARRIVED;
        }
        clearKeys();
        Vec3 pos = player.position();
        List<Vec3> pts = plan.points();
        while (next < pts.size() - 1 && Math.hypot(pts.get(next).x - pos.x, pts.get(next).z - pos.z) < PASSED) {
            next++;
        }
        Vec3 last = pts.get(pts.size() - 1);
        double remaining = Math.hypot(pts.get(next).x - pos.x, pts.get(next).z - pos.z);
        for (int i = next; i < pts.size() - 1; i++) {
            remaining += Math.hypot(pts.get(i + 1).x - pts.get(i).x, pts.get(i + 1).z - pts.get(i).z);
        }
        WalkPhysics.Model model = WalkPhysics.read(player);
        if (next == pts.size() - 1 && Math.hypot(last.x - pos.x, last.z - pos.z) < ARRIVED
                && Math.abs(last.y - pos.y) < 1.5 && player.onGround() && model.speed() < 0.12) {
            return Status.ARRIVED;
        }
        // ---- progress, on his real position: a failsafe, extended by any server stall
        if (remaining < best - PROGRESS) {
            best = remaining;
            sinceProgress = 0;
            lagMark = ServerLag.mark();
        } else if (++sinceProgress > STUCK_TICKS + ServerLag.ticksSince(lagMark)) {
            return Status.STUCK;
        }
        if (next > 0 && player.onGround()) {
            Vec3 a = pts.get(next - 1);
            Vec3 b = pts.get(next);
            if (offLine(pos, a, b) > OFF_ROUTE) {
                return Status.OFF_ROUTE;
            }
        }
        double jumpH = WalkTerrain.jumpHeight(player);
        // ---- the carrot: the farthest of the next few points with a straight walk to it
        Vec3 carrot = pts.get(next);
        for (int i = Math.min(pts.size() - 1, next + CARROT_LOOKAHEAD); i > next; i--) {
            if (WalkTerrain.walkable(level, pos, pts.get(i), jumpH)) {
                carrot = pts.get(i);
                break;
            }
        }
        double dx = carrot.x - pos.x;
        double dz = carrot.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.3) {
            float yaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
            turnBody.accept(player.getYRot() + Mth.wrapDegrees(yaw - player.getYRot()));
        }
        // ---- the keys: braked so the move and its slide stop at the end of the walk
        double allowed = Math.max(0.05, WalkPhysics.speedToStopWithin(remaining, model.friction) + 0.04);
        double ux = horizontal < 1.0e-6 ? 0 : dx / horizontal;
        double uz = horizontal < 1.0e-6 ? 0 : dz / horizontal;
        chooseKeys(model, player.getYRot(), ux * allowed, uz * allowed,
                !player.isInWater() && remaining > 1.5);
        // ---- a jump: AutoWalker's rule, an obstacle in front this jump clears
        double heading = Math.toRadians(player.getYRot());
        double hx = -Math.sin(heading);
        double hz = Math.cos(heading);
        double riseAhead = Math.max(WalkTerrain.rise(level, pos.x + hx * 0.45, pos.z + hz * 0.45, pos.y),
                WalkTerrain.rise(level, pos.x + hx * 0.85, pos.z + hz * 0.85, pos.y));
        boolean jumpable = riseAhead > player.maxUpStep() + 0.01 && riseAhead <= jumpH - 0.05
                && WalkTerrain.headroom(level, pos.x + hx * 0.85, pos.z + hz * 0.85, pos.y + riseAhead);
        if (player.onGround() && wantForward && (jumpable || player.isInWater() && carrot.y > pos.y + 0.3)) {
            wantJump = true;
            jumpsPressed++;
        }
        return Status.WALKING;
    }

    /** {@code AutoWalker.chooseKeys}'s rule: the key combination whose next move comes closest to the wanted one. */
    private static void chooseKeys(WalkPhysics.Model model, float bodyYaw, double wantX, double wantZ, boolean sprintOk) {
        int bestCombo = 0;
        boolean bestSprint = false;
        double bestScore = Double.MAX_VALUE;
        double wantLen = Math.sqrt(wantX * wantX + wantZ * wantZ);
        for (int c = 0; c < WalkPhysics.KEYS.length; c++) {
            int fw = WalkPhysics.KEYS[c][0];
            int st = WalkPhysics.KEYS[c][1];
            for (int s = 0; s < 2; s++) {
                boolean sprint = s == 1;
                if (sprint && (!sprintOk || fw <= 0)) {
                    continue;
                }
                double[] dir = WalkPhysics.keyDirection(fw, st, bodyYaw);
                double push = model.push(sprint);
                double mx = model.vx + dir[0] * push;
                double mz = model.vz + dir[1] * push;
                double ex = mx - wantX;
                double ez = mz - wantZ;
                double score = ex * ex + ez * ez;
                if (wantLen > 1.0e-6 && (mx * wantX + mz * wantZ) / wantLen > wantLen) {
                    score *= 3.0;   // too fast costs more than too slow
                }
                if (c != lastCombo) {
                    score += 0.0004;
                }
                if (fw != 1) {
                    score += 0.00005;
                }
                if (score < bestScore) {
                    bestScore = score;
                    bestCombo = c;
                    bestSprint = sprint;
                }
            }
        }
        lastCombo = bestCombo;
        int fw = WalkPhysics.KEYS[bestCombo][0];
        int st = WalkPhysics.KEYS[bestCombo][1];
        wantForward = fw > 0;
        wantBack = fw < 0;
        wantLeft = st > 0;
        wantRight = st < 0;
        wantSprint = bestSprint;
    }

    private static double offLine(Vec3 p, Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double len2 = dx * dx + dz * dz;
        if (len2 < 1e-6) {
            return Math.hypot(p.x - a.x, p.z - a.z);
        }
        double t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.z - a.z) * dz) / len2));
        return Math.hypot(p.x - (a.x + dx * t), p.z - (a.z + dz * t));
    }

    private static void clearKeys() {
        wantForward = false;
        wantBack = false;
        wantLeft = false;
        wantRight = false;
        wantJump = false;
        wantSprint = false;
    }

    public static boolean wantForward() { return wantForward; }
    public static boolean wantBack() { return wantBack; }
    public static boolean wantLeft() { return wantLeft; }
    public static boolean wantRight() { return wantRight; }
    public static boolean wantJump() { return wantJump; }
    public static boolean wantSprint() { return wantSprint; }

    /** For the testkit. */
    public static int walksStarted() { return walksStarted; }
    public static int jumpsPressed() { return jumpsPressed; }
}
