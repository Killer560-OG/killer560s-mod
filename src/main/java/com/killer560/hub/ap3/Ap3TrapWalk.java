package com.killer560.hub.ap3;

import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * An Auto Routes PATH pair walked by AP3 inside a trap room (killer560, 2026-10-19: in a trap room "a path node walks
 * from its first node to its second node, with no etherwarp", using AP3's path logic). No ability works in a trap room,
 * so the pair cannot fly its saved etherwarps; this walks it instead.
 * <p>
 * Nothing here is a new walker. It is AP3's Path node ({@link Ap3RouteRunner}) with one gate: the same world snapshot
 * ({@link Ap3RouteRunner.Snap}), the same planner ({@link Ap3RoutePlanner}, on a worker thread, first plan with the big
 * budget and the 4x retry when it only ran out of clock), the same movement model ({@link Ap3Executor#routeModel}), and
 * the same drift handling while it drives: off the plan by {@link #DRIFT_LIMIT} it re-plans from the state the plan
 * predicts {@link #PLAN_LATENCY} ticks on, by {@link #LOST_LIMIT} it holds still until the re-plan lands, and it re-syncs
 * every {@link #REPLAN_EVERY} ticks when the game is not following to a few hundredths. Each tick it hands back one
 * planned step - a real key combination at a yaw, plus jump and sprint - which Auto Routes installs through its own input
 * mixin; nothing writes a position or a velocity.
 * <p>
 * Saved like a path's warps (killer560, 2026-10-20): a first plan that completes is handed to the caller
 * ({@link SavedWalk}) to keep on the route's node, and a later run that starts where that plan started, at about the same
 * speed, plays it without searching (AP3's own rule for reusing a plan: within 0.5 blocks and 0.15 blocks/tick). Re-plans
 * mid-walk are never saved, as in AP3.
 * <p>
 * The finish is an AP3 ALIGN's (killer560, 2026-10-20: stop the way an align stops): once the plan has carried him into
 * the second node, the regular camera-method align ({@link Ap3DiscretePlanner#plan}, the yaw turned at most
 * {@link Ap3Executor#ALIGN_YAW_STEP_FOR_WALK} a tick, sprint off) brings him to rest on the node's point - within the
 * Align Tolerance and under vanilla's 0.003 zeroing line, held {@link Ap3Executor#ALIGN_SETTLE_TICKS} - so he neither
 * overshoots nor keeps drifting.
 * <p>
 * Refused, never guessed: a destination with no ground AP3 can stand on, or a plan the search cannot complete (a wall,
 * a gap, lava between the two nodes), ends in {@link Phase#REFUSED} with the reason, and Auto Routes stops the route
 * with it in chat.
 */
public final class Ap3TrapWalk {

    private static final Logger LOGGER = ModLog.get("killer560smod-autotrap");

    /** Ap3RouteRunner's numbers, for the same reasons (see there). */
    private static final double DRIFT_LIMIT = 0.35;
    private static final double LOST_LIMIT = 1.5;
    private static final int PLAN_LATENCY = 6;
    private static final int REPLAN_EVERY = 20;
    private static final int RETRY_EVERY = 20;
    private static final double REASK_MOVED = 1.5;
    private static final long BRUTE_FORCE_MS = 4000;
    /** Ap3RouteRunner.takePrePlan: how close a saved plan's start must be, in place and in speed, to be replayed. */
    private static final double SAVED_MAX_OFF = 0.5;
    private static final double SAVED_MAX_SPEED_OFF = 0.15;
    /** Ap3Executor.ALIGN_REACH: an align starts only this close to its point; further out the walk plans again. */
    private static final double ALIGN_REACH = 4.0;

    public enum Phase { IDLE, PLANNING, WALKING, ALIGNING, ARRIVED, REFUSED }

    /** One tick of keys: forward/back/left/right/jump/sneak/sprint, and the yaw to face (an absolute MC yaw). */
    public record Drive(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sneak,
                        boolean sprint, float yaw) {
    }

    /** One tick of a saved walk: {@code fw}/{@code st} in -1..1, an absolute yaw. */
    public record SavedStep(int fw, int st, boolean sneak, float yaw, boolean jump, boolean sprint) {
    }

    /** A planned walk and the state it was planned from (real coordinates, feet and velocity). */
    public record SavedWalk(double x, double y, double z, double vx, double vy, double vz, boolean onGround,
                            List<SavedStep> steps) {
    }

    private Ap3TrapWalk() {
    }

    private static Phase phase = Phase.IDLE;
    private static String refusal;
    private static String label = "";
    private static Ap3RoutePlanner.Gate gate;
    private static Ap3RoutePlanner.Plan plan;
    private static int stepIndex;
    private static int lastPlanStep;
    private static int retryIn;
    private static boolean firstPlanDone;
    private static Ap3RouteMath.RouteState planStart;
    private static Ap3RouteMath.RouteState predicted;
    private static Ap3DiscretePlanner.Model planModel;
    private static volatile Ap3RoutePlanner.Terrain snapshot;
    private static volatile Ap3RoutePlanner.Plan pending;
    private static volatile Ap3RouteMath.RouteState pendingFrom;
    private static volatile int pendingAt;
    private static volatile int pendingSeq = -1;
    private static volatile boolean pendingFirst;
    private static volatile boolean planning;
    private static int acceptFrom;
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();
    private static final Object PUBLISH = new Object();
    private static Thread worker;
    private static long startedMs;
    private static int ticks;
    private static Consumer<SavedWalk> onPlanned;
    /** The align finish: its ticks, ticks at rest on the point, and the sneak the last tick installed. */
    private static int alignTicks;
    private static int settleTicks;
    private static boolean lastSneak;

    public static Phase phase() {
        return phase;
    }

    /** Why the walk was refused (set with {@link Phase#REFUSED}). */
    public static String refusal() {
        return refusal;
    }

    public static boolean active() {
        return phase == Phase.PLANNING || phase == Phase.WALKING || phase == Phase.ALIGNING;
    }

    /**
     * Starts a walk from where he stands to {@code to} (feet, real coordinates), arriving inside a square of half size
     * {@code half} and then aligning onto {@code to} itself. {@code what} names the pair in the log ("Path #3 -> #4 in
     * Old Trap"). {@code saved} is the walk kept from an earlier run (null for none); {@code planned} receives a fresh
     * first plan that completed, on the client thread, to be kept with the route.
     */
    public static void begin(Minecraft client, LocalPlayer player, Vec3 to, double half, String what, SavedWalk saved,
                             Consumer<SavedWalk> planned) {
        cancel(null);
        label = what == null ? "" : what;
        onPlanned = planned;
        Ap3RoutePlanner.Gate g = new Ap3RoutePlanner.Gate();
        g.x = to.x;
        g.y = to.y;
        g.z = to.z;
        g.halfW = Math.max(0.05, half);
        g.halfL = Math.max(0.05, half);
        g.exactTol = Ap3Config.getInstance().getRouteExactTolerance();
        g.captureBlocks();
        g.group = 1;
        g.mustLand = true; // the walk ends standing on the second node, like an AP3 route's last gate
        gate = g;
        phase = Phase.PLANNING;
        refusal = null;
        firstPlanDone = false;
        startedMs = System.currentTimeMillis();
        ticks = 0;
        alignTicks = 0;
        settleTicks = 0;
        lastSneak = false;
        retryIn = RETRY_EVERY;
        acceptFrom = SEQ.get() + 1;
        Ap3RouteMath.RouteState from = Ap3RouteRunner.stateOf(player);
        LOGGER.info("[TrapWalk] {}: walk starts at ({}, {}, {}) to ({}, {}, {}), {} blocks, arriving within {} - AP3 path "
                        + "planning, no etherwarp", label, f(from.x), f(from.y), f(from.z), f(to.x), f(to.y), f(to.z),
                f(Math.hypot(to.x - from.x, to.z - from.z)), f(half));
        if (!readWorld(client, player, from, true)) {
            return;
        }
        if (saved != null && !saved.steps().isEmpty()) {
            double off = Math.hypot(from.x - saved.x(), from.z - saved.z());
            double speedOff = Math.abs(Math.hypot(from.vx, from.vz) - Math.hypot(saved.vx(), saved.vz()));
            if (off <= SAVED_MAX_OFF && Math.abs(from.y - saved.y()) < 0.6 && speedOff <= SAVED_MAX_SPEED_OFF) {
                Ap3RoutePlanner.Plan p = new Ap3RoutePlanner.Plan();
                p.steps = new Ap3RoutePlanner.Step[saved.steps().size()];
                for (int i = 0; i < p.steps.length; i++) {
                    SavedStep s = saved.steps().get(i);
                    p.steps[i] = new Ap3RoutePlanner.Step(new Ap3DiscretePlanner.Action(s.fw(), s.st(), s.sneak()),
                            s.yaw(), s.jump(), s.sprint());
                }
                p.ticks = p.steps.length;
                p.complete = true;
                plan = p;
                stepIndex = 0;
                lastPlanStep = 0;
                planStart = from;
                planModel = Ap3Executor.routeModel(player);
                predicted = from.copy();
                firstPlanDone = true;
                phase = Phase.WALKING;
                LOGGER.info("[TrapWalk] {}: using the saved {}-tick walk ({} from where it was planned, speed {} off) - "
                        + "no search", label, p.ticks, f(off), f(speedOff));
                return;
            }
            LOGGER.info("[TrapWalk] {}: the saved walk was planned from ({}, {}, {}) at speed {} - he is {} away at speed {}"
                            + ", planning afresh", label, f(saved.x()), f(saved.y()), f(saved.z()),
                    f(Math.hypot(saved.vx(), saved.vz())), f(off), f(Math.hypot(from.vx, from.vz)));
        }
        startPlanning(player, from, 0, true);
    }

    /**
     * Starts an AP3 align with no walk before it: the Auto Routes ALIGN node (killer560, 2026-10-20: "an align node that
     * I can do W2 L2 on"). The same finish as a walk's ({@link #tickAlign}, which is Ap3Executor's camera-method ALIGN):
     * onto {@code to} (feet, real coordinates) within the Align Tolerance, at rest, held the settle ticks. {@code halfX} /
     * {@code halfZ} are the node's W x L box in real axes, which is where an align that times out at rest still counts as
     * arrived, as in a walk. Refused at once - as AP3 fails an align - when he stands further than
     * {@code ALIGN_REACH + W/2 + L/2} from the point. A correction is honoured and the align carries on
     * ({@link #onCorrection}).
     */
    public static void beginAlign(Minecraft client, LocalPlayer player, Vec3 to, double halfX, double halfZ, String what) {
        cancel(null);
        label = what == null ? "" : what;
        onPlanned = null;
        Ap3RoutePlanner.Gate g = new Ap3RoutePlanner.Gate();
        g.x = to.x;
        g.y = to.y;
        g.z = to.z;
        g.halfW = Math.max(0.05, halfX);
        g.halfL = Math.max(0.05, halfZ);
        g.exactTol = Ap3Config.getInstance().getRouteExactTolerance();
        g.captureBlocks();
        g.group = 1;
        g.mustLand = true;
        gate = g;
        refusal = null;
        firstPlanDone = true; // nothing to save: a re-plan after a correction is never kept
        startedMs = System.currentTimeMillis();
        ticks = 0;
        alignTicks = 0;
        settleTicks = 0;
        lastSneak = false;
        retryIn = RETRY_EVERY;
        acceptFrom = SEQ.get() + 1;
        double dist = Math.hypot(to.x - player.getX(), to.z - player.getZ());
        double reach = ALIGN_REACH + g.halfW + g.halfL;
        phase = Phase.ALIGNING;
        LOGGER.info("[TrapWalk] {}: align starts at ({}, {}, {}) onto ({}, {}, {}), {} off (reach {}) - AP3 camera-method "
                        + "align", label, f(player.getX()), f(player.getY()), f(player.getZ()), f(to.x), f(to.y), f(to.z),
                f(dist), f(reach));
        if (dist > reach) {
            refuse(String.format(Locale.US, "too far from the align point (%.1f blocks, reach %.1f)", dist, reach));
        }
    }

    /** Ends the walk ({@code why} logged when it was still going; null = a silent reset). */
    public static void cancel(String why) {
        if (active() && why != null) {
            LOGGER.info("[TrapWalk] {}: walk stopped after {} tick(s) at step {}/{} ({}) - {}", label, ticks, stepIndex,
                    plan == null ? 0 : plan.steps.length, phase, why);
        }
        phase = Phase.IDLE;
        plan = null;
        predicted = null;
        pending = null;
        stepIndex = 0;
        planning = false;
        onPlanned = null;
        acceptFrom = SEQ.get() + 1; // no answer to an older request may drive the next walk
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * The server moved him (a correction): the schedule means nothing from there. Drop it and plan again from where he
     * is now - the walk carries on, it never stops for a correction. The align finish re-plans from the measured state
     * every tick anyway, so it carries on as it is unless the correction took him out of an align's reach.
     */
    public static void onCorrection(Minecraft client, LocalPlayer player) {
        if (!active()) {
            return;
        }
        if (phase == Phase.ALIGNING
                && Math.hypot(gate.x - player.getX(), gate.z - player.getZ()) <= ALIGN_REACH) {
            LOGGER.info("[TrapWalk] {}: server correction while aligning - the align carries on from ({}, {}, {})", label,
                    f(player.getX()), f(player.getY()), f(player.getZ()));
            settleTicks = 0;
            return;
        }
        LOGGER.info("[TrapWalk] {}: server correction at step {} - re-planning from ({}, {}, {}), carrying on", label,
                stepIndex, f(player.getX()), f(player.getY()), f(player.getZ()));
        if (phase == Phase.ALIGNING) {
            phase = Phase.WALKING;
        }
        plan = null;
        predicted = null;
        pending = null;
        planning = false;
        acceptFrom = SEQ.get() + 1;
        retryIn = 0;
        Ap3RouteMath.RouteState from = Ap3RouteRunner.stateOf(player);
        if (readWorld(client, player, from, false)) {
            startPlanning(player, from, 0, !firstPlanDone);
        }
    }

    /**
     * One tick (Auto Routes calls it at the START of the client tick, before the player's own tick moves him): the keys to
     * hold this tick, or null for none (planning, holding still while lost, or done - see {@link #phase}).
     */
    public static Drive tick(Minecraft client, LocalPlayer player) {
        if (!active() || player == null || client.level == null) {
            return null;
        }
        ticks++;
        if (phase == Phase.ALIGNING) {
            return tickAlign(player);
        }
        if (ticks > Ap3Config.getInstance().getRouteTimeoutTicks()) {
            refuse("AP3 could not walk it in " + Ap3Config.getInstance().getRouteTimeoutTicks()
                    + " ticks (AP3 Route Timeout)");
            return null;
        }
        if (plan == null && planning && planStart != null && !firstPlanDone
                && Math.hypot(player.getX() - planStart.x, player.getZ() - planStart.z) > REASK_MOVED) {
            LOGGER.info("[TrapWalk] {}: moved {} blocks while the first search ran - asking again from here", label,
                    f(Math.hypot(player.getX() - planStart.x, player.getZ() - planStart.z)));
            planning = false;
            acceptFrom = SEQ.get() + 1;
            Ap3RouteMath.RouteState from = Ap3RouteRunner.stateOf(player);
            if (readWorld(client, player, from, false)) {
                startPlanning(player, from, 0, true);
            }
            return null;
        }
        Ap3RoutePlanner.Plan fresh = pendingSeq >= acceptFrom ? pending : null;
        if (fresh != null) {
            pending = null;
            boolean wasFirst = pendingFirst;
            Ap3RouteMath.RouteState from = pendingFrom;
            if (!firstPlanDone) {
                firstPlanDone = true;
                if (!fresh.complete) {
                    String why = fresh.unreachable
                            ? "no walkable way there - nothing AP3 can stand on joins the two nodes"
                            : "AP3 found no walk there (" + (fresh.note.isEmpty() ? "no route" : fresh.note) + ")";
                    if (!fresh.diagnosis.isEmpty()) {
                        LOGGER.info("[TrapWalk] {}: why: {}", label, fresh.diagnosis);
                    }
                    refuse(why);
                    return null;
                }
                phase = Phase.WALKING;
                LOGGER.info("[TrapWalk] {}: planned {} ticks ({} jump(s)) in {} ms - walking", label, fresh.ticks,
                        jumps(fresh), System.currentTimeMillis() - startedMs);
                if (wasFirst && onPlanned != null && from != null) {
                    // Kept with the route, like a path's warps: the next run from here plays it without a search.
                    List<SavedStep> steps = new ArrayList<>(fresh.steps.length);
                    for (Ap3RoutePlanner.Step s : fresh.steps) {
                        steps.add(new SavedStep(s.keys().fw(), s.keys().st(), s.keys().sneak(), s.yaw(), s.jump(),
                                s.sprint()));
                    }
                    onPlanned.accept(new SavedWalk(from.x, from.y, from.z, from.vx, from.vy, from.vz, from.onGround,
                            steps));
                }
            } else if (fresh.steps.length == 0 && !fresh.complete) {
                LOGGER.info("[TrapWalk] {}: the re-plan got nowhere ({}) - keeping the current plan", label, fresh.note);
                fresh = null;
            }
            if (fresh != null && (plan == null || stepIndex >= pendingAt)) {
                int late = plan == null ? 0 : Math.max(0, stepIndex - pendingAt);
                if (late < fresh.steps.length) {
                    plan = fresh;
                    stepIndex = late;
                    lastPlanStep = late;
                    predicted = Ap3RouteRunner.stateOf(player);
                } else {
                    LOGGER.info("[TrapWalk] {}: the re-plan arrived {} tick(s) late, all its steps past - keeping the "
                            + "current one", label, late);
                }
            }
        }
        if (plan == null) {
            if (!planning && --retryIn <= 0) {
                // Nobody is working on one (a re-plan that got nowhere, a lost hold, or a first search that threw):
                // ask again, at most once every RETRY_EVERY ticks, as AP3 does.
                retryIn = RETRY_EVERY;
                Ap3RouteMath.RouteState from = Ap3RouteRunner.stateOf(player);
                if (readWorld(client, player, from, false)) {
                    startPlanning(player, from, 0, !firstPlanDone);
                }
            }
            return null; // still planning: no keys, he coasts
        }
        retryIn = 0;
        if (stepIndex >= plan.steps.length) {
            if (!plan.complete) {
                plan = null; // a partial re-plan that ran out: ask for the rest (rate limited above)
                predicted = null;
                return null;
            }
            double off = Math.hypot(player.getX() - gate.x, player.getZ() - gate.z);
            if (off > ALIGN_REACH) {
                LOGGER.info("[TrapWalk] {}: the plan ran out {} from the second node - re-planning", label, f(off));
                plan = null;
                predicted = null;
                retryIn = 0;
                return null;
            }
            phase = Phase.ALIGNING;
            alignTicks = 0;
            settleTicks = 0;
            LOGGER.info("[TrapWalk] {}: walk plan done after {} tick(s), {} from the second node at speed {} - aligning "
                            + "onto it like an AP3 align", label, ticks, f(off),
                    String.format(Locale.US, "%.4f", player.getDeltaMovement().horizontalDistance()));
            return tickAlign(player);
        }
        boolean drifted = false;
        boolean lost = false;
        double off = 0;
        if (predicted != null) {
            off = Math.hypot(player.getX() - predicted.x, player.getZ() - predicted.z);
            drifted = off > DRIFT_LIMIT;
            lost = off > LOST_LIMIT;
            if (drifted) {
                LOGGER.info("[TrapWalk] {}: {} blocks off the plan at step {} - re-planning{}", label, f(off), stepIndex,
                        lost ? " (lost - holding still)" : "");
            }
        }
        boolean worth = drifted || !plan.complete || off > 0.05;
        if (!planning && worth && (drifted || stepIndex - lastPlanStep >= REPLAN_EVERY)) {
            lastPlanStep = stepIndex;
            Ap3RouteMath.RouteState from = Ap3RouteRunner.stateOf(player);
            int at = stepIndex;
            for (int i = 0; i < PLAN_LATENCY && stepIndex + i < plan.steps.length; i++) {
                Ap3RoutePlanner.Step st = plan.steps[stepIndex + i];
                Ap3RouteMath.step(from, st.keys(), st.yaw(), st.jump(), st.sprint(), planModel, world());
                at = stepIndex + i + 1;
            }
            startPlanning(player, from, at, false);
        }
        if (lost) {
            plan = null;
            predicted = null;
            return null;
        }
        Ap3RoutePlanner.Step s = plan.steps[stepIndex++];
        if (predicted != null) {
            Ap3RouteMath.step(predicted, s.keys(), s.yaw(), s.jump(), s.sprint(), planModel, world());
        }
        Ap3DiscretePlanner.Action a = s.keys();
        lastSneak = a.sneak();
        return new Drive(a.fw() > 0, a.fw() < 0, a.st() > 0, a.st() < 0, s.jump(), a.sneak(), s.sprint() && a.fw() > 0,
                s.yaw());
    }

    /**
     * The finish, as Ap3Executor.tickAlign does it for a camera-method ALIGN: done when both axes are within the Align
     * Tolerance and the velocity is under the zeroing line, held for the settle ticks; otherwise the discrete planner's
     * key combination for this tick at a yaw at most ALIGN_YAW_STEP from the current one, sprint off. Timed out by the
     * AP3 Align Timeout: inside the second node's ring that is where the walk ends (logged with the error); outside it,
     * refused.
     */
    private static Drive tickAlign(LocalPlayer player) {
        alignTicks++;
        double ex = gate.x - player.getX();
        double ez = gate.z - player.getZ();
        double err = Math.max(Math.abs(ex), Math.abs(ez));
        Ap3Config cfg = Ap3Config.getInstance();
        Vec3 vel = player.getDeltaMovement();
        boolean still = Ap3AlignMath.horizontalZeroed(vel.x, vel.z);
        if (err <= cfg.getAlignTolerance() && still) {
            if (++settleTicks >= Ap3Executor.ALIGN_SETTLE_TICKS) {
                arrive(String.format(Locale.US, "aligned in %d tick(s), %+.5f / %+.5f off", alignTicks,
                        player.getX() - gate.x, player.getZ() - gate.z));
            }
            lastSneak = false;
            return null;
        }
        settleTicks = 0;
        if (alignTicks > cfg.getAlignTimeoutTicks()) {
            if (still && Math.abs(ex) <= gate.halfW && Math.abs(ez) <= gate.halfL) {
                arrive(String.format(Locale.US, "align timed out after %d tick(s) at rest %.4f off, inside the node",
                        alignTicks, err));
            } else {
                refuse(String.format(Locale.US, "couldn't align on the node (%.4f blocks off after %d ticks)",
                        err, alignTicks));
            }
            return null;
        }
        Ap3DiscretePlanner.Model m = Ap3Executor.alignModel(player);
        Ap3DiscretePlanner.State s = new Ap3DiscretePlanner.State();
        s.ex = ex;
        s.ez = ez;
        s.vx = vel.x;
        s.vz = vel.z;
        s.sprinting = player.isSprinting();
        s.crouching = lastSneak;
        s.sentYaw = player.getYRot();
        Ap3DiscretePlanner.Plan p = Ap3DiscretePlanner.plan(s, m);
        float step = (float) Ap3Executor.ALIGN_YAW_STEP_FOR_WALK;
        float yaw = player.getYRot() + Mth.clamp(Mth.wrapDegrees(p.yaw - player.getYRot()), -step, step);
        Ap3DiscretePlanner.Action a = p.action;
        lastSneak = a.sneak();
        return new Drive(a.fw() > 0, a.fw() < 0, a.st() > 0, a.st() < 0, false, a.sneak(), false, yaw);
    }

    private static void arrive(String how) {
        phase = Phase.ARRIVED;
        LocalPlayer p = Minecraft.getInstance().player;
        LOGGER.info("[TrapWalk] {}: walk ends at ({}, {}, {}) after {} tick(s) ({} ms) - {}", label,
                f(p == null ? Double.NaN : p.getX()), f(p == null ? Double.NaN : p.getY()),
                f(p == null ? Double.NaN : p.getZ()), ticks, System.currentTimeMillis() - startedMs, how);
    }

    private static void refuse(String why) {
        refusal = why;
        LOGGER.info("[TrapWalk] {}: refused after {} tick(s) - {}", label, ticks, why);
        cancel(null);
        phase = Phase.REFUSED;
    }

    private static Ap3RouteCollide.Shapes world() {
        Ap3RoutePlanner.Terrain t = snapshot;
        return t == null ? Ap3RouteMath.FLAT_GROUND : t.shapes();
    }

    /**
     * Reads the world around the walk on the client thread (Ap3RouteRunner.Snap - the drift prediction runs through it
     * even when a saved plan is replayed). On the first read the second node must be ground AP3 can stand on, else the
     * walk is refused. @return false when refused.
     */
    private static boolean readWorld(Minecraft client, LocalPlayer player, Ap3RouteMath.RouteState start, boolean first) {
        if (client.level == null || gate == null) {
            return false;
        }
        Ap3RouteRunner.Snap snap = Ap3RouteRunner.Snap.of(client.level, player, List.of(gate), start);
        snapshot = snap;
        if (first) {
            double floor = snap.floorAt(gate.x, gate.z);
            if (Double.isNaN(floor) || Math.abs(floor - gate.y) > 1.0) {
                refuse("the second node is not on ground AP3 can stand on (floor there: "
                        + (Double.isNaN(floor) ? "none" : f(floor)) + ", node at y " + f(gate.y) + ")");
                return false;
            }
        }
        return true;
    }

    /** Ap3RouteRunner.startPlanning for one gate, with no chain to avoid (a dungeon room has none). */
    private static void startPlanning(LocalPlayer player, Ap3RouteMath.RouteState start, int at, boolean first) {
        Ap3RoutePlanner.Terrain snap = snapshot;
        if (planning || gate == null || snap == null) {
            return;
        }
        List<Ap3RoutePlanner.Gate> gates = new ArrayList<>(List.of(gate));
        Ap3DiscretePlanner.Model model = Ap3Executor.routeModel(player);
        Ap3Config cfg = Ap3Config.getInstance();
        Ap3RoutePlanner.Options options = new Ap3RoutePlanner.Options();
        options.allowJump = cfg.isRouteAllowJumps();
        options.scanPad = cfg.getRouteScanPad();
        options.beam = first ? Math.min(4000, cfg.getRouteBeam() * 3) : cfg.getRouteBeam();
        double span = Math.hypot(gate.x - start.x, gate.z - start.z);
        double top = Math.max(0.2, Ap3RouteMath.topSpeed(model, options.allowJump));
        options.maxTicks = (int) Math.max(160, Math.min(900, span / top * 3.0 + 60));
        long scaled = (long) (BRUTE_FORCE_MS * Math.max(1.0, Math.min(8.0, span / 15.0)));
        options.budgetMs = first ? Math.max(cfg.getRouteBudgetMs(), scaled) : Math.min(cfg.getRouteBudgetMs(), 300);
        options.portfolio = first;
        planStart = start;
        planModel = model;
        pendingAt = at;
        planning = true;
        final List<Ap3RoutePlanner.Blocked> blocked = List.of();
        final Ap3RouteMath.RouteState from = start.copy();
        final int seq = SEQ.incrementAndGet();
        worker = new Thread(() -> {
            try {
                Ap3RoutePlanner.Plan p = Ap3RoutePlanner.plan(start, gates, blocked, snap, model, options);
                if (first && !p.complete && p.note.contains("time budget") && !p.unreachable) {
                    Ap3RoutePlanner.Options more = options.copy();
                    more.budgetMs = options.budgetMs * 4;
                    LOGGER.info("[TrapWalk] out of time at {} ms - trying once more with {} ms", options.budgetMs,
                            more.budgetMs);
                    Ap3RoutePlanner.Plan longer = Ap3RoutePlanner.plan(start, gates, blocked, snap, model, more);
                    if (longer.complete || longer.gatesReached > p.gatesReached) {
                        p = longer;
                    }
                }
                synchronized (PUBLISH) {
                    if (seq >= pendingSeq) {
                        pendingFrom = from;
                        pendingFirst = first;
                        pending = p;
                        pendingSeq = seq;
                    }
                }
                LOGGER.info("[TrapWalk] {}plan: {} ticks{}, from ({}, {}) splicing at step {}", first ? "first " : "re-",
                        p.ticks, p.complete ? "" : " INCOMPLETE - " + p.note, f(start.x), f(start.z), at);
            } catch (Throwable t) {
                LOGGER.warn("[TrapWalk] planning failed", t);
            } finally {
                planning = false;
            }
        }, "killer560smod-trapwalk");
        worker.setDaemon(true);
        worker.start();
    }

    private static int jumps(Ap3RoutePlanner.Plan p) {
        int n = 0;
        for (Ap3RoutePlanner.Step s : p.steps) {
            if (s.jump()) {
                n++;
            }
        }
        return n;
    }

    private static String f(double v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
