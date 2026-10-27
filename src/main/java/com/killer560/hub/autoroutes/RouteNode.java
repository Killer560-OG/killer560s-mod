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
     * enum only so {@link RouteStore} can still recognize {@code "type": "START"} / {@code "AWAIT"} in an old
     * routes file and fold it onto the modifier fields on load ({@code RouteStore#migrateLegacyMarkers}) instead
     * of silently dropping someone's saved route.
     */
    public enum Type {
        START, WALK, ETHERWARP, USE_ITEM, DUNGEON_BREAKER, BOOM, AWAIT, ROTATE, UNSNEAK, COMMAND,
        /** killer560, 2026-10-05: "put two nodes call them path ... it will pathfind from the first to the last via
         *  the etherwarping style our interactive map uses". Path nodes pair up in number order (1st with 2nd, 3rd
         *  with 4th): the first of a pair warps along its saved {@link #pathHops} to the second; the second is the
         *  arrival and does nothing itself. See {@link RoutePathPlanner}. */
        PATH,
        /** killer560, 2026-10-05: "add a crypt node ... it will do the same attacking thing till either a prince or
         *  crypt are killed". Aimed where he looked when placing it (the crypt), it uses the Crypt Weapon setting's
         *  Hyperion or Spirit Sceptre until one crypt undead or prince of HIS is killed - or {@code await:N} of them. */
        CRYPT,
        /** killer560, 2026-10-13: "add a bat node. This functions just like crypts ... but is for bats". A crypt node for
         *  secret bats: the Bat Weapon setting's Hyperion or Spirit Sceptre, straight down, until one secret bat dies
         *  near him after a use of his - or {@code await:N} of them. */
        BAT,
        /** killer560, 2026-10-09: "Add a jump node to auto routes"; 2026-10-12: "you have to type in jump _. That number
         *  is how many times it will jump". {@link #jumpCount} presses of the jump key, one tick each, each from the
         *  ground, then done. */
        JUMP,
        /** killer560, 2026-10-20: "for auto routes I need an align node that I can do W2 L2 on". AP3's ALIGN: its
         *  trigger is a {@link #alignWidth} x {@link #alignLength} box ({@code w2 l2}, as {@code /ap3 add align w2 l2}),
         *  and when it fires AP3's camera-method align brings him to rest on the node's point (the block center). */
        ALIGN;

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
                case "crypt" -> CRYPT;
                case "bat" -> BAT;
                case "jump" -> JUMP;
                case "align" -> ALIGN;
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
                case CRYPT -> "Crypt";
                case BAT -> "Bat";
                case JUMP -> "Jump";
                case ALIGN -> "Align";
            };
        }
    }

    /** What an {@link Type#AWAIT} node waits for. */
    public enum AwaitCondition {
        /** {@link #awaitAmount} secrets HE got since the wait began - his clicks on chests/levers/skulls, his item pickups, secret bats appearing near him ({@link AwaitEvents}). */
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
    /** Optional per-node ARGB color, or null to use the type / uniform color from the settings. */
    public Integer colour;

    // ---- modifiers (killer560, 2026-09-2x: "/ar add etherwarp start await:2" - any node can carry either, or
    //      both, of these; see the Type enum doc's note on START / AWAIT being legacy-only now) ----
    /** This is the route's start node - the one {@code /ar start record} etherwarps back onto to arm playback.
     *  At most one node per route should have this set; {@link RouteRecorder#addNode} clears it off whichever
     *  node had it before setting it on a new one. */
    public boolean start;
    /** True when this node waits for {@link #awaitCondition} / {@link #awaitAmount} to be satisfied BEFORE it
     *  fires (the old {@code Type.AWAIT} node's behavior, now a modifier instead of a separate node in the
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
    /** Fewest and most jumps a {@link Type#JUMP} node takes ({@code /ar add jump <n>}). */
    public static final int MIN_JUMPS = 1;
    public static final int MAX_JUMPS = 20;
    /** {@link Type#JUMP}: how many times it jumps. A node saved before the count existed loads as 1. */
    public int jumpCount = 1;

    /** {@code n} as a jump count, or -1 when it is not one ({@value #MIN_JUMPS}..{@value #MAX_JUMPS}). */
    public static int parseJumps(String s) {
        if (s == null || !s.trim().matches("\\d{1,2}")) {
            return -1;
        }
        int n = Integer.parseInt(s.trim());
        return n >= MIN_JUMPS && n <= MAX_JUMPS ? n : -1;
    }

    /** Smallest and largest side of an {@link Type#ALIGN} box, in blocks - AP3's {@code Ap3Node.MIN_WIDTH}/{@code MAX_WIDTH}. */
    public static final double MIN_ALIGN_SIZE = com.killer560.hub.ap3.Ap3Node.MIN_WIDTH;
    public static final double MAX_ALIGN_SIZE = com.killer560.hub.ap3.Ap3Node.MAX_WIDTH;
    public static final double DEFAULT_ALIGN_SIZE = 1.0;
    /** {@link Type#ALIGN}: the trigger box, width along the room's x and length along its z (room-relative, so a turned
     *  room turns the box with it). Every other type keeps its {@link #radius} ring and ignores these. */
    public double alignWidth = DEFAULT_ALIGN_SIZE;
    public double alignLength = DEFAULT_ALIGN_SIZE;

    public static double clampAlignSize(double v) {
        if (Double.isNaN(v)) {
            return DEFAULT_ALIGN_SIZE;
        }
        return Math.max(MIN_ALIGN_SIZE, Math.min(MAX_ALIGN_SIZE, v));
    }

    private static final java.util.regex.Pattern SIZE = java.util.regex.Pattern.compile("\\d{1,3}(?:\\.\\d{1,3})?|\\.\\d{1,3}");

    /**
     * One word of {@code /ar add align ...}, in AP3's spellings ({@code Ap3Commands.parseSpec}): {@code w2} / {@code l2},
     * {@code width:2} / {@code length:2} / {@code len:2}, or {@code 2x3}, any case. Writes {@code box[0]} (width) and/or
     * {@code box[1]} (length). @return 1 when it was a box word, 0 when it is not one (a modifier such as start), -1 when
     * it is a box word whose size is outside {@value #MIN_ALIGN_SIZE}..{@value #MAX_ALIGN_SIZE}.
     */
    public static int parseAlignToken(String raw, double[] box) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        String w = null;
        String l = null;
        if (t.startsWith("width:") || t.startsWith("width=")) {
            w = t.substring(6);
        } else if (t.startsWith("length:") || t.startsWith("length=")) {
            l = t.substring(7);
        } else if (t.startsWith("len:")) {
            l = t.substring(4);
        } else if (t.length() > 1 && t.charAt(0) == 'w' && SIZE.matcher(t.substring(1)).matches()) {
            w = t.substring(1);
        } else if (t.length() > 1 && t.charAt(0) == 'l' && SIZE.matcher(t.substring(1)).matches()) {
            l = t.substring(1);
        } else if (t.indexOf('x') > 0) {
            String[] wl = t.split("x", 2);
            if (SIZE.matcher(wl[0]).matches() && SIZE.matcher(wl[1]).matches()) {
                w = wl[0];
                l = wl[1];
            }
        }
        if (w == null && l == null) {
            return 0;
        }
        if ((w != null && !SIZE.matcher(w).matches()) || (l != null && !SIZE.matcher(l).matches())) {
            return -1;
        }
        double wv = w == null ? box[0] : Double.parseDouble(w);
        double lv = l == null ? box[1] : Double.parseDouble(l);
        if (wv < MIN_ALIGN_SIZE || wv > MAX_ALIGN_SIZE || lv < MIN_ALIGN_SIZE || lv > MAX_ALIGN_SIZE) {
            return -1;
        }
        box[0] = wv;
        box[1] = lv;
        return 1;
    }

    /** Half the trigger box along REAL x in a room turned {@code rotation} degrees: a quarter turn swaps the two sides. */
    public double halfRealX(int rotation) {
        if (type != Type.ALIGN) {
            return Math.max(0.1, radius) / 2.0;
        }
        return Math.max(0.05, (quarterTurned(rotation) ? alignLength : alignWidth) / 2.0);
    }

    /** Half the trigger box along REAL z (see {@link #halfRealX}). */
    public double halfRealZ(int rotation) {
        if (type != Type.ALIGN) {
            return Math.max(0.1, radius) / 2.0;
        }
        return Math.max(0.05, (quarterTurned(rotation) ? alignWidth : alignLength) / 2.0);
    }

    private static boolean quarterTurned(int rotation) {
        int r = ((rotation % 360) + 360) % 360;
        return r == 90 || r == 270;
    }

    /** {@link Type#COMMAND}: full command line, with or without the leading slash. */
    public String command;
    /** {@link Type#ETHERWARP} / teleporting {@link Type#USE_ITEM}: where the recording landed (room-relative),
     *  used as the "the teleport really happened" confirmation. {@link #hasLanding} says whether it was captured. */
    public double landingX;
    public double landingY;
    public double landingZ;
    public boolean hasLanding;
    /** {@link Type#ETHERWARP}: the eye (room-relative) the landing was seen from when the node was placed - the line
     *  from here along {@link #yaw}/{@link #pitch} to the landing block was CLEAR then. A block standing on that line
     *  at playback was not there when the node was placed, and the warp waits for it to move (killer560, 2026-10-09).
     *  {@link #hasPlaceEye} false (a node saved before this) waits for nothing, as before. */
    public double placeEyeX;
    public double placeEyeY;
    public double placeEyeZ;
    public boolean hasPlaceEye;

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
    /** {@link RoutePathPlanner#breakerStamp} when the pair was planned: when the route's breaker blocks before the pair
     *  change, the saved plan predates them (it is NOT re-planned on its own - /ar replan; chat says so). */
    public int planBreakers;

    /**
     * One saved warp of a path: stand at {@code (ox, oy, oz)}, look along {@code yaw}/{@code pitch} (relative, as a
     * node's look is) and land on block {@code target}, feet at {@code (lx, ly, lz)}. All room-relative.
     */
    public record PathHop(double ox, double oy, double oz, float yaw, float pitch, BlockPos target,
                          double lx, double ly, double lz) {
    }

    /**
     * {@link Type#PATH} in a trap room, on the FIRST node of a pair: the walk AP3 planned to the second node (killer560,
     * 2026-10-20: saved with the route like the warps above, so a later run plays it instead of planning from nothing).
     * One step per tick: keys, the yaw to face (room-relative, as a node's look is), jump and sprint. It was planned from
     * {@link #walkFromX}.. moving at {@link #walkFromVX}.. (room-relative), with this node at {@link #walkSrcX}.. and the
     * second at {@link #walkToX}..: a node moved since makes it stale, and a start too far from walkFrom plans afresh.
     */
    public final List<WalkStep> walkSteps = new ArrayList<>();
    public double walkFromX;
    public double walkFromY;
    public double walkFromZ;
    public double walkFromVX;
    public double walkFromVY;
    public double walkFromVZ;
    public boolean walkFromGround;
    public double walkSrcX;
    public double walkSrcY;
    public double walkSrcZ;
    public double walkToX;
    public double walkToY;
    public double walkToZ;

    /** One tick of a saved trap walk: {@code fw}/{@code st} in -1..1, room-relative {@code yaw}. */
    public record WalkStep(int fw, int st, boolean sneak, float yaw, boolean jump, boolean sprint) {
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
    /** Per-node ARGB color override, or null. */
    public Integer colour() { return colour; }
    public List<BlockPos> breakerBlocks() { return breakerBlocks; }
    public String item() { return item; }
    public AwaitCondition awaitCondition() { return awaitCondition; }
    public int awaitAmount() { return awaitAmount; }
    public String command() { return command; }

    public Vec3 relativePos() {
        return new Vec3(x, y, z);
    }

    public void setPlaceEye(Vec3 relative) {
        placeEyeX = relative.x;
        placeEyeY = relative.y;
        placeEyeZ = relative.z;
        hasPlaceEye = true;
    }

    public void setLanding(Vec3 relative) {
        landingX = relative.x;
        landingY = relative.y;
        landingZ = relative.z;
        hasLanding = true;
    }

    /** QUOI {@code RouteRing.boundingBox}: a flat ring-shaped box around the node's REAL position. An {@link Type#ALIGN}
     *  node's box is its width x length instead, turned with the room ({@code rotation}, the frame's). */
    public AABB boundingBox(Vec3 real, double height, int rotation) {
        double hx = halfRealX(rotation);
        double hz = halfRealZ(rotation);
        return new AABB(real.x - hx, real.y, real.z - hz, real.x + hx, real.y + Math.max(0.1, height), real.z + hz);
    }

    /** {@link #boundingBox(Vec3, double, int)} in {@code frame}'s room (rotation 0 with no frame). */
    public AABB boundingBox(RouteCoords.Frame frame, Vec3 real, double height) {
        return boundingBox(real, height, frame == null ? 0 : frame.rotation());
    }

    /** True when {@code playerBox} (the player's own bounding box) overlaps this node's ring. */
    public boolean contains(RouteCoords.Frame frame, Vec3 real, double height, AABB playerBox) {
        return boundingBox(frame, real, height).intersects(playerBox);
    }

    /**
     * {@code contains(RouteCoords.toReal(frame, relativePos()), height, playerBox)} with no Vec3, array or AABB: the
     * same arithmetic in the same order ({@link #boundingBox}'s corners through AABB's min/max, then
     * {@code AABB.intersects}), for the idle arm test that runs it for every node of the room every tick.
     */
    public boolean containsIn(RouteCoords.Frame frame, double height, AABB p) {
        double rx = RouteCoords.realX(frame, x, z);
        double ry = RouteCoords.realY(y);
        double rz = RouteCoords.realZ(frame, x, z);
        double hx = halfRealX(frame.rotation());
        double hz = halfRealZ(frame.rotation());
        double x1 = rx - hx;
        double x2 = rx + hx;
        double y2 = ry + Math.max(0.1, height);
        double z1 = rz - hz;
        double z2 = rz + hz;
        double minX = Math.min(x1, x2);
        double minY = Math.min(ry, y2);
        double minZ = Math.min(z1, z2);
        double maxX = Math.max(x1, x2);
        double maxY = Math.max(ry, y2);
        double maxZ = Math.max(z1, z2);
        return minX < p.maxX && maxX > p.minX && minY < p.maxY && maxY > p.minY && minZ < p.maxZ && maxZ > p.minZ;
    }

    /**
     * The center of the whole block {@code v} is in: {@code floor(v) + 0.5} (killer560, 2026-10-04: "Make it so nodes
     * need to snap to a whole block for the autoroutes"). Auto Routes nodes used AP3's half-block grid
     * ({@code Ap3Node.snapCentre}) until then; AP3 keeps it. Snapped in WORLD space and then made room-relative: a room
     * rotation is a quarter turn about an integer clay corner, which maps block centers onto block centers.
     */
    public static double snapBlockCentre(double v) {
        return Math.floor(v) + 0.5;
    }

    /**
     * True when {@code other} stands on the same TILE as this node, so the two fire together as one stack
     * ({@link Route#stackOf}). Nodes are placed on whole-block centers ({@link #snapBlockCentre}, via
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

    /** {@link #stackRank} of the first types that move him off the tile (etherwarp and path). */
    public static final int MOVER_RANK = 9;

    /**
     * Where this node fires within a stack of nodes on one tile (killer560, 2026-10-04, approved order): breaker,
     * superboom, crypt, use, command, rotate, unsneak, etherwarp, walk - the things that need you standing still and
     * aimed first, the things that move you last. The two legacy types (only ever seen in a file that has not been
     * migrated yet): a standalone {@link Type#AWAIT} is a wait, so it goes first and holds everything after it; a
     * {@link Type#START} marker fires nothing, so it goes first too and costs nothing.
     */
    public int stackRank() {
        return switch (type) {
            case AWAIT, START -> 0;
            // An align puts him on the point everything else on the tile then acts from (AP3: align, then act).
            case ALIGN -> 1;
            case DUNGEON_BREAKER -> 2;
            case BOOM -> 3;
            // Right after the superboom that opens the crypt (killer560, 2026-10-05: "The crypt node should go after
            // superboom nodes").
            case CRYPT, BAT -> 4;
            case USE_ITEM -> 5;
            case COMMAND -> 6;
            case ROTATE -> 7;
            case UNSNEAK -> 8;
            // A path is etherwarps, and fires in the etherwarp slot (killer560, 2026-10-05: "have the same priority
            // of an etherwarp in the ranking").
            case ETHERWARP, PATH -> MOVER_RANK;
            // A jump moves you, like a walk; ahead of the walk so a jump-and-sprint stack leaves the ground first.
            case JUMP -> 10;
            case WALK -> 11;
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
            case USE_ITEM -> sb.append(" [").append(item == null ? "empty hand - clicks the block" : item).append(']');
            case DUNGEON_BREAKER -> sb.append(" [").append(breakerBlocks.size()).append(" block(s)]");
            case COMMAND -> sb.append(" [").append(command == null ? "" : command).append(']');
            case JUMP -> sb.append(" [").append(jumpCount).append(jumpCount == 1 ? " jump]" : " jumps]");
            case ALIGN -> sb.append(" [W").append(fmtSize(alignWidth)).append(" L").append(fmtSize(alignLength)).append(']');
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

    /** An align box side as typed: "2", "2.5". */
    public static String fmtSize(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.2f", v).replaceAll("0+$", "");
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
        if (hasPlaceEye) {
            placeEyeX = roundSaved(placeEyeX);
            placeEyeY = roundSaved(placeEyeY);
            placeEyeZ = roundSaved(placeEyeZ);
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
        jumpCount = o.jumpCount;
        alignWidth = o.alignWidth;
        alignLength = o.alignLength;
        landingX = o.landingX;
        landingY = o.landingY;
        landingZ = o.landingZ;
        hasLanding = o.hasLanding;
        placeEyeX = o.placeEyeX;
        placeEyeY = o.placeEyeY;
        placeEyeZ = o.placeEyeZ;
        hasPlaceEye = o.hasPlaceEye;
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
        planBreakers = o.planBreakers;
        if (o != this) {
            walkSteps.clear();
            walkSteps.addAll(o.walkSteps);
        }
        walkFromX = o.walkFromX;
        walkFromY = o.walkFromY;
        walkFromZ = o.walkFromZ;
        walkFromVX = o.walkFromVX;
        walkFromVY = o.walkFromVY;
        walkFromVZ = o.walkFromVZ;
        walkFromGround = o.walkFromGround;
        walkSrcX = o.walkSrcX;
        walkSrcY = o.walkSrcY;
        walkSrcZ = o.walkSrcZ;
        walkToX = o.walkToX;
        walkToY = o.walkToY;
        walkToZ = o.walkToZ;
    }

    /** True when every stored field matches {@code o}'s - the editor's "nothing changed, push no undo entry". */
    public boolean sameData(RouteNode o) {
        return o != null && type == o.type && x == o.x && y == o.y && z == o.z && yaw == o.yaw && pitch == o.pitch
                && pathIndex == o.pathIndex && radius == o.radius && java.util.Objects.equals(colour, o.colour)
                && start == o.start && awaitEnabled == o.awaitEnabled && breakerBlocks.equals(o.breakerBlocks)
                && java.util.Objects.equals(item, o.item) && awaitCondition == o.awaitCondition
                && awaitAmount == o.awaitAmount && java.util.Objects.equals(command, o.command)
                && jumpCount == o.jumpCount && alignWidth == o.alignWidth && alignLength == o.alignLength
                && landingX == o.landingX && landingY == o.landingY && landingZ == o.landingZ
                && hasLanding == o.hasLanding && pathHops.equals(o.pathHops)
                && hasPlaceEye == o.hasPlaceEye && placeEyeX == o.placeEyeX && placeEyeY == o.placeEyeY
                && placeEyeZ == o.placeEyeZ
                && planFromX == o.planFromX && planFromY == o.planFromY && planFromZ == o.planFromZ
                && planToX == o.planToX && planToY == o.planToY && planToZ == o.planToZ
                && walkSteps.equals(o.walkSteps) && walkFromX == o.walkFromX && walkFromY == o.walkFromY
                && walkFromZ == o.walkFromZ && walkFromVX == o.walkFromVX && walkFromVY == o.walkFromVY
                && walkFromVZ == o.walkFromVZ && walkFromGround == o.walkFromGround && walkSrcX == o.walkSrcX
                && walkSrcY == o.walkSrcY && walkSrcZ == o.walkSrcZ && walkToX == o.walkToX && walkToY == o.walkToY
                && walkToZ == o.walkToZ;
    }
}
