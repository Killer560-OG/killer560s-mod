package com.killer560.hub.ap3;

import java.util.ArrayList;
import java.util.List;

/**
 * Vanilla's collision, transcribed rather than approximated: the player's box is swept against the real block
 * shapes one axis at a time, and a blocked move is retried at every step height the geometry offers.
 * <p>
 * The route optimiser used to resolve the ground by sampling floor heights along a tick's travel. That reads a
 * staircase correctly but it is not what the game does, and on stairs the two came apart: the plan expected 1.35
 * blocks of climb where the game managed 0.94, and the route stalled halfway up (killer560's logs, 2026-09-22).
 * This class removes the guess. No Minecraft types on purpose - the offline harness runs this exact code.
 *
 * <h2>Where this comes from</h2>
 * Read off 26.1.2's own bytecode ({@code Entity.collide}, {@code Entity.collideWithShapes},
 * {@code Entity.collectCandidateStepUpHeights}, {@code Shapes.collide}, {@code VoxelShape.collideX},
 * {@code Direction.axisStepOrder}), not from memory of an older version - 26.1.2 does NOT use the old
 * "raise by maxUpStep, sweep, drop back down" retry that earlier releases had. It collects the Y planes of the
 * blocks in the way, keeps the ones in {@code (0, maxUpStep]}, and takes the LOWEST one that buys more horizontal
 * travel than the blocked move did. There is no drop back down: you are left standing at that height and gravity
 * does the rest next tick.
 *
 * <h2>What is deliberately not modelled</h2>
 * {@code Player.maybeBackOffFromEdge} (a sneaking player refusing to walk off a ledge) and
 * {@code Entity.getBlockSpeedFactor} (soul sand / honey). Neither is on a dungeon floor, and both are additions to
 * this, not corrections of it.
 */
final class Ap3RouteCollide {

    private Ap3RouteCollide() {
    }

    /** The player's collision box: 0.6 wide, 1.8 tall standing and 1.5 crouching. */
    static final double HALF_WIDTH = 0.3;
    static final double HEIGHT = 1.8;
    static final double CROUCH_HEIGHT = 1.5;
    /** {@code Player.maxUpStep}: the step-height attribute, 0.6 for a player. */
    static final double MAX_UP_STEP = 0.6;
    /** {@code Shapes.collide} / {@code VoxelShape.collideX}: below this a move is treated as zero. */
    private static final double EPSILON = 1.0E-7;
    /** {@code Mth.equal}: how far two doubles may differ and still count as the same - what decides a wall hit. */
    private static final double MTH_EPSILON = 1.0E-5;
    /** {@code Entity.collide}: the sliver the step-up search box is grown downwards by when you did not just land. */
    private static final double STEP_SEARCH_SLIVER = 9.999999747378752E-6;

    // ---- boxes ---------------------------------------------------------------------------------------------------

    /** An axis-aligned box - a block's collision shape, or the player's own. */
    static final class Box {
        double minX, minY, minZ, maxX, maxY, maxZ;

        Box() {
        }

        Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        Box set(Box o) {
            minX = o.minX;
            minY = o.minY;
            minZ = o.minZ;
            maxX = o.maxX;
            maxY = o.maxY;
            maxZ = o.maxZ;
            return this;
        }

        /** In place, so a sweep does not allocate a box per axis. */
        Box shift(double dx, double dy, double dz) {
            minX += dx;
            maxX += dx;
            minY += dy;
            maxY += dy;
            minZ += dz;
            maxZ += dz;
            return this;
        }

        Box copy() {
            return new Box(minX, minY, minZ, maxX, maxY, maxZ);
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "[%.3f..%.3f, %.3f..%.3f, %.3f..%.3f]",
                    minX, maxX, minY, maxY, minZ, maxZ);
        }
    }

    /** The player's box with the feet at {@code (x, y, z)}. */
    static Box playerBox(double x, double y, double z, double height) {
        return new Box(x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + height, z + HALF_WIDTH);
    }

    /** Where the collision boxes come from: everything that overlaps the query is appended to {@code out}. */
    interface Shapes {
        void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<Box> out);
    }

    /** Endless floor with its surface at {@code top} - what the harness and the align model plan on. */
    static Shapes flat(double top) {
        Box floor = new Box(-3.0E7, top - 64.0, -3.0E7, 3.0E7, top, 3.0E7);
        return (minX, minY, minZ, maxX, maxY, maxZ, out) -> {
            if (maxY > floor.minY && minY < floor.maxY) {
                out.add(floor);
            }
        };
    }

    /** Nothing to collide with at all - a free-fall world, used when a route has no snapshot yet. */
    static final Shapes EMPTY = (minX, minY, minZ, maxX, maxY, maxZ, out) -> {
    };

    /**
     * A snapshot of the world's collision boxes, indexed by block column so a query touches only the few columns a
     * tick's travel crosses. Built once on the client thread, then read from the planning worker.
     */
    static final class BoxWorld implements Shapes {
        /**
         * Boxes by block column. This is the route search's hottest structure by a wide margin - one plan sweeps the
         * player box a few million times - so it is a flat array addressed by arithmetic rather than a HashMap: no
         * hashing, no Long boxing, no allocation per query. Columns outside the built region hold nothing, which is
         * correct for a snapshot taken around the route in the first place.
         */
        private Box[][] columns;
        private int originX, originZ, width, depth;
        private final List<Box> all = new ArrayList<>();
        void add(Box b) {
            all.add(b);
            columns = null; // rebuilt lazily on the next query
        }

        void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            add(new Box(minX, minY, minZ, maxX, maxY, maxZ));
        }

        int size() {
            return all.size();
        }

        /** Every box, for writing a snapshot out so it can be replayed away from the game. */
        List<Box> boxes() {
            return all;
        }

        private void build() {
            if (columns != null) {
                return;
            }
            int loX = 0;
            int hiX = 0;
            int loZ = 0;
            int hiZ = 0;
            boolean first = true;
            for (Box b : all) {
                int bx0 = (int) Math.floor(b.minX);
                int bx1 = (int) Math.ceil(b.maxX) - 1;
                int bz0 = (int) Math.floor(b.minZ);
                int bz1 = (int) Math.ceil(b.maxZ) - 1;
                if (first) {
                    loX = bx0;
                    hiX = bx1;
                    loZ = bz0;
                    hiZ = bz1;
                    first = false;
                } else {
                    loX = Math.min(loX, bx0);
                    hiX = Math.max(hiX, bx1);
                    loZ = Math.min(loZ, bz0);
                    hiZ = Math.max(hiZ, bz1);
                }
            }
            originX = loX;
            originZ = loZ;
            width = Math.max(1, hiX - loX + 1);
            depth = Math.max(1, hiZ - loZ + 1);
            int[] counts = new int[width * depth];
            for (Box b : all) {
                int x0 = colMinX(b);
                int x1 = colMaxX(b);
                int z0 = colMinZ(b);
                int z1 = colMaxZ(b);
                for (int cx = x0; cx <= x1; cx++) {
                    for (int cz = z0; cz <= z1; cz++) {
                        counts[(cx - originX) * depth + (cz - originZ)]++;
                    }
                }
            }
            Box[][] cols = new Box[width * depth][];
            for (int i = 0; i < cols.length; i++) {
                if (counts[i] > 0) {
                    cols[i] = new Box[counts[i]];
                    counts[i] = 0;
                }
            }
            for (Box b : all) {
                int x0 = colMinX(b);
                int x1 = colMaxX(b);
                int z0 = colMinZ(b);
                int z1 = colMaxZ(b);
                for (int cx = x0; cx <= x1; cx++) {
                    for (int cz = z0; cz <= z1; cz++) {
                        int i = (cx - originX) * depth + (cz - originZ);
                        cols[i][counts[i]++] = b;
                    }
                }
            }
            columns = cols;
        }

        private int colMinX(Box b) {
            return Math.max(originX, (int) Math.floor(b.minX));
        }

        private int colMaxX(Box b) {
            return Math.min(originX + width - 1, (int) Math.ceil(b.maxX) - 1);
        }

        private int colMinZ(Box b) {
            return Math.max(originZ, (int) Math.floor(b.minZ));
        }

        private int colMaxZ(Box b) {
            return Math.min(originZ + depth - 1, (int) Math.ceil(b.maxZ) - 1);
        }

        @Override
        public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                            List<Box> out) {
            build();
            int minCx = Math.max(originX, (int) Math.floor(minX));
            int maxCx = Math.min(originX + width - 1, (int) Math.floor(maxX));
            int minCz = Math.max(originZ, (int) Math.floor(minZ));
            int maxCz = Math.min(originZ + depth - 1, (int) Math.floor(maxZ));
            if (minCx > maxCx || minCz > maxCz) {
                return;
            }
            for (int cx = minCx; cx <= maxCx; cx++) {
                int base = (cx - originX) * depth;
                for (int cz = minCz; cz <= maxCz; cz++) {
                    Box[] in = columns[base + (cz - originZ)];
                    if (in == null) {
                        continue;
                    }
                    for (Box b : in) {
                        if (b.maxY <= minY || b.minY >= maxY
                                || b.maxX <= minX || b.minX >= maxX
                                || b.maxZ <= minZ || b.minZ >= maxZ) {
                            continue;
                        }
                        // A box wider than one block is filed under every column it covers, so take it only from
                        // the first of those columns this query visits. That deduplicates in constant time and
                        // WITHOUT writing to the box: the snapshot is read by the planning worker and the renderer
                        // at the same time, so a mark stored on the box would race between them.
                        if (cx > minCx && cx > colMinX(b)) {
                            continue;
                        }
                        if (cz > minCz && cz > colMinZ(b)) {
                            continue;
                        }
                        out.add(b);
                    }
                }
            }
        }
    }

    // ---- one axis ------------------------------------------------------------------------------------------------

    /**
     * {@code VoxelShape.collideX} for a cuboid: how far the box may travel along X before {@code b} stops it. The
     * 1.0E-7 margins are vanilla's own - they are what stops a box that is exactly flush with a wall from being
     * counted as overlapping it.
     */
    private static double clipX(Box b, Box e, double d) {
        if (e.maxY - EPSILON <= b.minY || e.minY + EPSILON >= b.maxY) {
            return d;
        }
        if (e.maxZ - EPSILON <= b.minZ || e.minZ + EPSILON >= b.maxZ) {
            return d;
        }
        if (d > 0.0) {
            double gap = b.minX - e.maxX;
            return gap >= -EPSILON ? Math.min(d, gap) : d;
        }
        double gap = b.maxX - e.minX;
        return gap <= EPSILON ? Math.max(d, gap) : d;
    }

    private static double clipY(Box b, Box e, double d) {
        if (e.maxX - EPSILON <= b.minX || e.minX + EPSILON >= b.maxX) {
            return d;
        }
        if (e.maxZ - EPSILON <= b.minZ || e.minZ + EPSILON >= b.maxZ) {
            return d;
        }
        if (d > 0.0) {
            double gap = b.minY - e.maxY;
            return gap >= -EPSILON ? Math.min(d, gap) : d;
        }
        double gap = b.maxY - e.minY;
        return gap <= EPSILON ? Math.max(d, gap) : d;
    }

    private static double clipZ(Box b, Box e, double d) {
        if (e.maxX - EPSILON <= b.minX || e.minX + EPSILON >= b.maxX) {
            return d;
        }
        if (e.maxY - EPSILON <= b.minY || e.minY + EPSILON >= b.maxY) {
            return d;
        }
        if (d > 0.0) {
            double gap = b.minZ - e.maxZ;
            return gap >= -EPSILON ? Math.min(d, gap) : d;
        }
        double gap = b.maxZ - e.minZ;
        return gap <= EPSILON ? Math.max(d, gap) : d;
    }

    /** {@code Shapes.collide}: narrow the move against every shape in turn, giving up as soon as it is zero. */
    private static double collideAxis(int axis, Box e, List<Box> shapes, double d) {
        for (int i = 0; i < shapes.size(); i++) {
            if (Math.abs(d) < EPSILON) {
                return 0.0;
            }
            Box b = shapes.get(i);
            d = axis == 0 ? clipX(b, e, d) : axis == 1 ? clipY(b, e, d) : clipZ(b, e, d);
        }
        return d;
    }

    // ---- a whole move --------------------------------------------------------------------------------------------

    /**
     * {@code Entity.collideWithShapes}: Y first, then the SMALLER of X and Z, then the other. The order matters -
     * resolving the larger axis first lets a box slip round a corner it should have hit.
     * Writes the resolved move into {@code out}.
     */
    private static void collideWithShapes(double dx, double dy, double dz, Box box, List<Box> shapes,
                                          Box scratch, double[] out) {
        out[0] = 0.0;
        out[1] = 0.0;
        out[2] = 0.0;
        if (shapes.isEmpty()) {
            out[0] = dx;
            out[1] = dy;
            out[2] = dz;
            return;
        }
        if (dy != 0.0) {
            out[1] = collideAxis(1, scratch.set(box), shapes, dy);
        }
        boolean zFirst = Math.abs(dx) < Math.abs(dz);
        if (zFirst) {
            if (dz != 0.0) {
                out[2] = collideAxis(2, scratch.set(box).shift(out[0], out[1], 0.0), shapes, dz);
            }
            if (dx != 0.0) {
                out[0] = collideAxis(0, scratch.set(box).shift(0.0, out[1], out[2]), shapes, dx);
            }
        } else {
            if (dx != 0.0) {
                out[0] = collideAxis(0, scratch.set(box).shift(0.0, out[1], out[2]), shapes, dx);
            }
            if (dz != 0.0) {
                out[2] = collideAxis(2, scratch.set(box).shift(out[0], out[1], 0.0), shapes, dz);
            }
        }
    }

    /**
     * {@code Entity.collectCandidateStepUpHeights}: every Y plane of the blocks in the way, measured from the feet,
     * that is above 0, at most {@code maxUpStep} and not the height the plain move already reached. Vanilla keeps
     * these as floats and sorts them ascending, and the first one that helps is the one taken - so a stair is
     * climbed by exactly its tread height and you end up flush with it.
     */
    private static int candidateStepHeights(Box base, List<Box> shapes, float collidedY, Scratch sc) {
        float[] heights = sc.heights;
        int n = 0;
        for (int i = 0; i < shapes.size(); i++) {
            Box b = shapes.get(i);
            // A cuboid's Y planes, in the ascending order VoxelShape.getCoords(Y) hands them out.
            for (int side = 0; side < 2; side++) {
                float h = (float) ((side == 0 ? b.minY : b.maxY) - base.minY);
                if (h < 0.0F) {
                    continue;
                }
                if (h == collidedY) {
                    continue;
                }
                if (h > (float) MAX_UP_STEP) {
                    break; // the planes are ascending: nothing higher up this shape can help either
                }
                boolean dup = false;
                for (int k = 0; k < n; k++) {
                    if (heights[k] == h) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    if (n == heights.length) {
                        heights = java.util.Arrays.copyOf(heights, n * 2);
                        sc.heights = heights;
                    }
                    heights[n++] = h;
                }
            }
        }
        java.util.Arrays.sort(heights, 0, n);
        return n;
    }

    /**
     * Reusable working room for one thread's collide calls. The beam search runs this hundreds of thousands of
     * times per plan, so the sweep allocates nothing.
     */
    static final class Scratch {
        final List<Box> shapes = new ArrayList<>(16);
        final List<Box> stepShapes = new ArrayList<>(16);
        final Box box = new Box();
        final Box base = new Box();
        final Box axis = new Box();
        final double[] moved = new double[3];
        final double[] stepped = new double[3];
        final Result result = new Result();
        float[] heights = new float[16];
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    static Scratch scratch() {
        return SCRATCH.get();
    }

    /** What one move did: how far it actually went, and which walls it found. */
    static final class Result {
        /** The move that actually happened. */
        double dx, dy, dz;
        /** Vanilla's {@code Mth.equal} test - a hit small enough to ignore does not zero the velocity. */
        boolean hitX, hitZ;
        /** {@code movement.y != vec3.y}: something stopped the vertical move (a floor, a ceiling, or a step up). */
        boolean hitY;
        /** {@code verticalCollisionBelow}: the vertical move was downwards and got stopped - i.e. you are standing. */
        boolean onGround;
    }

    /**
     * {@code Entity.collide} followed by the part of {@code Entity.move} that decides the flags. Feed it the move
     * the tick wants and the box it starts in; it returns the move that really happens.
     */
    static Result collide(double x, double y, double z, double height,
                          double dx, double dy, double dz, boolean wasOnGround, Shapes world, Scratch sc) {
        Box box = sc.box;
        box.minX = x - HALF_WIDTH;
        box.maxX = x + HALF_WIDTH;
        box.minY = y;
        box.maxY = y + height;
        box.minZ = z - HALF_WIDTH;
        box.maxZ = z + HALF_WIDTH;
        Result out = sc.result;
        List<Box> shapes = sc.shapes;
        shapes.clear();
        Box scratch = sc.axis;
        double[] moved = sc.moved;
        // One gather, sized to cover the step-up probe too: it reaches MAX_UP_STEP higher and a sliver lower than
        // the plain move, and a superset can only be narrowed by clips that do not overlap, so it is free to share.
        gather(world, box, dx, dy, dz, STEP_SEARCH_SLIVER, MAX_UP_STEP, shapes);
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
            moved[0] = 0.0;
            moved[1] = 0.0;
            moved[2] = 0.0;
        } else {
            collideWithShapes(dx, dy, dz, box, shapes, scratch, moved);
        }
        boolean changedX = dx != moved[0];
        boolean changedY = dy != moved[1];
        boolean changedZ = dz != moved[2];
        boolean landed = changedY && dy < 0.0;

        if ((landed || wasOnGround) && (changedX || changedZ)) {
            // Try again from the height the plain move left the feet at (which is the floor, when you just landed).
            Box base = sc.base.set(box);
            if (landed) {
                base.shift(0.0, moved[1], 0.0);
            }
            List<Box> stepShapes = shapes; // the single gather above already covers the probe's reach
            int nHeights = candidateStepHeights(base, stepShapes, (float) moved[1], sc);
            double flatSq = moved[0] * moved[0] + moved[2] * moved[2];
            double[] stepped = sc.stepped;
            for (int hi = 0; hi < nHeights; hi++) {
                float h = sc.heights[hi];
                collideWithShapes(dx, h, dz, base, stepShapes, scratch, stepped);
                if (stepped[0] * stepped[0] + stepped[2] * stepped[2] > flatSq) {
                    // Vanilla leaves you UP THERE - there is no drop back down; gravity settles it next tick.
                    double rebase = box.minY - base.minY;
                    moved[0] = stepped[0];
                    moved[1] = stepped[1] - rebase;
                    moved[2] = stepped[2];
                    changedX = dx != moved[0];
                    changedY = dy != moved[1];
                    changedZ = dz != moved[2];
                    break;
                }
            }
        }

        out.dx = moved[0];
        out.dy = moved[1];
        out.dz = moved[2];
        // Entity.move: the horizontal flags use Mth.equal (1.0E-5), the vertical one an exact compare.
        out.hitX = Math.abs(dx - moved[0]) >= MTH_EPSILON;
        out.hitZ = Math.abs(dz - moved[2]) >= MTH_EPSILON;
        out.hitY = changedY;
        out.onGround = changedY && dy < 0.0;
        return out;
    }

    /**
     * {@code collectColliders} over {@code box.expandTowards(move)}, grown downwards by a sliver and upwards by
     * {@code extraUp} so ONE gather serves both the plain move and the step-up probe.
     * <p>
     * {@code extraUp} must be added to the move's own reach, not substituted for it: folding it in as
     * {@code max(dy, extraUp)} threw away the downward extent whenever dy was negative, and a falling player then
     * queried no boxes below himself and dropped straight through the floor. The harness caught it immediately -
     * every jump test ended 36 blocks down inside a block.
     */
    private static void gather(Shapes world, Box box, double dx, double dy, double dz, double down, double extraUp,
                               List<Box> out) {
        double minX = box.minX + Math.min(dx, 0.0);
        double maxX = box.maxX + Math.max(dx, 0.0);
        double minY = box.minY + Math.min(dy, 0.0) - down;
        double maxY = box.maxY + Math.max(dy, 0.0) + extraUp;
        double minZ = box.minZ + Math.min(dz, 0.0);
        double maxZ = box.maxZ + Math.max(dz, 0.0);
        world.collect(minX, minY, minZ, maxX, maxY, maxZ, out);
    }

    /** Is the box free here? Used to reject a start or a landing that is inside a block. */
    static boolean free(Box box, Shapes world) {
        List<Box> shapes = new ArrayList<>(4);
        world.collect(box.minX + EPSILON, box.minY + EPSILON, box.minZ + EPSILON,
                box.maxX - EPSILON, box.maxY - EPSILON, box.maxZ - EPSILON, shapes);
        for (int i = 0; i < shapes.size(); i++) {
            Box b = shapes.get(i);
            if (b.maxX - EPSILON > box.minX && b.minX + EPSILON < box.maxX
                    && b.maxY - EPSILON > box.minY && b.minY + EPSILON < box.maxY
                    && b.maxZ - EPSILON > box.minZ && b.minZ + EPSILON < box.maxZ) {
                return false;
            }
        }
        return true;
    }
}
