package com.killer560.hub.autoroutes;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One node of an Auto Route (QUOI {@code RouteRing} + its {@code RingAction}, flattened into a single class so it
 * round-trips through the hand-editable routes file without a polymorphic type registry). Cheat build only.
 * <p>
 * Everything spatial is <b>room-relative</b> ({@link RouteCoords}) so a route recorded in one run works when the
 * same room shows up rotated in another. {@link #pathIndex} anchors the node to the recorded movement
 * ({@link RoutePath}): playback performs the node once the path seek reaches that sample, and a route with no
 * recorded path falls back to QUOI's "stand in the ring" trigger.
 */
public final class RouteNode {

    /** Node kinds. Aliases in {@link #parse} are the {@code /ar add <type>} spellings killer560 asked for. */
    public enum Type {
        START, WALK, ETHERWARP, USE_ITEM, DUNGEON_BREAKER, BOOM, AWAIT, ROTATE, UNSNEAK, COMMAND;

        public static Type parse(String s) {
            if (s == null) {
                return null;
            }
            String key = s.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (key) {
                case "start" -> START;
                case "walk", "w" -> WALK;
                case "ew", "etherwarp", "ether", "warp" -> ETHERWARP;
                case "use", "use_item", "useitem", "item" -> USE_ITEM;
                case "breaker", "db", "dungeonbreaker", "dungeon_breaker" -> DUNGEON_BREAKER;
                case "boom", "superboom", "tnt" -> BOOM;
                case "await", "wait" -> AWAIT;
                case "rotate", "rot", "look" -> ROTATE;
                case "unsneak", "unshift" -> UNSNEAK;
                case "command", "cmd" -> COMMAND;
                default -> null;
            };
        }

        /** Short label for chat / the settings tab. */
        public String label() {
            return switch (this) {
                case START -> "Start";
                case WALK -> "Walk";
                case ETHERWARP -> "Etherwarp";
                case USE_ITEM -> "Use Item";
                case DUNGEON_BREAKER -> "Dungeon Breaker";
                case BOOM -> "Superboom";
                case AWAIT -> "Await";
                case ROTATE -> "Rotate";
                case UNSNEAK -> "Unsneak";
                case COMMAND -> "Command";
            };
        }
    }

    /** What an {@link Type#AWAIT} node waits for. */
    public enum AwaitCondition {
        /** {@link #awaitAmount} secrets collected in this room since the node armed (action bar count + bats). */
        SECRET,
        /** {@link #awaitAmount} milliseconds. */
        DELAY
    }

    public static final double DEFAULT_RADIUS = 1.0;

    public Type type = Type.WALK;
    /** Room-relative feet position. */
    public double x;
    public double y;
    public double z;
    /** Room-relative look direction captured when the node was added (see {@link RouteCoords#toRealYaw}). */
    public float yaw;
    public float pitch;
    /** Index into the route's {@link RoutePath} this node is anchored to; 0 for a route with no path. */
    public int pathIndex;
    /** Ring diameter in blocks (QUOI's {@code radius} is really a diameter - kept for parity). */
    public double radius = DEFAULT_RADIUS;
    /** Optional per-node ARGB colour, or null to use the type / uniform colour from the settings. */
    public Integer colour;

    // ---- type-specific ----
    /** {@link Type#DUNGEON_BREAKER}: room-relative blocks this node breaks, in order. */
    public final List<BlockPos> breakerBlocks = new ArrayList<>();
    /** {@link Type#USE_ITEM}: {@link ItemIdentity} of the item to use. */
    public String item;
    /** {@link Type#AWAIT}. */
    public AwaitCondition awaitCondition = AwaitCondition.SECRET;
    public int awaitAmount = 1;
    /** {@link Type#COMMAND}: full command line, with or without the leading slash. */
    public String command;
    /** {@link Type#ETHERWARP} / teleporting {@link Type#USE_ITEM}: where the recording landed (room-relative),
     *  used as the "the teleport really happened" confirmation. {@link #hasLanding} says whether it was captured. */
    public double landingX;
    public double landingY;
    public double landingZ;
    public boolean hasLanding;

    public RouteNode() {
    }

    public RouteNode(Type type, double x, double y, double z, float yaw, float pitch, int pathIndex) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.pathIndex = pathIndex;
    }

    // ---- accessors (the fields are public for the codec; the UI reads through these) ----

    public Type type() { return type; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public int pathIndex() { return pathIndex; }
    public double radius() { return radius; }
    /** Per-node ARGB colour override, or null. */
    public Integer colour() { return colour; }
    public List<BlockPos> breakerBlocks() { return breakerBlocks; }
    public String item() { return item; }
    public AwaitCondition awaitCondition() { return awaitCondition; }
    public int awaitAmount() { return awaitAmount; }
    public String command() { return command; }

    public Vec3 relativePos() {
        return new Vec3(x, y, z);
    }

    public void setLanding(Vec3 relative) {
        landingX = relative.x;
        landingY = relative.y;
        landingZ = relative.z;
        hasLanding = true;
    }

    /** QUOI {@code RouteRing.boundingBox}: a flat ring-shaped box around the node's REAL position. */
    public AABB boundingBox(Vec3 real, double height) {
        double r = Math.max(0.1, radius) / 2.0;
        return new AABB(real.x - r, real.y, real.z - r, real.x + r, real.y + Math.max(0.1, height), real.z + r);
    }

    /** True when {@code playerBox} (the player's own bounding box) overlaps this node's ring. */
    public boolean contains(Vec3 real, double height, AABB playerBox) {
        return boundingBox(real, height).intersects(playerBox);
    }

    /** Discrete actions wait for confirmation during playback; markers just get passed through. */
    public boolean isDiscreteAction() {
        return switch (type) {
            case START, WALK -> false;
            default -> true;
        };
    }

    /** One-line description for {@code /ar list} and the settings tab. */
    public String describe() {
        StringBuilder sb = new StringBuilder(type.label());
        switch (type) {
            case USE_ITEM -> sb.append(" [").append(item == null ? "?" : item).append(']');
            case DUNGEON_BREAKER -> sb.append(" [").append(breakerBlocks.size()).append(" block(s)]");
            case AWAIT -> sb.append(" [").append(awaitCondition.name().toLowerCase(Locale.ROOT)).append(' ')
                    .append(awaitAmount).append(awaitCondition == AwaitCondition.DELAY ? "ms" : "").append(']');
            case COMMAND -> sb.append(" [").append(command == null ? "" : command).append(']');
            default -> {
            }
        }
        sb.append(String.format(Locale.US, " @ %.1f, %.1f, %.1f", x, y, z));
        return sb.toString();
    }

    public RouteNode copy() {
        RouteNode n = new RouteNode(type, x, y, z, yaw, pitch, pathIndex);
        n.radius = radius;
        n.colour = colour;
        n.breakerBlocks.addAll(breakerBlocks);
        n.item = item;
        n.awaitCondition = awaitCondition;
        n.awaitAmount = awaitAmount;
        n.command = command;
        n.landingX = landingX;
        n.landingY = landingY;
        n.landingZ = landingZ;
        n.hasLanding = hasLanding;
        return n;
    }
}
