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
 * <b>Trigger box.</b> Every node owns a box {@link #width} x {@link #length} blocks (width across the yaw, length
 * along it) centred on the node. Every node is armed on its own: walking INTO its box fires it, in whatever order
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
        ALIGN, AXIS_ALIGN, WALK, RUN, LEAP, LEAP_COUNTER, TERMINAL, STOP, LOOK, BOOM, STOPWATCH;

        public static Type parse(String s) {
            if (s == null) {
                return null;
            }
            String key = s.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (key) {
                case "align", "line", "l", "a" -> ALIGN;
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
            };
        }

        /** The two alignment nodes - the ones that END a held walk. */
        public boolean isAlign() {
            return this == ALIGN || this == AXIS_ALIGN;
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
                case BOOM -> 7;
                case LEAP -> 8;
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

    public static final double DEFAULT_LENGTH = 1.0;
    public static final double DEFAULT_WIDTH = 1.0;
    public static final double MIN_LENGTH = 0.5;
    public static final double MAX_LENGTH = 64.0;
    public static final double MIN_WIDTH = 0.5;
    public static final double MAX_WIDTH = 64.0;
    /** Matches {@code Ap3Commands.MAX_WAIT_MS}, which is what the command and the tooltip promise. */
    public static final int MAX_WAIT_MS = 120_000;
    public static final int MAX_LEAP_COUNT = 4;
    /** Half-width of the rendered marker box. */
    public static final double MARKER_HALF = 0.25;
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
        // killer560 (2026-09-20 in-game test): "The align shouldnt default to this 3x3. It should only be the small
        // inner box." Every node - aligns included - now defaults to the 1x1 block it sits on; an align still pulls
        // you in from ALIGN_REACH once the chain reaches it, so a small box does not mean it is easy to miss.
    }

    // ---- snapping -------------------------------------------------------------------------------------------

    /** The centre of the block the coordinate is in ({@code n.5}) - killer560's ".5/.5 on the block". */
    public static double snapCentre(double v) {
        return Math.floor(v) + 0.5;
    }

    /** The block floor under the feet. A tiny epsilon so a feet position that reads "68.99999" after a landing
     *  still snaps to 69, not 68. */
    public static double snapY(double y) {
        return Math.floor(y + 1e-4);
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

    /** Signed lateral offset of {@code p} from the node's centre line (positive = to the node's left). */
    public double lateralOffset(Vec3 p) {
        Vec3 l = left();
        return (p.x - x) * l.x + (p.z - z) * l.z;
    }

    /** Signed distance of {@code p} along the node's direction from its centre (positive = ahead). */
    public double alongOffset(Vec3 p) {
        Vec3 d = dir();
        return (p.x - x) * d.x + (p.z - z) * d.z;
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

    /** The marker box drawn for the node. */
    public AABB boundingBox(double height) {
        return new AABB(x - MARKER_HALF, y, z - MARKER_HALF, x + MARKER_HALF, y + Math.max(0.1, height), z + MARKER_HALF);
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
        if (length != DEFAULT_LENGTH || width != DEFAULT_WIDTH) {
            sb.append(String.format(Locale.US, " box %sx%s", fmt(width), fmt(length)));
        }
        if (waitAfterMs > 0) {
            sb.append(" wait:").append(waitAfterMs);
        }
        if (closeGate) {
            sb.append(" close");
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
        n.leapMode = leapMode;
        n.leapClass = leapClass;
        n.leapIgn = leapIgn;
        n.leapCount = leapCount;
        n.colour = colour;
        return n;
    }
}
