package com.killer560.hub.autosecret;

import com.killer560.hub.autoclear.AutoClearFeature;
import com.killer560.hub.autopuzzles.AutoPuzzlesConfig;
import com.killer560.hub.autoroutes.AutoRoutesConfig;
import com.killer560.hub.autoroutes.Route;
import com.killer560.hub.autoroutes.RouteStore;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.RoomStatus;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.livemap.autoclear.DungeonMapPathfinder;
import com.killer560.hub.scorecalc.ScoreCalculator;
import com.killer560.hub.scorecalc.ScoreCalculatorFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dungeon Autopilot - CHEAT BUILD ONLY (killer560, 2026-10-06: "if I wanted to solo clear an entire dungeon, they would
 * be able to do it ... enter a run during clear and not have to touch my keyboard"). Auto Secret, Auto Clear and Auto
 * Puzzles under one planner. It owns no movement: {@link AutoSecretFeature} is the body (its map trips, its routes, its
 * hand-off to Auto Clear, its door clicks) and asks this class what to do each time it would decide. Each decision
 * re-reads the map and the score calculator, so a teammate's door, clear or secret changes the next choice at once.
 *
 * <h2>Each decision, in order</h2>
 * <ol>
 *   <li>A known insta clear (Auto Secret's own step - it is a clear for one warp).</li>
 *   <li><b>Blood First</b>, until the blood door is open: the uncleared rooms of the Blood Rush Split path in path order
 *       (Auto Clear), then the next locked door on that path - a wither door when the team has a key, the blood door
 *       when it is next. Without the key it does not wait: the rest of the floor is planned meanwhile, and the door is
 *       tried again each decision.</li>
 *   <li><b>Solo</b>: once the score calculator reads 300 it finishes. Otherwise the action with the best score per
 *       second ({@link AutopilotPlanner}) among: a route's secrets (only while the S+ still needs secrets), an Auto Clear
 *       of a mob room, a puzzle whose Auto Puzzles auto is on, a room the map has not identified yet.</li>
 *   <li><b>Party</b>: routes first; puzzles, clears and unexplored rooms only when no route is left, and never one a
 *       teammate is standing in.</li>
 *   <li>Nothing reachable left: a wither door with something behind it (Auto Secret's door rule), then - Solo only -
 *       the blood door, the last thing it does: it starts the Watcher, so the run is handed back at blood camp.</li>
 * </ol>
 */
final class Autopilot {

    private static final Logger LOGGER = ModLog.get("killer560smod-autosecret");

    /** What Auto Secret should do next. */
    enum Type { SECRET, CLEAR, PUZZLE, EXPLORE, DOOR, FINISH }

    /**
     * @param room       the target room (SECRET, CLEAR, PUZZLE, EXPLORE)
     * @param door       the door cell (DOOR)
     * @param blood      DOOR: it is the blood door
     * @param tryWither  FINISH: open a wither door with something behind it first
     * @param openBlood  FINISH: then open the blood door (Solo)
     */
    record Order(Type type, RoomStatus.Room room, int door, boolean blood, boolean tryWither, boolean openBlood, String why) {
        static Order of(Type t, RoomStatus.Room r, String why) {
            return new Order(t, r, -1, false, false, false, why);
        }
    }

    // ---- learned this run (estimates are logged beside what really happened, for calibrating on Hypixel) ----
    /** Seconds an Auto Clear of one room takes, travel excluded. Starting guess; then a running average of this run. */
    private static final double CLEAR_SECONDS_START = 12.0;
    /** Seconds a route takes per node, travel excluded. */
    private static final double ROUTE_SECONDS_PER_NODE_START = 1.0;
    private static final double LEARN = 0.4;

    private static double clearSeconds = CLEAR_SECONDS_START;
    private static double routeSecondsPerNode = ROUTE_SECONDS_PER_NODE_START;
    private static boolean bloodOpened;
    private static final Set<Integer> explored = new HashSet<>();
    private static final Set<String> teammateOverride = new HashSet<>();
    private static boolean noRoutesSaid;
    private static boolean noClearSaid;
    private static boolean noScoreSaid;
    private static String lastBloodFirstWait;

    /** The action under way: what it was, when it began, its estimate. */
    private static AutopilotPlanner.Candidate current;
    private static double currentTravel;
    private static int currentNodes;
    private static long currentStartMs;

    // ---- HUD ----
    private static String hudAction = "-";
    private static String hudWhy = "";
    private static String hudScore = "";

    private Autopilot() {
    }

    static void reset() {
        clearSeconds = CLEAR_SECONDS_START;
        routeSecondsPerNode = ROUTE_SECONDS_PER_NODE_START;
        bloodOpened = false;
        explored.clear();
        noRoutesSaid = false;
        noClearSaid = false;
        noScoreSaid = false;
        lastBloodFirstWait = null;
        current = null;
        hudAction = "Starting";
        hudWhy = "";
        hudScore = "";
    }

    static void onBloodDoorOpened() {
        bloodOpened = true;
        LOGGER.info("[Autopilot] the blood door is open");
    }

    static boolean bloodOpened() {
        return bloodOpened;
    }

    static String hudAction() {
        return hudAction;
    }

    static String hudWhy() {
        return hudWhy;
    }

    static String hudScore() {
        return hudScore;
    }

    static void setHud(String action, String why) {
        hudAction = action;
        hudWhy = why == null ? "" : why;
    }

    /** For the testkit: rooms treated as having a teammate in them, on top of the map's own markers. */
    public static void testSetTeammateRooms(Collection<String> names) {
        teammateOverride.clear();
        if (names != null) {
            teammateOverride.addAll(names);
        }
    }

    // =========================================================================================== deciding

    static Order decide(DungeonLayout layout, List<RoomStatus.Room> rooms, Map<Integer, Integer> dist,
                        Set<String> secreted, Set<String> clearsDone, Set<String> puzzlesDone) {
        closeCurrent();
        AutoSecretConfig cfg = AutoSecretConfig.getInstance();
        boolean party = cfg.getRunMode() == AutoSecretConfig.RunMode.PARTY;
        AutopilotScore.State s = state(layout, rooms);
        hudScore = s.currentTotal() < 0 ? "score ?" : "score " + s.currentTotal() + " (" + ScoreCalculator.rank(s.currentTotal()) + ")";
        int blood = layout.bloodDoor();
        boolean bloodLocked = blood >= 0 && layout.isLocked(blood);
        if (blood >= 0 && !bloodLocked && !bloodOpened) {
            bloodOpened = true;
            LOGGER.info("[Autopilot] the blood door reads open");
        }

        List<AutopilotPlanner.Candidate> cands = candidates(layout, rooms, dist, secreted, clearsDone, puzzlesDone, s, party);
        String head = String.format(Locale.US, "[Autopilot] decide (%s%s): %s, useful secrets %d, room %.2f, secret %.2f,"
                        + " clear ~%.1fs, route ~%.2fs/node", party ? "PARTY" : "SOLO", cfg.isBloodFirst() ? "+BLOOD_FIRST" : "",
                hudScore, AutopilotScore.usefulSecrets(s), AutopilotScore.roomValue(s), AutopilotScore.secretValue(s),
                clearSeconds, routeSecondsPerNode);

        // ---- Blood First ----
        if (cfg.isBloodFirst() && !bloodOpened) {
            AutopilotPlanner.Choice rush = AutopilotPlanner.chooseBloodFirst(cands);
            if (rush != null) {
                return begin(head, rush, rooms);
            }
            Order door = bloodFirstDoor(layout, blood, bloodLocked);
            if (door != null) {
                LOGGER.info("{} | pick DOOR cell {} ({}) - {}", head, door.door(), door.blood() ? "blood" : "wither", door.why());
                setHud(door.blood() ? "Opening the blood door" : "Opening a wither door on the blood path", door.why());
                return door;
            }
        }

        // ---- Solo: done at 300 ----
        if (!party && AutopilotScore.reached300(s)) {
            LOGGER.info("{} | 300 reached - finishing", head);
            return finish(false, "300 score reached");
        }

        AutopilotPlanner.Choice c = AutopilotPlanner.choose(party, cands);
        if (c != null) {
            return begin(head, c, rooms);
        }
        LOGGER.info("{} | nothing left it can reach ({} candidate(s), none worth score)", head, cands.size());
        return finish(!party || !AutopilotScore.reached300(s), "nothing left it can reach");
    }

    private static Order finish(boolean tryWither, String why) {
        AutoSecretConfig cfg = AutoSecretConfig.getInstance();
        boolean party = cfg.getRunMode() == AutoSecretConfig.RunMode.PARTY;
        // The blood door only ever in Solo (Blood First opened it already, if that was on).
        boolean openBlood = !party && !bloodOpened;
        setHud(openBlood ? "Finishing: then the blood door" : "Finishing", why);
        return new Order(Type.FINISH, null, -1, false, tryWither, openBlood, why);
    }

    /**
     * Blood First's next door: the first locked door on the way from here to the room in front of the blood door
     * (Auto Blood Rush's rule), then the blood door itself. Null when the team lacks the key for it (the rest of the
     * floor is planned meanwhile) or there is no door on the way.
     */
    private static Order bloodFirstDoor(DungeonLayout layout, int blood, boolean bloodLocked) {
        int here = layout.currentRoom();
        if (here < 0) {
            return null;
        }
        int door = -1;
        if (blood >= 0) {
            int[] resolved = DungeonMapPathfinder.resolve(layout, here, blood, true);
            if (resolved == null) {
                return null;
            }
            int bloodSide = layout.roomOfCell(resolved[1]);
            List<DungeonMapPathfinder.RoomStep> path = bloodSide == here ? List.of()
                    : DungeonMapPathfinder.findPath(layout, here, bloodSide, true);
            if (path == null) {
                return null;
            }
            for (DungeonMapPathfinder.RoomStep step : path) {
                if (step.door() >= 0 && layout.isLocked(step.door())) {
                    door = step.door();
                    break;
                }
            }
            if (door < 0 && bloodLocked) {
                door = blood;
            }
        } else {
            // The blood door is not on the map yet: the nearest locked wither door (Auto Blood Rush's fallback).
            int bestDist = Integer.MAX_VALUE;
            for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
                if (layout.doorType(idx) != DungeonLayout.DOOR_WITHER || !layout.isLocked(idx)) {
                    continue;
                }
                int d = DungeonMapPathfinder.getDistToDoor(layout, here, idx, true);
                if (d != Integer.MAX_VALUE && d < bestDist) {
                    bestDist = d;
                    door = idx;
                }
            }
        }
        if (door < 0) {
            return null;
        }
        boolean isBlood = layout.doorType(door) == DungeonLayout.DOOR_BLOOD;
        int[] near = DungeonMapPathfinder.resolve(layout, here, door, false);
        if (near == null || near[2] == Integer.MAX_VALUE) {
            return null;   // another door first - the loop above already returned the first one, so this is a blood door
        }
        boolean key = isBlood ? DungeonState.sidebarBloodKey() != 0
                : WitherDoorOpener.haveKey(Minecraft.getInstance().player);
        if (!key) {
            String what = isBlood ? "the blood key" : "a wither key";
            if (!what.equals(lastBloodFirstWait)) {
                lastBloodFirstWait = what;
                LOGGER.info("[Autopilot] Blood First: the next door wants {} - planning the rest meanwhile", what);
                AutoSecretFeature.sayAutopilot("Blood First: waiting on " + what + " - doing the rest of the floor meanwhile");
            }
            return null;
        }
        lastBloodFirstWait = null;
        return new Order(Type.DOOR, null, door, isBlood, false, false,
                isBlood ? "Blood First: the blood door is next" : "Blood First: the next wither door on the blood path");
    }

    private static Order begin(String head, AutopilotPlanner.Choice c, List<RoomStatus.Room> rooms) {
        AutopilotPlanner.Candidate p = c.pick();
        List<String> ranked = new ArrayList<>();
        for (AutopilotPlanner.Candidate k : c.ranked()) {
            ranked.add(k.describe());
        }
        LOGGER.info("{} | pick {} - {} | ranked: {}", head, p.describe(), c.why(), String.join("; ", ranked));
        RoomStatus.Room room = null;
        for (RoomStatus.Room r : rooms) {
            if (keyOf(r).equals(p.room())) {
                room = r;
            }
        }
        current = p;
        currentStartMs = System.currentTimeMillis();
        currentTravel = travelByKey.getOrDefault(p.room(), 0.0);
        currentNodes = 0;
        if (p.kind() == AutopilotPlanner.Kind.SECRET && room != null) {
            Route route = RouteStore.getInstance().forRoom(room.name());
            currentNodes = route == null ? 0 : route.nodes().size();
        }
        String verb = switch (p.kind()) {
            case SECRET -> "Secreting";
            case CLEAR -> "Clearing";
            case PUZZLE -> "Puzzle";
            case EXPLORE -> "Exploring";
        };
        setHud(verb + " " + displayName(room, p.room()), String.format(Locale.US, "%.1f pts in ~%.0f s - %s", p.gain(),
                p.seconds(), c.why()));
        Type t = switch (p.kind()) {
            case SECRET -> Type.SECRET;
            case CLEAR -> Type.CLEAR;
            case PUZZLE -> Type.PUZZLE;
            case EXPLORE -> Type.EXPLORE;
        };
        if (t == Type.EXPLORE && room != null) {
            explored.add(room.mainTile());
        }
        return room == null ? finish(true, "the chosen room left the map") : Order.of(t, room, c.why());
    }

    private static String displayName(RoomStatus.Room r, String key) {
        return r == null ? key : "Unknown".equals(r.name()) ? "an unexplored room" : r.name();
    }

    /** The last action is over (a new decision is being made): log it beside its estimate, and learn from it. */
    private static void closeCurrent() {
        if (current == null) {
            return;
        }
        double took = (System.currentTimeMillis() - currentStartMs) / 1000.0;
        double work = Math.max(0.5, took - currentTravel);
        LOGGER.info(String.format(Locale.US, "[Autopilot] done %s %s in %.1f s (estimated %.1f s, travel ~%.1f s)",
                current.kind(), current.room(), took, current.seconds(), currentTravel));
        if (current.kind() == AutopilotPlanner.Kind.CLEAR) {
            clearSeconds = clearSeconds * (1 - LEARN) + work * LEARN;
        } else if (current.kind() == AutopilotPlanner.Kind.SECRET && currentNodes > 0) {
            routeSecondsPerNode = routeSecondsPerNode * (1 - LEARN) + (work / currentNodes) * LEARN;
        }
        current = null;
    }

    /** Auto Secret stopped: close the action so the log has its time. */
    static void onStopped() {
        closeCurrent();
    }

    // =========================================================================================== candidates

    /** Each candidate room's travel estimate from the last decision, by {@link #keyOf}. */
    private static final Map<String, Double> travelByKey = new java.util.HashMap<>();

    private static List<AutopilotPlanner.Candidate> candidates(DungeonLayout layout, List<RoomStatus.Room> rooms,
                                                               Map<Integer, Integer> dist, Set<String> secreted,
                                                               Set<String> clearsDone, Set<String> puzzlesDone,
                                                               AutopilotScore.State s, boolean party) {
        AutoSecretConfig cfg = AutoSecretConfig.getInstance();
        List<AutopilotPlanner.Candidate> out = new ArrayList<>();
        travelByKey.clear();
        boolean routes = AutoRoutesConfig.getInstance().isEnabled();
        if (!routes && !noRoutesSaid) {
            noRoutesSaid = true;
            AutoSecretFeature.sayAutopilot("Auto Routes is off - no secret routes this run");
        }
        boolean clears = AutoClearFeature.isAvailable();
        if (!clears && !noClearSaid) {
            noClearSaid = true;
            AutoSecretFeature.sayAutopilot("Auto Clear is off - no room clears this run (Solo cannot reach 300 without them)");
        }
        List<Integer> rush = AutoClearFeature.bloodRushRooms(layout);
        for (RoomStatus.Room r : rooms) {
            Integer steps = dist.get(r.room());
            if (steps == null) {
                continue;   // behind a closed door
            }
            double travel = travelSeconds(layout, r, steps);
            boolean mate = teammateInside(r);
            int rushIndex = rush == null ? -1 : rush.indexOf(r.room());
            String key = keyOf(r);
            travelByKey.put(key, travel);
            if ("Unknown".equals(r.name())) {
                if (!explored.contains(r.mainTile())) {
                    // Unidentified: going there identifies it; worth about half a room until it is known.
                    out.add(new AutopilotPlanner.Candidate(AutopilotPlanner.Kind.EXPLORE, key,
                            AutopilotScore.roomValue(s) * 0.5, travel, mate, -1));
                }
                continue;
            }
            if (routes && r.unfound() > 0 && !secreted.contains(r.name()) && !r.isType("PUZZLE") && !r.isType("TRAP")
                    && !r.isType("BLOOD") && !r.isType("ENTRANCE")) {
                Route route = RouteStore.getInstance().forRoom(r.name());
                if (route != null && route.startNode() != null) {
                    double secs = travel + 1.0 + routeSecondsPerNode * route.nodes().size();
                    out.add(new AutopilotPlanner.Candidate(AutopilotPlanner.Kind.SECRET, key,
                            AutopilotScore.secretGain(s, r.unfound(), party), secs, mate, rushIndex));
                }
            }
            if (clears && !r.cleared() && !clearsDone.contains(r.name()) && AutoClearFeature.isMobRoom(layout, r.room())) {
                out.add(new AutopilotPlanner.Candidate(AutopilotPlanner.Kind.CLEAR, key, AutopilotScore.roomValue(s),
                        travel + clearSeconds, mate, rushIndex));
            }
            if (cfg.isDoPuzzles() && r.isType("PUZZLE") && !puzzlesDone.contains(r.name()) && !puzzleFinished(r)
                    && autoFor(r.name())) {
                out.add(new AutopilotPlanner.Candidate(AutopilotPlanner.Kind.PUZZLE, key, AutopilotScore.puzzleValue(s),
                        travel + puzzleSeconds(r.name()), mate, -1));
            }
        }
        return out;
    }

    /** A room's key in a candidate: its name, or its main tile for an unidentified one (several share "Unknown"). */
    static String keyOf(RoomStatus.Room r) {
        return "Unknown".equals(r.name()) ? "Unknown@" + r.mainTile() : r.name();
    }

    private static boolean teammateInside(RoomStatus.Room r) {
        return teammateOverride.contains(r.name()) || RoomStatus.teammateInside(r.cells());
    }

    /**
     * The Interactive Map's trip, estimated the way Auto Clear does ({@code ETHER_FIXED_TICKS} 14 + 2 a warp): straight
     * distance to the room's nearest tile over the hop range, a quarter longer for the way round walls, plus a little a
     * room for the search. Zero inside the room.
     */
    private static double travelSeconds(DungeonLayout layout, RoomStatus.Room r, int steps) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || layout.currentRoom() == r.room()) {
            return 0;
        }
        double best = Double.MAX_VALUE;
        for (int t : r.tiles()) {
            BlockPos c = DungeonLayout.cellCenter(t);
            double dx = client.player.getX() - (c.getX() + 0.5);
            double dz = client.player.getZ() - (c.getZ() + 0.5);
            best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
        }
        int warps = Math.max(1, (int) Math.ceil(best * 1.25 / Math.max(1.0, ClearExecutor.hopRange())));
        return (14 + 2 * warps + 4 * steps) / 20.0;
    }

    /** Estimated seconds for each Auto Puzzles auto, standing in the room (unmeasured guesses - logged beside the real time). */
    static double puzzleSeconds(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "ice fill" -> 12;
            case "ice path" -> 20;
            case "higher blaze", "lower blaze" -> 20;
            case "creeper beams" -> 15;
            case "boulder" -> 25;
            case "water board" -> 30;
            case "tic tac toe" -> 10;
            case "teleport maze" -> 10;
            case "quiz" -> 25;
            case "three weirdos" -> 6;
            default -> 30;
        };
    }

    /** How long to stand in a puzzle for its auto before moving on. */
    static int puzzleTimeoutSeconds(String name) {
        return (int) Math.min(120, Math.max(30, puzzleSeconds(name) * 3));
    }

    /** Whether Auto Puzzles has this puzzle's auto switched on (the master toggle included). */
    static boolean autoFor(String name) {
        AutoPuzzlesConfig a = AutoPuzzlesConfig.getInstance();
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "ice fill" -> a.isAutoIceFillEnabled();
            case "ice path" -> a.isAutoIcePathEnabled();
            case "higher blaze", "lower blaze" -> a.isAutoBlazeEnabled();
            case "creeper beams" -> a.isAutoBeamsEnabled();
            case "boulder" -> a.isAutoBoulderEnabled();
            case "water board" -> a.isAutoWaterEnabled();
            case "tic tac toe" -> a.isAutoTicTacToeEnabled();
            case "teleport maze" -> a.isAutoTeleportMazeEnabled();
            case "quiz" -> a.isAutoQuizEnabled();
            case "three weirdos" -> a.isAutoWeirdosEnabled();
            default -> false;
        } && a.isAutoPuzzlesMasterEnabled();
    }

    /** Done or failed: the map's check or cross, or the tab list's ✔ / ✖. */
    static boolean puzzleFinished(RoomStatus.Room r) {
        char tab = ScoreCalculatorFeature.puzzleState(r.name());
        return r.cleared() || r.failed() || tab == '✔' || tab == '✖';
    }

    /** The run's score inputs: the score calculator's, with the map filling anything it has not read yet. */
    private static AutopilotScore.State state(DungeonLayout layout, List<RoomStatus.Room> rooms) {
        ScoreCalculator.Result res = ScoreCalculatorFeature.currentResult();
        ScoreCalculator.Inputs in = ScoreCalculatorFeature.currentInputs();
        int mapSecrets = 0;
        int mapFound = 0;
        int mapCleared = 0;
        for (RoomStatus.Room r : rooms) {
            mapSecrets += r.secrets();
            mapFound += r.secrets() - r.unfound();
            mapCleared += r.cleared() ? 1 : 0;
        }
        if (res == null || in == null) {
            if (!noScoreSaid) {
                noScoreSaid = true;
                AutoSecretFeature.sayAutopilot("Score Calculator has no reading - the 300 check is off, so it does everything");
            }
            return new AutopilotScore.State(DungeonState.getFloor(), Math.max(1, layout.roomCount()), mapCleared, mapSecrets,
                    mapFound, 0, 0, 0, 100, -1);
        }
        int totalRooms = res.totalRooms() > 0 ? res.totalRooms() : Math.max(1, layout.roomCount());
        int totalSecrets = Math.max(res.totalSecrets(), mapSecrets);
        int deathPenalty = Math.max(0, in.deaths() * 2 - (in.assumeSpiritPet() ? 1 : 0));
        return new AutopilotScore.State(in.floor(), totalRooms, Math.max(in.completedRooms(), mapCleared), totalSecrets,
                Math.max(in.secretsFound(), mapFound), res.bonus(), deathPenalty, in.puzzlesFailed(), res.speed(), res.total());
    }
}
