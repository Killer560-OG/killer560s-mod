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

    /** One secret of his this run. {@code tick} is {@link AwaitEvents#now()} when it was got. */
    record Got(String key, Vec3 pos, int tick, String what) {
    }

    private static final Map<String, Got> got = new LinkedHashMap<>();
    /** Secrets a met await has used: never counted for a second node. */
    private static final Set<String> claimed = new HashSet<>();
    /** Nodes whose await (or bat node's kill) was met this run, by {@link #nodeKey}. */
    private static final Set<String> metNodes = new HashSet<>();
    private static final Set<String> routedRooms = new HashSet<>();

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
            level = client.level;
            generation = gen;
            clearAll();
            if (had) {
                LOGGER.info("[AutoRoutes] run memory cleared (new run)");
            }
        }
    }

    private static void clearAll() {
        got.clear();
        claimed.clear();
        metNodes.clear();
        routedRooms.clear();
    }

    /** For the testkit: forget the run, as a new run would. */
    public static void reset() {
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
            got.put(key, new Got(key, pos, AwaitEvents.now(), what));
        }
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
     * no met await and not among {@code exclude}: what the node before this one picked up on its way in, just before
     * this node's window opened ({@link AwaitEvents#CARRY_TICKS}).
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

    /** A node's await was met by {@code keys} (its window's secrets plus any remembered ones). */
    static void met(String nodeKey, java.util.Collection<String> keys) {
        check();
        metNodes.add(nodeKey);
        claimed.addAll(keys);
    }

    // ------------------------------------------------------------------------------------------- rooms

    /** A route ran to its end in {@code room}. */
    static void routeCompleted(String room) {
        check();
        if (room != null && routedRooms.add(room)) {
            LOGGER.info("[AutoRoutes] run memory: {}'s route ran to the end - its secrets count as done this run", room);
        }
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

    /** For the testkit: what is remembered, in one line. */
    public static String describe() {
        check();
        return "secrets " + got.keySet() + ", claimed " + claimed + ", met nodes " + metNodes + ", routed rooms "
                + routedRooms;
    }

    /** For the testkit: mark {@code room} as having had its route run, as {@link #routeCompleted} does. */
    public static void testMarkRouted(String room) {
        routeCompleted(room);
    }
}
