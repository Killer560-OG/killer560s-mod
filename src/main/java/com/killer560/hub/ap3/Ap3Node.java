package com.killer560.hub.ap3;

import com.killer560.hub.dungeonclass.DungeonClass;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * One node of an AP3 chain (F7/M7 boss-fight movement, any phase). Cheat build only. Flattened into a single
 * class, like {@code autoroutes/RouteNode}, so it round-trips through the hand-editable chains file without a
 * polymorphic type registry.
 * <p>
 * Everything spatial is in <b>absolute world coordinates</b>: the boss arena is fixed, so none of Auto Routes'
 * room-relative transform applies here.
 * <p>
 * <b>Trigger box.</b> Every node owns a box {@link #width} x {@link #length} blocks (width across, length along
 * the grid-snapped {@link #boxYaw()}; 0.5 x 0.5 by default) centred on the node. Every node is armed on its own: walking INTO its box fires it, in whatever order
 * you reach the nodes (killer560, 2026-09-20: "node one, then node two, then node 4, then node 18, they should all
 * fire ... The order never matters"); same-tick collisions fire one per tick by {@link Type#priority()}. "/ap3 add
 * walk w1 l1" sets the box "in whole blocks, any size". A held Walk carries you into the next box.
 * <p>
 * <b>Snapping</b> (killer560: "All align nodes snap you to .5/.5 on the block by default. '/ap3 add align precise'
 * uses your exact current coordinates instead"): X and Z snap to the block centre ONCE at placement
 * ({@link #snapCentre}) unless the node is {@link #precise}; Y snaps to the block floor.
 * <p>
 * <b>Modifiers</b> (his "modifiers, replacing the wait node", and they apply to ANY node): {@link #waitAfterMs}
 * holds the next queued node by that long after this one is done; {@link #closeGate} makes this node perform only
 * on a manual left click or after a terminal/GUI closes.
 * <p>
 * A stored {@link #yaw} is <b>data</b>: it is only ever turned into a world direction ({@link #dir()}) or handed to
 * {@code RouteRotation} as a target that becomes a wrapped delta on the live yaw. It is never written to the player
 * (Rotation 360 rule).
 */
public final class Ap3Node {

    /** Node kinds. Aliases in {@link #parse} are the {@code /ap3 add <type>} spellings; the pre-2026-09-20 names
     *  (LINE, AXIS_LINE, LEAP_DETECTOR) still parse so a recorded chain keeps loading. WAIT and BREAKER are gone
     *  (a modifier and Breaker Aura respectively) - {@link Ap3Store} migrates those, {@link #parse} does not. */
    public enum Type {
        ALIGN, AXIS_ALIGN, WALK, RUN, LEAP, LEAP_COUNTER, TERMINAL, STOP, LOOK, BOOM, STOPWATCH, JUMP, EDGE;

        public static Type parse(String s) {
            if (s == null) {
                return null;
            }
            String key = s.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (key) {
                // "fastalign" etc.: Fast Align was removed 2026-09-21; nodes saved as one load as a plain Align.
                case "align", "line", "l", "a", "fastalign", "fast_align", "fast", "fa" -> ALIGN;
                case "axisalign", "axis_align", "axisline", "axis_line", "axis", "al", "aa", "wall" -> AXIS_ALIGN;
                case "walk", "w" -> WALK;
                case "run", "r", "sprint" -> RUN;
                case "leap" -> LEAP;
                case "leapcounter", "leap_counter", "counter", "lc", "leapdetector", "leap_detector", "ld" -> LEAP_COUNTER;
                case "terminal", "term", "t" -> TERMINAL;
                case "stop" -> STOP;
                case "look", "rotate" -> LOOK;
                case "boom", "superboom", "tnt" -> BOOM;
                case "stopwatch", "sw", "timer" -> STOPWATCH;
                case "jump", "j" -> JUMP;
                case "edge", "edgejump", "edge_jump", "ej" -> EDGE;
                default -> null;
            };
        }

        /** Short label for chat / the settings tab / world labels. */
        public String label() {
            return switch (this) {
                case ALIGN -> "Align";
                case AXIS_ALIGN -> "Axis Align";
                case WALK -> "Walk";
                case RUN -> "Run";
                case LEAP -> "Leap";
                case LEAP_COUNTER -> "Leap Counter";
                case TERMINAL -> "Terminal";
                case STOP -> "Stop";
                case LOOK -> "Look";
                case BOOM -> "Boom";
                case STOPWATCH -> "Stopwatch";
                case JUMP -> "Jump";
                case EDGE -> "Edge Jump";
            };
        }

        /** The two alignment nodes - the ones that END a held walk. */
        public boolean isAlign() {
            return this == ALIGN || this == AXIS_ALIGN;
        }

        /** Nodes that fire WITHOUT ending a held walk (killer560, 2026-09-21: "make both things that can go after
         *  something like a walk command as well") - the walk keeps driving while they jump. */
        public boolean keepsHold() {
            return this == JUMP || this == EDGE;
        }

        /** The two movers (they start a held walk that lasts until any other node fires). */
        public boolean isMover() {
            return this == WALK || this == RUN;
        }

        /**
         * The universal priority when more than one node triggers on the same tick - LOWER fires first, one per
         * tick. killer560 (2026-09-20): "There should be a universal priority though. stop should go first, then
         * align, then look, then walk, then boom, then leap. So if i hit a boom and leap node on the same tick then
         * it should boom then the next tick leap." He named those six; the rest are slotted in: a STOPWATCH split is
         * a timestamp and belongs at the instant you hit the box, ahead of a stop that takes ticks to settle; the two
         * waits (TERMINAL, LEAP_COUNTER) sit after look and before walk, so "stop, do the terminal, walk on" reads in
         * that order and a walk in the same box does not start and get cut by the terminal's own hold-drop.
         */
        public int priority() {
            return switch (this) {
                case STOPWATCH -> 0;
                case STOP -> 1;
                case ALIGN, AXIS_ALIGN -> 2;
                case LOOK -> 3;
                case TERMINAL -> 4;
                case LEAP_COUNTER -> 5;
                case WALK, RUN -> 6;
                // right after a walk in the same box, so the walk is already driving when the jump goes in
                case JUMP, EDGE -> 7;
                case BOOM -> 8;
                case LEAP -> 9;
            };
        }

        /** Nodes that block waiting for something outside the executor's control. A manual LEFT-CLICK satisfies
         *  any of them (killer560: "if I ever left click manually, then it should act like the terminal was
         *  completed. Same thing for leaps or any other type of wait modifier"). */
        public boolean isWaiting() {
            return this == LEAP || this == LEAP_COUNTER || this == TERMINAL;
        }
    }

    /** What a {@link Type#LEAP} node targets. */
    public enum LeapMode {
        /** Whoever Fast Leap's target for this area resolves to. */
        DEFAULT,
        /** First alive teammate of {@link Ap3Node#leapClass} (through {@code ClassOverrides}). */
        CLASS,
        /** A specific IGN ({@link Ap3Node#leapIgn}). */
        IGN;

        public static LeapMode parse(String s) {
            if (s == null) {
                return null;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "default", "auto", "fastleap", "none" -> DEFAULT;
                case "class", "c" -> CLASS;
                case "ign", "name", "player" -> IGN;
                default -> null;
            };
        }
    }

    /** Every node starts at half a block each way - killer560 (2026-09-21): "by default our nodes should be .5 .5 of
     *  a block" and "For other nodes they should also default to that .5 .5." Only the placement default: a saved
     *  size is kept exactly. */
    public static final double DEFAULT_LENGTH = 0.5;
    public static final double DEFAULT_WIDTH = 0.5;
    public static final double MIN_LENGTH = 0.5;
    public static final double MAX_LENGTH = 64.0;
    public static final double MIN_WIDTH = 0.5;
    public static final double MAX_WIDTH = 64.0;
    /** Matches {@code Ap3Commands.MAX_WAIT_MS}, which is what the command and the tooltip promise. */
    public static final int MAX_WAIT_MS = 120_000;
    public static final int MAX_LEAP_COUNT = 4;
    /** A node counts as reached while your feet are within this much of its floor (stairs, a small ledge). */
    public static final double BOX_Y_TOLERANCE = 1.5;
    private static final double BOX_EPS = 0.02;

    public Type type = Type.WALK;
    /** Absolute feet position (snapped to the block centre unless {@link #precise}). */
    public double x;
    public double y;
    public double z;
    /** Look direction captured when the node was placed (WALK/RUN travel direction, box orientation, LOOK / BOOM
     *  target). */
    public float yaw;
    public float pitch;
    /** Trigger box: extent along the yaw. */
    public double length = DEFAULT_LENGTH;
    /** Trigger box: extent across the yaw. */
    public double width = DEFAULT_WIDTH;
    /** ALIGN / AXIS_ALIGN: the coordinates were kept exactly as recorded ({@code /ap3 add align precise}). */
    public boolean precise;
    /** {@link Type#AXIS_ALIGN}: the world side of the wall you were touching at placement; null when unknown. */
    public Direction wallDir;
    /** Modifier ({@code wait:1000}): milliseconds to hold the chain after this node before the next one; 0 = none. */
    public int waitAfterMs;
    /** Modifier ({@code close}): this node fires only on a manual left click or after a terminal / GUI closes. */
    public boolean closeGate;
    /** Modifier on ANY node (killer560, 2026-09-21: "/ap3 add run edge ... should run at the right degree then jump
     *  at the edge"): once this node has done its thing, jump (JUMP) or jump at the edge (EDGE). */
    public JumpMod jumpMod = JumpMod.NONE;

    public enum JumpMod { NONE, JUMP, EDGE }
    /** {@link Type#LEAP}. */
    public LeapMode leapMode = LeapMode.DEFAULT;
    public DungeonClass leapClass;
    public String leapIgn;
    /** {@link Type#LEAP_COUNTER}: how many teammates must leap to you. */
    public int leapCount = 1;
    /** Optional per-node ARGB colour, or null to use the type / uniform colour from the settings. */
    public Integer colour;

    public Ap3Node() {
    }

    public Ap3Node(Type type, double x, double y, double z, float yaw, float pitch) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        // Box: DEFAULT_WIDTH x DEFAULT_LENGTH (0.5 x 0.5) for every type - the field initialisers. An align still
        // pulls you in from ALIGN_REACH once it fires, so a small box does not mean it is easy to miss.
    }

    // ---- snapping -------------------------------------------------------------------------------------------

    /** The centre of the block the coordinate is in ({@code n.5}) - killer560's ".5/.5 on the block". */
    public static double snapCentre(double v) {
        return Math.floor(v) + 0.5;
    }

    /** The feet height itself, to a thousandth - no longer floored to the block (killer560, 2026-09-21: "if a node
     *  is placed on carpet it is placed through the carpet and I cannot see it. Have the height not be fixed to a
     *  block"). Carpet, slabs and snow layers keep the node on their top surface. */
    public static double snapY(double y) {
        return Math.round(y * 1000.0) / 1000.0;
    }

    // ---- accessors (public fields for the codec; the UI reads through these) -------------------------------

    public Type type() { return type; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public double length() { return length; }
    public double width() { return width; }
    public boolean precise() { return precise; }
    public Direction wallDir() { return wallDir; }
    public int waitAfterMs() { return waitAfterMs; }
    public boolean closeGate() { return closeGate; }
    public LeapMode leapMode() { return leapMode; }
    public DungeonClass leapClass() { return leapClass; }
    public String leapIgn() { return leapIgn; }
    public int leapCount() { return leapCount; }
    public Integer colour() { return colour; }

    public void setLength(double v) {
        if (Double.isFinite(v)) {
            length = Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, v));
        }
    }

    public void setWidth(double v) {
        if (Double.isFinite(v)) {
            width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, v));
        }
    }

    public void setWaitAfterMs(int ms) {
        waitAfterMs = Math.max(0, Math.min(MAX_WAIT_MS, ms));
    }

    public void setLeapCount(int n) {
        leapCount = Math.max(1, Math.min(MAX_LEAP_COUNT, n));
    }

    public Vec3 pos() {
        return new Vec3(x, y, z);
    }

    /** Horizontal unit direction the node's yaw points in - Minecraft's {@code (-sin yaw, 0, cos yaw)}. */
    public Vec3 dir() {
        double r = Math.toRadians(yaw);
        return new Vec3(-Math.sin(r), 0.0, Math.cos(r));
    }

    /** Horizontal unit vector to the node's LEFT ({@code (cos yaw, 0, sin yaw)}) - the lateral axis. */
    public Vec3 left() {
        double r = Math.toRadians(yaw);
        return new Vec3(Math.cos(r), 0.0, Math.sin(r));
    }

    /**
     * The yaw the TRIGGER BOX is laid out along: the nearest cardinal of the placement yaw, for EVERY type, so the
     * box is always square to the block grid. killer560 (2026-09-21) for aligns: the box "rotates ... if I'm not
     * centered"; then for the rest: "for something like walk if i place it at an angle still have the node squared
     * to a block face like the align does, but have that arrow facing away." Only the box snaps - {@link #yaw},
     * {@link #dir()} and {@link #left()} (the walk's travel direction, the arrow, a LOOK / BOOM target) keep the real
     * angle untouched. Old nodes need no migration: the snap is applied when the yaw is read. Width stays across
     * and length along the snapped direction, so a {@code w1 l3} walk placed facing roughly east is 3 long east-west.
     */
    public float boxYaw() {
        return Math.round(Mth.wrapDegrees(yaw) / 90f) * 90f;
    }

    /** {@link #dir()} for the trigger box - see {@link #boxYaw()}. */
    public Vec3 boxDir() {
        double r = Math.toRadians(boxYaw());
        return new Vec3(-Math.sin(r), 0.0, Math.cos(r));
    }

    /** {@link #left()} for the trigger box - see {@link #boxYaw()}. */
    public Vec3 boxLeft() {
        double r = Math.toRadians(boxYaw());
        return new Vec3(Math.cos(r), 0.0, Math.sin(r));
    }

    /** Signed lateral offset of {@code p} from the trigger box's centre line (positive = to the box's left). */
    public double lateralOffset(Vec3 p) {
        Vec3 l = boxLeft();
        return (p.x - x) * l.x + (p.z - z) * l.z;
    }

    /** Signed distance of {@code p} along the trigger box's direction from its centre (positive = ahead). */
    public double alongOffset(Vec3 p) {
        Vec3 d = boxDir();
        return (p.x - x) * d.x + (p.z - z) * d.z;
    }

    /** True while the box is still the 0.5x0.5 every node is placed with - only decides whether chat / labels print
     *  the size; every box is drawn and tested at its exact size. */
    public boolean hasDefaultBox() {
        return width == DEFAULT_WIDTH && length == DEFAULT_LENGTH;
    }

    /** The trigger box's four floor corners at {@code y}, in world space, in ring order (a, b, c, d). */
    public Vec3[] triggerCorners(double y) {
        Vec3 d = boxDir();
        Vec3 l = boxLeft();
        double hl = length / 2.0;
        double hw = width / 2.0;
        return new Vec3[]{
                new Vec3(x + d.x * hl + l.x * hw, y, z + d.z * hl + l.z * hw),
                new Vec3(x + d.x * hl - l.x * hw, y, z + d.z * hl - l.z * hw),
                new Vec3(x - d.x * hl - l.x * hw, y, z - d.z * hl - l.z * hw),
                new Vec3(x - d.x * hl + l.x * hw, y, z - d.z * hl + l.z * hw)
        };
    }

    /** The trigger box as a world AABB of the given height - exact, since every box is square to the grid
     *  ({@link #boxYaw()}); the same corners {@link #contains} tests against. */
    public AABB triggerBox(double height) {
        Vec3[] c = triggerCorners(y);
        double minX = c[0].x, maxX = c[0].x, minZ = c[0].z, maxZ = c[0].z;
        for (int i = 1; i < c.length; i++) {
            minX = Math.min(minX, c[i].x);
            maxX = Math.max(maxX, c[i].x);
            minZ = Math.min(minZ, c[i].z);
            maxZ = Math.max(maxZ, c[i].z);
        }
        return new AABB(minX, y, minZ, maxX, y + Math.max(0.1, height), maxZ);
    }

    /** Whether feet position {@code p} is inside this node's trigger box. */
    public boolean contains(Vec3 p) {
        if (Math.abs(p.y - y) > BOX_Y_TOLERANCE) {
            return false;
        }
        return Math.abs(alongOffset(p)) <= length / 2.0 + BOX_EPS && Math.abs(lateralOffset(p)) <= width / 2.0 + BOX_EPS;
    }

    /** Horizontal distance from {@code p} to the node centre. */
    public double horizontalDistance(Vec3 p) {
        double dx = p.x - x;
        double dz = p.z - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Unit world vector of the AXIS_ALIGN wall side, or null when none was recorded. */
    public Vec3 wallVector() {
        return wallDir == null ? null : new Vec3(wallDir.getStepX(), 0.0, wallDir.getStepZ());
    }

    /** The world axis an AXIS_ALIGN corrects on besides the wall: perpendicular to the wall side, horizontal. */
    public Vec3 wallPerpendicular() {
        if (wallDir == null) {
            return null;
        }
        return wallDir.getAxis() == Direction.Axis.X ? new Vec3(0, 0, 1) : new Vec3(1, 0, 0);
    }

    /** The nearest horizontal world direction to the node's yaw ("front"), for migrating old FRONT/LEFT/RIGHT walls. */
    public static Direction nearestHorizontal(float yaw) {
        return Direction.fromYRot(Mth.wrapDegrees(yaw));
    }

    /** One-line description for {@code /ap3 list} and the settings tab (no index - the caller prefixes the
     *  1-based number so every place agrees on it). */
    public String describe() {
        StringBuilder sb = new StringBuilder(type.label());
        switch (type) {
            case ALIGN -> sb.append(precise ? " [precise]" : " [centre]");
            case AXIS_ALIGN -> sb.append(" [wall ").append(wallDir == null ? "?" : wallDir.getName())
                    .append(precise ? ", precise]" : "]");
            case WALK, RUN -> sb.append(String.format(Locale.US, " [@ %.0f deg]", yaw));
            case LEAP -> sb.append(" [").append(leapDescription()).append(']');
            case LEAP_COUNTER -> sb.append(" [").append(leapCount).append(leapCount == 1 ? " leap]" : " leaps]");
            case LOOK, BOOM -> sb.append(String.format(Locale.US, " [%.1f / %.1f]", yaw, pitch));
            default -> {
            }
        }
        if (!hasDefaultBox()) {
            sb.append(String.format(Locale.US, " box %sx%s", fmt(width), fmt(length)));
        }
        if (waitAfterMs > 0) {
            sb.append(" wait:").append(waitAfterMs);
        }
        if (closeGate) {
            sb.append(" close");
        }
        if (jumpMod != JumpMod.NONE) {
            sb.append(jumpMod == JumpMod.EDGE ? " edge" : " jump");
        }
        sb.append(String.format(Locale.US, " @ %.2f, %.1f, %.2f", x, y, z));
        return sb.toString();
    }

    public String leapDescription() {
        return switch (leapMode) {
            case CLASS -> leapClass == null ? "class ?" : leapClass.displayName();
            case IGN -> leapIgn == null || leapIgn.isBlank() ? "ign ?" : leapIgn;
            default -> "Fast Leap target";
        };
    }

    /** "3" for a whole number, "2.5" otherwise. */
    static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.US, "%.1f", v);
    }

    public Ap3Node copy() {
        Ap3Node n = new Ap3Node(type, x, y, z, yaw, pitch);
        n.length = length;
        n.width = width;
        n.precise = precise;
        n.wallDir = wallDir;
        n.waitAfterMs = waitAfterMs;
        n.closeGate = closeGate;
        n.jumpMod = jumpMod;
        n.leapMode = leapMode;
        n.leapClass = leapClass;
        n.leapIgn = leapIgn;
        n.leapCount = leapCount;
        n.colour = colour;
        return n;
    }
}
