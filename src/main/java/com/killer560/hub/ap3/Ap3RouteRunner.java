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
    private static final int SNAP_PAD = 16;
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
    /** How close to the first Path node the pre-plan starts, so the route does not stall on arrival. */
    private static final double PRE_PLAN_RANGE = 12.0;

    private Ap3RouteRunner() {
    }

    // ---- session state ------------------------------------------------------------------------------------------

    /** The Path nodes this run is driving, in chain order. */
    private static final List<Ap3Node> route = new ArrayList<>();
    private static Ap3RoutePlanner.Plan plan;
    private static int stepIndex;
    private static Thread worker;
    private static volatile Ap3RoutePlanner.Plan pending;
    private static volatile boolean planning;
    private static Ap3RouteMath.RouteState planStart;
    private static Ap3RouteMath.RouteState predicted;
    private static Ap3DiscretePlanner.Model planModel;
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

    /** The path the current plan follows, for the renderer; empty when there is nothing planned. */
    static List<Vec3> plannedPath(double y) {
        Ap3RoutePlanner.Plan p = plan;
        if (p == null || planStart == null || planModel == null || p.steps.length == 0) {
            return List.of();
        }
        List<Vec3> out = new ArrayList<>(p.steps.length + 1);
        Ap3RouteMath.RouteState s = planStart.copy();
        out.add(new Vec3(s.x, y, s.z));
        for (Ap3RoutePlanner.Step st : p.steps) {
            Ap3RouteMath.step(s, st.keys(), st.yaw(), st.jump(), planModel);
            out.add(new Vec3(s.x, y + (s.y - s.groundY), s.z));
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
        // A plan that was started earlier lands here; it begins at the tick it was planned for, so it waits for it.
        Ap3RoutePlanner.Plan fresh = pendingSeq >= acceptFrom ? pending : null;
        if (pendingSeq >= 0 && pendingSeq < acceptFrom) {
            pending = null; // an answer to a question this route never asked (a pre-plan from before it started)
        }
        if (fresh != null && (plan == null || stepIndex >= pendingAt)) {
            pending = null;
            plan = fresh;
            stepIndex = 0;
            lastPlanStep = 0;
            predicted = planStart == null ? null : planStart.copy();
        }
        if (plan == null) {
            return true; // still planning - no keys this tick, the player coasts
        }
        if (stepIndex >= plan.steps.length) {
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
            }
        }
        if (!planning && (drifted || stepIndex - lastPlanStep >= REPLAN_EVERY)) {
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
            Ap3RouteMath.step(predicted, step.keys(), step.yaw(), step.jump(), planModel);
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
            Ap3RouteMath.step(from, st.keys(), st.yaw(), st.jump(), planModel);
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
     */
    private static void collectRoute(Ap3Node first) {
        Ap3Chain chain = Ap3Feature.currentChain();
        route.clear();
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
            if (n.pathIndex < from || n.pathIndex > stopAfter) {
                continue;
            }
            route.add(n);
            if (n.termWait) {
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
        List<Ap3RoutePlanner.Blocked> blocked = noGoZones(player);
        Ap3DiscretePlanner.Model model = Ap3Executor.routeModel(player);
        Snap snap = Snap.of(client.level, player, gates, start);
        Ap3RoutePlanner.Options options = new Ap3RoutePlanner.Options();
        Ap3Config cfg = Ap3Config.getInstance();
        options.allowJump = cfg.isRouteAllowJumps();
        options.beam = cfg.getRouteBeam();
        // The first plan may think; a re-plan may not - every millisecond it spends is a tick the route is coasting.
        options.budgetMs = plan == null ? cfg.getRouteBudgetMs() : Math.min(cfg.getRouteBudgetMs(), 300);
        planStart = start;
        planModel = model;
        pendingAt = at;
        planning = true;
        final int seq = planSeq.incrementAndGet();
        worker = new Thread(() -> {
            try {
                Ap3RoutePlanner.Plan p = Ap3RoutePlanner.plan(start, gates, blocked, snap, model, options);
                pending = p;
                pendingSeq = seq;
                if (announce && Ap3Config.getInstance().isChatFeedback()) {
                    ModChat.send("AP3", ModChat.text("Route "),
                            ModChat.value(String.format(Locale.US, "%.2fs", p.ticks / 20.0)),
                            ModChat.dim(" (" + p.ticks + " ticks, " + jumpsIn(p) + " jumps"
                                    + (p.complete ? "" : ", INCOMPLETE - " + p.note) + ")"));
                }
                LOGGER.info("[AP3 route] planned {} ticks, {} gates{}, from ({}, {}) v {} splicing at step {}",
                        p.ticks, p.gateTick.length, p.complete ? "" : " INCOMPLETE - " + p.note,
                        String.format(Locale.US, "%.2f", start.x), String.format(Locale.US, "%.2f", start.z),
                        String.format(Locale.US, "%.4f", Math.hypot(start.vx, start.vz)), at);
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

    /** Every No Go node in the chain becomes a box the route may not enter. */
    private static List<Ap3RoutePlanner.Blocked> noGoZones(LocalPlayer player) {
        List<Ap3RoutePlanner.Blocked> out = new ArrayList<>();
        Ap3Chain chain = Ap3Feature.currentChain();
        if (chain == null) {
            return out;
        }
        for (Ap3Node n : chain.nodes()) {
            if (n.type != Ap3Node.Type.NO_GO) {
                continue;
            }
            Ap3RoutePlanner.Blocked b = new Ap3RoutePlanner.Blocked();
            b.minX = n.x - n.width / 2.0;
            b.maxX = n.x + n.width / 2.0;
            b.minZ = n.z - n.length / 2.0;
            b.maxZ = n.z + n.length / 2.0;
            b.minY = n.y - 0.5;
            b.maxY = n.y + BODY_HEIGHT;
            out.add(b);
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
        s.groundY = pos.y;
        s.vx = vel.x;
        s.vz = vel.z;
        s.vy = vel.y;
        s.onGround = player.onGround();
        s.sprinting = player.isSprinting();
        s.crouching = player.isShiftKeyDown();
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
        private final int minX, minZ, w, h;
        private final boolean[] wall;
        private final boolean[] floor;
        /** Columns whose only floor is a block an AP3 Block node is going to place (see {@link #placedFloorOnly}). */
        private final boolean[] placed;

        private Snap(int minX, int minZ, int w, int h) {
            this.minX = minX;
            this.minZ = minZ;
            this.w = w;
            this.h = h;
            this.wall = new boolean[w * h];
            this.floor = new boolean[w * h];
            this.placed = new boolean[w * h];
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
            int minX = Mth.floor(lox) - SNAP_PAD;
            int minZ = Mth.floor(loz) - SNAP_PAD;
            int w = Mth.floor(hix) + SNAP_PAD - minX + 1;
            int h = Mth.floor(hiz) + SNAP_PAD - minZ + 1;
            Snap snap = new Snap(minX, minZ, w, h);
            int feetY = Mth.floor(start.y);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int i = 0; i < w; i++) {
                for (int j = 0; j < h; j++) {
                    int bx = minX + i;
                    int bz = minZ + j;
                    boolean solid = false;
                    for (int dy = 0; dy <= 1 && !solid; dy++) {
                        pos.set(bx, feetY + dy, bz);
                        solid = blocksBody(level, pos);
                    }
                    if (!solid) {
                        // Lava has no collision and usually solid ground under it, so without this the route would
                        // run straight through a pool. A bounce costs the whole run (horizontal speed halves every
                        // tick in there - see Ap3RouteMath), so until the planner can spend that on purpose for the
                        // height it gets, lava is somewhere the route does not go.
                        pos.set(bx, feetY, bz);
                        solid = !level.getBlockState(pos).getFluidState().isEmpty();
                    }
                    pos.set(bx, feetY - 1, bz);
                    boolean hasFloor = !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
                    snap.wall[i * h + j] = solid;
                    snap.floor[i * h + j] = hasFloor;
                }
            }
            snap.addBlockNodes(level, feetY);
            return snap;
        }

        /**
         * A Block node puts a block down in front of itself, so the route may run over that spot even though there is
         * nothing there now - killer560 (2026-09-22): "It should just be able to recognize that... a block is going to
         * appear at that area. Then it should know that it will be able to run on that block for two ticks". Assumed
         * to be a bottom slab at most, and never placed by the route itself: the Block node does that.
         */
        private void addBlockNodes(ClientLevel level, int feetY) {
            Ap3Chain chain = Ap3Feature.currentChain();
            if (chain == null) {
                return;
            }
            for (Ap3Node n : chain.nodes()) {
                if (n.type != Ap3Node.Type.BLOCK) {
                    continue;
                }
                // Where the node aims: one block along its own facing, at the height its feet were placed at.
                double rad = Math.toRadians(n.yaw);
                int bx = Mth.floor(n.x - Math.sin(rad));
                int bz = Mth.floor(n.z + Math.cos(rad));
                int i = bx - minX;
                int j = bz - minZ;
                if (i < 0 || j < 0 || i >= w || j >= h || floor[i * h + j] || wall[i * h + j]) {
                    continue; // already solid ground, or a wall: nothing for the Block node to add
                }
                floor[i * h + j] = true;
                placed[i * h + j] = true;
            }
        }

        /** Does this block stop the body - counting anything Breaker Aura is going to remove as already gone? */
        private static boolean blocksBody(ClientLevel level, BlockPos pos) {
            if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return false;
            }
            return !BreakerAuraFeature.plannerTreatsAsAir(level, pos);
        }

        private boolean cellWall(int bx, int bz) {
            int i = bx - minX;
            int j = bz - minZ;
            if (i < 0 || j < 0 || i >= w || j >= h) {
                return true; // outside what was read: treat as a wall rather than plan blind
            }
            return wall[i * h + j];
        }

        private boolean cellFloor(int bx, int bz) {
            int i = bx - minX;
            int j = bz - minZ;
            return i >= 0 && j >= 0 && i < w && j < h && floor[i * h + j];
        }

        @Override
        public boolean walkable(double x, double z) {
            return check(x, z, true);
        }

        @Override
        public boolean placedFloorOnly(double x, double z) {
            int x0 = Mth.floor(x - HALF_WIDTH);
            int x1 = Mth.floor(x + HALF_WIDTH);
            int z0 = Mth.floor(z - HALF_WIDTH);
            int z1 = Mth.floor(z + HALF_WIDTH);
            boolean any = false;
            for (int bx = x0; bx <= x1; bx++) {
                for (int bz = z0; bz <= z1; bz++) {
                    int i = bx - minX;
                    int j = bz - minZ;
                    if (i < 0 || j < 0 || i >= w || j >= h || !floor[i * h + j]) {
                        continue;
                    }
                    if (!placed[i * h + j]) {
                        return false; // real ground under part of the box: that is what holds you up
                    }
                    any = true;
                }
            }
            return any;
        }

        @Override
        public boolean clearAir(double x, double z) {
            return check(x, z, false);
        }

        private boolean check(double x, double z, boolean needFloor) {
            int x0 = Mth.floor(x - HALF_WIDTH);
            int x1 = Mth.floor(x + HALF_WIDTH);
            int z0 = Mth.floor(z - HALF_WIDTH);
            int z1 = Mth.floor(z + HALF_WIDTH);
            for (int bx = x0; bx <= x1; bx++) {
                for (int bz = z0; bz <= z1; bz++) {
                    if (cellWall(bx, bz)) {
                        return false;
                    }
                }
            }
            if (!needFloor) {
                return true;
            }
            for (int bx = x0; bx <= x1; bx++) {
                for (int bz = z0; bz <= z1; bz++) {
                    if (cellFloor(bx, bz)) {
                        return true; // any block under the box holds you up
                    }
                }
            }
            return false;
        }
    }

    /** Unused import guard: the body height is what the snapshot samples. */
    static AABB bodyBox(double x, double y, double z) {
        return new AABB(x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + BODY_HEIGHT, z + HALF_WIDTH);
    }
}
