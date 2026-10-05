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

    /**
     * Node kinds. Aliases in {@link #parse} are the {@code /ar add <type>} spellings killer560 asked for.
     * <p>
     * {@link #START} and {@link #AWAIT} are LEGACY ONLY (killer560, 2026-09-2x: "start should not be a node...
     * i should do something like /ar add etherwarp start, and that is the start node. same with await."). Nothing
     * creates a node of either type any more - {@code /ar add} now takes {@code start} and {@code await:<n>} as
     * modifiers on any real node ({@link #start} / {@link #awaitEnabled} below). The two constants stay in the
     * enum only so {@link RouteStore} can still recognise {@code "type": "START"} / {@code "AWAIT"} in an old
     * routes file and fold it onto the modifier fields on load ({@code RouteStore#migrateLegacyMarkers}) instead
     * of silently dropping someone's saved route.
     */
    public enum Type {
        START, WALK, ETHERWARP, USE_ITEM, DUNGEON_BREAKER, BOOM, AWAIT, ROTATE, UNSNEAK, COMMAND,
        /** killer560, 2026-10-05: "put two nodes call them path ... it will pathfind from the first to the last via
         *  the etherwarping style our interactive map uses". Path nodes pair up in number order (1st with 2nd, 3rd
         *  with 4th): the first of a pair warps along its saved {@link #pathHops} to the second; the second is the
         *  arrival and does nothing itself. See {@link RoutePathPlanner}. */
        PATH;

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
                case "path" -> PATH;
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
                case PATH -> "Path";
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

    // ---- modifiers (killer560, 2026-09-2x: "/ar add etherwarp start await:2" - any node can carry either, or
    //      both, of these; see the Type enum doc's note on START / AWAIT being legacy-only now) ----
    /** This is the route's start node - the one {@code /ar start record} etherwarps back onto to arm playback.
     *  At most one node per route should have this set; {@link RouteRecorder#addNode} clears it off whichever
     *  node had it before setting it on a new one. */
    public boolean start;
    /** True when this node waits for {@link #awaitCondition} / {@link #awaitAmount} to be satisfied BEFORE it
     *  fires (the old {@code Type.AWAIT} node's behaviour, now a modifier instead of a separate node in the
     *  sequence - see {@code RouteExecutor#tickAction}'s pre-action await gate). */
    public boolean awaitEnabled;

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

    /**
     * {@link Type#PATH}, on the FIRST node of a pair: the etherwarps that take him from this node to the next path
     * node, planned once by the Interactive Map's planner and saved (killer560, 2026-10-05: "It only needs to
     * generate the movement once and then save it not generate it every time"). Room-relative, like everything
     * else here. {@link #planFromX}.. and {@link #planToX}.. are where the pair's two nodes stood when it was
     * planned: when either has moved since (an edit, a delete, an undo) the hops are stale and are planned again.
     */
    public final List<PathHop> pathHops = new ArrayList<>();
    public double planFromX;
    public double planFromY;
    public double planFromZ;
    public double planToX;
    public double planToY;
    public double planToZ;

    /**
     * One saved warp of a path: stand at {@code (ox, oy, oz)}, look along {@code yaw}/{@code pitch} (relative, as a
     * node's look is) and land on block {@code target}, feet at {@code (lx, ly, lz)}. All room-relative.
     */
    public record PathHop(double ox, double oy, double oz, float yaw, float pitch, BlockPos target,
                          double lx, double ly, double lz) {
    }

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

    /**
     * The centre of the whole block {@code v} is in: {@code floor(v) + 0.5} (killer560, 2026-10-04: "Make it so nodes
     * need to snap to a whole block for the autoroutes"). Auto Routes nodes used AP3's half-block grid
     * ({@code Ap3Node.snapCentre}) until then; AP3 keeps it. Snapped in WORLD space and then made room-relative: a room
     * rotation is a quarter turn about an integer clay corner, which maps block centres onto block centres.
     */
    public static double snapBlockCentre(double v) {
        return Math.floor(v) + 0.5;
    }

    /**
     * True when {@code other} stands on the same TILE as this node, so the two fire together as one stack
     * ({@link Route#stackOf}). Nodes are placed on whole-block centres ({@link #snapBlockCentre}, via
     * {@code RouteRecorder.snappedFeet}), so "same tile" is "the same block": {@code floor} of x, of z and of the feet
     * height (with a thousandth of slack for the snapped height) are equal. A node saved on the old half-block grid
     * (x or z on a seam, {@code n.0}) counts as the block on its +x / +z side until it is next moved, which snaps it.
     * Compared in ROOM-RELATIVE coordinates, as stored, so the answer is the same in every rotation. An equivalence:
     * every node of a stack agrees on who is in it.
     */
    public boolean sameTile(RouteNode other) {
        return other != null && Math.floor(x) == Math.floor(other.x) && Math.floor(z) == Math.floor(other.z)
                && Math.floor(y + 0.001) == Math.floor(other.y + 0.001);
    }

    /**
     * Where this node fires within a stack of nodes on one tile (killer560, 2026-10-04, approved order): breaker,
     * superboom, use, command, rotate, unsneak, etherwarp, walk - the things that need you standing still and
     * aimed first, the things that move you last. The two legacy types (only ever seen in a file that has not been
     * migrated yet): a standalone {@link Type#AWAIT} is a wait, so it goes first and holds everything after it; a
     * {@link Type#START} marker fires nothing, so it goes first too and costs nothing.
     */
    public int stackRank() {
        return switch (type) {
            case AWAIT, START -> 0;
            case DUNGEON_BREAKER -> 1;
            case BOOM -> 2;
            case USE_ITEM -> 3;
            case COMMAND -> 4;
            case ROTATE -> 5;
            case UNSNEAK -> 6;
            // A path is etherwarps, and fires in the etherwarp slot (killer560, 2026-10-05: "have the same priority
            // of an etherwarp in the ranking").
            case ETHERWARP, PATH -> 7;
            case WALK -> 8;
        };
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
            case COMMAND -> sb.append(" [").append(command == null ? "" : command).append(']');
            default -> {
            }
        }
        if (type == Type.PATH && !pathHops.isEmpty()) {
            sb.append(" [").append(pathHops.size()).append(" warp(s) saved]");
        }
        sb.append(modifierTag());
        sb.append(String.format(Locale.US, " @ %.1f, %.1f, %.1f", x, y, z));
        return sb.toString();
    }

    /** " [start]" / " [await ...]" / " [start, await ...]" - the {@code start} / {@code awaitEnabled} modifiers
     *  (see their fields above), shared by {@link #describe()} and the world label so both read the same tags. */
    public String modifierTag() {
        if (!start && !awaitEnabled) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" [");
        if (start) {
            sb.append("start");
        }
        if (awaitEnabled) {
            if (start) {
                sb.append(", ");
            }
            sb.append("await ").append(awaitCondition.name().toLowerCase(Locale.ROOT)).append(' ')
                    .append(awaitAmount).append(awaitCondition == AwaitCondition.DELAY ? "ms" : "");
        }
        sb.append(']');
        return sb.toString();
    }

    /** Decimal places every saved coordinate, angle and landing keeps in the routes file - AP3's figure. */
    public static final int SAVED_DECIMALS = 6;

    /** {@code v} as the routes file will hold it. */
    public static double roundSaved(double v) {
        double f = Math.pow(10, SAVED_DECIMALS);
        return Math.round(v * f) / f;
    }

    /**
     * Rounds this node to exactly what the file will hold (killer560, 2026-10-05: "make both go to 6 decimals ...
     * if I place a node then hit it again without reloading it is still that truncated value"), so a node placed or
     * edited behaves the same now as after {@code /ar reload}. Called wherever a node gets new values; the store
     * writes through {@link #roundSaved} too, so the two always agree.
     */
    public void roundToSaved() {
        x = roundSaved(x);
        y = roundSaved(y);
        z = roundSaved(z);
        yaw = (float) roundSaved(yaw);
        pitch = (float) roundSaved(pitch);
        if (hasLanding) {
            landingX = roundSaved(landingX);
            landingY = roundSaved(landingY);
            landingZ = roundSaved(landingZ);
        }
        planFromX = roundSaved(planFromX);
        planFromY = roundSaved(planFromY);
        planFromZ = roundSaved(planFromZ);
        planToX = roundSaved(planToX);
        planToY = roundSaved(planToY);
        planToZ = roundSaved(planToZ);
        for (int i = 0; i < pathHops.size(); i++) {
            PathHop h = pathHops.get(i);
            pathHops.set(i, new PathHop(roundSaved(h.ox()), roundSaved(h.oy()), roundSaved(h.oz()),
                    (float) roundSaved(h.yaw()), (float) roundSaved(h.pitch()), h.target(), roundSaved(h.lx()),
                    roundSaved(h.ly()), roundSaved(h.lz())));
        }
    }

    public RouteNode copy() {
        RouteNode n = new RouteNode();
        n.copyFrom(this);
        return n;
    }

    /** Overwrites every field of this node with {@code o}'s, keeping this object's identity - the node editor and
     *  its undo change a node IN PLACE, because {@link RouteHistory} and the executor hold the live object. */
    public void copyFrom(RouteNode o) {
        type = o.type;
        x = o.x;
        y = o.y;
        z = o.z;
        yaw = o.yaw;
        pitch = o.pitch;
        pathIndex = o.pathIndex;
        radius = o.radius;
        colour = o.colour;
        start = o.start;
        awaitEnabled = o.awaitEnabled;
        if (o != this) {
            breakerBlocks.clear();
            breakerBlocks.addAll(o.breakerBlocks);
        }
        item = o.item;
        awaitCondition = o.awaitCondition;
        awaitAmount = o.awaitAmount;
        command = o.command;
        landingX = o.landingX;
        landingY = o.landingY;
        landingZ = o.landingZ;
        hasLanding = o.hasLanding;
        if (o != this) {
            pathHops.clear();
            pathHops.addAll(o.pathHops);
        }
        planFromX = o.planFromX;
        planFromY = o.planFromY;
        planFromZ = o.planFromZ;
        planToX = o.planToX;
        planToY = o.planToY;
        planToZ = o.planToZ;
    }

    /** True when every stored field matches {@code o}'s - the editor's "nothing changed, push no undo entry". */
    public boolean sameData(RouteNode o) {
        return o != null && type == o.type && x == o.x && y == o.y && z == o.z && yaw == o.yaw && pitch == o.pitch
                && pathIndex == o.pathIndex && radius == o.radius && java.util.Objects.equals(colour, o.colour)
                && start == o.start && awaitEnabled == o.awaitEnabled && breakerBlocks.equals(o.breakerBlocks)
                && java.util.Objects.equals(item, o.item) && awaitCondition == o.awaitCondition
                && awaitAmount == o.awaitAmount && java.util.Objects.equals(command, o.command)
                && landingX == o.landingX && landingY == o.landingY && landingZ == o.landingZ
                && hasLanding == o.hasLanding && pathHops.equals(o.pathHops)
                && planFromX == o.planFromX && planFromY == o.planFromY && planFromZ == o.planFromZ
                && planToX == o.planToX && planToY == o.planToY && planToZ == o.planToZ;
    }
}
