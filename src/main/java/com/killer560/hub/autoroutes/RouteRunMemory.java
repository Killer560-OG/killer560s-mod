package com.killer560.hub.autoroutes;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What Auto Routes remembers for the rest of one dungeon run (killer560, 2026-10-14):
 * <ul>
 *   <li><b>His secrets</b> - every chest, lever and skull he clicked, item he picked up, secret bat that appeared beside
 *   him and mimic he killed ({@link AwaitEvents} reports them, route or no route), with where it was. "have it remember
 *   my auto routes in a sense so if it remembers I already got that secret or that bat then it will not wait for the
 *   await before doing that action."</li>
 *   <li><b>Which nodes' awaits were met</b>, and with which secrets. A node whose await was met earlier this run is met
 *   at once when the route reaches it again; a node reached for the first time also counts a secret he already got
 *   within {@link #NEAR_NODE} blocks of it, before this route run started, that no met await has used (he took it by
 *   hand, or a stopped route left it behind).</li>
 *   <li><b>Rooms whose route ran to the end</b> - Auto Secret and Dungeon Autopilot treat such a room as fully
 *   secreted: "if i have already used auto routes in a room during auto clear then it should treat it as though I got
 *   every secret in the room even if it is missing one".</li>
 * </ul>
 * A run is the live map's reset generation in one world ({@link LiveMapFeature#resetGeneration}, bumped by a world
 * change, entering a dungeon and a sim floor being published), as Secret Aura's done-set is.
 */
public final class RouteRunMemory {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoroutes");

    /** A remembered secret this close to a node (blocks, node to secret) can meet that node's await. Block reach is
     *  4.5 from the eye, so a secret taken from the node's own spot is well inside; the next node's spot rarely is. */
    public static final double NEAR_NODE = 6.0;

    /** One secret of his this run. {@code tick} is {@link AwaitEvents#now()} when it was got; {@code room} is the
     *  identified room it was got in ({@link LiveMapFeature#roomNameAt}), or null when the map did not know. */
    record Got(String key, Vec3 pos, int tick, String what, String room) {
        Got(String key, Vec3 pos, int tick, String what) {
            this(key, pos, tick, what, pos == null ? null : LiveMapFeature.roomNameAt(pos.x, pos.z));
        }
    }

    private static final Map<String, Got> got = new LinkedHashMap<>();
    /** Secrets a met await has used: never counted for a second node. */
    private static final Set<String> claimed = new HashSet<>();
    /** Nodes whose await (or bat node's kill) was met this run, by {@link #nodeKey}. */
    private static final Set<String> metNodes = new HashSet<>();
    private static final Set<String> routedRooms = new HashSet<>();
    /** Boom nodes that blew blocks this run, by {@link #boomKey}. */
    private static final Set<String> boomedNodes = new HashSet<>();
    /** How far each room's route got this run: {nodes done, nodes in the route}, kept after a stop (the HUD's percent). */
    private static final Map<String, int[]> progress = new LinkedHashMap<>();

    private static Object level;
    private static int generation = Integer.MIN_VALUE;

    private RouteRunMemory() {
    }

    /** Forgets everything when the run changed. Called before every read and write. */
    private static void check() {
        Minecraft client = Minecraft.getInstance();
        int gen = LiveMapFeature.resetGeneration();
        if (client.level != level || gen != generation) {
            boolean had = !got.isEmpty() || !metNodes.isEmpty() || !routedRooms.isEmpty();
            boolean levelChanged = client.level != level;
            int oldGen = generation;
            int gotCount = got.size();
            int metCount = metNodes.size();
            int roomCount = routedRooms.size();
            level = client.level;
            generation = gen;
            clearAll();
            if (had) {
                LOGGER.info("[AutoRoutes] run memory cleared (new run)");
            }
            LOGGER.info("[RouteMemory] new run: generation {} -> {}, level changed={}, dropped {} secret(s), {} met node(s),"
                    + " {} routed room(s)", oldGen, gen, levelChanged, gotCount, metCount, roomCount);
        }
    }

    private static void clearAll() {
        got.clear();
        claimed.clear();
        metNodes.clear();
        routedRooms.clear();
        progress.clear();
        boomedNodes.clear();
    }

    /** For the testkit: forget the run, as a new run would. */
    public static void reset() {
        LOGGER.info("[RouteMemory] reset by the testkit");
        clearAll();
        Minecraft client = Minecraft.getInstance();
        level = client.level;
        generation = LiveMapFeature.resetGeneration();
    }

    // ------------------------------------------------------------------------------------------- secrets

    /** A secret of his, by {@link AwaitEvents}' key ("block 123", "item 45", "bat 67", "mimic 89"). Once per key. */
    static void gotSecret(String key, Vec3 pos, String what) {
        check();
        if (pos != null && !got.containsKey(key)) {
            // Whoever took it - a route node, Secret Aura, Auto Clear's run, his own hand, a walk over an item - it is
            // remembered here, so a route that reaches this room later counts it instead of waiting for it.
            Got g = new Got(key, pos, AwaitEvents.now(), what);
            got.put(key, g);
            LiveMapFeature.noteSecretTakenAt(pos.x, pos.z);
            LOGGER.info("[RouteMemory] remembered secret {} [{}] at {} in room {} tick {} ({} this run; route running={},"
                            + " Auto Clear running={})", what, key,
                    String.format(Locale.ROOT, "%.1f,%.1f,%.1f", pos.x, pos.y, pos.z), g.room() == null ? "?" : g.room(),
                    AwaitEvents.now(), got.size(), RouteExecutor.isRunning(),
                    com.killer560.hub.autoclear.AutoClearFeature.isBusy());
            if (what != null && what.startsWith("chest")) {
                // killer560's rule: a mob room whose secret chest Auto Clear or Auto Secret grabbed counts as cleared a few
                // seconds later (and may be come back to once if the map still disagrees 30 s on).
                com.killer560.hub.autoclear.ChestClearRule.noteChest(pos, what);
            }
        } else {
            LOGGER.info("[RouteMemory] secret {} [{}] not remembered again: {}", what, key,
                    pos == null ? "no position" : "already remembered this run");
        }
    }

    /** Whether a secret under this key ("bat 67") was already got this run. */
    static boolean has(String key) {
        check();
        return got.containsKey(key);
    }

    /** The tick count secrets are stamped with ({@link AwaitEvents#now()}), for {@link #gotSince}. */
    public static int clock() {
        return AwaitEvents.now();
    }

    /**
     * His secrets got at or after {@code sinceTick} ({@link #clock}) where {@code where} holds - Auto Secret counts what a
     * route took in its room this way, on the tick it was taken, instead of waiting for the action bar's next "x/y".
     */
    public static int gotSince(int sinceTick, java.util.function.Predicate<Vec3> where) {
        check();
        int n = 0;
        for (Got g : got.values()) {
            if (g.tick() >= sinceTick && g.pos() != null && where.test(g.pos())) {
                n++;
            }
        }
        return n;
    }

    /**
     * {@link #gotSince} without the levers: an await counts his lever flips (a route records one to wait on), but a lever
     * is not one of a room's secrets - not in the room database's count, not on Hypixel's "x/y Secrets" - so a room
     * count that took them in called a room done one secret early and Auto Secret left it (fullclear-1016: Museum
     * "5/5: map 4/5" three runs of three, its lever among the five).
     */
    public static int secretsSince(int sinceTick, java.util.function.Predicate<Vec3> where) {
        check();
        int n = 0;
        for (Got g : got.values()) {
            if (g.tick() >= sinceTick && g.pos() != null && !isLever(g) && where.test(g.pos())) {
                n++;
            }
        }
        return n;
    }

    /** What {@link #secretsSince} counts, for log lines. */
    public static List<String> secretsWhatSince(int sinceTick, java.util.function.Predicate<Vec3> where) {
        check();
        List<String> out = new ArrayList<>();
        for (Got g : got.values()) {
            if (g.tick() >= sinceTick && g.pos() != null && !isLever(g) && where.test(g.pos())) {
                out.add(g.what());
            }
        }
        return out;
    }

    private static boolean isLever(Got g) {
        return g.what() != null && g.what().startsWith("lever");
    }

    /** What {@link #gotSince} counts, one entry per secret ("chest at x, y, z"), for log lines. */
    public static List<String> whatSince(int sinceTick, java.util.function.Predicate<Vec3> where) {
        check();
        List<String> out = new ArrayList<>();
        for (Got g : got.values()) {
            if (g.tick() >= sinceTick && g.pos() != null && where.test(g.pos())) {
                out.add(g.what());
            }
        }
        return out;
    }

    /** Whether he got a secret of this kind ({@code "item"}, {@code "block"}, {@code "bat"}) within {@code range} blocks
     *  of {@code pos} this run. */
    public static boolean gotNear(String kind, Vec3 pos, double range) {
        check();
        String prefix = kind + " ";
        for (Got g : got.values()) {
            if (g.key().startsWith(prefix) && g.pos() != null && g.pos().distanceToSqr(pos) <= range * range) {
                return true;
            }
        }
        return false;
    }

    /** A stable name for a node within its room's route: room, type and its relative spot. */
    static String nodeKey(Route route, RouteNode node) {
        Vec3 p = node.relativePos();
        return String.format(Locale.US, "%s|%s|%.1f,%.1f,%.1f", route == null ? "?" : route.roomName(), node.type,
                p.x, p.y, p.z);
    }

    /** Whether this node's await (or bat node's kill) was met earlier this run. */
    static boolean metThisRun(String nodeKey) {
        check();
        return metNodes.contains(nodeKey);
    }

    /**
     * The secrets of this run that can meet a node's await on its first visit: got before {@code beforeTick}, within
     * {@link #NEAR_NODE} of {@code nodePos}, used by no met await, and not among {@code exclude} (the window's own).
     */
    static List<Got> unclaimedNear(Vec3 nodePos, int beforeTick, Set<String> exclude) {
        check();
        List<Got> out = new ArrayList<>();
        if (nodePos == null) {
            return out;
        }
        for (Got g : got.values()) {
            if (g.tick() < beforeTick && !claimed.contains(g.key()) && !exclude.contains(g.key())
                    && g.pos().distanceTo(nodePos) <= NEAR_NODE) {
                out.add(g);
            }
        }
        return out;
    }

    /**
     * The secrets of this run got at or after {@code sinceTick} within {@link #NEAR_NODE} of {@code nodePos}, used by
     * no met await and not among {@code exclude}: what the node before this one picked up since its window opened
     * ({@link AwaitEvents#windowStart}), whether on its way in or while it acted.
     */
    static List<Got> unclaimedNearSince(Vec3 nodePos, int sinceTick, Set<String> exclude) {
        check();
        List<Got> out = new ArrayList<>();
        if (nodePos == null) {
            return out;
        }
        for (Got g : got.values()) {
            if (g.tick() >= sinceTick && !claimed.contains(g.key()) && !exclude.contains(g.key())
                    && g.pos().distanceTo(nodePos) <= NEAR_NODE) {
                out.add(g);
            }
        }
        return out;
    }

    /** A boom node's key: {@link #nodeKey} plus its look, since the booms of one stack share a tile. */
    static String boomKey(Route route, RouteNode node) {
        return nodeKey(route, node) + String.format(Locale.US, "|%.2f/%.2f", node.yaw, node.pitch);
    }

    /** A boom node blew blocks this run ({@link #boomKey}): what it opens stays open for the rest of the run. */
    static void boomed(String key) {
        check();
        if (boomedNodes.add(key)) {
            LOGGER.info("[RouteMemory] boom {} blew its wall this run - not sent again this run", key);
        }
    }

    /** Whether this boom node already blew blocks this run. */
    static boolean boomedThisRun(String key) {
        check();
        return boomedNodes.contains(key);
    }

    /** A node's await was met by {@code keys} (its window's secrets plus any remembered ones). */
    static void met(String nodeKey, java.util.Collection<String> keys) {
        check();
        boolean newNode = metNodes.add(nodeKey);
        claimed.addAll(keys);
        LOGGER.info("[RouteMemory] await met for {} ({}) with {} - {} secret(s) claimed this run", nodeKey,
                newNode ? "first time" : "again", keys, claimed.size());
    }

    // ------------------------------------------------------------------------------------------- rooms

    /** A route ran to its end in {@code room}. */
    static void routeCompleted(String room) {
        check();
        if (room != null && routedRooms.add(room)) {
            LOGGER.info("[AutoRoutes] run memory: {}'s route ran to the end - its secrets count as done this run", room);
        }
    }

    /** The route in {@code room} has finished {@code done} of its {@code total} nodes (the executor reports every tick). */
    public static void noteProgress(String room, int done, int total) {
        check();
        if (room == null || total <= 0) {
            return;
        }
        int[] p = progress.computeIfAbsent(room, k -> new int[2]);
        p[0] = Math.max(0, Math.min(total, done));
        p[1] = total;
    }

    /**
     * How far {@code room}'s route got this run as {nodes done, nodes in the route}, or null when it has not started.
     * A route that ran to its end is all done, whatever the last tick said.
     */
    public static int[] progressOf(String room) {
        check();
        int[] p = room == null ? null : progress.get(room);
        if (routedRooms.contains(room)) {
            int total = p != null ? p[1] : 1;
            return new int[]{total, total};
        }
        return p == null ? null : p.clone();
    }

    /** What the executor itself last reported for {@code room} this run, or null if it never has (a test hook: unlike
     *  {@link #progressOf} it does not turn a finished route into all-done). */
    public static int[] reportedProgress(String room) {
        check();
        int[] p = room == null ? null : progress.get(room);
        return p == null ? null : p.clone();
    }

    /** Whether a route ran to its end in {@code room} this run. */
    public static boolean routeCompletedThisRun(String room) {
        check();
        return routedRooms.contains(room);
    }

    /** Every room whose route ran to its end this run (a copy). */
    public static Set<String> routedRooms() {
        check();
        return new HashSet<>(routedRooms);
    }

    /** What was got this run in {@code room} (by {@link Got#room}), one entry per secret, for log lines and the testkit. */
    public static List<String> gotInRoom(String room) {
        check();
        List<String> out = new ArrayList<>();
        for (Got g : got.values()) {
            if (room != null && room.equals(g.room())) {
                out.add(g.what());
            }
        }
        return out;
    }

    /** For the testkit: what is remembered, in one line. */
    public static String describe() {
        check();
        List<String> secrets = new ArrayList<>();
        for (Got g : got.values()) {
            secrets.add(g.key() + " @" + (g.room() == null ? "?" : g.room()));
        }
        return "secrets " + secrets + ", claimed " + claimed + ", met nodes " + metNodes + ", routed rooms "
                + routedRooms;
    }

    /** For the testkit: mark {@code room} as having had its route run, as {@link #routeCompleted} does. */
    public static void testMarkRouted(String room) {
        routeCompleted(room);
    }
}
