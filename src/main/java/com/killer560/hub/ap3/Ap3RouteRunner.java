package com.killer560.hub.ap3;

import com.killer560.hub.dungeonextras.BreakerAuraFeature;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs an optimised route through the chain's Path nodes - killer560 (2026-09-22): "a movement optimizer/pathfinder...
 * set the first node, a second node, a third node... find the most optimal movement to hit each node from the first to
 * the last without skipping one".
 * <p>
 * The search itself is {@link Ap3RoutePlanner} (no Minecraft types, so it can be tested offline and run off the client
 * thread). This class is the part that touches the game:
 * <ol>
 * <li><b>Reads the world once</b> into a {@link Snap} - which block columns you can stand in and which are walls -
 *     because the planner runs on a worker thread and cannot touch the level. Blocks Breaker Aura is going to break
 *     are recorded as air (killer560: "If a block is selected for breaker aura treat that block as not being there").</li>
 * <li><b>Plans on a worker thread</b> so a 1-2 second search never freezes the game, then</li>
 * <li><b>drives the schedule</b> one tick at a time through the same {@code writeDiscrete} every other AP3 node uses -
 *     real key combinations at a real yaw, nothing written to the position or the velocity - re-planning when the game
 *     drifts from the plan, and stopping dead the moment the server corrects you.</li>
 * </ol>
 * A Path node marked {@code term} ends its leg standing still, waits for the terminal GUI to open and close, and the
 * next leg is planned from rest (killer560: "it will have to wait for a term to close and thus lose its speed").
 */
final class Ap3RouteRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ap3");
    /** The player's box: 0.6 wide, 1.8 tall. */
    private static final double HALF_WIDTH = 0.3;
    private static final double BODY_HEIGHT = 1.8;
    /** How far outside the gates the world is sampled, so a route can swing wide around a wall. */
    /**
     * How far past the route the world snapshot reaches. It must be at least the planner's scanPad, or the
     * heuristic would be offering ways round through blocks the collider cannot see.
     */
    private static int snapPad() {
        return (int) Math.ceil(Ap3Config.getInstance().getRouteScanPad()) + 2;
    }
    /**
     * Off the plan by more than this and a fresh plan is started - but the current one keeps driving while it is
     * computed, re-anchored to the measured position. Dropping it instead made the route stall every few ticks
     * (killer560, 2026-09-22: "it tried to go somewhere then stops then goes again like once every 4ish ticks").
     */
    private static final double DRIFT_LIMIT = 0.35;
    /** Off by THIS much and the schedule is meaningless - hold still until the new one lands. */
    private static final double LOST_LIMIT = 1.5;
    /**
     * A search takes longer than a tick, so a plan made "from here, now" is already stale when it lands. Every
     * re-plan therefore starts from the state the CURRENT plan says you will be in this many ticks from now, and is
     * spliced in when that tick arrives. 6 ticks = 300ms, comfortably more than a search costs.
     */
    private static final int PLAN_LATENCY = 6;
    /** A fresh plan is started this often while running, so the route keeps correcting instead of drifting. */
    private static final int REPLAN_EVERY = 20;
    /** How often a route with no plan at all may ask for another one. See the plan == null branch in tick(). */
    private static final int RETRY_EVERY = 20;
    private static int retryIn;
    /**
     * How long the first plan of a route may take. Long, on purpose: it is found once and then remembered, and it
     * runs off the client thread, so the only cost is the pause before he sets off - which he already sees and
     * would rather trade for an answer that works.
     */
    private static final long BRUTE_FORCE_MS = 4000;
    /**
     * How far he may move while a first search runs before it is worth asking again from where he now is. A little
     * over a block: the saved plan he wants is keyed to the node he is standing on, and START_TOLERANCE is 1.25.
     */
    private static final double REASK_MOVED = 1.5;
    /** How far from the route's own level a surface may be and still be treated as this route's ground. */
    private static final double SURFACE_BAND = 4.0;
    /** How close to the first Path node the pre-plan starts, so the route does not stall on arrival. */
    private static final double PRE_PLAN_RANGE = 12.0;

    private Ap3RouteRunner() {
    }

    // ---- session state ------------------------------------------------------------------------------------------

    /** The Path nodes this run is driving, in chain order. */
    private static final List<Ap3Node> route = new ArrayList<>();
    private static Ap3RoutePlanner.Plan plan;
    /**
     * The node that STARTED this route - the one he stepped on. It is the one gate the search never has to solve,
     * because entering it is what set the route going in the first place.
     */
    private static Ap3Node routeEntry;
    private static int stepIndex;
    private static Thread worker;
    private static volatile Ap3RoutePlanner.Plan pending;
    private static volatile boolean planning;
    private static Ap3RouteMath.RouteState planStart;
    private static Ap3RouteMath.RouteState predicted;
    private static Ap3DiscretePlanner.Model planModel;
    /** The world the current plan was made against - the renderer draws the path at its heights. */
    private static volatile Ap3RoutePlanner.Terrain snapshot;
    /** Set by /ap3 dump: the next plan writes everything it was given to a file, for replaying offline. */
    static volatile boolean dumpNext;
    /** The step index the pending plan begins at (it was planned from the predicted state at that tick). */
    private static volatile int pendingAt;
    /**
     * Every request gets a number and a worker only publishes its answer if it is still the newest one. Without this
     * a pre-plan made while he stood next to the route (they run once a second) could land AFTER the route started
     * and be adopted - a schedule for standing still, spliced into a run - which is what made the route twitch
     * forward and stop (killer560, 2026-09-22: "a tiny bit of movement in a random direction then it stops").
     */
    private static final java.util.concurrent.atomic.AtomicInteger planSeq = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile int pendingSeq = -1;
    /** Guards the (plan, sequence) pair so a worker cannot publish half of it. */
    private static final Object PUBLISH = new Object();
    /** The sequence the running route is willing to accept (bumped when the route starts, so pre-plans are dropped). */
    private static int acceptFrom;
    private static int lastPlanStep;
    /** A plan made while walking up to the route, and the node it starts at. */
    private static Ap3RoutePlanner.Plan prePlan;
    private static Ap3Node prePlanFor;
    private static long prePlanMs;
    private static int prePlanCooldown;
    /** Whether this route has already said its planned time in chat. */
    private static boolean announced;
    /** Set by {@link #planWholeRoute} so the chat line goes out even though nothing is being driven. */
    private static boolean announceWhenDone;
    /** Which Path node that announcement is about - see the chat line in startPlanning. */
    private static int announceIndex;
    private static int waitingForTermTicks;
    private static boolean waitingForTerm;
    private static boolean sawTermScreen;

    static boolean active() {
        return !route.isEmpty();
    }

    /** Whether this node is being driven by the running route (so the box scan must not queue it again). */
    static boolean owns(Ap3Node node) {
        return route.contains(node);
    }

    static void stop() {
        route.clear();
        announced = false;
        plan = null;
        pending = null;
        planning = false;
        stepIndex = 0;
        waitingForTerm = false;
        waitingForTermTicks = 0;
        sawTermScreen = false;
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * The world the plan was built against. Everything that carries a state forward - the drift prediction, the
     * hand-over into a re-plan, the drawn path - has to use it, or a staircase reads as drift the moment the run
     * leaves flat ground.
     */
    private static Ap3RouteCollide.Shapes planWorld() {
        Ap3RoutePlanner.Terrain t = snapshot;
        return t == null ? Ap3RouteMath.FLAT_GROUND : t.shapes();
    }

    /** The path the current plan follows, for the renderer; empty when there is nothing planned. */
    static List<Vec3> plannedPath(double y) {
        Ap3RoutePlanner.Plan p = plan;
        if (p == null || planStart == null || planModel == null || p.steps.length == 0) {
            return List.of();
        }
        // Drawn at the heights the plan actually runs at, so a staircase shows as a line going up it.
        List<Vec3> out = new ArrayList<>(p.steps.length + 1);
        Ap3RouteMath.RouteState s = planStart.copy();
        Ap3RoutePlanner.Terrain terrain = snapshot;
        out.add(new Vec3(s.x, s.y + 0.1, s.z));
        Ap3RouteCollide.Shapes world = terrain == null ? Ap3RouteMath.FLAT_GROUND : terrain.shapes();
        for (Ap3RoutePlanner.Step st : p.steps) {
            // The same move the plan was built on, so the drawn line goes up a staircase exactly where the run will.
            Ap3RouteMath.step(s, st.keys(), st.yaw(), st.jump(), st.sprint(), planModel, world);
            out.add(new Vec3(s.x, s.y + 0.1, s.z));
        }
        return out;
    }

    // ---- driving ------------------------------------------------------------------------------------------------

    /**
     * One tick of a Path node. Collects the route the first time, plans it on a worker, then plays the schedule out.
     * Returns true while it still owns the movement; false when the route is done and the node should finish.
     */
    static boolean tick(Minecraft client, LocalPlayer player, Ap3Node node,
                        java.util.function.Consumer<Ap3RoutePlanner.Step> drive) {
        if (route.isEmpty()) {
            begin(client, player, node);
            return true;
        }
        if (waitingForTerm) {
            return tickTermWait(client, player);
        }
        // Still waiting on a first search, and he is no longer where it was asked from? Ask again from here.
        //
        // Measured from his log, 2026-09-23. The route began at (74.30, 60.41) while he was still sprinting past,
        // eleven blocks from its start node - a state nothing was saved for, so a ten-second search began. A
        // second later he aligned and stepped on the node properly, where a saved plan sat waiting from exactly
        // that spot, and he got none of it: the route had already begun, so nothing asked the cache again. He
        // waited four seconds for an answer to a question about somewhere he had left. Afterwards, the very next
        // request hit the cache "0.00 blocks from where it was planned".
        //
        // Only while nothing is being driven yet - once a plan is running, the drift check owns re-planning, and
        // restarting from here would throw away a route mid-flight.
        if (plan == null && planning && planStart != null) {
            Ap3RouteMath.RouteState now = stateOf(player);
            if (Math.hypot(now.x - planStart.x, now.z - planStart.z) > REASK_MOVED) {
                LOGGER.info("[AP3 route] moved {} blocks since the search began - asking again from here",
                        String.format(Locale.US, "%.1f",
                                Math.hypot(now.x - planStart.x, now.z - planStart.z)));
                planning = false;
                pending = null;
                startPlanning(client, player);
                return true;
            }
        }
        // A plan that was started earlier lands here; it begins at the tick it was planned for, so it waits for it.
        Ap3RoutePlanner.Plan fresh = pendingSeq >= acceptFrom ? pending : null;
        if (pendingSeq >= 0 && pendingSeq < acceptFrom) {
            pending = null; // an answer to a question this route never asked (a pre-plan from before it started)
        }
        if (fresh != null && fresh.steps.length == 0 && !fresh.complete) {
            // A search that got nowhere. Keep whatever is already being driven rather than replacing it with
            // nothing, and let the drift check below decide whether to hold still.
            LOGGER.info("[AP3 route] the re-plan got nowhere ({}) - keeping the current plan", fresh.note);
            pending = null;
            fresh = null;
        }
        if (fresh != null && (plan == null || stepIndex >= pendingAt)) {
            // A re-plan is given at most 300 ms, which IS PLAN_LATENCY ticks, and it is only picked up on the
            // following client tick - so it routinely lands one tick after the step it was built for. Adopting it
            // as if it started now anchored `predicted` to a position killer560 had already left, and at 1.08
            // blocks a tick that is an instant drift bigger than DRIFT_LIMIT. Every re-plan after it was late the
            // same way, so the route spiralled: a schedule meant for one place, driven from another, until it
            // turned round and ran off the staircase (2026-09-22, three runs in a row).
            // So: start the plan at the step that belongs to THIS tick, and measure drift from where he really is.
            int late = plan == null ? 0 : Math.max(0, stepIndex - pendingAt);
            if (late >= fresh.steps.length) {
                LOGGER.info("[AP3 route] the plan arrived {} ticks late - all {} of its steps are in the past,"
                        + " keeping the current one", late, fresh.steps.length);
                pending = null;
                fresh = null;
            } else {
                if (late > 0) {
                    LOGGER.info("[AP3 route] the plan arrived {} tick(s) late - starting it at step {}/{}",
                            late, late + 1, fresh.steps.length);
                }
                pending = null;
                plan = fresh;
                stepIndex = late;
                lastPlanStep = late;
                predicted = stateOf(player);
            }
        }
        if (plan == null) {
            // Nothing to drive. If nobody is working on one either, ask again - a pre-plan still in flight when he
            // stepped on the node used to leave the route here for good.
            // But not EVERY tick: startPlanning reads the whole world first, which is tens of milliseconds on the
            // client thread, and when a search fails immediately (as it did when a No Go box sat on his feet) that
            // became a fresh world scan every single tick and the game locked up. Once every RETRY_EVERY ticks is
            // plenty - the answer is not going to change in the meantime.
            if (!planning && --retryIn <= 0) {
                retryIn = RETRY_EVERY;
                startPlanning(client, player);
            }
            return true; // still planning - no keys this tick, the player coasts
        }
        retryIn = 0;
        if (stepIndex >= plan.steps.length) {
            if (!plan.complete) {
                // A partial that ran out before REPLAN_EVERY could fire. Finishing here would mark the node done
                // having never reached its last gate, and the rest of the chain would run from the wrong place.
                plan = null;
                predicted = null;
                retryIn = 0; // the branch above asks for the next one, rate limited
                return true;
            }
            return finishLeg(client, player);
        }
        // Drift: the game and the plan disagree (a mob, a slab, a lag spike). Re-plan from where you really are.
        boolean drifted = false;
        boolean lost = false;
        if (predicted != null) {
            double off = Math.hypot(player.getX() - predicted.x, player.getZ() - predicted.z);
            drifted = off > DRIFT_LIMIT;
            lost = off > LOST_LIMIT;
            if (drifted) {
                LOGGER.info("[AP3 route] {} blocks off the plan - re-planning{}",
                        String.format(Locale.US, "%.2f", off), lost ? " (lost - holding still)" : "");
                logDisagreement(player);
            }
        }
        // The routine re-sync is there to catch the world moving under a plan. When the plan is complete and the
        // game is following it to within a few hundredths, there is nothing to re-sync and a re-plan only risks
        // arriving late - so leave a route that is working alone.
        boolean worthReplanning = drifted || !plan.complete
                || (predicted != null && Math.hypot(player.getX() - predicted.x, player.getZ() - predicted.z) > 0.05);
        if (!planning && worthReplanning && (drifted || stepIndex - lastPlanStep >= REPLAN_EVERY)) {
            lastPlanStep = stepIndex;
            replanAhead(client, player);
        }
        if (lost) {
            // Properly lost (a correction, a wall, a leap): the schedule means nothing here, so hold still for the
            // few ticks the re-plan takes rather than driving keys meant for somewhere else.
            plan = null;
            predicted = null;
            return true;
        }
        Ap3RoutePlanner.Step step = plan.steps[stepIndex++];
        if (Ap3Config.getInstance().isAlignTimerDev()) {
            // Dev line per driven tick: what went out, where the plan said we would be, and where we actually are.
            LOGGER.info("[AP3 route] step {}/{} keys[{}] yaw {} jump {} | at ({}, {}) plan ({}, {}) drift {} | v {}",
                    stepIndex, plan.steps.length, step.keys().label(),
                    String.format(Locale.US, "%.1f", step.yaw()), step.jump(),
                    String.format(Locale.US, "%.3f", player.getX()), String.format(Locale.US, "%.3f", player.getZ()),
                    predicted == null ? "-" : String.format(Locale.US, "%.3f", predicted.x),
                    predicted == null ? "-" : String.format(Locale.US, "%.3f", predicted.z),
                    predicted == null ? "-" : String.format(Locale.US, "%.3f",
                            Math.hypot(player.getX() - predicted.x, player.getZ() - predicted.z)),
                    String.format(Locale.US, "%.4f", player.getDeltaMovement().horizontalDistance()));
        }
        if (predicted != null) {
            Ap3RouteMath.step(predicted, step.keys(), step.yaw(), step.jump(), step.sprint(), planModel, planWorld());
        }
        drive.accept(step);
        return true;
    }

    /**
     * Starts a search from where the plan says you will be {@link #PLAN_LATENCY} ticks from now (or from the measured
     * state when the two have already come apart), so the answer is still current when it arrives.
     */
    private static void replanAhead(Minecraft client, LocalPlayer player) {
        // Start from where he REALLY is, carried forward by the steps the current plan will drive in the meantime,
        // so the new schedule begins exactly where the old one hands over and nothing has to stop.
        Ap3RouteMath.RouteState from = stateOf(player);
        int at = stepIndex;
        for (int i = 0; i < PLAN_LATENCY && stepIndex + i < plan.steps.length; i++) {
            Ap3RoutePlanner.Step st = plan.steps[stepIndex + i];
            Ap3RouteMath.step(from, st.keys(), st.yaw(), st.jump(), st.sprint(), planModel, planWorld());
            at = stepIndex + i + 1;
        }
        startPlanning(client, player, from, at);
    }

    /**
     * Called every tick while AP3 is armed and idle: once the first Path node is close, the route is planned in the
     * background so stepping into it starts moving at once instead of standing there while the search runs.
     */
    static void prePlan(Minecraft client, LocalPlayer player, List<Ap3Node> nodes) {
        if (!route.isEmpty() || planning || player == null) {
            return;
        }
        if (prePlanCooldown > 0) {
            prePlanCooldown--;
            return;
        }
        Ap3Node first = null;
        double best = PRE_PLAN_RANGE;
        for (Ap3Node n : nodes) {
            if (n.type != Ap3Node.Type.PATH) {
                continue;
            }
            double d = Math.hypot(n.x - player.getX(), n.z - player.getZ());
            if (d < best) {
                best = d;
                first = n;
            }
        }
        if (first == null) {
            return;
        }
        prePlanCooldown = REPLAN_EVERY;
        prePlanFor = first;
        prePlanMs = System.currentTimeMillis();
        collectRoute(first);
        startPlanning(client, player);
        route.clear(); // it is only a plan: the route is not running yet
    }

    /**
     * Plan the route NOW, from where he is standing, without waiting for him to get near its first node.
     * <p>
     * killer560 (2026-09-23): "have it such that the second a line is complete, even if i am not standing on it,
     * then it starts calculating. that would help alot if the node is inside of a wall that I plan on using my
     * breaker aura on but is hard to stand in cause the blocks keep coming back."
     * <p>
     * An align finishing is the moment this is worth doing: he is stationary, he is exactly where the route will
     * start from, and the search has however long it takes him to move. The ordinary pre-plan waits until he is
     * within {@link #PRE_PLAN_RANGE} of a Path node and cannot help when the node is somewhere he cannot stand.
     */
    static void prePlanNow(Minecraft client, LocalPlayer player, List<Ap3Node> nodes, double atX, double atZ) {
        if (!route.isEmpty() || planning || player == null || client == null) {
            return;
        }
        Ap3Node first = null;
        double best = Double.MAX_VALUE;
        for (Ap3Node n : nodes) {
            if (n.type != Ap3Node.Type.PATH) {
                continue;
            }
            // Nearest to where the ALIGN was, not to him: an align hands over to the Path node sharing its spot,
            // and by the time this runs he may already have drifted a little off it.
            double d = Math.hypot(n.x - atX, n.z - atZ);
            if (d < best) {
                best = d;
                first = n;
            }
        }
        if (first == null) {
            return;
        }
        prePlanCooldown = REPLAN_EVERY;
        prePlanFor = first;
        prePlanMs = System.currentTimeMillis();
        LOGGER.info("[AP3 route] align finished - planning {} blocks ahead to Path {} now",
                String.format(Locale.US, "%.1f", best), first.pathIndex);
        collectRoute(first);
        startPlanning(client, player);
        route.clear(); // it is only a plan: the route is not running yet
    }

    /**
     * Plan a route the moment it is finished being built, from ITS OWN first node rather than from wherever he is
     * standing, and tell him in chat when the answer arrives.
     * <p>
     * killer560 (2026-09-23): "The second I build that end node it should start generating a route and then notify
     * me in chat once it finishes."
     * <p>
     * From the route's own start, at rest, because that is the state he will actually run it in - he aligns onto
     * the first node and goes. A plan is a schedule of keys from one particular state, so planning it from where
     * he happens to be standing while placing the end node would cache an approach he is never going to use.
     * Planned this way it lands in the cache under the approach he will use, and stepping on is instant.
     */
    static void planWholeRoute(Minecraft client, LocalPlayer player, Ap3Node first) {
        if (client == null || player == null || first == null || !route.isEmpty() || planning) {
            return;
        }
        collectRoute(first);
        if (route.isEmpty()) {
            return;
        }
        Ap3RouteMath.RouteState from = new Ap3RouteMath.RouteState();
        from.x = first.x;
        from.y = first.y;
        from.z = first.z;
        from.onGround = true;
        announced = false; // this one always says so in chat, however many times he rebuilds the route
        announceWhenDone = true;
        announceIndex = first.pathIndex;
        prePlanCooldown = REPLAN_EVERY;
        prePlanFor = first;
        prePlanMs = System.currentTimeMillis();
        LOGGER.info("[AP3 route] route finished being built - planning it from its own start ({}, {}, {})",
                String.format(Locale.US, "%.2f", first.x), String.format(Locale.US, "%.2f", first.y),
                String.format(Locale.US, "%.2f", first.z));
        startPlanning(client, player, from, 0);
        route.clear(); // it is only a plan: the route is not running yet
    }

    /** The pending pre-plan becomes this run's plan when it was made for this node and is still fresh. */
    private static boolean takePrePlan(Ap3Node first, LocalPlayer player) {
        Ap3RoutePlanner.Plan p = pending;
        if (p == null || prePlanFor != first || System.currentTimeMillis() - prePlanMs > 4000) {
            return false;
        }
        if (planStart == null || Math.hypot(player.getX() - planStart.x, player.getZ() - planStart.z) > 0.5
                || Math.abs(Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z)
                - Math.hypot(planStart.vx, planStart.vz)) > 0.15) {
            return false; // it was planned from a different place or a different speed - plan again from here
        }
        pending = null;
        plan = p;
        stepIndex = 0;
        lastPlanStep = 0;
        predicted = planStart == null ? null : planStart.copy();
        return true;
    }

    private static void begin(Minecraft client, LocalPlayer player, Ap3Node first) {
        collectRoute(first);
        if (takePrePlan(first, player)) {
            acceptFrom = planSeq.get() + 1; // the pre-plan is in hand; ignore any other one still being computed
            // Walked up to it with a plan already in hand: drive that, and correct it from the measured state.
            return;
        }
        acceptFrom = planSeq.get() + 1; // only answers to THIS route's own requests may drive it
        startPlanning(client, player);
    }

    /**
     * The Path nodes this route covers, in STEP-NUMBER order starting at the one you stepped on. Nodes sharing a
     * number are one step to be done in any order, so they all come along together; the leg ends after a step that
     * waits for a terminal.
     * <p>
     * Where it STOPS is whichever comes first: a node marked {@code end}, a step that waits for a terminal, or the
     * last Path node in the chain. killer560 (2026-09-22): "That way it knows which ones I want the line between
     * incase I have multiple in one section." A chain with no {@code end} marked anywhere runs to the end of the
     * chain exactly as it always did, so nothing he has already built changes.
     */
    private static void collectRoute(Ap3Node first) {
        Ap3Chain chain = Ap3Feature.currentChain();
        route.clear();
        routeEntry = first;
        if (chain == null) {
            route.add(first);
            return;
        }
        List<Ap3Node> paths = new ArrayList<>();
        for (Ap3Node n : chain.nodes()) {
            if (n.type == Ap3Node.Type.PATH) {
                paths.add(n);
            }
        }
        paths.sort((a, b) -> Integer.compare(a.pathIndex, b.pathIndex));
        int from = first.pathIndex;
        int stopAfter = Integer.MAX_VALUE;
        for (Ap3Node n : paths) {
            // The nearest `end` at or after him closes this route. Ends before him belong to routes he is past.
            if (n.pathEnd && n.pathIndex >= from && n.pathIndex < stopAfter) {
                stopAfter = n.pathIndex;
            }
        }
        for (Ap3Node n : paths) {
            if (n.pathIndex < from || n.pathIndex > stopAfter) {
                continue;
            }
            route.add(n);
            if (n.termWait && n.pathIndex < stopAfter) {
                stopAfter = n.pathIndex; // finish this step's nodes, then stop for the terminal
            }
        }
        if (route.isEmpty()) {
            route.add(first);
        }
    }

    private static void startPlanning(Minecraft client, LocalPlayer player) {
        startPlanning(client, player, stateOf(player), 0);
    }

    /** Samples the world (client thread) and kicks the search off on a worker, starting from {@code start}. */
    private static void startPlanning(Minecraft client, LocalPlayer player, Ap3RouteMath.RouteState start, int at) {
        if (planning || client.level == null) {
            return;
        }
        // Once per route, not once per plan: it re-plans every 20 ticks, and a paused game re-plans again on resume.
        final boolean announce = !announced;
        announced = true;
        List<Ap3RoutePlanner.Gate> gates = new ArrayList<>();
        for (Ap3Node n : route) {
            gates.add(gateFor(n));
        }
        // The gate for the node he STEPPED ON is not something to plan for. Entering it is what started the
        // route; it is solved by definition, and asking the search to solve it again costs the whole route.
        // Measured on the real s3 dump with the terrain grid the game itself wrote, same beam and budget: with
        // that gate, INCOMPLETE 5 ticks in 8.3 s; without it, COMPLETE 24 ticks in 3.5 s, replayed to
        // (3.38, 121.000, 83.96) standing on the node.
        //
        // Identified by WHICH NODE it is, never by measuring where he is now. Measuring was the first attempt and
        // it failed the same evening: he stepped on that node standing at (1.50, 94.50) - a metre from its centre,
        // just outside a 1x1 box - so the gate was kept, and his log went straight back to "INCOMPLETE 5 ticks,
        // g1 1 blocks round". Where he is standing inside the node he triggered is not the question; that he
        // triggered it is.
        //
        // Kept when there is still something to ask of it: a speed or heading is a real requirement even underfoot,
        // an exact node is a placement he wants hit, and the last gate is where the route ENDS, never free.
        if (gates.size() > 1 && routeEntry != null && !route.isEmpty() && route.get(0) == routeEntry) {
            Ap3RoutePlanner.Gate g = gates.get(0);
            if (!g.wantsVelocity() && !g.exact) {
                gates.remove(0);
            }
        }
        if (!gates.isEmpty()) {
            gates.get(gates.size() - 1).mustLand = true; // the route ends standing, not mid-jump
        }
        List<Ap3RoutePlanner.Blocked> blocked = noGoZones(player);
        final List<Ap3RoutePlanner.Blocked> blockedHard = noGoZones(player, false);
        Ap3DiscretePlanner.Model model = Ap3Executor.routeModel(player);
        String signature = Ap3RouteCache.signature(route);
        Snap reused = reusableSnapshot(signature, start);
        if (reused == null) {
            reused = Snap.of(client.level, player, gates, start);
            keepSnapshot(signature, reused, start);
        }
        final Snap snap = reused;
        if (dumpNext) {
            dumpNext = false;
            Ap3RouteDump.write(start, gates, blocked, snap, model);
        }
        snapshot = snap;
        // A route he has run before, from about where he is standing now, is answered from what it learned then -
        // no search at all. killer560, 2026-09-22: "it should scan and just save one."
        // The world still has to be read first: every tick of the run is checked against `predicted`, which is
        // simulated through `snapshot`. Handing back a saved plan before taking the snapshot left that simulation
        // running against FLAT_GROUND - an endless floor at y = 0 - so the prediction free-fell, drift passed
        // LOST_LIMIT within three ticks and the route stopped dead. The scan is the cheap half anyway; the search
        // is what the cache is really saving.
        // Only a route's FIRST plan may come out of the cache. What is saved is the schedule for the whole route
        // from its start; a re-plan happens mid-run from wherever he has drifted to, and needs a plan for what is
        // LEFT. Letting re-plans ask as well is how a plan from 30 blocks away got replayed on 2026-09-22 - it
        // drifted, asked again, got the same plan, restarted it, and looped off a staircase into lava.
        boolean firstPlan = plan == null;
        Ap3RoutePlanner.Plan saved = firstPlan ? Ap3RouteCache.lookup(signature, start) : null;
        if (saved != null) {
            planStart = start;
            planModel = model;
            pendingAt = at;
            pendingSeq = planSeq.incrementAndGet();
            pending = saved;
            if (announce) {
                LOGGER.info("[AP3 route] using the saved {}-tick plan for this route", saved.ticks);
            }
            return;
        }
        Ap3RoutePlanner.Options options = new Ap3RoutePlanner.Options();
        Ap3Config cfg = Ap3Config.getInstance();
        options.allowJump = cfg.isRouteAllowJumps();
        options.scanPad = cfg.getRouteScanPad();
        // killer560 (2026-09-22): "i do the vast majority of the configging myself... but for really annoying
        // movement areas I want the path to help as a sort of brute forcer." So the FIRST plan of a route is
        // allowed to think hard - a wide beam and seconds rather than a fraction of one - because the search runs
        // on a worker, nothing in the game waits on it, and Ap3RouteCache keeps the answer so the price is paid
        // once and never again. A re-plan mid-run gets the old small budget: there, every millisecond it spends is
        // a tick the route is coasting.
        options.beam = firstPlan ? Math.min(4000, cfg.getRouteBeam() * 3) : cfg.getRouteBeam();
        // A long route is a bigger problem and needs proportionally more of everything. killer560 (2026-09-22):
        // "if it is trying to generate a really long route then it runs out of time due to the time budget."
        // Both the time AND the tick ceiling scale with how far there is to go - 160 ticks is only eight seconds of
        // running, which a long chain passes without reaching the end, and no amount of budget helps once the
        // search is not allowed to look far enough ahead.
        double span = 0;
        double px = start.x;
        double pz = start.z;
        for (Ap3RoutePlanner.Gate g : gates) {
            span += Math.hypot(g.x - px, g.z - pz);
            px = g.x;
            pz = g.z;
        }
        double top = Math.max(0.2, Ap3RouteMath.topSpeed(model, options.allowJump));
        options.maxTicks = (int) Math.max(160, Math.min(900, span / top * 3.0 + 60));
        long scaled = (long) (BRUTE_FORCE_MS * Math.max(1.0, Math.min(4.0, span / 40.0)));
        options.budgetMs = firstPlan ? Math.max(cfg.getRouteBudgetMs(), scaled)
                : Math.min(cfg.getRouteBudgetMs(), 300);
        // The first plan runs a portfolio of differently-shaped searches and keeps the shortest answer, because no
        // single beam setting is best everywhere - see Ap3RoutePlanner.portfolio. It is the plan that gets saved.
        options.portfolio = firstPlan;
        planStart = start;
        planModel = model;
        pendingAt = at;
        planning = true;
        logProblem(start, gates, blocked, snap, options, firstPlan, model);
        final int seq = planSeq.incrementAndGet();
        final long askedAt = System.currentTimeMillis();
        // Captured per REQUEST, not read from the field when the answer lands. These were statics, and a search
        // that took 42 seconds came back to find a newer request had since set them: the chat line then named one
        // route and gave the other's coordinates (2026-09-23). A request's own announcement belongs to it.
        final boolean tellHim = announceWhenDone;
        final int tellIndex = announceIndex;
        announceWhenDone = false;
        worker = new Thread(() -> {
            try {
                Ap3RoutePlanner.Plan p = Ap3RoutePlanner.plan(start, gates, blocked, snap, model, options);
                if (!p.complete && blocked.size() > blockedHard.size()) {
                    // Keeping out of the chain's other nodes is a preference, not a rule. If it makes the route
                    // impossible - a node sitting in the only doorway, say - having no route at all is worse than
                    // running over something, so try again without them. This is the safety net for a change that
                    // took his whole config out on 2026-09-22 by fencing off the align he was stood on.
                    Ap3RoutePlanner.Plan relaxed =
                            Ap3RoutePlanner.plan(start, gates, blockedHard, snap, model, options);
                    if (relaxed.complete) {
                        LOGGER.info("[AP3 route] no way round the chain's other nodes - planning through them"
                                + " instead ({} ticks)", relaxed.ticks);
                        p = relaxed;
                    }
                }
                // Ran out of clock rather than out of places to go? Then give it more clock. killer560,
                // 2026-09-23: "it is stopping because it ran out of time", and separately: "I don't care if it
                // takes me a little bit longer to have to wait for the first run". A first plan is the one that
                // gets remembered, so this is paid once for the life of the route; every run after it is a cache
                // hit. Only on the FIRST plan - a re-plan that overran is a route already moving, and making it
                // wait longer mid-run is worse than the imperfect line it already has.
                if (firstPlan && !p.complete && p.note.contains("time budget")) {
                    Ap3RoutePlanner.Options more = options.copy();
                    more.budgetMs = options.budgetMs * 4;
                    LOGGER.info("[AP3 route] out of time at {} ms and still {} - trying once more with {} ms",
                            options.budgetMs, p.diagnosis.isEmpty() ? "unfinished" : p.diagnosis, more.budgetMs);
                    // The SAME constraints - only the clock changes. Retrying with blockedHard here would relax
                    // the chain's other nodes at the same time, and then a success says nothing about which of the
                    // two helped; the relaxation above is already the place that decision gets made, on its own.
                    Ap3RoutePlanner.Plan longer =
                            Ap3RoutePlanner.plan(start, gates, blocked, snap, model, more);
                    if (longer.complete || longer.gatesReached > p.gatesReached) {
                        LOGGER.info("[AP3 route] the longer search {} ({} ticks)",
                                longer.complete ? "got there" : "got further but still not there", longer.ticks);
                        p = longer;
                    }
                }
                // Remember it, so the next run is instant and identical - but only a FIRST plan. A re-plan starts
                // from wherever he had drifted to halfway along, and lookup only ever asks at the start of a
                // route, so such an entry can never be matched again. All it does is take a place from an approach
                // he really uses and push it out of the file.
                if (firstPlan) {
                    Ap3RouteCache.offer(signature, start, p);
                }
                // Only publish if no newer request has been made since this one started. Writing the plan and its
                // sequence as two separate fields let a slow worker overwrite a fresher answer and then have its
                // own discarded for being stale - losing both.
                synchronized (PUBLISH) {
                    if (seq >= pendingSeq) {
                        pending = p;
                        pendingSeq = seq;
                    }
                }
                if (announce && Ap3Config.getInstance().isChatFeedback()) {
                    ModChat.send("AP3", ModChat.text("Route "),
                            ModChat.value(String.format(Locale.US, "%.2fs", p.ticks / 20.0)),
                            ModChat.dim(" (" + p.ticks + " ticks, " + jumpsIn(p) + " jumps"
                                    + (p.complete ? "" : ", INCOMPLETE - " + p.note) + ")"));
                }
                if (tellHim) {
                    long took = System.currentTimeMillis() - askedAt;
                    // Name the route it planned. Announcing a bare "ready" once planned the wrong route of three
                    // and read as a promise about the one he was about to step on (2026-09-23).
                    String which = String.format(Locale.US, "Path %d at %.1f, %.1f, %.1f",
                            tellIndex, start.x, start.y, start.z);
                    if (p.complete) {
                        ModChat.send("AP3", ModChat.text("Route ready - "),
                                ModChat.value(p.ticks + " ticks"),
                                ModChat.dim(" from " + which + " (" + jumpsIn(p) + " jumps, found in "
                                        + String.format(Locale.US, "%.1fs", took / 1000.0)
                                        + "). Step on it and it will run at once."));
                    } else {
                        ModChat.send("AP3", ModChat.text("Could not plan the route from "),
                                ModChat.value(which),
                                ModChat.dim(" - " + (p.note.isEmpty() ? "no route found" : p.note)
                                        + " (" + String.format(Locale.US, "%.1fs", took / 1000.0) + ")"));
                    }
                }
                logTerrainProfile(snap, start, gates);
                logPlanShape(p, start, gates, snap, model);
                LOGGER.info("[AP3 route] planned {} ticks, {} gates{}, from ({}, {}) v {} splicing at step {}",
                        p.ticks, p.gateTick.length,
                        p.complete ? (p.note.isEmpty() ? "" : " (" + p.note + ")") : " INCOMPLETE - " + p.note,
                        String.format(Locale.US, "%.2f", start.x), String.format(Locale.US, "%.2f", start.z),
                        String.format(Locale.US, "%.4f", Math.hypot(start.vx, start.vz)), at);
                if (!p.complete && !p.diagnosis.isEmpty()) {
                    // Why it could not finish, in terms that separate "nothing connects these two places" from
                    // "the search ran out of room". Without this the two look identical from outside.
                    LOGGER.info("[AP3 route] why: {}", p.diagnosis);
                    // ...and keep the whole problem - his world, his nodes, his state - so it can be replayed
                    // offline without him having to catch it by hand.
                    Ap3RouteDump.writeFailure(start, gates, blocked, snap, model, p.note + " | " + p.diagnosis);
                }
            } catch (Throwable t) {
                LOGGER.warn("[AP3 route] planning failed", t);
                pending = null;
            } finally {
                planning = false;
            }
        }, "killer560smod-ap3-route");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Where the plan and the game came apart: the ground the planner believes in at his real position, at the one it
     * expected, and at every quarter block between - so a stop against something the plan thought was open says which
     * cell is wrong rather than leaving it to be guessed at.
     */
    private static void logDisagreement(LocalPlayer player) {
        Ap3RoutePlanner.Terrain t = snapshot;
        if (t == null || predicted == null || !Ap3Config.getInstance().isAlignTimerDev()) {
            return;
        }
        double x0 = player.getX();
        double z0 = player.getZ();
        double dx = predicted.x - x0;
        double dz = predicted.z - z0;
        int steps = (int) Math.max(1, Math.round(Math.hypot(dx, dz) / 0.25));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= steps; i++) {
            double t2 = (double) i / steps;
            double x = x0 + dx * t2;
            double z = z0 + dz * t2;
            double f = t.floorAt(x, z);
            sb.append(Double.isNaN(f) ? "X" : String.format(Locale.US, "%.2f", f));
            sb.append(t.bodyClear(x, z, Double.isNaN(f) ? player.getY() : f) ? " " : "! ");
        }
        LOGGER.info("[AP3 route] disagreement: he is at ({}, {}) y {} onGround {}, plan said ({}, {}); ground he -> plan: {}",
                String.format(Locale.US, "%.3f", x0), String.format(Locale.US, "%.3f", z0),
                String.format(Locale.US, "%.3f", player.getY()), player.onGround(),
                String.format(Locale.US, "%.3f", predicted.x), String.format(Locale.US, "%.3f", predicted.z),
                sb.toString().trim());
    }

    /**
     * What the planner believes the ground is, straight from where he stands to the last gate: the floor height every
     * half block, or "X" where it thinks nothing can stand. When the route walks into something the plan thought was
     * open, this line says whether the snapshot was wrong or the search was.
     */
    /**
     * Everything the search was actually asked, before it is asked: the options, the gates as passed, the no-go
     * boxes, and - the part that has caught the most bugs - what the terrain believes is underfoot at the start and
     * at every gate. A route fails for one of four reasons and this tells them apart at a glance: the ground is
     * wrong, a no-go box is in the way, the budget is too small, or the movement genuinely cannot be done.
     */
    private static void logProblem(Ap3RouteMath.RouteState start, List<Ap3RoutePlanner.Gate> gates,
                                   List<Ap3RoutePlanner.Blocked> blocked, Snap snap,
                                   Ap3RoutePlanner.Options o, boolean firstPlan, Ap3DiscretePlanner.Model model) {
        StringBuilder gs = new StringBuilder();
        for (int i = 0; i < gates.size(); i++) {
            Ap3RoutePlanner.Gate g = gates.get(i);
            double floor = snap.floorAt(g.x, g.z);
            if (gs.length() > 0) {
                gs.append(", ");
            }
            gs.append(String.format(Locale.US, "g%d(%.2f,%.2f,%.2f)%s%s %.1f away floor %s%s", i + 1, g.x, g.y, g.z,
                    g.mustLand ? " land" : "", g.exact ? " exact" : "",
                    Math.hypot(g.x - start.x, g.z - start.z),
                    Double.isNaN(floor) ? "NONE" : String.format(Locale.US, "%.2f", floor),
                    // The one that has cost the most time: the ground under a node not being the node's own level.
                    !Double.isNaN(floor) && Math.abs(floor - g.y) > 0.05 ? " <-- FLOOR != NODE" : ""));
        }
        double startFloor = snap.floorAt(start.x, start.z);
        int containing = 0;
        for (Ap3RoutePlanner.Blocked b : blocked) {
            if (start.x >= b.minX && start.x <= b.maxX && start.z >= b.minZ && start.z <= b.maxZ
                    && start.y >= b.minY - 1.0 && start.y <= b.maxY) {
                containing++;
            }
        }
        LOGGER.info("[AP3 route] asking for: from ({}, {}, {}) v {} onGround {} | floor under start {}{} | {}",
                String.format(Locale.US, "%.2f", start.x), String.format(Locale.US, "%.2f", start.y),
                String.format(Locale.US, "%.2f", start.z),
                String.format(Locale.US, "%.3f", start.speed()), start.onGround,
                Double.isNaN(startFloor) ? "NONE" : String.format(Locale.US, "%.2f", startFloor),
                !Double.isNaN(startFloor) && Math.abs(startFloor - start.y) > 0.6 ? " <-- NOT WHERE HE IS" : "",
                gs);
        LOGGER.info("[AP3 route] settings: {} plan, beam {}, budget {} ms, maxTicks {}, dirs {}, portfolio {},"
                        + " jumps {}, walking {}, scanPad {}, speed attr {} (top {} b/t) | {} no-go box(es){}",
                firstPlan ? "FIRST" : "re-", o.beam, o.budgetMs, o.maxTicks, o.dirs, o.portfolio, o.allowJump,
                o.allowWalking, String.format(Locale.US, "%.0f", o.scanPad),
                String.format(Locale.US, "%.3f", model.baseSpeedAttr),
                String.format(Locale.US, "%.3f", Ap3RouteMath.topSpeed(model, o.allowJump)),
                blocked.size(), containing > 0 ? " (" + containing + " CONTAIN THE START - exempted?)" : "");
    }

    /** What a finished plan actually does, replayed through the physics rather than taken on trust. */
    private static void logPlanShape(Ap3RoutePlanner.Plan p, Ap3RouteMath.RouteState start,
                                     List<Ap3RoutePlanner.Gate> gates, Snap snap, Ap3DiscretePlanner.Model model) {
        if (p.steps.length == 0) {
            return;
        }
        Ap3RouteMath.RouteState r = start.copy();
        int jumps = 0;
        int walks = 0;
        int coasts = 0;
        int clips = 0;
        int sneaks = 0;
        double lowest = start.y;
        int firstClip = -1;
        for (int i = 0; i < p.steps.length; i++) {
            Ap3RoutePlanner.Step st = p.steps[i];
            if (st.jump()) {
                jumps++;
            }
            if (st.keys().none()) {
                coasts++;
            } else if (!st.sprint()) {
                walks++;
            }
            if (st.keys().sneak()) {
                sneaks++;
            }
            Ap3RouteMath.step(r, st.keys(), st.yaw(), st.jump(), st.sprint(), model, snap.shapes());
            if (r.sprintBlocked) {
                clips++;
                if (firstClip < 0) {
                    firstClip = i;
                }
            }
            lowest = Math.min(lowest, r.y);
        }
        Ap3RoutePlanner.Gate last = gates.isEmpty() ? null : gates.get(gates.size() - 1);
        boolean onIt = last != null && r.onGround && Math.abs(r.y - last.y) < 0.05
                && Math.abs(r.x - last.x) <= last.halfW + 0.02 && Math.abs(r.z - last.z) <= last.halfL + 0.02;
        // A clip is the route bumping into the world - killer560, 2026-09-23: "it is still bumping". Counting them
        // here is what makes "it bumped" a number instead of an impression.
        LOGGER.info("[AP3 route] the plan: {} ticks | {} jump(s) {} walking {} coasting {} sneaking | {} clip(s){}"
                        + " | lowest y {} | replay ends ({}, {}, {}) onGround {} {}",
                p.ticks, jumps, walks, coasts, sneaks, clips,
                firstClip >= 0 ? " (first at tick " + firstClip + ")" : "",
                String.format(Locale.US, "%.2f", lowest),
                String.format(Locale.US, "%.3f", r.x), String.format(Locale.US, "%.3f", r.y),
                String.format(Locale.US, "%.3f", r.z), r.onGround,
                last == null ? "" : (onIt ? "ON THE LAST NODE" : "*** NOT ON THE LAST NODE ***"));
    }

    private static void logTerrainProfile(Snap snap, Ap3RouteMath.RouteState start, List<Ap3RoutePlanner.Gate> gates) {
        if (!Ap3Config.getInstance().isAlignTimerDev() || gates.isEmpty()) {
            return;
        }
        Ap3RoutePlanner.Gate last = gates.get(gates.size() - 1);
        double dx = last.x - start.x;
        double dz = last.z - start.z;
        double len = Math.hypot(dx, dz);
        if (len < 0.1) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        int steps = (int) Math.min(80, Math.round(len / 0.5));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = start.x + dx * t;
            double z = start.z + dz * t;
            double f = snap.floorAt(x, z);
            sb.append(Double.isNaN(f) ? "X" : String.format(Locale.US, "%.1f", f - start.y));
            sb.append(' ');
        }
        LOGGER.info("[AP3 route] ground from ({}, {}) y {} to the last gate, every 0.5 blocks (relative heights): {}",
                String.format(Locale.US, "%.2f", start.x), String.format(Locale.US, "%.2f", start.z),
                String.format(Locale.US, "%.2f", start.y), sb.toString().trim());
    }

    private static int jumpsIn(Ap3RoutePlanner.Plan p) {
        int n = 0;
        for (Ap3RoutePlanner.Step s : p.steps) {
            if (s.jump()) {
                n++;
            }
        }
        return n;
    }

    private static Ap3RoutePlanner.Gate gateFor(Ap3Node n) {
        Ap3RoutePlanner.Gate g = new Ap3RoutePlanner.Gate();
        g.x = n.x;
        g.z = n.z;
        g.y = n.y;
        g.exact = n.precise;
        g.halfW = n.precise ? 0 : n.width / 2.0;
        g.halfL = n.precise ? 0 : n.length / 2.0;
        g.exactTol = Ap3Config.getInstance().getRouteExactTolerance();
        g.captureBlocks();
        g.minSpeed = n.minSpeed;
        g.maxSpeed = n.maxSpeed;
        if (n.termWait) {
            g.minSpeed = -1;
            g.maxSpeed = 0.05; // arrive stopped: the terminal costs the speed anyway
        }
        g.hasDir = n.hasDir;
        g.dirDeg = n.dirDeg;
        g.dirTolDeg = n.dirTolDeg;
        g.group = n.pathIndex;
        return g;
    }

    /**
     * Boxes the route may not enter: every No Go node, and every node of the chain that running back over would
     * disturb.
     * <p>
     * killer560 (2026-09-22): "it needs to recognize if it is trying to run back over another node and if that is
     * goign to mess with it. Some things are fine like block nodes but others like aligns will cause problems."
     * An align, an axis align, a leap or a terminal is a place the config expects him to ARRIVE at in its own turn;
     * a route that happens to cross one on the way somewhere else can set it off or leave him standing in it at the
     * wrong moment. A Block node, a stopwatch, a look - those do not care, so the route is free to run over them.
     * Nodes belonging to THIS route are obviously exempt: reaching them is the whole point.
     */
    private static boolean disturbedByCrossing(Ap3Node.Type t) {
        return switch (t) {
            case ALIGN, AXIS_ALIGN, FAST_ALIGN, LEAP, LEAP_COUNTER, TERMINAL, BOOM, STOP -> true;
            default -> false;
        };
    }

    private static List<Ap3RoutePlanner.Blocked> noGoZones(LocalPlayer player) {
        return noGoZones(player, true);
    }

    /**
     * @param avoidNodes also keep out of chain nodes that crossing would disturb. Passed false for the retry when
     *                   a route cannot be planned at all with them - see startPlanning.
     */
    private static List<Ap3RoutePlanner.Blocked> noGoZones(LocalPlayer player, boolean avoidNodes) {
        List<Ap3RoutePlanner.Blocked> out = new ArrayList<>();
        Ap3Chain chain = Ap3Feature.currentChain();
        if (chain == null) {
            return out;
        }
        double hereX = player.getX();
        double hereY = player.getY();
        double hereZ = player.getZ();
        for (Ap3Node n : chain.nodes()) {
            // A No Go node is a GHOST BLOCK now, not a forbidden box - see Snap.addGhostBlocks. It is solid
            // geometry the route may stand on and jump off, so fencing the route out of it would be backwards.
            if (n.type == Ap3Node.Type.NO_GO) {
                continue;
            }
            if (avoidNodes && disturbedByCrossing(n.type) && !route.contains(n)) {
                Ap3RoutePlanner.Blocked b = new Ap3RoutePlanner.Blocked();
                // Just the node itself, not a margin around it - this is about not standing IN it.
                b.minX = n.x - n.width / 2.0;
                b.maxX = n.x + n.width / 2.0;
                b.minZ = n.z - n.length / 2.0;
                b.maxZ = n.z + n.length / 2.0;
                b.minY = n.y - 0.5;
                b.maxY = n.y + BODY_HEIGHT;
                // ...but never one he is already standing in. A route very often starts ON the align that
                // handed over to it, and fencing off the square he is stood on rejects every move he could
                // make: the search died in one layer having gone nowhere, every plan came back "no route
                // found", and he lost jumping and everything else with it (2026-09-22). A box he is inside
                // cannot be avoided, only escaped.
                if (hereX >= b.minX - HALF_WIDTH && hereX <= b.maxX + HALF_WIDTH
                        && hereZ >= b.minZ - HALF_WIDTH && hereZ <= b.maxZ + HALF_WIDTH
                        && hereY >= b.minY - 1.0 && hereY <= b.maxY) {
                    continue;
                }
                out.add(b);
            }
        }
        return out;
    }

    static Ap3RouteMath.RouteState stateOf(LocalPlayer player) {
        Ap3RouteMath.RouteState s = new Ap3RouteMath.RouteState();
        Vec3 pos = player.position();
        Vec3 vel = player.getDeltaMovement();
        s.x = pos.x;
        s.z = pos.z;
        s.y = pos.y;
        s.vx = vel.x;
        s.vz = vel.z;
        s.vy = vel.y;
        s.onGround = player.onGround();
        s.sprinting = player.isSprinting();
        s.crouching = player.isShiftKeyDown();
        // Whatever the last tick's move ran into decides whether this one can sprint at all.
        s.sprintBlocked = player.horizontalCollision && !player.minorHorizontalCollision;
        s.yaw = player.getYRot();
        return s;
    }

    // ---- terminal stops -----------------------------------------------------------------------------------------

    private static boolean tickTermWait(Minecraft client, LocalPlayer player) {
        if (client.screen != null) {
            sawTermScreen = true;
            waitingForTermTicks = 0;
            return true;
        }
        if (!sawTermScreen && ++waitingForTermTicks < Ap3Config.getInstance().getRouteTermWaitTicks()) {
            return true; // give yourself time to open it
        }
        waitingForTerm = false;
        sawTermScreen = false;
        waitingForTermTicks = 0;
        // The next leg starts from rest, right here.
        Ap3Node last = route.get(route.size() - 1);
        Ap3Chain chain = Ap3Feature.currentChain();
        Ap3Node next = null;
        if (last.pathEnd) {
            stop(); // this route is closed; the next one is its own run
            return false;
        }
        if (chain != null) {
            for (Ap3Node n : chain.nodes()) {
                if (n.type == Ap3Node.Type.PATH && n.pathIndex > last.pathIndex
                        && (next == null || n.pathIndex < next.pathIndex)) {
                    next = n; // the next step of the route, after the terminal
                }
            }
        }
        if (next == null) {
            stop();
            return false;
        }
        plan = null;
        stepIndex = 0;
        begin(client, player, next);
        return true;
    }

    private static boolean finishLeg(Minecraft client, LocalPlayer player) {
        Ap3Node last = route.isEmpty() ? null : route.get(route.size() - 1);
        if (last != null && last.termWait) {
            waitingForTerm = true;
            waitingForTermTicks = 0;
            sawTermScreen = false;
            return true;
        }
        stop();
        return false;
    }

    // ---- the world snapshot -------------------------------------------------------------------------------------

    /**
     * Which block columns the route can use, read once on the client thread. A column is a wall when anything in the
     * body's space collides; it is standable when it also has a floor. Blocks Breaker Aura will break are air here.
     */
    static final class Snap implements Ap3RoutePlanner.Terrain {
        /**
         * Half-block cells, not whole ones. A stair's bounding box is a full block tall because its back half is, so
         * reading a column as {@code bounds().maxY} made every staircase a 1.0 rise - over the 0.6 a player steps up -
         * and the route refused to climb it (killer560, 2026-09-22: "it still doesn't keep running up stairs"). At
         * half-block resolution the front of a stair reads 0.5 and the back 1.0, which is what you actually walk up.
         */
        private static final double CELL = 0.5;
        /** How far above and below the starting level a cell is searched for its standing surface. */
        private static final int BAND_UP = 6;
        private static final int BAND_DOWN = 8;

        private final double minX, minZ;
        private final int w, h;
        /** The height the feet rest at in this half-block cell, or NaN where there is nothing to stand on. */
        private final double[] floorY;
        /** Air above that surface, so a step up or a jump can be refused when the head would not fit. */
        private final double[] headroom;
        /** Cells whose only floor is a block an AP3 Block node is going to place (see {@link #placedFloorOnly}). */
        private final boolean[] placed;
        /**
         * Cells that are solid rather than simply empty. Both read as "nowhere to stand", but a hole can be crossed
         * in the air and a wall cannot - and treating a wall as a hole is what walked a route into one at the top of
         * his stairs (drift 0.000 for 22 ticks, then 0.409 and the speed collapsing from 0.71 to 0.05).
         */
        private final boolean[] wall;
        /**
         * The real collision boxes - what a tick's move is actually swept against. The grids above are only the
         * coarse read the search's distance heuristic is built from; the physics never looks at them.
         */
        private final Ap3RouteCollide.BoxWorld world = new Ap3RouteCollide.BoxWorld();
        /** Fluid boxes: nothing stops you entering lava, so the route is told to treat it as somewhere it may not be. */
        private final Ap3RouteCollide.BoxWorld hazards = new Ap3RouteCollide.BoxWorld();

        private Snap(double minX, double minZ, int w, int h) {
            this.minX = minX;
            this.minZ = minZ;
            this.w = w;
            this.h = h;
            this.floorY = new double[w * h];
            this.headroom = new double[w * h];
            this.placed = new boolean[w * h];
            this.wall = new boolean[w * h];
            java.util.Arrays.fill(floorY, Double.NaN);
        }

        static Snap of(ClientLevel level, LocalPlayer player, List<Ap3RoutePlanner.Gate> gates,
                       Ap3RouteMath.RouteState start) {
            double lox = start.x, hix = start.x, loz = start.z, hiz = start.z;
            for (Ap3RoutePlanner.Gate g : gates) {
                lox = Math.min(lox, g.x);
                hix = Math.max(hix, g.x);
                loz = Math.min(loz, g.z);
                hiz = Math.max(hiz, g.z);
            }
            double minX = Math.floor(lox) - snapPad();
            double minZ = Math.floor(loz) - snapPad();
            int w = (int) Math.ceil((Math.ceil(hix) + snapPad() - minX) / CELL) + 1;
            int h = (int) Math.ceil((Math.ceil(hiz) + snapPad() - minZ) / CELL) + 1;
            Snap snap = new Snap(minX, minZ, w, h);
            // The band has to cover the NODES as well as his feet. It used to sit 6 up and 8 down from wherever he
            // was standing, so a node five or seven blocks above him - killer560's course has both - was simply not
            // in the snapshot, and a route to it could not be planned through geometry nobody had read.
            double loY = start.y;
            double hiY = start.y;
            for (Ap3RoutePlanner.Gate g : gates) {
                loY = Math.min(loY, g.y);
                hiY = Math.max(hiY, g.y);
            }
            int feetY = Mth.floor(start.y);
            int bandLo = Mth.floor(loY) - BAND_DOWN;
            int bandHi = Mth.floor(hiY) + BAND_UP;
            // What height a column's surface is judged against.
            //
            // A column often holds several surfaces and the grid keeps one, so something has to choose. Both
            // previous rules chose against the player's feet AT THE MOMENT OF PLANNING - "nearest his feet", then
            // "the highest within a jump of his feet" - and both are wrong in the same way: one transient height
            // decides the surface for every column on the map, thirty blocks away and nine blocks up included.
            // Measured on killer560's world, 2026-09-23: a grid built while he stood in a pit at y 112 resolved the
            // column he starts that route from - where he stands at y 119 - to y 106, and the planner then had no
            // idea where the ground under his own feet was. The snapshot is also kept and reused, so the height it
            // was judged against need not even be the one he has now.
            //
            // The route knows better than he does. Its start and its nodes are places whose heights are known
            // exactly, so each column is judged against the nearest of THOSE - which makes the grid a property of
            // the route rather than of where he happened to be standing, and therefore the same every time.
            double[] anchorX = new double[gates.size() + 1];
            double[] anchorZ = new double[gates.size() + 1];
            double[] anchorY = new double[gates.size() + 1];
            anchorX[0] = start.x;
            anchorZ[0] = start.z;
            anchorY[0] = start.y;
            for (int k = 0; k < gates.size(); k++) {
                Ap3RoutePlanner.Gate g = gates.get(k);
                anchorX[k + 1] = g.x;
                anchorZ[k + 1] = g.z;
                anchorY[k + 1] = g.y;
            }
            long t0 = System.nanoTime();
            for (int i = 0; i < w; i++) {
                for (int j = 0; j < h; j++) {
                    double cx = minX + (i + 0.5) * CELL;
                    double cz = minZ + (j + 0.5) * CELL;
                    double refY = anchorY[0];
                    double nearest = Double.MAX_VALUE;
                    for (int k = 0; k < anchorX.length; k++) {
                        double d = (cx - anchorX[k]) * (cx - anchorX[k]) + (cz - anchorZ[k]) * (cz - anchorZ[k]);
                        if (d < nearest) {
                            nearest = d;
                            refY = anchorY[k];
                        }
                    }
                    snap.readCell(level, cx, cz, refY, bandLo, bandHi, i * h + j);
                }
            }
            snap.readBoxes(level, bandLo, bandHi);
            snap.addBlockNodes(level, feetY);
            snap.addGhostBlocks();
            snap.gateFloors(gates);
            // Index it here, on the client thread, while this Snap is still private to us. BoxWorld built its
            // index lazily on first query, and the first query can come from the planning worker and the renderer
            // at the same moment - one of them could then see the array published before the columns inside it
            // were, read a null column, and conclude there was no floor there.
            snap.boxWorld().freeze();
            snap.hazardWorld().freeze();
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (ms > 20 && Ap3Config.getInstance().isAlignTimerDev()) {
                // This runs on the client thread, so it is a stutter if it grows. Worth seeing before he feels it.
                LOGGER.info("[AP3 route] world snapshot {}x{} cells, y {}..{} took {} ms", w, h, bandLo, bandHi, ms);
            }
            return snap;
        }

        /**
         * The coarse surface grid, exactly as the planner sees it: {@code {minX, minZ, w, h, CELL}} followed by
         * {@code w * h} floor heights in {@code i * h + j} order, NaN where there is nowhere to stand.
         * <p>
         * Dumped with a failure so the offline harness can replay the terrain the GAME used rather than rebuilding
         * its own from the collision boxes. Those two are not the same thing, and on 2026-09-23 the difference hid
         * a real bug for a day: the harness solved a route in 24 ticks that the game could not solve at all,
         * because the harness had never run this code and so never saw that the node's own column was being
         * reported four blocks below the node.
         */
        double[] surfaceGrid() {
            double[] out = new double[5 + floorY.length];
            out[0] = minX;
            out[1] = minZ;
            out[2] = w;
            out[3] = h;
            out[4] = CELL;
            System.arraycopy(floorY, 0, out, 5, floorY.length);
            return out;
        }

        /**
         * Make every column a node covers report the surface that node is actually standing on.
         * <p>
         * A node is somewhere he has already stood, so there is ground there by definition - but the coarse read
         * above has one surface per column and can easily pick a different one, and when it does the route is
         * being asked to reach a place its own terrain model says does not exist. The heuristic then leads it
         * somewhere else entirely and `mustLand` can never be satisfied. Measured on killer560's world,
         * 2026-09-23: his node at y 121 had its column reported at y 117, and the route arrived underneath it
         * every time, "still 0.0 blocks out" horizontally and two blocks low.
         * <p>
         * The node's y IS the answer - it is where his feet were when he placed it - so it is simply written in,
         * for every cell the node's box covers. Nothing else about the column changes: the real collision boxes
         * are untouched, so the physics still decides what actually happens when the route gets there.
         */
        private void gateFloors(List<Ap3RoutePlanner.Gate> gates) {
            List<Ap3RouteCollide.Box> hit = new ArrayList<>();
            for (Ap3RoutePlanner.Gate g : gates) {
                double halfW = Math.max(g.halfW, Ap3RoutePlanner.HALF_WIDTH);
                double halfL = Math.max(g.halfL, Ap3RoutePlanner.HALF_WIDTH);
                int i0 = (int) Math.floor((g.x - halfW - minX) / CELL);
                int i1 = (int) Math.floor((g.x + halfW - minX) / CELL);
                int j0 = (int) Math.floor((g.z - halfL - minZ) / CELL);
                int j1 = (int) Math.floor((g.z + halfL - minZ) / CELL);
                for (int i = Math.max(0, i0); i <= Math.min(w - 1, i1); i++) {
                    for (int j = Math.max(0, j0); j <= Math.min(h - 1, j1); j++) {
                        double cx = minX + (i + 0.5) * CELL;
                        double cz = minZ + (j + 0.5) * CELL;
                        // Only where there is really something to stand on at the node's own level. A node he
                        // placed in mid-air - one he passes THROUGH on a jump - has no floor, and inventing one
                        // would tell the heuristic there is ground in the middle of a gap.
                        hit.clear();
                        boxWorld().collect(cx - 0.01, g.y - 0.6, cz - 0.01, cx + 0.01, g.y + 0.01, cz + 0.01, hit);
                        double top = Double.NaN;
                        for (Ap3RouteCollide.Box b : hit) {
                            if (b.minX <= cx && b.maxX >= cx && b.minZ <= cz && b.maxZ >= cz
                                    && b.maxY <= g.y + 1.0E-6 && (Double.isNaN(top) || b.maxY > top)) {
                                top = b.maxY;
                            }
                        }
                        if (Double.isNaN(top)) {
                            continue;
                        }
                        int cell = i * h + j;
                        floorY[cell] = top;
                        wall[cell] = false;
                        headroom[cell] = Math.max(headroom[cell], BODY_HEIGHT);
                    }
                }
            }
        }

        /**
         * Every collision box in the route's band, filed by block column. One pass over the blocks, taking each
         * state's real collision shape - so a stair contributes its two boxes, a slab its one, and a fence its post
         * and rails. Blocks Breaker Aura is going to break contribute nothing, exactly as they do to the grids.
         */
        private void readBoxes(ClientLevel level, int bandLo, int bandHi) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int bx0 = Mth.floor(minX);
            int bx1 = Mth.floor(minX + (w - 1) * CELL);
            int bz0 = Mth.floor(minZ);
            int bz1 = Mth.floor(minZ + (h - 1) * CELL);
            for (int bx = bx0; bx <= bx1; bx++) {
                for (int bz = bz0; bz <= bz1; bz++) {
                    for (int y = bandLo; y <= bandHi; y++) {
                        pos.set(bx, y, bz);
                        if (!level.isLoaded(pos)) {
                            continue;
                        }
                        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                        if (!state.getFluidState().isEmpty()) {
                            hazards.add(bx, y, bz, bx + 1.0, y + 1.0, bz + 1.0);
                            // ...and fall through. A WATERLOGGED stair, slab, fence or trapdoor has a fluid state
                            // AND a collision shape; skipping it here made the planner sweep straight through the
                            // stairs it was supposed to climb.
                        }
                        if (BreakerAuraFeature.plannerTreatsAsAir(level, pos)) {
                            continue;
                        }
                        net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, pos);
                        if (shape.isEmpty()) {
                            continue;
                        }
                        for (AABB b : shape.toAabbs()) {
                            world.add(bx + b.minX, y + b.minY, bz + b.minZ,
                                    bx + b.maxX, y + b.maxY, bz + b.maxZ);
                        }
                    }
                }
            }
        }

        @Override
        public Ap3RouteCollide.Shapes shapes() {
            return world;
        }

        Ap3RouteCollide.BoxWorld boxWorld() {
            return world;
        }

        Ap3RouteCollide.BoxWorld hazardWorld() {
            return hazards;
        }

        @Override
        public boolean deny(double x, double y, double z) {
            // Called once per candidate tick - millions of times per plan - so it borrows the collider's
            // per-thread scratch rather than allocating a box and a list each time.
            return Ap3RouteCollide.overlaps(x, y, z, BODY_HEIGHT, hazards);
        }

        /**
         * The surface under one half-block cell: the highest block top at this exact spot within the band, with room
         * for the body above it. The height is read from the collision boxes that actually cover this spot, so half
         * slabs, stairs and carpets give their real height rather than their block's outline.
         */
        /**
         * The surface this column reports, searched downwards from the top of the band.
         * <p>
         * It must keep looking past a surface it cannot stand on. It used to give up at the first block top it
         * found: in a roofed room that is the CEILING, and a ceiling two blocks thick has no headroom, so the whole
         * column was marked an impassable wall and the floor underneath it was never seen. Every such cell reads
         * NaN, the heuristic field marks it blocked, the flood reaches nothing, and the search is left with
         * straight-line distance - which is exactly the "it just runs straight at the node" behaviour, and why
         * killer560's ground profiles came back as row after row of X. A roof is not a wall; it is something with a
         * floor under it.
         */
        private void readCell(ClientLevel level, double x, double z, double feetY, int bandLo, int bandHi,
                              int cell) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int bx = Mth.floor(x);
            int bz = Mth.floor(z);
            // Start above his head rather than at his feet: a node on a platform several blocks up needs its own
            // surface to be findable, and the level test below picks the one he can actually use.
            boolean fluid = false;
            for (int y = bandHi; y >= bandLo; y--) {
                pos.set(bx, y, bz);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                if (!level.getBlockState(pos).getFluidState().isEmpty()) {
                    // Lava or water: somewhere the route may not be. Keep the column blocked but go on looking, so
                    // a pool on a balcony does not blind the floor below it.
                    fluid = true;
                    continue;
                }
                double top = topAt(level, pos, x - bx, z - bz);
                if (Double.isNaN(top)) {
                    continue;
                }
                // The air above a HALF-height surface is inside that surface's OWN block, so the scan has to start
                // above it: starting at floor(top) found the slab / stair itself, reported no headroom and marked
                // every half-height cell unstandable - which is why the route climbed the back of a stair (top 1.0)
                // and refused its front (top 0.5).
                double head = 0;
                int firstBlock;
                if (Math.abs(top - Math.round(top)) < 1.0E-6) {
                    firstBlock = (int) Math.round(top); // flush with a block boundary: the air starts in the next one
                } else {
                    firstBlock = (int) Math.floor(top) + 1;
                    head += firstBlock - top; // whatever is left of the surface's own block is already air
                }
                for (int up = 0; up < 3; up++) {
                    pos.set(bx, firstBlock + up, bz);
                    if (!Double.isNaN(topAt(level, pos, x - bx, z - bz))) {
                        break;
                    }
                    head += 1.0;
                }
                if (head < BODY_HEIGHT - 0.1) {
                    continue; // no room to stand on this one - it is a roof, so keep looking for the floor below
                }
                // Which surface this column reports, when it has more than one: the one nearest his feet.
                // Searching from the top would otherwise hand back a balcony three floors up in place of the
                // ground he is standing on, and Field.at then charges him the whole UNDER_PENALTY for being on
                // his own floor.
                //
                // This was briefly "the highest one within a jump of his feet" (2026-09-22). That was wrong in a
                // way that took a day to see: `feetY` is where he happens to be standing at the moment of
                // planning, and the rule applied it to EVERY column in the snapshot. So no surface more than 1.2
                // blocks above him was usable anywhere on the map, and his two-blocks-up node reported the floor
                // of the pit beneath it instead. His log, 2026-09-23, with the node at y 121 and his feet at 119:
                //   ground ... (relative heights): 0.0 0.0 0.0 0.0 0.0 X X X X X X X X X 1.0 1.0 1.0 -7.0 -7.0
                //   -7.0 -2.0 -2.0 -2.0 -2.0
                // The node's own column reads -2.0, four blocks below the node, so the search was being asked to
                // land on ground its terrain said was not there: "reached 1/2 gates, best was 11 ticks in and
                // still 0.0 blocks out" - horizontally on target, never on it. See gateFloors() for what makes a
                // node's own column tell the truth.
                if (!Double.isNaN(floorY[cell]) && Math.abs(floorY[cell] - feetY) <= Math.abs(top - feetY)) {
                    continue;
                }
                floorY[cell] = top;
                headroom[cell] = head;
                // A surface far outside the route's own height band is not this route's ground - it is a ceiling,
                // or a balcony three floors up - and calling it THE surface of this column is worse than calling
                // the column empty. An empty column is a hole the field can fly over; a phantom floor severs it.
                // Measured on his neo, 2026-09-23: the column three blocks past his ledge reported y 128, eight
                // above the route, which is not a hole, so the flood stopped there and the whole approach read as
                // unreachable. The search then got straight-line distance and ran off the edge every time.
                if (Math.abs(floorY[cell] - feetY) > SURFACE_BAND) {
                    floorY[cell] = Double.NaN;
                    headroom[cell] = 0;
                }
                if (!Double.isNaN(floorY[cell]) && floorY[cell] <= feetY + Ap3RouteCollide.MAX_UP_STEP
                        && floorY[cell] >= feetY - Ap3RouteCollide.MAX_UP_STEP) {
                    break; // standing on it already: nothing further down can be a better answer
                }
                continue;
            }
            if (Double.isNaN(floorY[cell]) && fluid) {
                wall[cell] = true; // nothing but fluid in this column
            }
        }

        /** The top of whatever collides at this spot inside the block, or NaN when nothing does. */
        private static double topAt(ClientLevel level, BlockPos pos, double fx, double fz) {
            if (BreakerAuraFeature.plannerTreatsAsAir(level, pos)) {
                return Double.NaN;
            }
            net.minecraft.world.phys.shapes.VoxelShape shape =
                    level.getBlockState(pos).getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                return Double.NaN;
            }
            double best = Double.NaN;
            for (AABB box : shape.toAabbs()) {
                if (fx >= box.minX - 1.0E-7 && fx <= box.maxX + 1.0E-7
                        && fz >= box.minZ - 1.0E-7 && fz <= box.maxZ + 1.0E-7) {
                    double top = pos.getY() + box.maxY;
                    best = Double.isNaN(best) ? top : Math.max(best, top);
                }
            }
            return best;
        }

        /**
         * A Block node puts a block down in front of itself, so the route may run over that spot even though there is
         * nothing there now - killer560 (2026-09-22): "It should just be able to recognize that... a block is going to
         * appear at that area. Then it should know that it will be able to run on that block for two ticks". Assumed
         * to be a bottom slab at most, and never placed by the route itself: the Block node does that.
         */
        /**
         * No Go nodes, as GHOST BLOCKS: solid to the route planner, absent from the world.
         * <p>
         * killer560 (2026-09-23): "change nogo to me creating ghost blocks that do nothing except tell the ap3 hey
         * there is a block here even if we cannot see it. That way i can make it hit neos on something even if it
         * could do it normally."
         * <p>
         * It used to be the opposite - a box the route was forbidden to enter - and the difference matters: a
         * forbidden box is somewhere the search routes AROUND, while a ghost block is something it can stand on,
         * step up, be stopped by and jump off. A full block on the node's own level, not the half slab a Block
         * node places, because he is building geometry to move on rather than a ledge to cross.
         * <p>
         * Only the planner ever sees these. Nothing is placed, nothing is sent, and the real world is untouched.
         */
        private void addGhostBlocks() {
            Ap3Chain chain = Ap3Feature.currentChain();
            if (chain == null) {
                return;
            }
            for (Ap3Node n : chain.nodes()) {
                if (n.type != Ap3Node.Type.NO_GO) {
                    continue;
                }
                double halfW = Math.max(n.width, CELL) / 2.0;
                double halfL = Math.max(n.length, CELL) / 2.0;
                int i0 = (int) Math.floor((n.x - halfW - minX) / CELL);
                int i1 = (int) Math.floor((n.x + halfW - minX) / CELL);
                int j0 = (int) Math.floor((n.z - halfL - minZ) / CELL);
                int j1 = (int) Math.floor((n.z + halfL - minZ) / CELL);
                for (int i = Math.max(0, i0); i <= Math.min(w - 1, i1); i++) {
                    for (int j = Math.max(0, j0); j <= Math.min(h - 1, j1); j++) {
                        double cx = minX + i * CELL;
                        double cz = minZ + j * CELL;
                        world.add(cx, n.y, cz, cx + CELL, n.y + 1.0, cz + CELL);
                        int cell = i * h + j;
                        // Its top is somewhere to stand, unless the real world already puts something higher here.
                        if (Double.isNaN(floorY[cell]) || floorY[cell] < n.y + 1.0) {
                            floorY[cell] = n.y + 1.0;
                            headroom[cell] = Math.max(headroom[cell], BODY_HEIGHT);
                            wall[cell] = false;
                        }
                    }
                }
            }
        }

        private void addBlockNodes(ClientLevel level, int feetY) {
            Ap3Chain chain = Ap3Feature.currentChain();
            if (chain == null) {
                return;
            }
            for (Ap3Node n : chain.nodes()) {
                if (n.type != Ap3Node.Type.BLOCK) {
                    continue;
                }
                double rad = Math.toRadians(n.yaw);
                double bx = n.x - Math.sin(rad);
                double bz = n.z + Math.cos(rad);
                for (int dx = 0; dx <= 1; dx++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        int cell = cellIndex(Mth.floor(bx) + dx * CELL + 0.25, Mth.floor(bz) + dz * CELL + 0.25);
                        if (cell < 0 || !Double.isNaN(floorY[cell])) {
                            continue; // already somewhere to stand
                        }
                        floorY[cell] = n.y + 0.5; // a bottom slab at most, on the node's own level
                        headroom[cell] = 3.0;
                        placed[cell] = true;
                        // ... and give the collider something to stand on, or the move would fall straight through.
                        double cx = Mth.floor(bx) + dx * CELL;
                        double cz = Mth.floor(bz) + dz * CELL;
                        world.add(cx, n.y, cz, cx + CELL, n.y + 0.5, cz + CELL);
                    }
                }
            }
        }

        /**
         * Where to sample across the 0.6-wide hitbox. Stepping by 0.25 from -0.3 gave -0.30, -0.05 and +0.20 and
         * stopped: the +0.30 edge was never looked at, so a hole or a riser in that last tenth of a block on the
         * positive side was invisible, and asymmetrically so.
         */
        private static double[] edges(double c) {
            return new double[]{c - HALF_WIDTH, c - HALF_WIDTH / 2, c, c + HALF_WIDTH / 2, c + HALF_WIDTH};
        }

        private int cellIndex(double x, double z) {
            int i = (int) Math.floor((x - minX) / CELL);
            int j = (int) Math.floor((z - minZ) / CELL);
            return i < 0 || j < 0 || i >= w || j >= h ? -1 : i * h + j;
        }

        @Override
        public double floorAt(double x, double z) {
            // Every cell the 0.6-wide box covers has to hold it up; the feet rest on the highest of them.
            double best = Double.NaN;
            for (double sx : edges(x)) {
                for (double sz : edges(z)) {
                    int cell = cellIndex(sx, sz);
                    if (cell < 0) {
                        return Double.NaN; // outside what was read: not somewhere to plan through
                    }
                    double f = floorY[cell];
                    if (Double.isNaN(f)) {
                        return Double.NaN;
                    }
                    best = Double.isNaN(best) ? f : Math.max(best, f);
                }
            }
            return best;
        }

        @Override
        public boolean bodyClear(double x, double z, double y) {
            for (double sx : edges(x)) {
                for (double sz : edges(z)) {
                    int cell = cellIndex(sx, sz);
                    if (cell < 0) {
                        return false;
                    }
                    if (wall[cell]) {
                        return false; // solid, whatever height you are at
                    }
                    double f = floorY[cell];
                    if (Double.isNaN(f)) {
                        continue; // open space above a hole: nothing for the body to hit
                    }
                    if (y < f - 1.0E-4 || y + BODY_HEIGHT > f + headroom[cell] + 1.0E-4) {
                        return false;
                    }
                }
            }
            return true;
        }

        @Override
        public boolean walkable(double x, double z) {
            return !Double.isNaN(floorAt(x, z));
        }

        @Override
        public boolean placedFloorOnly(double x, double z) {
            boolean any = false;
            for (double sx : edges(x)) {
                for (double sz : edges(z)) {
                    int cell = cellIndex(sx, sz);
                    if (cell < 0 || Double.isNaN(floorY[cell])) {
                        continue;
                    }
                    if (!placed[cell]) {
                        return false; // real ground under part of the box: that is what holds you up
                    }
                    any = true;
                }
            }
            return any;
        }
    }


    // ---- keeping the world scan ---------------------------------------------------------------------------------

    /**
     * The last world scan, kept so running the same route again costs nothing. killer560 (2026-09-22): "It should
     * load in instantly after it has been generated there should be 0 downtime."
     * <p>
     * Reading the world is 40-50 ms on the client thread and it happens on EVERY plan - so even when the search
     * itself is answered from a saved plan, that scan was still being paid, and it is most of what he feels as the
     * route taking a moment to appear. It is only reused for the same route, from near the same place, within a few
     * seconds, because the thing it is a picture of can change: a door opens, a crypt goes, Breaker Aura takes a
     * block. Beyond that it is worth looking again.
     */
    private static String snapKey = "";
    private static Snap snapKept;
    private static long snapTakenAt;
    private static double snapX, snapY, snapZ;
    /** How long a scan stays good for, in milliseconds. */
    private static final long SNAP_TTL_MS = 5000;
    /** ...and how far he may have moved since, before it is worth taking another. */
    private static final double SNAP_MOVED = 6.0;

    private static Snap reusableSnapshot(String key, Ap3RouteMath.RouteState start) {
        if (snapKept == null || !snapKey.equals(key)) {
            return null;
        }
        if (System.currentTimeMillis() - snapTakenAt > SNAP_TTL_MS) {
            return null;
        }
        if (Math.hypot(start.x - snapX, start.z - snapZ) > SNAP_MOVED) {
            return null;
        }
        // Height counts as having moved. Falling into a pit barely changes x and z, and the kept scan's band was
        // read around the height he was at when it was taken.
        if (Math.abs(start.y - snapY) > SNAP_MOVED) {
            return null;
        }
        return snapKept;
    }

    private static void keepSnapshot(String key, Snap snap, Ap3RouteMath.RouteState start) {
        snapKey = key;
        snapKept = snap;
        snapTakenAt = System.currentTimeMillis();
        snapX = start.x;
        snapY = start.y;
        snapZ = start.z;
    }

    /** Throw the kept scan away - the world it pictures is no longer the one we are in. */
    static void forgetSnapshot() {
        snapKept = null;
        snapKey = "";
    }
}
