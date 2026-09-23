package com.killer560.hub.ap3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
     * What a block of unexplained height under an overhang costs the heuristic, in blocks of distance. Large on
     * purpose: it has to outweigh any way round the grid could offer, or standing under a platform still looks
     * like the cheapest place to be.
     */
    private static final double UNDER_PENALTY = 64.0;

    /** The eight directions a jump may reach across a hole in the heuristic's grid. */
    private static final int[] STEP_I = {1, 1, 1, 0, 0, -1, -1, -1};
    private static final int[] STEP_J = {1, 0, -1, 1, -1, 1, 0, -1};
    /**
     * How far a jump may reach across a hole, in half-block cells. Eight cells is four blocks, which is about what a
     * sprint jump covers at killer560's speed; reaching further would cost the flood more than the guidance is worth.
     */
    private static final int JUMP_CELLS = 8;

    /**
     * How much height a sprint jump gains, for deciding whether the heuristic's grid is connected upwards. A jump
     * peaks at 1.252 blocks, and you have to land ON the ledge rather than brush it, so this is a little under.
     */
    static final double JUMP_CLIMB = 1.2;

    /**
     * What one BUMP - a collision that gained no height - costs the search, in ticks.
     * <p>
     * Climbing collisions are charged nothing at all, and that is not a rounding decision: walking into a step and
     * rising on to it is how you get up, and every non-zero price tried made the way up unfindable. Measured
     * 2026-09-23 on LedgeHarness's two inner-edge cases, which go from 20 and 28 ticks to INCOMPLETE and two
     * blocks low at a charge of 1.5, at 0.25, at 0.10 and even at 0.05. Those climbs are marginal enough that the
     * beam only just holds them, so nothing may lean on them.
     */
    private static final double CLIP_COST = 1.5;

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
        /**
         * How far past the route's own bounding box the world is read and the heuristic grid is built.
         * <p>
         * killer560 (2026-09-22): "it runs and jumps straight at it instead of taking a detour to go up a few
         * blocks... you also need to increase how much it scans up to a point". At 12 blocks the stairs up to a
         * platform 14 blocks off to one side were simply outside the grid, so the field had no way up to offer and
         * the search did the only thing left - walk under the node. With the pad past them the same route plans in
         * 190 ms instead of failing in 1350, because a heuristic that knows the way is worth far more than the
         * cells it costs.
         */
        double scanPad = 32.0;

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

        /**
         * May the route WALK - hold forward without sprint? killer560 (2026-09-22): "make it so it knows it doesnt
         * have to sprint. It can walk if it deems that gives it a faster overall time by having better block
         * placement." Without it the only ways to slow down are coasting and sneaking, so a route that has to
         * arrive gently at a one-block ledge cannot, and runs into the side of it instead.
         */
        boolean allowWalking = true;

        /**
         * Run several differently-shaped searches and keep the shortest answer. Only worth it for a route's FIRST
         * plan, which is the one that gets remembered; a re-plan mid-run has no time for it.
         */
        boolean portfolio;

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
            c.scanPad = scanPad;
            c.preferRunning = preferRunning;
            c.allowWalking = allowWalking;
            c.portfolio = portfolio;
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
    /**
     * One tick of the answer: which keys, which way to face, whether to jump, and whether to hold sprint. Sprint is
     * part of the plan because walking can be worth more than the speed it costs - it is what lets a route arrive
     * slowly enough to land ON a one-block ledge rather than run into the side of it.
     */
    record Step(Ap3DiscretePlanner.Action keys, float yaw, boolean jump, boolean sprint) {
        Step(Ap3DiscretePlanner.Action keys, float yaw, boolean jump) {
            this(keys, yaw, jump, true);
        }
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
        /**
         * Why an incomplete plan is incomplete, in the terms that tell the two cases apart: a gate the heuristic
         * cannot reach at all is a world problem (nothing connects the start to it), whereas a gate it CAN reach
         * with the search still running out of layers is a budget problem. Guessing at which from the outside cost
         * two rounds of testing on 2026-09-22.
         */
        String diagnosis = "";
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
        /** Whether this tick held sprint - false is a deliberate walk, see Step. */
        boolean sprint = true;
        /** How many jumps this whole path has used - the tie-break that keeps a route on its feet. */
        int jumps;
        /**
         * How many ticks of this path ran into something hard enough to lose the sprint. killer560 (2026-09-22):
         * "if it does have to jump up a block, it shouldnt walk to the edge most of the time. It should try to jump
         * early so it doesnt clip the block so it doesnt lose all of its momentum."
         * <p>
         * Jumping late and catching the side of the ledge costs the sprint and most of the speed. Usually that also
         * costs ticks and the search avoids it on its own - but not always, and when the tick count ties it had no
         * reason to prefer the clean jump. Now it does, and it keeps the speed for whatever comes after the plan
         * ends, which the tick count inside the plan never sees.
         */
        int clips;
        /** Collisions that gained no height - see where this is counted. Bumps are charged; climbing is not. */
        int bumps;
        /** Scratch for {@link #cutLayer}. */
        boolean picked;
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
        // The field is the same for every attempt below - same world, same gates, same start - and on killer560's
        // course it is a 145x185 grid with a flood per gate. Building it inside search() meant building it three
        // times over for one plan, which is time the search never got to spend on searching.
        Field field = new Field(start, gates, blocked, terrain, o);
        if (o.portfolio) {
            return portfolio(start, gates, blocked, terrain, m, o, field);
        }
        if (!o.allowJump || !o.preferRunning) {
            return search(start, gates, blocked, terrain, m, o, field);
        }
        // Running first (see Options.preferRunning). Half the budget is plenty: with jumps off the search either
        // finds the way across quickly or runs out of places to stand and dies on its own.
        long began = System.nanoTime();
        // Two stages, because the probe is paid for on EVERY plan and most of the time the ordinary beam already
        // finds the way across. Only when it does not is the wide beam worth its cost - with jumps off the
        // branching is halved, and the answer can hang on one approach phase surviving the cut ("it may have to
        // adjust earlier portions of its movement to get the propper block alignment"). Measured on a 3-block gap
        // at 550: nine of eleven approaches are answered at beam 400, and the remaining two need about 1500.
        // Both stages are capped: when a probe FAILS its whole share is spent before the real search starts, and a
        // mid-route re-plan only has 300 ms to begin with.
        Plan running = null;
        int[] beams = {o.beam, Math.min(4000, o.beam * 3)};
        for (int stage = 0; stage < beams.length; stage++) {
            Options onFoot = o.copy();
            onFoot.allowJump = false;
            onFoot.beam = beams[stage];
            onFoot.budgetMs = Math.max(1, Math.min(o.budgetMs / 4, 250));
            Plan probe = search(start, gates, blocked, terrain, m, onFoot, field);
            if (probe.complete) {
                return probe;
            }
            if (running == null || probe.gatesReached > running.gatesReached) {
                running = probe;
            }
            if ((System.nanoTime() - began) / 1_000_000L >= o.budgetMs / 2) {
                break; // half the budget is the most the probing may ever take
            }
        }
        long spentMs = (System.nanoTime() - began) / 1_000_000L;
        Options withJumps = o.copy();
        withJumps.budgetMs = Math.max(1, o.budgetMs - spentMs);
        Plan jumping = search(start, gates, blocked, terrain, m, withJumps, field);
        // If neither finishes, hand back whichever got further rather than the later one by default.
        return jumping.complete || jumping.gatesReached >= running.gatesReached ? jumping : running;
    }

    /**
     * Several searches, best answer wins. killer560 (2026-09-22): "I really just want it to be 100% be most
     * optimal... I dont care if it takes me a little bit longer to have to wait for the first run."
     * <p>
     * A beam search is not exhaustive, so turning its dials up does NOT monotonically improve it - measured across
     * twenty scenarios, a beam of 2400 lost a tick on cases a beam of 1200 got right, and one case at beam 1200
     * beat a beam of 6000 by two ticks. There is no single setting that is best everywhere, and no amount of budget
     * spent on one setting finds what that setting's pruning throws away. Running a handful of genuinely different
     * searches and keeping the shortest answer is therefore strictly better than any one of them, which is the only
     * honest way to get closer to optimal here. It costs what it costs - and it is paid once, because
     * Ap3RouteCache keeps the result.
     * <p>
     * The no-jump search leads so that a route which can be run is found even when a jumping one would be found
     * sooner; ties then go to whichever uses fewer jumps, which is the preference he asked for, while a strictly
     * quicker route still wins on its merits.
     */
    private static Plan portfolio(Ap3RouteMath.RouteState start, List<Gate> gates, List<Blocked> blocked,
                                  Terrain terrain, Ap3DiscretePlanner.Model m, Options o, Field field) {
        int[][] configs = {
                {o.beam * 2, 16, 0},   // on foot, wide: the running answer, given room to be found
                // Narrow, and therefore DEEP. A beam search spends its budget per layer, so width is bought with
                // depth, and a route whose payoff is twenty ticks away needs depth above all. Measured on his
                // two-jump section, 2026-09-23, same world and same terrain: beam 250 solved it in 197 ms while
                // beam 600 and 1200 both ran 8 seconds and never got past the ledge. Every member here used to be
                // o.beam or wider, so the one shape that solves this kind of route was the one shape never tried.
                {Math.max(200, Math.min(300, o.beam / 5)), 16, 1},
                {o.beam, 16, 1},
                {o.beam * 2, 24, 1},
                {o.beam * 3, 32, 1},
        };
        long deadline = System.nanoTime() + o.budgetMs * 1_000_000L;
        // How much a LONG route has to give up in width to reach the depth it needs.
        //
        // A beam search spends its budget per layer, and a route cannot be found before the search has simulated
        // as many ticks as the route takes. Measured on killer560's storm section, 2026-09-23, from his own log:
        // beam 1200 reached 77 layers in 9519 ms - about 123 ms a layer - on a route that turned out to need 97.
        // It was not close to being found, and it never would have been at any budget he was willing to wait for.
        // A layer costs time roughly in proportion to the beam, so the beam that reaches maxTicks is about
        // beam * (layers reached / layers needed); this errs on the wide side of that and leaves a floor, because
        // a beam too narrow to hold the alternatives is its own failure.
        double depthScale = Math.max(0.2, Math.min(1.0, 160.0 / Math.max(1, o.maxTicks)));
        Plan best = null;
        int bestJumps = Integer.MAX_VALUE;
        int bestClips = Integer.MAX_VALUE;
        Plan noJump = null;
        int noJumpClips = Integer.MAX_VALUE;
        Plan fallback = null;
        int membersLeft = configs.length;
        for (int[] cfg : configs) {
            if (System.nanoTime() > deadline && best != null) {
                break;
            }
            Options c = o.copy();
            c.portfolio = false;
            c.preferRunning = false;
            c.beam = Math.max(100, Math.min(6000, (int) Math.round(cfg[0] * depthScale)));
            c.dirs = cfg[1];
            c.allowJump = o.allowJump && cfg[2] == 1;
            // What is LEFT, shared among what is still to come - not a fixed fifth each. With a fixed share a
            // member that dies in a tenth of its slice hands the leftovers to nobody, and the narrow deep member,
            // which is the one that solves a long route, was being given a fifth of the budget to do it in.
            c.budgetMs = Math.max(1, ((deadline - System.nanoTime()) / 1_000_000L) / Math.max(1, membersLeft--));
            Plan p = search(start, gates, blocked, terrain, m, c, field);
            if (!p.complete) {
                if (fallback == null || p.gatesReached > fallback.gatesReached) {
                    fallback = p;
                }
                continue;
            }
            int jumps = 0;
            int clips = 0;
            Ap3RouteMath.RouteState sim = start.copy();
            for (Step st : p.steps) {
                if (st.jump()) {
                    jumps++;
                }
                Ap3RouteMath.step(sim, st.keys(), st.yaw(), st.jump(), st.sprint(), m, terrain.shapes());
                if (sim.sprintBlocked) {
                    clips++;
                }
            }
            if (jumps == 0 && (noJump == null || p.ticks < noJump.ticks
                    || (p.ticks == noJump.ticks && clips < noJumpClips))) {
                noJump = p;
                noJumpClips = clips;
            }
            if (best == null || p.ticks < best.ticks
                    || (p.ticks == best.ticks && jumps < bestJumps)
                    || (p.ticks == best.ticks && jumps == bestJumps && clips < bestClips)) {
                best = p;
                bestJumps = jumps;
                bestClips = clips;
            }
        }
        // A route that never leaves the ground wins outright, even when a jumping one is a tick or two quicker.
        // killer560 has asked for this more than once and in the strongest terms he has used about any of it:
        // "if it is fast enough such that it can cross something like a 3 block gap without jumping then it
        // always should prioritize that over jumping... 99.999999% of the time just running is faster over gaps it
        // can cross". Comparing ticks first is what let a jumping plan one tick shorter keep winning, and that
        // margin is inside the noise of a re-plan anyway. Jumping is for gaps that genuinely cannot be run - if no
        // jumpless plan was found, `best` still carries one that does.
        if (noJump != null) {
            return noJump;
        }
        if (best != null) {
            return best;
        }
        // Nothing could plan it in one go, so stop trying to.
        //
        // Measured on killer560's own world, 2026-09-22, from the dump of the route that keeps failing: the gap
        // ALONE plans in 13 ticks, and the hop onto the node two blocks up plans in 12 - but only from a standing
        // start on the ledge between them. Asked for both at once the search never finishes, and handing it a
        // waypoint half way does not help either (tried three, all "reached 1/2 gates, 1.2 blocks out"), because
        // crossing a waypoint at speed is not the state the second half needs. The beam keeps whatever is quickest
        // so far, and arriving at that ledge slowly is never quickest so far - it only pays off twenty ticks later,
        // long after those states have been cut.
        //
        // So give each node its own little problem, and come to a stop on every one. That is the shape the search
        // solves reliably, and a route that pauses on a node beats a route that does not exist.
        // Its own allowance rather than the portfolio's leftovers - by now there is none left, and this is the
        // attempt most likely to work. Bounded all the same: without a deadline the recursion below can run for
        // half a minute on a long route, and a route that is still thinking is a route that is not running.
        long fallbackBy = System.nanoTime() + Math.max(o.budgetMs, 4000L) * 1_000_000L;
        Plan oneAtATime = nodeByNode(start, gates, blocked, terrain, m, o, fallbackBy);
        if (oneAtATime != null) {
            return oneAtATime;
        }
        // Nothing finished. Breadth was the wrong answer for this one, so spend everything that is left going DEEP
        // on a single search instead - measured on a pad course that no member of the portfolio could solve in its
        // quarter of the budget but one of them solved comfortably given the lot.
        long left = (deadline - System.nanoTime()) / 1_000_000L;
        if (left > 50) {
            Options deep = o.copy();
            deep.portfolio = false;
            deep.preferRunning = false;
            deep.beam = Math.min(6000, o.beam * 3);
            deep.dirs = 24;
            deep.budgetMs = left;
            Plan p = search(start, gates, blocked, terrain, m, deep, field);
            if (p.complete || fallback == null || p.gatesReached > fallback.gatesReached) {
                return p;
            }
        }
        return fallback != null ? fallback : new Plan();
    }

    /** How many times the last resort may cut a leg in half before it admits defeat. */
    private static final int MAX_SPLITS = 2;

    /**
     * The last resort: stop trying to plan the route in one piece, and cut it up until each piece is something the
     * search can actually hold in its head.
     * <p>
     * Each node becomes its own leg, planned from the state the previous leg ended in, and every leg has to be
     * LANDED ON - which is also the point. A leg that still will not plan gets cut in half again at a point the
     * heuristic picks off its own distance field, and that halfway point is landed on too.
     * <p>
     * Measured on killer560's world, 2026-09-22, from the dump of the route that kept failing - one node, ten
     * blocks away, over a 3-block gap onto a ledge and then up two: the gap alone plans in 13 ticks and the hop
     * alone in 12, but only from a STANDING START on the ledge, and asked for both at once the search never
     * finishes. Handing it a waypoint it could cross at speed did not help either (three tried, all "reached 1/2
     * gates, 1.2 blocks out"). Arriving at that ledge slowly only pays off twenty ticks later, long after the beam,
     * which keeps whatever is quickest so far, has cut those states.
     * <p>
     * The other half of it is WHICH ledge. Those columns carry a surface at 119 and another at 120; from 120 the
     * hop plans, from 119 the node is not reachable at all. The field only remembers one surface per column, so
     * each cut point is tried at the height the field believes and at the one above it.
     */
    private static Plan nodeByNode(Ap3RouteMath.RouteState start, List<Gate> gates, List<Blocked> blocked,
                                   Terrain terrain, Ap3DiscretePlanner.Model m, Options o, long deadline) {
        List<int[]> groups = groupsOf(gates);
        if (groups.isEmpty()) {
            return null;
        }
        Ap3RouteMath.RouteState at = start.copy();
        List<Step> all = new ArrayList<>();
        int[] gateTick = new int[gates.size()];
        for (int[] group : groups) {
            List<Gate> leg = new ArrayList<>(group.length);
            for (int idx : group) {
                Gate c = copyGate(gates.get(idx));
                c.group = 0;
                c.mustLand = true;
                leg.add(c);
            }
            List<Step> got = planLeg(at, leg, blocked, terrain, m, o, 0, deadline);
            if (got == null) {
                return null;
            }
            for (Step st : got) {
                Ap3RouteMath.step(at, st.keys(), st.yaw(), st.jump(), st.sprint(), m, terrain.shapes());
                all.add(st);
            }
            for (int idx : group) {
                gateTick[idx] = all.size() - 1;
            }
        }
        Plan out = new Plan();
        out.steps = all.toArray(new Step[0]);
        out.ticks = out.steps.length;
        out.complete = true;
        out.gatesReached = gates.size();
        out.gateTick = gateTick;
        out.note = "planned in pieces";
        return out;
    }

    private static Gate copyGate(Gate g) {
        Gate c = new Gate();
        c.group = g.group;
        c.x = g.x;
        c.y = g.y;
        c.z = g.z;
        c.halfW = g.halfW;
        c.halfL = g.halfL;
        c.exact = g.exact;
        c.exactTol = g.exactTol;
        c.cellMinX = g.cellMinX;
        c.cellMaxX = g.cellMaxX;
        c.cellMinZ = g.cellMinZ;
        c.cellMaxZ = g.cellMaxZ;
        c.minSpeed = g.minSpeed;
        c.maxSpeed = g.maxSpeed;
        c.hasDir = g.hasDir;
        c.dirDeg = g.dirDeg;
        c.dirTolDeg = g.dirTolDeg;
        c.mustLand = g.mustLand;
        return c;
    }

    /** One leg, landed on; cut in half and recursed when it will not plan whole. Null when it cannot be done. */
    private static List<Step> planLeg(Ap3RouteMath.RouteState from, List<Gate> leg, List<Blocked> blocked,
                                      Terrain terrain, Ap3DiscretePlanner.Model m, Options o, int depth,
                                      long deadline) {
        long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
        if (leftMs < 200) {
            return null;
        }
        Options lo = o.copy();
        lo.portfolio = false;
        lo.preferRunning = false;
        lo.beam = Math.min(6000, o.beam * 2);
        lo.budgetMs = Math.max(200, Math.min(leftMs, Math.max(600, o.budgetMs / 2)));
        Field field = new Field(from, leg, blocked, terrain, lo);
        // Running first here too. This is the path that answered his storm route, and it was handing back plans
        // with two and four jumps in them - killer560, 2026-09-23: "then when I did it still jumped the 3 block
        // gap". The jumpless preference lives in portfolio(), which this never goes through, so a leg that could
        // be run was being jumped whenever jumping happened to be a tick quicker. Half the leg's allowance is
        // plenty: with jumps off the search either finds the way across quickly or runs out of places to stand.
        if (o.allowJump) {
            Options run = lo.copy();
            run.allowJump = false;
            run.budgetMs = Math.max(150, lo.budgetMs / 2);
            Plan onFoot = search(from, leg, blocked, terrain, m, run,
                    new Field(from, leg, blocked, terrain, run));
            if (onFoot.complete) {
                return List.of(onFoot.steps);
            }
        }
        Plan p = search(from, leg, blocked, terrain, m, lo, field);
        if (p.complete) {
            return List.of(p.steps);
        }
        if (depth >= MAX_SPLITS) {
            return null;
        }
        double[] via = field.waypoint(0, from.x, from.z, 0.5);
        if (via == null) {
            return null;
        }
        Gate goal = leg.get(leg.size() - 1);
        // A cut that lands nearly on one end splits nothing and burns the budget twice over.
        if (Math.hypot(via[0] - from.x, via[2] - from.z) < 2.0
                || Math.hypot(via[0] - goal.x, via[2] - goal.z) < 2.0) {
            return null;
        }
        for (double y : new double[]{via[1], via[1] + 1.0}) {
            Gate w = new Gate();
            w.x = via[0];
            w.y = y;
            w.z = via[2];
            w.halfW = 0.5;
            w.halfL = 0.5;
            w.mustLand = true;
            w.captureBlocks();
            List<Step> first = planLeg(from, List.of(w), blocked, terrain, m, o, depth + 1, deadline);
            if (first == null) {
                continue;
            }
            Ap3RouteMath.RouteState mid = from.copy();
            for (Step st : first) {
                Ap3RouteMath.step(mid, st.keys(), st.yaw(), st.jump(), st.sprint(), m, terrain.shapes());
            }
            List<Step> rest = planLeg(mid, leg, blocked, terrain, m, o, depth + 1, deadline);
            if (rest == null) {
                continue;
            }
            List<Step> both = new ArrayList<>(first.size() + rest.size());
            both.addAll(first);
            both.addAll(rest);
            return both;
        }
        return null;
    }

    private static Plan search(Ap3RouteMath.RouteState start, List<Gate> gates, List<Blocked> blocked,
                               Terrain terrain, Ap3DiscretePlanner.Model m, Options o, Field field) {
        Plan plan = new Plan();
        if (gates.isEmpty()) {
            plan.complete = true;
            return plan;
        }
        long deadline = System.nanoTime() + o.budgetMs * 1_000_000L;
        double top = Math.max(Ap3RouteMath.topSpeed(m, o.allowJump), start.speed());
        List<int[]> groups = groupsOf(gates);
        Node root = new Node();
        root.s = start.copy();
        root.group = 0;
        double startLeft = field.heuristic(start.x, start.z, start.y, 0, 0, groups, top);
        root.f = startLeft;
        List<Node> layer = new ArrayList<>();
        layer.add(root);
        Node best = root;
        Map<Long, Node> seen = new HashMap<>();

        int layersSearched = 0;
        for (int tick = 0; tick < o.maxTicks && !layer.isEmpty(); tick++) {
            if (System.nanoTime() > deadline) {
                plan.note = "time budget";
                break;
            }
            layersSearched = tick + 1;
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
                    if (goal == null || better(n, goal)) {
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
            layer = cutLayer(next, o.beam, top);
        }
        plan.gatesReached = gatesBefore(groups, best.group) + Integer.bitCount(best.mask);
        plan.note = plan.note.isEmpty() ? "no route found" : plan.note;
        // Which gates the heuristic can even see a way to, from where he is standing now.
        StringBuilder reach = new StringBuilder();
        for (int gi = 0; gi < gates.size(); gi++) {
            Gate gate = gates.get(gi);
            double fieldDist = field.at(gi, start.x, start.z);
            double straight = Math.hypot(gate.x - start.x, gate.z - start.z);
            boolean walled = fieldDist > straight * 4 + 8;
            reach.append(gi == 0 ? "" : ", ").append("g").append(gi + 1).append(' ')
                    .append(walled ? "NO WAY THERE" : String.format(Locale.US, "%.0f blocks round", fieldDist));
        }
        plan.diagnosis = String.format(Locale.US,
                "reached %d/%d gates, best was %d ticks in and still %.1f blocks out; %d layers searched; %s",
                plan.gatesReached, gates.size(), best.ticks,
                field.heuristic(best.s.x, best.s.z, best.s.y, best.group, best.mask, groups, top) * top,
                layersSearched, reach);
        if (field.heuristic(best.s.x, best.s.z, best.s.y, best.group, best.mask, groups, top) >= startLeft - 1e-9) {
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

    /**
     * Cut a layer to the beam width WITHOUT throwing away every slow state.
     * <p>
     * Ranking purely by {@code f} means ranking by speed, because {@code f} is ticks plus distance over top speed:
     * a state that has deliberately slowed costs more ticks for the same ground and always sorts below a state that
     * has not. That is fine on open floor and fatal on a short hop. Landing on a pad two blocks deep, one block up
     * and one block away, needs an approach of about 0.5 blocks a tick where killer560 runs at 1.5 - so every state
     * that could make the jump was cut, the rest fell in the hole, and the search reported "no route found" having
     * never tried slowing down. That is his "it just tries to jump towards the node with no regard to it still
     * being a whole block low": the only approaches it kept were ones going too fast to land.
     * <p>
     * So the beam is shared between speed bands, cheapest first within each. The fast band still gets the lion's
     * share on any tick where nothing else survives, because unused quota is handed back in the second pass.
     */
    private static List<Node> cutLayer(List<Node> sorted, int beam, double top) {
        if (sorted.size() <= beam) {
            return sorted;
        }
        // Most of the beam still goes to the cheapest states, in order. Splitting it evenly between bands instead
        // cost real quality - a route round a wall went from 26 ticks to 53 - because the best states were being
        // squeezed out by a reserved share for bands that had nothing useful to offer on that tick. So: keep the
        // best half outright, and spend the other half on diversity.
        // A quarter of the beam goes to the cheapest states outright and the rest is shared between bands.
        // Measured across the pad courses, the gap sweep and the wall detour: at a half the wall detour
        // failed outright, at nothing it cost 53 ticks instead of 26, and at a quarter every pad course that is
        // physically possible completes, every runnable gap is run rather than jumped, and the detour costs 34.
        int keepBest = beam / 4;
        List<Node> out = new ArrayList<>(beam);
        for (Node n : sorted) {
            n.picked = false;
        }
        for (int i = 0; i < keepBest && i < sorted.size(); i++) {
            Node n = sorted.get(i);
            n.picked = true;
            out.add(n);
        }
        // Speed bands, doubled by whether the state is in the air. Airborne states are the ones a hop between pads
        // lives or dies on, and on any given tick they are far outnumbered by states still running about on the
        // floor - so without a reserved share they are cut before the landing they were setting up ever happens.
        int bands = SPEED_BANDS * 2;
        int quota = Math.max(1, (beam - keepBest) / bands);
        int[] taken = new int[bands];
        for (Node n : sorted) {
            if (n.picked) {
                continue;
            }
            int band = speedBand(n, top, bands);
            if (taken[band] < quota) {
                taken[band]++;
                n.picked = true;
                out.add(n);
                if (out.size() == beam) {
                    return out;
                }
            }
        }
        for (Node n : sorted) { // bands with nothing to offer give their room back to the best of the rest
            if (!n.picked) {
                out.add(n);
                if (out.size() == beam) {
                    break;
                }
            }
        }
        return out;
    }

    /** How many speed bands the beam is shared between; doubled again by ground versus air. */
    private static final int SPEED_BANDS = 4;

    private static int speedBand(Node n, double top, int bands) {
        int band = 0;
        if (top > 1.0E-9) {
            band = Math.max(0, Math.min(SPEED_BANDS - 1, (int) (n.s.speed() / top * SPEED_BANDS)));
        }
        return n.s.onGround ? band : band + SPEED_BANDS;
    }

    /**
     * Between two ways of finishing on the same tick: fewer jumps first (running is what he wants where it works),
     * then fewer ticks spent scraping along things, then the cheaper f. All of these are ties on the only thing the
     * search actually minimises, so none of them can buy a slower route.
     */
    private static boolean better(Node a, Node b) {
        if (a.jumps != b.jumps) {
            return a.jumps < b.jumps;
        }
        if (a.clips != b.clips) {
            return a.clips < b.clips;
        }
        return a.f < b.f;
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
        Ap3DiscretePlanner.Action[] shapes = fine ? FINE_SHAPES
                : (canBrakeInAir(n, target, top) ? AIR_BRAKE_SHAPES : FAST_SHAPES);
        boolean walkToo = o.allowWalking && (fine || nearTarget(n, target, top));
        for (double dir : dirs) {
            for (Ap3DiscretePlanner.Action a : shapes) {
                float yaw = (float) (Math.toDegrees(dir) - keyOffset(a));
                tryStep(n, a, yaw, false, gates, groups, blocked, terrain, m, o, top, out, seen, field);
                if (o.allowJump && n.s.onGround) {
                    tryStep(n, a, yaw, true, gates, groups, blocked, terrain, m, o, top, out, seen, field);
                }
                if (walkToo && a.fw() > 0) {
                    // The same press without sprint. Only offered where it can earn its branching: closing on the
                    // gate being aimed at, which is where arriving at the right speed decides whether he lands on
                    // the ledge or runs into it.
                    tryStep(n, a, yaw, false, false, gates, groups, blocked, terrain, m, o, top, out, seen, field);
                    if (o.allowJump && n.s.onGround) {
                        tryStep(n, a, yaw, true, false, gates, groups, blocked, terrain, m, o, top, out, seen, field);
                    }
                }
            }
        }
        // Coast / sneak-only: no push at all (sneak-only still arms the 0.3x tap for the next tick).
        // killer560 (2026-09-22): "it should also be able to have no input and effectivley just drift if it thinks
        // that is most optimal." Coasting on the ground was already here; coasting THROUGH a jump was not, so a
        // pure ballistic hop - leave the ground and touch nothing until you land - could not be expressed at all.
        // It is often the cleanest way over a ledge, because air control only bleeds speed you already have.
        tryStep(n, Ap3DiscretePlanner.NONE, n.s.yaw, false, gates, groups, blocked, terrain, m, o, top, out, seen, field);
        if (o.allowJump && n.s.onGround) {
            tryStep(n, Ap3DiscretePlanner.NONE, n.s.yaw, true, gates, groups, blocked, terrain, m, o, top, out, seen,
                    field);
        }
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
        tryStep(n, a, yaw, jump, true, gates, groups, blocked, terrain, m, o, top, out, seen, field);
    }

    private static void tryStep(Node n, Ap3DiscretePlanner.Action a, float yaw, boolean jump, boolean sprint,
                                List<Gate> gates,
                                List<int[]> groups, List<Blocked> blocked, Terrain terrain,
                                Ap3DiscretePlanner.Model m, Options o,
                                double top, List<Node> out, Map<Long, Node> seen, Field field) {
        Ap3RouteMath.RouteState s = n.s.copy();
        double fromX = s.x;
        double fromZ = s.z;
        boolean wasOnGround = s.onGround;
        Ap3RouteMath.step(s, a, yaw, jump, sprint, m, terrain.shapes());
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
        c.sprint = sprint;
        c.jumps = n.jumps + (jump ? 1 : 0);
        c.clips = n.clips + (s.sprintBlocked ? 1 : 0);
        // A collision that GAINED height is how you climb: walking into a step and stepping up on to it is the
        // normal way up, and charging for it priced the way up out of reach - LedgeHarness's two inner-edge cases
        // went from 20 and 28 ticks to INCOMPLETE, two blocks low. A collision that gained nothing is a bump.
        c.bumps = n.bumps + (s.sprintBlocked && s.y <= n.s.y + 1.0E-9 ? 1 : 0);
        c.crossed = crossed;
        // NOTE: the jump preference is deliberately NOT priced in here. f is what the beam prunes by, and charging
        // jumps made the beam drop every jumping state in favour of running ones that could not finish: a 5-block
        // gap at speed 550 has to be jumped, and it went from crossing every time to INCOMPLETE every time. The
        // preference belongs where the answer is chosen, not where the search is cut.
        // Clipping IS priced in, unlike jumping, and for the opposite reason: a jump is sometimes the only way
        // across, but running into the world is never required - it cancels the sprint and throws the speed away.
        // killer560, 2026-09-23: "it is still bumping and it isnt using any normal walks to align it better or
        // jumping earlier to not bump". Small on purpose. A clip already costs real ticks through the speed it
        // takes, so this only has to break the search's indifference, and a route that genuinely must brush a wall
        // has to stay findable - which is exactly what charging JUMPS this way destroyed (see above).
        c.f = c.ticks + field.heuristic(s.x, s.z, s.y, group, mask, groups, top)
                + o.turnCost * Math.abs(wrap(yaw - n.s.yaw)) / 180.0
                + c.bumps * CLIP_COST;
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
            old.clips = c.clips;
            old.bumps = c.bumps;
            old.parent = c.parent;
            old.keys = c.keys;
            old.yaw = c.yaw;
            old.jump = c.jump;
            old.sprint = c.sprint;
            old.f = c.f;
            old.crossed = c.crossed;
            return;
        }
        seen.put(key, c);
        out.add(c);
    }


    /**
     * Close enough to the gate that how fast he arrives decides whether he lands on it. Kept deliberately tight:
     * offering the walking variant of every press doubles the branching wherever it applies, and at top * 16 - some
     * thirteen blocks - that was enough to push a five-pad course off the end of the beam entirely.
     */
    private static boolean nearTarget(Node n, Gate g, double top) {
        double d = Math.hypot(g.x - n.s.x, g.z - n.s.z);
        return d < Math.max(3.0, top * 6);
    }

    /**
     * Airborne with the gate close enough that overshooting it is the risk worth spending branching on. A flight is
     * about twelve ticks and carries roughly a tick of ground travel each, so "close" is measured in flights.
     */
    private static boolean canBrakeInAir(Node n, Gate g, double top) {
        if (n.s.onGround) {
            return false;
        }
        double d = Math.hypot(g.x - n.s.x, g.z - n.s.z);
        return d < Math.max(3.0, top * 12);
    }

    /** Fine control (sneak, sideways braking, a denser ring) near a gate that asks for a position or a speed. */
    private static boolean needsFineControl(Node n, Gate g, double top) {
        // A gate that has to be LANDED ON needs the same care as an exact one: the approach has to be slowed and
        // aimed, not just aimed, or the tick that reaches it carries straight past.
        if (!g.exact && !g.wantsVelocity() && !g.mustLand) {
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
    static final class Field {
        static final double CELL = 0.5;
        final double minX, minZ;
        final int w, h;
        /** Distance to each gate's box, per cell; NaN where blocked or unreachable. */
        final float[][] dist;
        /** The surface height this grid believes each cell has - one per column, which is the whole difficulty. */
        float[] surface;
        /** Distance from gate i-1's centre on to the end, walked through the fields. */
        final double[] tail;
        /**
         * Per gate: did the flood reach the START? When it did, the field knows a way, and a floored cell it could
         * NOT reach is somewhere that way does not go - a pit you can stand in but not get out of. When it did not,
         * the grid has missed something (a jump longer than JUMP_CELLS, say) and nothing can be concluded from a
         * cell being unreachable, so the straight-line fallback stays as it was.
         */
        final boolean[] startKnown;
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
            double pad = o.scanPad; // room to walk around the outside of everything
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
            surface = floor;
            dist = new float[gates.size()][];
            for (int g = 0; g < gates.size(); g++) {
                dist[g] = flood(wall, floor, climb, gates.get(g));
            }
            startKnown = new boolean[gates.size()];
            for (int g = 0; g < gates.size(); g++) {
                int i = (int) Math.round((start.x - minX) / CELL);
                int j = (int) Math.round((start.z - minZ) / CELL);
                startKnown[g] = i >= 0 && j >= 0 && i < w && j < h && !Float.isInfinite(dist[g][i * h + j]);
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
            // Seed at the gate - but only where the ground is actually AT the gate's height. The field measures XZ
            // only, so seeding the gate's whole column tells the search that standing directly underneath a node is
            // zero blocks away from it. killer560, 2026-09-22: "it runs and jumps straight at it instead of taking a
            // detour to go up a few blocks", and the diagnosis line agreed - "best was 13 ticks in and still 0.0
            // blocks out" while stood on the floor beneath a platform. With the seeding levelled, the floor under
            // the platform measures its real distance - the whole way round by the stairs - and the detour becomes
            // the cheap thing it actually is.
            boolean seeded = false;
            for (int pass = 0; pass < 2 && !seeded; pass++) {
                for (int i = 0; i < w; i++) {
                    for (int j = 0; j < h; j++) {
                        int cell = i * h + j;
                        double cx = minX + i * CELL;
                        double cz = minZ + j * CELL;
                        if (wall[cell]) {
                            continue;
                        }
                        if (Math.abs(cx - g.x) > g.halfW + CELL || Math.abs(cz - g.z) > g.halfL + CELL) {
                            continue;
                        }
                        // Second pass drops the height test: if nothing in the gate's column sits at its level -
                        // a node on a shape this 2.5D grid reads differently, say - an unseeded field would be
                        // worse than a flat one, so fall back to the old behaviour rather than to nothing.
                        if (pass == 0 && Math.abs(floor[cell] - g.y) > GATE_Y_TOLERANCE) {
                            continue;
                        }
                        d[cell] = 0f;
                        queue.add(cell);
                        seeded = true;
                    }
                }
            }
            // Bellman-Ford style sweep on a small grid: cheap and simple, the grid is only thousands of cells.
            while (!queue.isEmpty()) {
                int cur = queue.poll();
                int ci = cur / h;
                int cj = cur % h;
                float base = d[cur];
                // Only cells beside a hole can be jumped to or from, and in open terrain almost none are - so the
                // reach below costs nothing on ordinary ground.
                boolean onLip = false;
                for (int di = -1; di <= 1 && !onLip; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        int ni = ci + di;
                        int nj = cj + dj;
                        if (ni < 0 || nj < 0 || ni >= w || nj >= h || hole(wall, floor, cur, ni * h + nj)) {
                            onLip = true;
                            break;
                        }
                    }
                }
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
                // ...and over a gap. A cell with nothing to stand on is a hole in this grid, and treating it as a
                // wall means a course built out of pillars and ledges - killer560's, whose ground profiles read
                // "X X X X X X X X -4.0 -2.5 ... 5.0" - is almost entirely unreachable to the field. It then falls
                // back to straight-line distance, which is admissible but tells the search nothing, so the search
                // does the only thing an uninformed search can: it heads straight at the node. That is exactly what
                // he described - "it just tries to jump towards the node with no regard to it still being a whole
                // block low" - and it is why 47 plans in one session came back INCOMPLETE.
                // So: from a cell on the lip of a hole, reach across it to anywhere a jump could actually land.
                if (!onLip) {
                    continue;
                }
                for (int dir = 0; dir < 8; dir++) {
                    int si = STEP_I[dir];
                    int sj = STEP_J[dir];
                    for (int len = 2; len <= JUMP_CELLS; len++) {
                        int ni = ci + si * len;
                        int nj = cj + sj * len;
                        if (ni < 0 || nj < 0 || ni >= w || nj >= h) {
                            break;
                        }
                        int mid = (ci + si * (len - 1)) * h + (cj + sj * (len - 1));
                        if (!hole(wall, floor, cur, mid)) {
                            break; // solid ground all the way: the ordinary neighbours already walked it
                        }
                        int at = ni * h + nj;
                        if (wall[at]) {
                            continue; // still over the hole - keep reaching
                        }
                        if (floor[cur] - floor[at] > JUMP_CLIMB) {
                            continue; // a jump from there could not gain this much height
                        }
                        float step = (float) (Math.hypot(si * len, sj * len) * CELL);
                        if (base + step < d[at] - 1e-4f) {
                            d[at] = base + step;
                            queue.add(at);
                        }
                    }
                }
            }
            return d;
        }

        /**
         * Is this cell, seen from {@code cur}, something a route flies OVER rather than walks through? A cell with
         * no floor is; so is a cell with a floor too far below {@code cur} to climb back out of.
         * <p>
         * Measured on killer560's two-jump section, 2026-09-23 (ap3-route-failure-2.json): between the ledge at
         * 120 and the node at 121 lies a two-block pit with a floor at 112. Because that floor exists, the pit was
         * not a hole to this flood, and because 112 -> 121 is not a climb, the flood stopped dead at the node's
         * edge: every cell on the approach - the pit, the ledge, the gap, the start - read as unreachable and the
         * search was handed straight-line distance for the whole route. Straight-line distance is shortest from
         * INSIDE the pit, so every state that overflew the ledge and fell in outranked every state still on a way
         * up, and from layer 20 the entire beam was down there (traced: 1200 of 1200 kept states "fallen").
         */
        private static boolean hole(boolean[] wall, float[] floor, int cur, int cell) {
            return wall[cell] || floor[cur] - floor[cell] > JUMP_CLIMB;
        }

        /**
         * A point roughly {@code fraction} of the way along this field's OWN route from (x, z) to the gate, found by
         * walking downhill through the distance field. It is where the heuristic already believes the route goes, so
         * it is a sensible place to cut a leg in half.
         */
        double[] waypoint(int gate, double x, double z, double fraction) {
            int i = (int) Math.round((x - minX) / CELL);
            int j = (int) Math.round((z - minZ) / CELL);
            if (i < 0 || j < 0 || i >= w || j >= h) {
                return null;
            }
            float from = dist[gate][i * h + j];
            if (Float.isInfinite(from) || from <= 0) {
                return null;
            }
            double want = from * (1 - fraction);
            int ci = i;
            int cj = j;
            for (int step = 0; step < 8000; step++) {
                if (dist[gate][ci * h + cj] <= want) {
                    break;
                }
                int bi = -1;
                int bj = -1;
                float best = dist[gate][ci * h + cj];
                // Widen the ring until something lower turns up. One cell is not enough: the flood JUMPS holes
                // (see JUMP_CELLS), so at the lip of a gap every neighbour is either the gap or further away, and
                // a walk that only looks one cell out stops dead there. Measured on killer560's route, 2026-09-22:
                // every fraction from 0.3 to 0.7 came back with the same point, the near lip of his 3-block gap -
                // which is no use as a place to cut the route in half, because it is before the hard part.
                for (int r = 1; r <= JUMP_CELLS && bi < 0; r++) {
                    for (int di = -r; di <= r; di++) {
                        for (int dj = -r; dj <= r; dj++) {
                            if (Math.max(Math.abs(di), Math.abs(dj)) != r) {
                                continue; // only the new edge of the ring
                            }
                            int ni = ci + di;
                            int nj = cj + dj;
                            if (ni < 0 || nj < 0 || ni >= w || nj >= h || Float.isNaN(surface[ni * h + nj])) {
                                continue;
                            }
                            float d = dist[gate][ni * h + nj];
                            if (d < best) {
                                best = d;
                                bi = ni;
                                bj = nj;
                            }
                        }
                    }
                }
                if (bi < 0) {
                    break;
                }
                ci = bi;
                cj = bj;
            }
            float f = surface[ci * h + cj];
            if (Float.isNaN(f)) {
                return null;
            }
            return new double[]{minX + ci * CELL, f, minZ + cj * CELL};
        }

        double at(int gate, double x, double z) {
            return at(gate, x, z, Double.NaN);
        }

        /**
         * Distance from here to a gate - with {@code y}, from here ON THE LEVEL HE IS ACTUALLY ON.
         * <p>
         * This grid holds one surface per column, so the floor beneath a platform and the platform itself are the
         * same cell, and a player stood underneath reads as being AT the node on top of it. That is what sent
         * killer560's route running under a balcony and jumping at the ceiling instead of round to the stairs -
         * the diagnosis said "13 ticks in and still 0.0 blocks out" while he was on the floor five blocks below.
         * Being ABOVE the surface is ordinary (he is mid-jump); being well below it means this cell's distance is
         * somebody else's, so it is charged for the climb it is hiding. The charge is deliberately steeper than any
         * detour the grid could offer, because the whole point is that walking under the thing must never look
         * cheaper than walking round to the way up.
         */
        double at(int gate, double x, double z, double y) {
            int i = (int) Math.round((x - minX) / CELL);
            int j = (int) Math.round((z - minZ) / CELL);
            if (i < 0 || j < 0 || i >= w || j >= h) {
                Gate g = gates.get(gate);
                return Math.hypot(g.x - x, g.z - z);
            }
            int cell = i * h + j;
            float d = dist[gate][cell];
            double out;
            if (Float.isInfinite(d)) {
                Gate g = gates.get(gate);
                out = Math.hypot(g.x - x, g.z - z); // walled in on the grid: fall back, the step check still rules
                // ...unless the grid knows the way from the start and this is a floor it never reached: then
                // standing here is standing in a pit. Straight-line distance is SHORTEST from inside a pit next to
                // the node, which made killer560's pit a sink for the whole beam (see hole()). Only charged when
                // the feet are down on it, or within a jump of it: a state flying over the pit is on its way.
                if (startKnown[gate] && !Float.isNaN(surface[cell]) && !Double.isNaN(y)
                        && y - surface[cell] < JUMP_CLIMB) {
                    out += UNDER_PENALTY;
                }
            } else {
                out = d;
            }
            if (!Double.isNaN(y)) {
                double under = surface[cell] - y;
                if (under > Ap3RouteCollide.MAX_UP_STEP) {
                    out += UNDER_PENALTY * under;
                }
            }
            return out;
        }

        /**
         * Time left: the nearest gate of the current group that is still open, plus the legs after it. Within a group
         * only the nearest one counts, which never overestimates (you have to reach at least that one).
         */
        double heuristic(double x, double z, int group, int mask, List<int[]> groups, double top) {
            return heuristic(x, z, Double.NaN, group, mask, groups, top);
        }

        double heuristic(double x, double z, double y, int group, int mask, List<int[]> groups, double top) {
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
                    // Only the FIRST hop is measured from where he is standing, so only that one knows his level;
                    // after it the walk continues from gate to gate, all of them on their own ground.
                    double d = at(members[i], cx, cz, cx == x && cz == z ? y : Double.NaN);
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
        if (g.mustLand) {
            // Also rules out a jump on the crossing tick itself: pressing jump leaves the move airborne.
            if (!to.onGround) {
                return false;
            }
            // And it has to FINISH on the node, not merely pass through it. An ordinary gate is crossed when the
            // tick's travel intersects its box, which is right for a waypoint you run past - but at speed 1000 a
            // tick covers three blocks, so "crossed" could mean sailing 1.9 blocks beyond the thing you were
            // supposed to stop on (measured). killer560: "sometimes when it goes to jump to a node it will overshoot
            // it a ton."
            if (Math.abs(to.x - g.x) > g.halfW + 1.0E-9 || Math.abs(to.z - g.z) > g.halfL + 1.0E-9) {
                return false;
            }
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
    /**
     * The identity two states are merged on: position and horizontal velocity on a lattice, plus how long the
     * state has been airborne and which gates it has ticked off.
     * <p>
     * KNOWN ISSUE, left deliberately. {@code group * 64 + mask * 4 + air} packs three things into overlapping bit
     * ranges - air runs to 16 while mask is only given a stride of 4 - so a state that has ticked off a lever can
     * share a key with one that has not, and tryStep then drops one of them on f alone. That is a real loss of
     * work. Four separate fixes for it were written and measured against the twenty-scenario optimality audit
     * (OptimalityAudit in the route harness): exact tags, a splitmix hash, height in the key, air folded into the
     * hash. EVERY one of them scored worse than this - 16 of 20 optimal with one outright failure, against 18 of
     * 20 with none - and the failure was on the hardest case in the suite, three one-block hops across one-block
     * gaps. The aliasing is acting as a state-space reduction that this beam width depends on.
     * <p>
     * So it stays until it can be fixed AND measured to be at least neutral. If you change it, run the audit
     * first; do not fix it on the grounds that it is obviously wrong, because it is obviously wrong and the
     * obvious fix is worse.
     */
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
            steps[i] = new Step(n.keys, n.yaw, n.jump, n.sprint);
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
            Ap3RouteMath.step(landed, st.keys(), yaw, st.jump(), st.sprint(), m, terrain.shapes());
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
            plan.steps[from + i] = new Step(s.keys(), (float) y[i], s.jump(), s.sprint());
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
            Ap3RouteMath.step(s, st.keys(), yaw, st.jump(), st.sprint(), m, terrain.shapes());
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

    /**
     * In the air, closing on the gate you are aiming at: the braking shapes as well. killer560 (2026-09-22):
     * "sometimes when it goes to jump to a node it will overshoot it a ton, make sure it can do things like press s
     * to slow it down even faster midair."
     * <p>
     * Every other shape set is forward-only (fw = 1) because forward is what keeps a sprint, so until now the
     * planner had no way to express S at all and a jump that carried too far could only be watched. Air control is
     * a flat 0.02 a tick, so S is worth little per tick - but a twelve-tick flight has twelve of them, and it is the
     * difference between landing on a node and sailing past it. Kept to the air and to the approach so the extra
     * branching is not paid for on every ordinary running tick.
     */
    private static final Ap3DiscretePlanner.Action[] AIR_BRAKE_SHAPES = {
            new Ap3DiscretePlanner.Action(1, 0, false),
            new Ap3DiscretePlanner.Action(1, 1, false),
            new Ap3DiscretePlanner.Action(0, 1, false),
            new Ap3DiscretePlanner.Action(-1, 0, false),
            new Ap3DiscretePlanner.Action(-1, 1, false),
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
