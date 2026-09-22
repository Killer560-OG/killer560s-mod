package com.killer560.hub.ap3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The route optimiser - killer560 (2026-09-22): "a movement optimizer/pathfinder... I should be able to set the first
 * node, a second node, a third node, etc. It should find the most optimal movement to hit each node from the first to
 * the last without skipping one... prioritize reaching the last node in that set as fast as possible."
 * <p>
 * <b>What it solves.</b> Given where you are and an ORDERED list of gates, find the key/yaw/jump schedule that reaches
 * the LAST gate in the fewest ticks, passing through every gate on the way (a gate is crossed when the tick's movement
 * passes through its box - or, for an exact gate, when the feet land on its point), never entering a blacklisted box,
 * and meeting any speed/heading a gate asks for.
 * <p>
 * <b>How.</b> A layered beam search on the exact step ({@link Ap3RouteMath#step}): every tick is one real key
 * combination at one yaw, with jump on or off, so what comes out is something a keyboard could have typed. Since the
 * yaw is free, the branching is over WORLD push directions (a ring of them, plus the ones that aim at the next gate
 * and along/against the current velocity) crossed with the key shapes that matter - straight, diagonal (a bit more
 * push), sideways (drops sprint) and their sneaking versions (0.3x, one tick late). All ticks cost the same, so the
 * layers are explored in tick order and each layer is cut to the best {@link Options#beam} states by
 * {@code ticks + remaining distance / top speed} - an A* bound that never overestimates, because nothing in the model
 * moves faster than a sprint-jump cycle. States that land on the same lattice cell with the same gate progress are
 * merged. Jumping is planned, not assumed: the air keeps 0.91 of your speed a tick where stone keeps 0.546, and a
 * sprinting jump adds 0.2 along the facing, so the search finds bunny-hopping by itself where it is actually faster.
 * <p>
 * <b>Exact gates</b> are searched with a small radius and then polished: the yaws of the few ticks before the crossing
 * are re-solved on the exact step until the feet land on the point (see {@link #refineExact}).
 * <p>
 * The plan is a fixed schedule; the executor re-checks it every tick against the measured state and re-plans when the
 * game disagrees. Nothing here writes a position or a velocity - it only chooses keys and a yaw.
 */
final class Ap3RoutePlanner {

    private Ap3RoutePlanner() {
    }

    /**
     * A point the route must pass through. Gates run in {@link #group} order, and gates that SHARE a group must all
     * be reached but in NO particular order - killer560 (2026-09-22): "I should be able to for instance select both
     * levers... It just needs to get both not in any particular order". One tick can tick off several of them at
     * once, so two boxes that overlap are satisfied by running through the middle rather than visiting each in turn.
     */
    static final class Gate {
        int group;
        double x, z;
        /** Half sizes of the box; 0 when {@link #exact}. */
        double halfW, halfL;
        /**
         * An exact gate - killer560 (2026-09-22): "I care about how close to being on the edge of the block it is.
         * Not that it is 200 decimals precise to my original location but that it is still either on or off of a
         * block at nearly identical coordinates". So what is checked is the BLOCK RELATIONSHIP: the hitbox has to
         * cover the same blocks it covered when the node was placed (same overhang on the same edges), and the feet
         * have to be within {@link #exactTol} of the point. Hitting 0.02 costs far fewer ticks than chasing 0.001.
         */
        boolean exact;
        double exactTol = 0.02;
        /** Block cells the 0.6-wide hitbox covered at placement; the landing has to cover the same ones. */
        int cellMinX, cellMaxX, cellMinZ, cellMaxZ;
        double y;
        /** Speed window at the crossing, blocks/tick; negative = no requirement. */
        double minSpeed = -1, maxSpeed = -1;
        /** Optional heading requirement at the crossing (MC yaw degrees of the travel direction). */
        boolean hasDir;
        double dirDeg, dirTolDeg = 15.0;
        /**
         * Reach this one with both feet down. Set on the LAST gate of a plan, because that is where the route ends
         * and killer560 (2026-09-22) wants it to end like a Stop node does: "don't have it randomly jump. It should
         * just stop holding any movement key". Crossing a box gate in mid-air is fine everywhere else - it is how a
         * route hops a gap - but arriving at the end still airborne means landing wherever the arc happens to put
         * you, which is not stopping.
         */
        boolean mustLand;

        boolean wantsVelocity() {
            return minSpeed >= 0 || maxSpeed >= 0 || hasDir;
        }

        /** Records which blocks the hitbox covers at this gate's own point - call once after setting x/z. */
        void captureBlocks() {
            cellMinX = (int) Math.floor(x - HALF_WIDTH);
            cellMaxX = (int) Math.floor(x + HALF_WIDTH);
            cellMinZ = (int) Math.floor(z - HALF_WIDTH);
            cellMaxZ = (int) Math.floor(z + HALF_WIDTH);
        }

        /** Same blocks under the box as at placement: on the edge stays on the edge, off it stays off. */
        boolean sameBlocks(double px, double pz) {
            return (int) Math.floor(px - HALF_WIDTH) == cellMinX && (int) Math.floor(px + HALF_WIDTH) == cellMaxX
                    && (int) Math.floor(pz - HALF_WIDTH) == cellMinZ && (int) Math.floor(pz + HALF_WIDTH) == cellMaxZ;
        }
    }

    /**
     * How much height a sprint jump gains, for deciding whether the heuristic's grid is connected upwards. A jump
     * peaks at 1.252 blocks, and you have to land ON the ledge rather than brush it, so this is a little under.
     */
    static final double JUMP_CLIMB = 1.2;

    /** How far past an exact gate's point to aim when the landing came down on the wrong side of a block edge. */
    private static final double EDGE_NUDGE = 0.004;

    /** How many ticks before an exact gate the polish may re-aim, widened until one of them lands it. */
    private static final int[] POLISH_WINDOWS = {3, 6, 10};

    /** Half of the player's 0.6-wide collision box: what decides whether you are on or off an edge. */
    static final double HALF_WIDTH = 0.3;
    /** {@code Player.maxUpStep}: this much of a rise is walked up with no jump - a slab or a stair, not a full block. */
    static final double STEP_UP = Ap3RouteCollide.MAX_UP_STEP;
    /** A gate counts as reached only on its own level, within this much. */
    static final double GATE_Y_TOLERANCE = 1.0;

    /**
     * The world the route runs through, sampled ahead of planning so the search can run off the client thread. A
     * position is only usable when every block column the 0.6-wide hitbox touches is walkable: nothing solid in the
     * body's way and a floor to stand on. Blocks that Breaker Aura is going to break count as air (killer560:
     * "If a block is selected for breaker aura treat that block as not being there when you go to run through it").
     */
    interface Terrain {
        /**
         * The real collision boxes, which is what a tick's move is actually swept against. Everything else on this
         * interface is a COARSE read of the same blocks, used only to build the search's distance heuristic - the
         * physics never consults it, because a height profile is not what the game collides with.
         */
        default Ap3RouteCollide.Shapes shapes() {
            return Ap3RouteMath.FLAT_GROUND;
        }

        /**
         * Somewhere the route is not allowed to be even though nothing stops it physically: lava, mainly, whose
         * bounce costs the whole run. Checked on the position a tick lands at.
         */
        default boolean deny(double x, double y, double z) {
            return false;
        }

        /**
         * The height the feet rest at here, or NaN where there is nowhere to stand. This is what lets a route use
         * stairs: vanilla steps up to {@link #STEP_UP} of a block for free, so a stair or a slab is walked up without
         * jumping, and anything taller needs a jump or is a wall.
         */
        double floorAt(double x, double z);

        /** Is the body's space free with the feet at {@code y}? (Head room for a step up, a jump or a landing.) */
        boolean bodyClear(double x, double z, double y);

        /** Can the player's box stand here, feet at the route's floor height? */
        boolean walkable(double x, double z);


        /**
         * Is the only thing holding you up here a block an AP3 Block node is going to place? killer560 (2026-09-22):
         * "make it always assume that block is going to be a bottom half slab at most. It should be able to get two
         * walking ticks off of the slab... Do not make it place the block on its own." So the planner may run over
         * one, but only for {@link Options#slabTicks} ticks before it is gone again.
         */
        default boolean placedFloorOnly(double x, double z) {
            return false;
        }

        Terrain OPEN = new Terrain() {
            public double floorAt(double x, double z) {
                return 0.0;
            }

            public boolean bodyClear(double x, double z, double y) {
                return true;
            }

            public boolean walkable(double x, double z) {
                return true;
            }

        };
    }

    /** A box the route may never enter (walls, drops, "do not step here"). */
    static final class Blocked {
        double minX, minZ, maxX, maxZ;
        /** Height band; a jump over it counts as clear when the feet are above {@link #maxY}. */
        double minY = Double.NEGATIVE_INFINITY, maxY = Double.POSITIVE_INFINITY;
    }

    static final class Options {
        boolean allowJump = true;
        int beam = 400;
        int maxTicks = 160;
        /** Ring of world push directions tried per state. */
        int dirs = 16;
        /** Give up after this long and report the best partial route. */
        long budgetMs = 1500;
        /**
         * What a turn costs the plan, in ticks per 180 degrees. The same travel direction can be had as "W at this
         * yaw" or "W+A at 45 degrees off it", and with nothing to separate them the search happily alternates - which
         * on screen is the player spinning on the spot while walking straight (killer560, 2026-09-22: "it tends to
         * just spin in circles"). Small enough that it only breaks ties, never enough to buy a slower route.
         */
        double turnCost = 0.25;
        /** How many ticks in a row the route may stand on a block a Block node places (a ghost block is short-lived). */
        int slabTicks = 2;
        /**
         * Look for a route that never jumps before considering ones that do. killer560 (2026-09-22): "If it is fast
         * enough such that it can cross something like a 3 block gap without jumping then it always should
         * prioritize that over jumping... 99.999999% of the time just running is faster over gaps it can cross, but
         * it may have to adjust earlier portions of its movement to get the propper block alignment to do so."
         * <p>
         * This is not a tie-break, and a tie-break does not fix it. Measured on a 3-block gap at speed 550: from one
         * approach the free search returned 21 ticks WITH a jump while a 15-tick route with none existed. A jumping
         * state looks good early - the sprint-jump boost buys 0.2 along the facing and the heuristic prices travel
         * at the jump cycle's speed - so jumping states crowd the beam and the running answer, which needs its
         * run-up aligned a few ticks earlier, gets pruned before it can pay off. Searching with jumps switched off
         * first cannot be crowded out, and it is cheaper anyway because the branching is halved.
         */
        boolean preferRunning = true;

        Options copy() {
            Options c = new Options();
            c.allowJump = allowJump;
            c.beam = beam;
            c.maxTicks = maxTicks;
            c.dirs = dirs;
            c.budgetMs = budgetMs;
            c.turnCost = turnCost;
            c.slabTicks = slabTicks;
            c.exactSearch = exactSearch;
            c.exactTol = exactTol;
            c.preferRunning = preferRunning;
            return c;
        }
        /**
         * How close the search must get to an exact gate before the polish takes over. The polish only re-aims yaws,
         * so it can redirect the speed that is there but cannot add any: hand it a 0.15 gap at walking pace and it
         * cannot close it (measured - a 0.146 hand-over polished to 0.055 and no further). 0.10 is inside its reach,
         * and costs about one extra tick.
         */
        double exactSearch = 0.10;
        /** Default landing tolerance on an exact gate when the gate does not set its own. */
        double exactTol = 0.02;
    }

    /** One tick of the answer. */
    record Step(Ap3DiscretePlanner.Action keys, float yaw, boolean jump) {
    }

    static final class Plan {
        Step[] steps = new Step[0];
        int ticks;
        /** Every gate reached. */
        boolean complete;
        /** Gates reached when it is not complete. */
        int gatesReached;
        String note = "";
        /** Where each gate is crossed (index into steps). */
        int[] gateTick = new int[0];
    }

    // ---- the search --------------------------------------------------------------------------------------------

    private static final class Node {
        Ap3RouteMath.RouteState s;
        /** Which group is being worked on, and which of its gates are already ticked off (bit per gate in the group). */
        int group;
        int mask;
        int ticks;
        double f;
        Node parent;
        Ap3DiscretePlanner.Action keys;
        float yaw;
        boolean jump;
        /** How many jumps this whole path has used - the tie-break that keeps a route on its feet. */
        int jumps;
        /** Ticks in a row spent standing on a block a Block node will place. */
        int slabTicks;
        /** Gates crossed on this tick, as indices into the gate list. */
        int[] crossed;
    }

    /** The gates of one group, in the order they appear in the list. */
    private static List<int[]> groupsOf(List<Gate> gates) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (i < gates.size()) {
            int g = gates.get(i).group;
            List<Integer> idx = new ArrayList<>();
            while (i < gates.size() && gates.get(i).group == g) {
                idx.add(i++);
            }
            int[] arr = new int[idx.size()];
            for (int k = 0; k < arr.length; k++) {
                arr[k] = idx.get(k);
            }
            out.add(arr);
        }
        return out;
    }

    static Plan plan(Ap3RouteMath.RouteState start, List<Gate> gates, List<Blocked> blocked,
                     Ap3DiscretePlanner.Model m, Options o) {
        return plan(start, gates, blocked, Terrain.OPEN, m, o);
    }

    static Plan plan(Ap3RouteMath.RouteState start, List<Gate> gates, List<Blocked> blocked, Terrain terrain,
                     Ap3DiscretePlanner.Model m, Options o) {
        if (!o.allowJump || !o.preferRunning) {
            return search(start, gates, blocked, terrain, m, o);
        }
        // Running first (see Options.preferRunning). Half the budget is plenty: with jumps off the search either
        // finds the way across quickly or runs out of places to stand and dies on its own.
        long began = System.nanoTime();
        Options onFoot = o.copy();
        onFoot.allowJump = false;
        // Capped as well as halved: when the probe FAILS its whole share is spent before the real search starts,
        // and a mid-route re-plan only gets 300 ms in the first place. When it succeeds it is quick (under 250 ms
        // in every measured case), so a ceiling costs nothing and bounds the worst case.
        onFoot.budgetMs = Math.max(1, Math.min(o.budgetMs / 2, 400));
        // A wider beam, because with jumps off the branching is halved and the answer often hangs on ONE approach
        // phase surviving the cut - "it may have to adjust earlier portions of its movement to get the propper
        // block alignment". Measured on a 3-block gap at 550: two of eleven approaches found nothing at beam 400
        // and a 15-tick route at beam 1500, one of them in half the time, because finding the way across is
        // cheaper than exhausting every way that falls in.
        onFoot.beam = Math.min(4000, o.beam * 3);
        Plan running = search(start, gates, blocked, terrain, m, onFoot);
        if (running.complete) {
            return running;
        }
        long spentMs = (System.nanoTime() - began) / 1_000_000L;
        Options withJumps = o.copy();
        withJumps.budgetMs = Math.max(1, o.budgetMs - spentMs);
        Plan jumping = search(start, gates, blocked, terrain, m, withJumps);
        // If neither finishes, hand back whichever got further rather than the later one by default.
        return jumping.complete || jumping.gatesReached >= running.gatesReached ? jumping : running;
    }

    private static Plan search(Ap3RouteMath.RouteState start, List<Gate> gates, List<Blocked> blocked,
                               Terrain terrain, Ap3DiscretePlanner.Model m, Options o) {
        Plan plan = new Plan();
        if (gates.isEmpty()) {
            plan.complete = true;
            return plan;
        }
        long deadline = System.nanoTime() + o.budgetMs * 1_000_000L;
        double top = Math.max(Ap3RouteMath.topSpeed(m, o.allowJump), start.speed());
        Field field = new Field(start, gates, blocked, terrain, o);
        List<int[]> groups = groupsOf(gates);
        Node root = new Node();
        root.s = start.copy();
        root.group = 0;
        double startLeft = field.heuristic(start.x, start.z, 0, 0, groups, top);
        root.f = startLeft;
        List<Node> layer = new ArrayList<>();
        layer.add(root);
        Node best = root;
        Map<Long, Node> seen = new HashMap<>();

        for (int tick = 0; tick < o.maxTicks && !layer.isEmpty(); tick++) {
            if (System.nanoTime() > deadline) {
                plan.note = "time budget";
                break;
            }
            List<Node> next = new ArrayList<>(Math.min(o.beam * 8, 4096));
            seen.clear();
            for (Node n : layer) {
                expand(n, gates, groups, blocked, terrain, m, o, top, next, seen, field);
            }
            if (next.isEmpty()) {
                break;
            }
            // Goal: the last gate is crossed. Every node in a layer has the same tick count, so taking the first
            // one expansion order happened to produce was a coin toss between an identical run and jump - which is
            // exactly what killer560 saw ("Right now sometimes it jumps sometimes it doesnt"). Look at all of them
            // and take the one that stayed on its feet.
            Node goal = null;
            for (Node n : next) {
                if (n.group >= groups.size()) {
                    if (goal == null || n.jumps < goal.jumps || (n.jumps == goal.jumps && n.f < goal.f)) {
                        goal = n;
                    }
                    continue;
                }
                if (n.group > best.group || (n.group == best.group
                        && (Integer.bitCount(n.mask) > Integer.bitCount(best.mask)
                        || (Integer.bitCount(n.mask) == Integer.bitCount(best.mask) && n.f < best.f)))) {
                    best = n;
                }
            }
            if (goal != null) {
                return finish(goal, gates, blocked, m, o, terrain, plan);
            }
            next.sort((a, b) -> Double.compare(a.f, b.f));
            layer = next.size() > o.beam ? new ArrayList<>(next.subList(0, o.beam)) : next;
        }
        plan.gatesReached = gatesBefore(groups, best.group) + Integer.bitCount(best.mask);
        plan.note = plan.note.isEmpty() ? "no route found" : plan.note;
        if (field.heuristic(best.s.x, best.s.z, best.group, best.mask, groups, top) >= startLeft - 1e-9) {
            // The search ran out of time without getting anywhere, and the best it has is no closer to the goal than
            // standing still. Driving that is worse than not driving: killer560's route did exactly this at the top
            // of a staircase on 2026-09-22 - a near-180 and a sprint back off the stairs, because the partial it was
            // handed happened to close XZ distance fastest by going downhill. An empty plan tells the runner to hold.
            plan.note += " (no progress - not driving it)";
            plan.complete = false;
            return plan;
        }
        Plan partial = finish(best, gates, blocked, m, o, terrain, plan);
        partial.complete = false;
        return partial;
    }

    private static int gatesBefore(List<int[]> groups, int group) {
        int n = 0;
        for (int i = 0; i < Math.min(group, groups.size()); i++) {
            n += groups.get(i).length;
        }
        return n;
    }

    private static void expand(Node n, List<Gate> gates, List<int[]> groups, List<Blocked> blocked, Terrain terrain,
                               Ap3DiscretePlanner.Model m, Options o, double top, List<Node> out,
                               Map<Long, Node> seen, Field field) {
        Gate target = gates.get(nextGate(n, groups));
        boolean fine = needsFineControl(n, target, top);
        double[] dirs = directions(n, target, o, fine);
        Ap3DiscretePlanner.Action[] shapes = fine ? FINE_SHAPES : FAST_SHAPES;
        for (double dir : dirs) {
            for (Ap3DiscretePlanner.Action a : shapes) {
                float yaw = (float) (Math.toDegrees(dir) - keyOffset(a));
                tryStep(n, a, yaw, false, gates, groups, blocked, terrain, m, o, top, out, seen, field);
                if (o.allowJump && n.s.onGround) {
                    tryStep(n, a, yaw, true, gates, groups, blocked, terrain, m, o, top, out, seen, field);
                }
            }
        }
        // Coast / sneak-only: no push at all (sneak-only still arms the 0.3x tap for the next tick).
        tryStep(n, Ap3DiscretePlanner.NONE, n.s.yaw, false, gates, groups, blocked, terrain, m, o, top, out, seen, field);
        if (fine) {
            tryStep(n, SNEAK_ONLY, n.s.yaw, false, gates, groups, blocked, terrain, m, o, top, out, seen, field);
        }
    }

    /** The gate of the current group the search is steering at: the nearest one not yet ticked off. */
    private static int nextGate(Node n, List<int[]> groups) {
        int[] g = groups.get(Math.min(n.group, groups.size() - 1));
        for (int i = 0; i < g.length; i++) {
            if ((n.mask & (1 << i)) == 0) {
                return g[i];
            }
        }
        return g[0];
    }

    private static void tryStep(Node n, Ap3DiscretePlanner.Action a, float yaw, boolean jump, List<Gate> gates,
                                List<int[]> groups, List<Blocked> blocked, Terrain terrain,
                                Ap3DiscretePlanner.Model m, Options o,
                                double top, List<Node> out, Map<Long, Node> seen, Field field) {
        Ap3RouteMath.RouteState s = n.s.copy();
        double fromX = s.x;
        double fromZ = s.z;
        boolean wasOnGround = s.onGround;
        Ap3RouteMath.step(s, a, yaw, jump, m, terrain.shapes());
        if (hits(blocked, fromX, fromZ, s.x, s.z, s.y)) {
            return;
        }
        if (terrain.deny(s.x, s.y, s.z)) {
            return;
        }
        // A move the world ate entirely is not a move: it is the search sitting still against a wall, and every
        // such node looks new to the beam because its keys differ. Drop them rather than paying ticks for nothing.
        if (wasOnGround && s.onGround && Math.abs(s.x - fromX) < 1.0E-9 && Math.abs(s.z - fromZ) < 1.0E-9
                && !a.none()) {
            return;
        }
        // A Block node's slab is only there for a moment: standing on one for longer is not a route that exists.
        int slab = 0;
        if (s.onGround && terrain.placedFloorOnly(s.x, s.z)) {
            slab = n.slabTicks + 1;
            if (slab > o.slabTicks) {
                return;
            }
        }
        // Every gate of the current group this tick reaches is ticked off - one run between two levers can take both.
        int group = n.group;
        int mask = n.mask;
        int[] members = groups.get(Math.min(group, groups.size() - 1));
        int[] crossed = null;
        for (int i = 0; i < members.length; i++) {
            if ((mask & (1 << i)) != 0) {
                continue;
            }
            if (crosses(gates.get(members[i]), fromX, fromZ, s, o)) {
                mask |= 1 << i;
                crossed = crossed == null ? new int[]{members[i]} : append(crossed, members[i]);
            }
        }
        if (mask == (1 << members.length) - 1) {
            group++;
            mask = 0;
        }
        Node c = new Node();
        c.s = s;
        c.group = group;
        c.mask = mask;
        c.slabTicks = slab;
        c.ticks = n.ticks + 1;
        c.parent = n;
        c.keys = a;
        c.yaw = yaw;
        c.jump = jump;
        c.jumps = n.jumps + (jump ? 1 : 0);
        c.crossed = crossed;
        // NOTE: the jump preference is deliberately NOT priced in here. f is what the beam prunes by, and charging
        // jumps made the beam drop every jumping state in favour of running ones that could not finish: a 5-block
        // gap at speed 550 has to be jumped, and it went from crossing every time to INCOMPLETE every time. The
        // preference belongs where the answer is chosen, not where the search is cut.
        c.f = c.ticks + field.heuristic(s.x, s.z, group, mask, groups, top)
                + o.turnCost * Math.abs(wrap(yaw - n.s.yaw)) / 180.0;
        long key = cell(s, group, mask);
        Node old = seen.get(key);
        if (old != null) {
            if (old.f <= c.f) {
                return;
            }
            old.s = c.s;
            old.group = c.group;
            old.mask = c.mask;
            old.slabTicks = c.slabTicks;
            old.ticks = c.ticks;
            old.jumps = c.jumps;
            old.parent = c.parent;
            old.keys = c.keys;
            old.yaw = c.yaw;
            old.jump = c.jump;
            old.f = c.f;
            old.crossed = c.crossed;
            return;
        }
        seen.put(key, c);
        out.add(c);
    }


    /** Fine control (sneak, sideways braking, a denser ring) near a gate that asks for a position or a speed. */
    private static boolean needsFineControl(Node n, Gate g, double top) {
        if (!g.exact && !g.wantsVelocity()) {
            return false;
        }
        double d = Math.hypot(g.x - n.s.x, g.z - n.s.z);
        return d < Math.max(1.5, top * 4);
    }

    private static double[] directions(Node n, Gate g, Options o, boolean fine) {
        int ring = fine ? o.dirs * 2 : o.dirs;
        double[] out = new double[ring + 3];
        for (int i = 0; i < ring; i++) {
            out[i] = i * (2 * Math.PI / ring);
        }
        out[ring] = mcAngle(g.x - n.s.x, g.z - n.s.z);
        double sp = n.s.speed();
        out[ring + 1] = sp > 1e-9 ? mcAngle(n.s.vx, n.s.vz) : out[ring];
        out[ring + 2] = out[ring + 1] + Math.PI;
        return out;
    }

    /**
     * How far is left, AROUND the blacklist - a straight line would say "through the wall is 10 blocks" and the beam
     * would then throw away every state that walks around it. One coarse grid per gate, flooded out from that gate's
     * box with blacklisted cells removed, gives a real remaining distance; the legs after the next gate are added from
     * the same fields so the whole tail counts. Divided by the top speed it is a time, in the same units as the ticks
     * already spent, and it never overestimates by more than the grid's own diagonal slack.
     */
    private static final class Field {
        static final double CELL = 0.5;
        final double minX, minZ;
        final int w, h;
        /** Distance to each gate's box, per cell; NaN where blocked or unreachable. */
        final float[][] dist;
        /** Distance from gate i-1's centre on to the end, walked through the fields. */
        final double[] tail;
        final List<Gate> gates;

        Field(Ap3RouteMath.RouteState start, List<Gate> gates, List<Blocked> blocked, Terrain terrain, Options o) {
            this.gates = gates;
            double lo_x = start.x, hi_x = start.x, lo_z = start.z, hi_z = start.z;
            for (Gate g : gates) {
                lo_x = Math.min(lo_x, g.x - g.halfW);
                hi_x = Math.max(hi_x, g.x + g.halfW);
                lo_z = Math.min(lo_z, g.z - g.halfL);
                hi_z = Math.max(hi_z, g.z + g.halfL);
            }
            for (Blocked b : blocked) {
                lo_x = Math.min(lo_x, b.minX);
                hi_x = Math.max(hi_x, b.maxX);
                lo_z = Math.min(lo_z, b.minZ);
                hi_z = Math.max(hi_z, b.maxZ);
            }
            double pad = 12.0; // room to walk around the outside of everything
            minX = lo_x - pad;
            minZ = lo_z - pad;
            w = (int) Math.ceil((hi_x + pad - minX) / CELL) + 1;
            h = (int) Math.ceil((hi_z + pad - minZ) / CELL) + 1;
            boolean[] wall = new boolean[w * h];
            float[] floor = new float[w * h];
            for (int i = 0; i < w; i++) {
                for (int j = 0; j < h; j++) {
                    double cx = minX + i * CELL;
                    double cz = minZ + j * CELL;
                    double f = terrain.floorAt(cx, cz);
                    floor[i * h + j] = (float) f;
                    if (Double.isNaN(f)) {
                        wall[i * h + j] = true;
                        continue;
                    }
                    for (Blocked b : blocked) {
                        if (cx >= b.minX - CELL && cx <= b.maxX + CELL && cz >= b.minZ - CELL && cz <= b.maxZ + CELL) {
                            wall[i * h + j] = true;
                            break;
                        }
                    }
                }
            }
            double climb = o.allowJump ? JUMP_CLIMB : Ap3RouteCollide.MAX_UP_STEP;
            dist = new float[gates.size()][];
            for (int g = 0; g < gates.size(); g++) {
                dist[g] = flood(wall, floor, climb, gates.get(g));
            }
            tail = new double[gates.size() + 1];
            tail[gates.size()] = 0;
            for (int g = gates.size() - 1; g >= 1; g--) {
                Gate prev = gates.get(g - 1);
                tail[g] = tail[g + 1] + at(g, prev.x, prev.z);
            }
        }

        /** Dijkstra out from a gate's box over the open cells (8-connected, true diagonal cost). */
        private float[] flood(boolean[] wall, float[] floor, double climb, Gate g) {
            float[] d = new float[w * h];
            java.util.Arrays.fill(d, Float.POSITIVE_INFINITY);
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            for (int i = 0; i < w; i++) {
                for (int j = 0; j < h; j++) {
                    double cx = minX + i * CELL;
                    double cz = minZ + j * CELL;
                    if (wall[i * h + j]) {
                        continue;
                    }
                    if (Math.abs(cx - g.x) <= g.halfW + CELL && Math.abs(cz - g.z) <= g.halfL + CELL) {
                        d[i * h + j] = 0f;
                        queue.add(i * h + j);
                    }
                }
            }
            // Bellman-Ford style sweep on a small grid: cheap and simple, the grid is only thousands of cells.
            while (!queue.isEmpty()) {
                int cur = queue.poll();
                int ci = cur / h;
                int cj = cur % h;
                float base = d[cur];
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        if (di == 0 && dj == 0) {
                            continue;
                        }
                        int ni = ci + di;
                        int nj = cj + dj;
                        if (ni < 0 || nj < 0 || ni >= w || nj >= h || wall[ni * h + nj]) {
                            continue;
                        }
                        // The field measures the way TO the gate, so the player travels neighbour -> cur. He may
                        // drop any distance, but he may only rise what he can walk or jump up: without this the
                        // field is flat, and the cheapest way to close the distance to a gate at the top of a
                        // staircase is to jump OFF the staircase and sprint along the floor below it - which is
                        // exactly what killer560's route did on 2026-09-22, a near-180 and a jump back down.
                        if (floor[cur] - floor[ni * h + nj] > climb) {
                            continue;
                        }
                        float step = (float) ((di != 0 && dj != 0 ? 1.41421356 : 1.0) * CELL);
                        if (base + step < d[ni * h + nj] - 1e-4f) {
                            d[ni * h + nj] = base + step;
                            queue.add(ni * h + nj);
                        }
                    }
                }
            }
            return d;
        }

        double at(int gate, double x, double z) {
            int i = (int) Math.round((x - minX) / CELL);
            int j = (int) Math.round((z - minZ) / CELL);
            if (i < 0 || j < 0 || i >= w || j >= h) {
                Gate g = gates.get(gate);
                return Math.hypot(g.x - x, g.z - z);
            }
            float d = dist[gate][i * h + j];
            if (Float.isInfinite(d)) {
                Gate g = gates.get(gate);
                return Math.hypot(g.x - x, g.z - z); // walled in on the grid: fall back, the step check still rules
            }
            return d;
        }

        /**
         * Time left: the nearest gate of the current group that is still open, plus the legs after it. Within a group
         * only the nearest one counts, which never overestimates (you have to reach at least that one).
         */
        double heuristic(double x, double z, int group, int mask, List<int[]> groups, double top) {
            if (group >= groups.size()) {
                return 0;
            }
            int[] members = groups.get(group);
            // Every open gate of this step still has to be reached, so the estimate walks them nearest-first rather
            // than counting only the closest one - counting one would make "not taken either lever yet" look cheaper
            // than "took one", and the beam would then drop every state that had actually done some of the work.
            double total = 0;
            double cx = x, cz = z;
            int open = 0;
            for (int i = 0; i < members.length; i++) {
                if ((mask & (1 << i)) == 0) {
                    open |= 1 << i;
                }
            }
            int last = members[members.length - 1];
            while (open != 0) {
                int bestI = -1;
                double bestD = Double.MAX_VALUE;
                for (int i = 0; i < members.length; i++) {
                    if ((open & (1 << i)) == 0) {
                        continue;
                    }
                    double d = at(members[i], cx, cz);
                    if (d < bestD) {
                        bestD = d;
                        bestI = i;
                    }
                }
                total += bestD;
                open &= ~(1 << bestI);
                last = members[bestI];
                cx = gates.get(last).x;
                cz = gates.get(last).z;
            }
            return (total + tail[last + 1]) / top;
        }
    }

    /** Did this tick's movement pass through the gate (and meet what it asks for)? */
    private static boolean crosses(Gate g, double fromX, double fromZ, Ap3RouteMath.RouteState to, Options o) {
        boolean in;
        if (g.exact) {
            in = Math.hypot(to.x - g.x, to.z - g.z) <= o.exactSearch;
        } else {
            // The whole tick's travel counts: at speed a 1x1 box can be stepped clean over in one tick.
            in = segmentHitsBox(fromX, fromZ, to.x, to.z, g.x - g.halfW, g.z - g.halfL, g.x + g.halfW, g.z + g.halfL);
        }
        if (!in) {
            return false;
        }
        if (Math.abs(to.y - g.y) > GATE_Y_TOLERANCE) {
            return false; // the right spot on the wrong floor is not the gate
        }
        if (g.exact && !g.sameBlocks(to.x, to.z)) {
            return false; // right distance, wrong side of a block edge
        }
        if (g.mustLand && !to.onGround) {
            // Also rules out a jump on the crossing tick itself: pressing jump leaves the move airborne.
            return false;
        }
        if (g.exact && !to.onGround) {
            // An exact gate is a place to STAND (killer560: "still either on or off of a block"), so passing over it
            // in mid-air does not count. It also has to be true for the polish to have anything to work with: in the
            // air a yaw is worth 0.02 a tick, so a crossing 0.15 out cannot be pulled onto the point.
            return false;
        }
        return meetsVelocity(g, to);
    }

    static boolean meetsVelocity(Gate g, Ap3RouteMath.RouteState s) {
        if (!g.wantsVelocity()) {
            return true;
        }
        double sp = s.speed();
        if (g.minSpeed >= 0 && sp < g.minSpeed) {
            return false;
        }
        if (g.maxSpeed >= 0 && sp > g.maxSpeed) {
            return false;
        }
        if (g.hasDir) {
            if (sp < 1e-6) {
                return false;
            }
            double have = Math.toDegrees(mcAngle(s.vx, s.vz));
            double diff = Math.abs(wrap(have - g.dirDeg));
            return diff <= g.dirTolDeg;
        }
        return true;
    }

    private static boolean hits(List<Blocked> blocked, double x0, double z0, double x1, double z1, double y) {
        for (Blocked b : blocked) {
            if (y < b.minY || y > b.maxY) {
                continue; // jumped over it (or below it)
            }
            if (segmentHitsBox(x0, z0, x1, z1, b.minX, b.minZ, b.maxX, b.maxZ)) {
                return true;
            }
        }
        return false;
    }

    /** Segment (x0,z0)-(x1,z1) against an axis-aligned box (slab test). */
    static boolean segmentHitsBox(double x0, double z0, double x1, double z1,
                                  double minX, double minZ, double maxX, double maxZ) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        double t0 = 0.0, t1 = 1.0;
        for (int axis = 0; axis < 2; axis++) {
            double p = axis == 0 ? dx : dz;
            double s = axis == 0 ? x0 : z0;
            double lo = axis == 0 ? minX : minZ;
            double hi = axis == 0 ? maxX : maxZ;
            if (Math.abs(p) < 1e-12) {
                if (s < lo || s > hi) {
                    return false;
                }
                continue;
            }
            double ta = (lo - s) / p;
            double tb = (hi - s) / p;
            if (ta > tb) {
                double tmp = ta;
                ta = tb;
                tb = tmp;
            }
            t0 = Math.max(t0, ta);
            t1 = Math.min(t1, tb);
            if (t0 > t1) {
                return false;
            }
        }
        return true;
    }

    private static int[] append(int[] a, int v) {
        int[] out = Arrays.copyOf(a, a.length + 1);
        out[a.length] = v;
        return out;
    }

    /** Lattice cell for merging: position, velocity, gate progress and whether the feet are down. */
    private static long cell(Ap3RouteMath.RouteState s, int group, int mask) {
        long x = Math.round(s.x / 0.08);
        long z = Math.round(s.z / 0.08);
        long vx = Math.round(s.vx / 0.02);
        long vz = Math.round(s.vz / 0.02);
        long air = s.onGround ? 0 : 1 + Math.min(15, s.airTicks);
        long h = x * 73856093L ^ z * 19349663L ^ vx * 83492791L ^ vz * 2654435761L;
        return h * 2048L + ((group * 64L + mask * 4L + air) % 2048L);
    }

    // ---- the answer --------------------------------------------------------------------------------------------

    private static Plan finish(Node end, List<Gate> gates, List<Blocked> blocked, Ap3DiscretePlanner.Model m,
                               Options o, Terrain terrain, Plan plan) {
        List<Node> chain = new ArrayList<>();
        for (Node n = end; n != null && n.parent != null; n = n.parent) {
            chain.add(n);
        }
        java.util.Collections.reverse(chain);
        Step[] steps = new Step[chain.size()];
        int[] gateTick = new int[gates.size()];
        Arrays.fill(gateTick, -1);
        int gi = 0;
        for (int i = 0; i < chain.size(); i++) {
            Node n = chain.get(i);
            steps[i] = new Step(n.keys, n.yaw, n.jump);
            if (n.crossed != null) {
                for (int g : n.crossed) {
                    if (g < gateTick.length) {
                        gateTick[g] = i; // a tick can tick off several gates of the same group at once
                        gi++;
                    }
                }
            }
        }
        plan.steps = steps;
        plan.ticks = steps.length;
        plan.gateTick = gateTick;
        plan.gatesReached = Math.max(plan.gatesReached, gi);
        plan.complete = gi >= gates.size();
        if (plan.complete) {
            refineExact(plan, gates, m, o, terrain, chain.isEmpty() ? null : chain.get(0).parent);
        }
        return plan;
    }

    /**
     * An exact gate was searched with a radius; here the yaws of the last few ticks before each crossing are re-solved
     * on the exact step until the feet land ON the point. Only yaws move, so the keys stay the ones the search chose.
     */
    private static void refineExact(Plan plan, List<Gate> gates, Ap3DiscretePlanner.Model m, Options o,
                                    Terrain terrain, Node rootNode) {
        if (rootNode == null) {
            return;
        }
        for (int g = 0; g < gates.size(); g++) {
            Gate gate = gates.get(g);
            if (!gate.exact || plan.gateTick[g] < 0) {
                continue;
            }
            int at = plan.gateTick[g];
            // Yaw-only polishing can only redirect what speed there already is, so three ticks of it reaches about
            // three ticks' worth of travel. When that is not enough - the search may hand over anything up to
            // Options.exactSearch away - widen the window and try again rather than giving up on the gate.
            boolean done = false;
            for (int window : POLISH_WINDOWS) {
                int from = Math.max(0, at - window);
                if (polish(plan, rootNode.s, gate, m, terrain, from, at, o)) {
                    done = true;
                    break;
                }
                if (from == 0) {
                    break;
                }
            }
            if (!done) {
                plan.note = plan.note.isEmpty() ? "exact gate " + (g + 1) + " not polished" : plan.note;
            }
        }
    }

    /** Levenberg-Marquardt on the yaws of ticks [from, at] so the feet land on the exact gate at tick {@code at}. */
    private static boolean polish(Plan plan, Ap3RouteMath.RouteState start, Gate gate, Ap3DiscretePlanner.Model m,
                                  Terrain terrain, int from, int at, Options o) {
        return polish(plan, start, gate, m, terrain, from, at, o, gate.x, gate.z, true);
    }

    /**
     * {@code tx/tz} is what the yaws are solved against, which is the gate's own point the first time round. An
     * exact gate normally sits ON a block edge - that is the whole point of it - so landing 0.0002 short of the
     * point puts the hitbox in the wrong block and {@link Gate#sameBlocks} throws the answer away. When that
     * happens the target is nudged a hair further from where it landed and solved once more, which costs one extra
     * solve and lands on the side killer560 asked for.
     */
    private static boolean polish(Plan plan, Ap3RouteMath.RouteState start, Gate gate, Ap3DiscretePlanner.Model m,
                                  Terrain terrain, int from, int at, Options o,
                                  double tx, double tz, boolean mayNudge) {
        int k = at - from + 1;
        double[] y = new double[k];
        for (int i = 0; i < k; i++) {
            y[i] = plan.steps[from + i].yaw();
        }
        double[] r = new double[2];
        double[] r2 = new double[2];
        double cost = residual(plan, start, tx, tz, m, terrain, from, at, y, r);
        double lambda = 1e-3;
        for (int it = 0; it < 20 && cost > gate.exactTol * gate.exactTol * 0.25; it++) {
            double[][] jac = new double[2][k];
            for (int j = 0; j < k; j++) {
                double h = 0.02;
                y[j] += h;
                residual(plan, start, tx, tz, m, terrain, from, at, y, r2);
                y[j] -= h;
                jac[0][j] = (r2[0] - r[0]) / h;
                jac[1][j] = (r2[1] - r[1]) / h;
            }
            double[][] a = new double[k][k];
            double[] grad = new double[k];
            for (int i = 0; i < k; i++) {
                for (int j = 0; j < k; j++) {
                    a[i][j] = jac[0][i] * jac[0][j] + jac[1][i] * jac[1][j];
                }
                grad[i] = -(jac[0][i] * r[0] + jac[1][i] * r[1]);
            }
            boolean improved = false;
            for (int tries = 0; tries < 6 && !improved; tries++) {
                double[][] aa = new double[k][k];
                for (int i = 0; i < k; i++) {
                    System.arraycopy(a[i], 0, aa[i], 0, k);
                    aa[i][i] += lambda * (a[i][i] + 1e-12);
                }
                double[] dy = solveLinear(aa, grad.clone());
                if (dy == null) {
                    lambda *= 10;
                    continue;
                }
                double[] ny = new double[k];
                for (int i = 0; i < k; i++) {
                    ny[i] = y[i] + Math.max(-30.0, Math.min(30.0, dy[i]));
                }
                double nc = residual(plan, start, tx, tz, m, terrain, from, at, ny, r2);
                if (nc < cost) {
                    y = ny;
                    System.arraycopy(r2, 0, r, 0, 2);
                    cost = nc;
                    lambda = Math.max(1e-9, lambda / 5);
                    improved = true;
                } else {
                    lambda *= 8;
                }
            }
            if (!improved) {
                break;
            }
        }
        if (Math.sqrt(cost) > gate.exactTol) {
            return false;
        }
        if (Math.hypot(tx - gate.x, tz - gate.z) + Math.sqrt(cost) > gate.exactTol) {
            return false; // the nudge plus the miss would take it outside what the gate asked for
        }
        Ap3RouteMath.RouteState landed = start.copy();
        for (int i = 0; i <= at; i++) {
            Step st = plan.steps[i];
            float yaw = i >= from ? (float) y[i - from] : st.yaw();
            Ap3RouteMath.step(landed, st.keys(), yaw, st.jump(), m, terrain.shapes());
        }
        if (!gate.sameBlocks(landed.x, landed.z)) {
            // Close, but it would stand on the other side of the edge. Aim a hair past the point, away from where
            // it landed, on whichever axis is on the wrong side.
            if (!mayNudge) {
                return false;
            }
            double nx = tx;
            double nz = tz;
            if ((int) Math.floor(landed.x - HALF_WIDTH) != gate.cellMinX
                    || (int) Math.floor(landed.x + HALF_WIDTH) != gate.cellMaxX) {
                nx += Math.copySign(EDGE_NUDGE, gate.x - landed.x);
            }
            if ((int) Math.floor(landed.z - HALF_WIDTH) != gate.cellMinZ
                    || (int) Math.floor(landed.z + HALF_WIDTH) != gate.cellMaxZ) {
                nz += Math.copySign(EDGE_NUDGE, gate.z - landed.z);
            }
            if (nx == tx && nz == tz) {
                return false;
            }
            return polish(plan, start, gate, m, terrain, from, at, o, nx, nz, false);
        }
        for (int i = 0; i < k; i++) {
            Step s = plan.steps[from + i];
            plan.steps[from + i] = new Step(s.keys(), (float) y[i], s.jump());
        }
        return true;
    }

    private static double residual(Plan plan, Ap3RouteMath.RouteState start, double tx, double tz,
                                   Ap3DiscretePlanner.Model m,
                                   Terrain terrain, int from, int at, double[] y, double[] r) {
        Ap3RouteMath.RouteState s = start.copy();
        for (int i = 0; i <= at; i++) {
            Step st = plan.steps[i];
            float yaw = i >= from ? (float) y[i - from] : st.yaw();
            Ap3RouteMath.step(s, st.keys(), yaw, st.jump(), m, terrain.shapes());
        }
        r[0] = s.x - tx;
        r[1] = s.z - tz;
        return r[0] * r[0] + r[1] * r[1];
    }

    // ---- shared little things ---------------------------------------------------------------------------------

    private static final Ap3DiscretePlanner.Action SNEAK_ONLY = new Ap3DiscretePlanner.Action(0, 0, true);
    /** Shapes worth trying when the only thing that matters is getting there: both keep sprint (fw > 0). */
    private static final Ap3DiscretePlanner.Action[] FAST_SHAPES = {
            new Ap3DiscretePlanner.Action(1, 0, false),
            new Ap3DiscretePlanner.Action(1, 1, false),
    };
    /** Near a gate that asks for a position or a speed: sideways (drops sprint) and the 0.3x sneaking taps as well. */
    private static final Ap3DiscretePlanner.Action[] FINE_SHAPES = {
            new Ap3DiscretePlanner.Action(1, 0, false),
            new Ap3DiscretePlanner.Action(1, 1, false),
            new Ap3DiscretePlanner.Action(1, 0, true),
            new Ap3DiscretePlanner.Action(1, 1, true),
            new Ap3DiscretePlanner.Action(0, 1, false),
            new Ap3DiscretePlanner.Action(0, 1, true),
    };

    /** Where an action pushes relative to the facing, in degrees (W = 0, W+A = -45, A = -90). */
    private static double keyOffset(Ap3DiscretePlanner.Action a) {
        return a.none() ? 0.0 : Math.toDegrees(Math.atan2(-a.st(), a.fw()));
    }

    /** MC yaw (radians) of a world direction: dir(t) = (-sin t, cos t). */
    private static double mcAngle(double x, double z) {
        return Math.atan2(-x, z);
    }

    private static double wrap(double deg) {
        double w = deg % 360.0;
        if (w >= 180.0) {
            w -= 360.0;
        } else if (w < -180.0) {
            w += 360.0;
        }
        return w;
    }

    private static double[] solveLinear(double[][] a, double[] b) {
        int n = b.length;
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) {
                    piv = r;
                }
            }
            if (Math.abs(a[piv][col]) < 1e-15) {
                return null;
            }
            double[] tmp = a[col];
            a[col] = a[piv];
            a[piv] = tmp;
            double tb = b[col];
            b[col] = b[piv];
            b[piv] = tb;
            for (int r = col + 1; r < n; r++) {
                double f = a[r][col] / a[col][col];
                for (int c = col; c < n; c++) {
                    a[r][c] -= f * a[col][c];
                }
                b[r] -= f * b[col];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double sum = b[r];
            for (int c = r + 1; c < n; c++) {
                sum -= a[r][c] * x[c];
            }
            x[r] = sum / a[r][r];
        }
        return x;
    }
}
