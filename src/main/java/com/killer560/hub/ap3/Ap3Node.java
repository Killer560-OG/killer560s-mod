package com.killer560.hub.ap3;

import com.killer560.hub.dungeonclass.DungeonClass;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One node of an AP3 chain (F7/M7 boss-fight movement, any phase). Cheat build only. Flattened into a single
 * class, like {@code autoroutes/RouteNode}, so it round-trips through the hand-editable chains file without a
 * polymorphic type registry.
 * <p>
 * Everything spatial is in <b>absolute world coordinates</b>: the boss arena is fixed, so none of Auto Routes'
 * room-relative transform applies here.
 * <p>
 * <b>Snapping</b> (killer560: "All of the nodes should naturally snap to either the center of a block, or if I'm
 * closer to being in between two blocks than in [the center of one], [then to the line] between those two"): X and Z
 * are rounded to the nearest half block ONCE at placement ({@link #snapXZ}) - {@code x.5} is a block centre,
 * {@code x.0} the seam between two blocks, whichever is nearer. Y snaps to the block floor.
 * <p>
 * A stored {@link #yaw} is <b>data</b>: it is only ever turned into a world direction ({@link #dir()}) or handed to
 * {@code RouteRotation} as a target that becomes a wrapped delta on the live yaw. It is never written to the player
 * (Rotation 360 rule).
 */
public final class Ap3Node {

    /** Node kinds. Aliases in {@link #parse} are the {@code /ap3 add <type>} spellings. */
    public enum Type {
        LINE, AXIS_LINE, WALK, RUN, LEAP, LEAP_DETECTOR, TERMINAL, WAIT, STOP, LOOK, BREAKER;

        public static Type parse(String s) {
            if (s == null) {
                return null;
            }
            String key = s.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (key) {
                case "line", "l" -> LINE;
                case "axisline", "axis_line", "axis", "al" -> AXIS_LINE;
                case "walk", "w" -> WALK;
                case "run", "r", "sprint" -> RUN;
                case "leap" -> LEAP;
                case "leapdetector", "leap_detector", "detector", "ld" -> LEAP_DETECTOR;
                case "terminal", "term", "t" -> TERMINAL;
                case "wait", "delay" -> WAIT;
                case "stop" -> STOP;
                case "look", "rotate" -> LOOK;
                case "breaker", "db", "dungeonbreaker", "dungeon_breaker" -> BREAKER;
                default -> null;
            };
        }

        /** Short label for chat / the settings tab / world labels. */
        public String label() {
            return switch (this) {
                case LINE -> "Line";
                case AXIS_LINE -> "Axis Line";
                case WALK -> "Walk";
                case RUN -> "Run";
                case LEAP -> "Leap";
                case LEAP_DETECTOR -> "Leap Detector";
                case TERMINAL -> "Terminal";
                case WAIT -> "Wait";
                case STOP -> "Stop";
                case LOOK -> "Look";
                case BREAKER -> "Breaker";
            };
        }

        /** The two alignment corridors (length = active span, width = tolerance band). */
        public boolean isCorridor() {
            return this == LINE || this == AXIS_LINE;
        }

        /** The two movers (length = distance travelled). */
        public boolean isMover() {
            return this == WALK || this == RUN;
        }

        /** Nodes that block waiting for something outside the executor's control. A manual LEFT-CLICK satisfies
         *  any of them (killer560: "if I ever left click manually, then it should act like the terminal was
         *  completed. Same thing for leaps or any other type of wait modifier"). */
        public boolean isWaiting() {
            return this == LEAP || this == LEAP_DETECTOR || this == TERMINAL || this == WAIT;
        }
    }

    /** What a {@link Type#LEAP} node targets. */
    public enum LeapMode {
        /** Whoever Fast Leap's P3 target for this section resolves to ({@code FastLeapConfig.LeapTarget.S1..S4}). */
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

    /** {@link Type#AXIS_LINE}: which axis the wall was found on at placement, relative to the node's yaw. */
    public enum WallAxis {
        NONE, FRONT, LEFT, RIGHT
    }

    public static final double DEFAULT_LENGTH = 3.0;
    public static final double DEFAULT_WIDTH = 1.0;
    public static final double MIN_LENGTH = 0.1;
    public static final double MAX_LENGTH = 64.0;
    public static final double MIN_WIDTH = 0.1;
    public static final double MAX_WIDTH = 16.0;
    /** Matches {@code Ap3Commands.MAX_WAIT_MS}, which is what the command and the tooltip promise. The load
     *  path used to accept ten times this, so a hand-edited or shared file could sit a chain still for ten
     *  minutes with nothing in the UI able to express it (2026-09-16 review). */
    public static final int MAX_WAIT_MS = 120_000;
    public static final int MAX_LEAP_COUNT = 4;
    /** Half-width of the rendered marker box. */
    public static final double MARKER_HALF = 0.25;

    public Type type = Type.WALK;
    /** Snapped absolute feet position. */
    public double x;
    public double y;
    public double z;
    /** Look direction captured when the node was placed (WALK/RUN travel direction, LINE axis, LOOK target). */
    public float yaw;
    public float pitch;
    /** LINE / AXIS_LINE: how far along the line the node stays active. WALK / RUN: how far to travel. */
    public double length = DEFAULT_LENGTH;
    /** LINE / AXIS_LINE: the tolerance band (full width) inside which it still corrects. */
    public double width = DEFAULT_WIDTH;
    /** {@link Type#WAIT}: delay in milliseconds. */
    public int waitMs = 1000;
    /** {@link Type#LEAP}. */
    public LeapMode leapMode = LeapMode.DEFAULT;
    public DungeonClass leapClass;
    public String leapIgn;
    /** {@link Type#LEAP_DETECTOR}: how many teammates must leap to you. */
    public int leapCount = 1;
    /** {@link Type#BREAKER}: absolute blocks this node breaks, in order. */
    public final List<BlockPos> breakerBlocks = new ArrayList<>();
    /** Optional per-node ARGB colour, or null to use the type / uniform colour from the settings. */
    public Integer colour;
    /** {@link Type#AXIS_LINE}: the wall measured at placement (axis + distance from the node centre to its face). */
    public WallAxis wallAxis = WallAxis.NONE;
    public double wallDistance;

    public Ap3Node() {
    }

    public Ap3Node(Type type, double snappedX, double snappedY, double snappedZ, float yaw, float pitch) {
        this.type = type;
        this.x = snappedX;
        this.y = snappedY;
        this.z = snappedZ;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    // ---- snapping -------------------------------------------------------------------------------------------

    /** Nearest half block: block centre ({@code n.5}) or the seam between two blocks ({@code n.0}). */
    public static double snapXZ(double v) {
        return Math.round(v * 2.0) / 2.0;
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
    public int waitMs() { return waitMs; }
    public LeapMode leapMode() { return leapMode; }
    public DungeonClass leapClass() { return leapClass; }
    public String leapIgn() { return leapIgn; }
    public int leapCount() { return leapCount; }
    public List<BlockPos> breakerBlocks() { return breakerBlocks; }
    public Integer colour() { return colour; }
    public WallAxis wallAxis() { return wallAxis; }
    public double wallDistance() { return wallDistance; }

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

    public void setWaitMs(int ms) {
        waitMs = Math.max(0, Math.min(MAX_WAIT_MS, ms));
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

    /** The marker box drawn for the node (and used for "standing on it" checks). */
    public AABB boundingBox(double height) {
        return new AABB(x - MARKER_HALF, y, z - MARKER_HALF, x + MARKER_HALF, y + Math.max(0.1, height), z + MARKER_HALF);
    }

    /** One-line description for {@code /ap3 list} and the settings tab (no index - the caller prefixes the
     *  1-based number so every place agrees on it). */
    public String describe() {
        StringBuilder sb = new StringBuilder(type.label());
        switch (type) {
            case LINE -> sb.append(String.format(Locale.US, " [len %.1f, width %.1f]", length, width));
            case AXIS_LINE -> sb.append(String.format(Locale.US, " [len %.1f, width %.1f, wall %s %.2f]", length, width,
                    wallAxis.name().toLowerCase(Locale.ROOT), wallDistance));
            case WALK, RUN -> sb.append(String.format(Locale.US, " [%.1f blocks @ %.0f deg]", length, yaw));
            case LEAP -> sb.append(" [").append(leapDescription()).append(']');
            case LEAP_DETECTOR -> sb.append(" [").append(leapCount).append(leapCount == 1 ? " leap]" : " leaps]");
            case WAIT -> sb.append(" [").append(waitMs).append("ms]");
            case LOOK -> sb.append(String.format(Locale.US, " [%.1f / %.1f]", yaw, pitch));
            case BREAKER -> sb.append(" [").append(breakerBlocks.size()).append(" block(s)]");
            default -> {
            }
        }
        sb.append(String.format(Locale.US, " @ %.1f, %.1f, %.1f", x, y, z));
        return sb.toString();
    }

    public String leapDescription() {
        return switch (leapMode) {
            case CLASS -> leapClass == null ? "class ?" : leapClass.displayName();
            case IGN -> leapIgn == null || leapIgn.isBlank() ? "ign ?" : leapIgn;
            default -> "Fast Leap target";
        };
    }

    public Ap3Node copy() {
        Ap3Node n = new Ap3Node(type, x, y, z, yaw, pitch);
        n.length = length;
        n.width = width;
        n.waitMs = waitMs;
        n.leapMode = leapMode;
        n.leapClass = leapClass;
        n.leapIgn = leapIgn;
        n.leapCount = leapCount;
        n.breakerBlocks.addAll(breakerBlocks);
        n.colour = colour;
        n.wallAxis = wallAxis;
        n.wallDistance = wallDistance;
        return n;
    }
}
