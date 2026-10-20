package com.killer560.hub.autosecret;

import com.killer560.hub.autoclear.AutoClearFeature;
import com.killer560.hub.autopuzzles.AutoPuzzlesConfig;
import com.killer560.hub.autopuzzles.AutoPuzzlesFeature;
import com.killer560.hub.autoroutes.AutoRoutesConfig;
import com.killer560.hub.autoroutes.AutoRoutesFeature;
import com.killer560.hub.autoroutes.Route;
import com.killer560.hub.autoroutes.RouteExecutor;
import com.killer560.hub.autoroutes.RouteStore;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.dungeoninfo.DungeonInfoFeature;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.RoomStatus;
import com.killer560.hub.livemap.autoclear.AutoClearUtils;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.livemap.autoclear.DungeonMapPathfinder;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModSounds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Auto Secret - CHEAT BUILD ONLY (killer560, 2026-10-06). Secrets the whole floor with his own recorded Auto Routes:
 * picks the reachable room with the most unfound secrets, warps to its route's START node the way the Interactive
 * Map's "Go + Secret" does ({@link AutoRoutesFeature#warpToStartNode}), lets the route play, and moves on.
 *
 * <p>It owns no movement of its own. Every warp is the Interactive Map's planner and executor ({@code ClearExecutor},
 * with its "land near the centre without extra warps" tile rule via {@link AutoClearUtils#pathToRoom}); every route
 * is {@link RouteExecutor}; Ice Fill is Auto Ice Fill. So the safety rules (real key presses, running yaw, BodyAim,
 * packet order) are theirs and unchanged - this class only decides where to go next.
 *
 * <p>Order of a run, re-decided after every step from a fresh {@link DungeonLayout} (so a door a teammate opened is
 * seen at once):
 * <ol>
 * <li>Ice Fill, early, when the floor has it and Auto Ice Fill is on.</li>
 * <li>Insta clears: an uncleared room the {@link InstaClearTracker} has a KNOWN-good entry for is warped into through
 * that entry - never a guessed one.</li>
 * <li>Secrets: the reachable room (no closed wither/blood door on the way - only teammates open doors) with the most
 * unfound secrets, ignoring traps and every puzzle. A room with no recorded route is skipped with a chat line, once
 * a run.</li>
 * <li>Endgame: unfinished puzzles nobody else is standing in; then an uncleared room (handed to Auto Clear when
 * "Auto Clear Rooms" is on and Auto Clear is in the build); then it hands control back.</li>
 * </ol>
 * Stops on any movement key he presses, the boss, the run ending, or every secret found. A server correction does
 * NOT stop it (killer560, 2026-10-06, mod-wide: "Nothing in this mod should stop from server corrections ever - just
 * have it send a chat message and make noises"): it says what happened and where, sounds the alarm and re-plans
 * from where the server put him.
 */
public final class AutoSecretFeature {

    static final String CHAT = "Auto Secret";
    private static final Logger LOGGER = ModLog.get("killer560smod-autosecret");

    enum Phase { IDLE, DECIDE, TRAVEL, AWAIT_ROUTE, ROUTE, ICE_FILL, PUZZLE_WAIT, SETTLE, AUTO_CLEAR, WAITING, DOOR,
        KEY_WAIT, QUIZ }

    /** What a TRAVEL is for, so its arrival knows what comes next. */
    enum Trip { START_NODE, ICE_FILL, INSTA_FROM, INSTA_INTO, PUZZLE, CLEAR, FINAL, DOOR, EXPLORE, KEY, QUIZ }

    /** Ticks to wait for a route to arm once he has landed on its start node. */
    private static final int ARM_TIMEOUT_TICKS = 40;
    /** A trip that has not finished in this long is given up on. */
    private static final int TRAVEL_TIMEOUT_TICKS = 30 * 20;
    private static final int ICE_FILL_TIMEOUT_TICKS = 60 * 20;
    /** Position packets this soon after anything of ours moved him are that thing's own landings. */
    private static final int CORRECTION_GRACE_TICKS = 20;
    private static final int MAX_TRIP_FAILURES = 2;
    private static final int SETTLE_TICKS = 10;

    private static Phase phase = Phase.IDLE;
    private static Trip trip;
    private static String target;
    private static int targetRoomId = -1;
    private static int phaseTicks;
    private static boolean busySeen;
    private static int arrivalSeqAtStart;
    private static int routeActions;
    private static int routeIdleTicks;
    private static BlockPos instaLanding;
    /** The closed wither door it is opening (a map cell), where it stands for it, and its lock block. */
    private static int doorCell = -1;
    /** The door it is at is the blood door (Dungeon Autopilot, or Auto Secret's Blood First). */
    private static boolean doorBlood;
    /** The dropped key it is going to pick up, where it stands for it, and its distance from him last tick. */
    private static com.killer560.hub.doorkeys.DungeonKeys.Dropped keyTarget;
    private static BlockPos keyGoal;
    private static double keyLastDist = -1;
    private static int keyWitherBefore;
    private static int keyBloodBefore;
    /** Ticks to stand by a key for it to be picked up before calling the try failed. */
    private static final int KEY_WAIT_TICKS = 40;
    /** Within this many blocks a key vanishing from the client means it was taken (by him or a teammate). */
    private static final double KEY_GONE_NEAR = 12.0;
    private static BlockPos doorGoal;
    private static BlockPos doorLock;
    private static int doorClicks;
    private static int doorLastClickTick;
    private static String lastDoorSaid;
    private static boolean noKeySaid;
    /** Secret rooms behind a closed door, from the last decision (waited on only when nothing else is left). */
    private static String blockedSecrets;
    private static String instaFrom;
    private static int runId;
    /** The room he was last in by the map, for a decision made from a doorway (which reads as no room). */
    private static String lastRoomName;
    /** This run is Dungeon Autopilot's: {@link Autopilot} decides, this class carries it out. */
    private static boolean pilot;
    private static Object level;
    private static String status = "Off";

    /** Rooms whose route ran (or could not) this run - never targeted again. */
    private static final Set<String> secreted = new HashSet<>();
    private static final Set<String> noRouteSaid = new HashSet<>();
    private static final Set<String> instaTried = new HashSet<>();
    private static final Set<String> puzzlesDone = new HashSet<>();
    private static final Set<String> clearsDone = new HashSet<>();
    private static final Map<String, Integer> tripFailures = new HashMap<>();
    private static boolean iceFillTried;
    private static String lastWaiting;
    /** This run's Blood First (Auto Secret alone; OFF under the Autopilot, which has its own), and whether it is over. */
    private static AutoSecretConfig.SecretBloodFirst bloodFirstMode = AutoSecretConfig.SecretBloodFirst.OFF;
    private static boolean bloodFirstDone;
    /** The last "nothing toward blood right now" reason logged, so it is logged once per reason. */
    private static String bloodFirstNote;
    /** The room in front of the blood door while it is held back for last (decideSecrets), else null. */
    private static RoomStatus.Room heldPreBlood;
    /** The held room last logged, so the hold is said once. */
    private static String preBloodSaid;

    private static int seenPositionPackets;
    private static int seenCorrections;
    private static int quietTicks;

    // ---- the hand-over from a route to the next trip (killer560, 2026-10-15) ----
    /** At most this many ticks a decision waits for its trips to be searched before it estimates them instead. */
    private static final int TOUR_WAIT_TICKS = 10;
    /** {@link RouteExecutor#completions()} when the trip to the target's start node began. */
    private static int routeCompletionsSeen;
    /** {@link com.killer560.hub.autoroutes.RouteRunMemory#clock()} then, and the room's found count then. */
    private static int routeStartClock;
    private static int routeFoundAtStart;
    /** {@link #routeStartClock} and {@link #routeFoundAtStart} of the room whose last-node wait is running. */
    private static int leaveStartClock;
    private static int leaveFoundAtStart;
    /** The room whose last secrets the next trip's first warp is held for, and the ticks held so far. */
    private static String leaveWaitRoom;
    private static int leaveWaitTicks;
    private static int tourWaitTicks;

    private static boolean toggleWasDown;
    private static boolean pilotKeyWasDown;
    /** Movement keys held when it started stay ignored until released: only a fresh press is him taking over. */
    private static final boolean[] latchedKeys = new boolean[5];
    private static final boolean[] keyWasDown = new boolean[5];

    private AutoSecretFeature() {
    }

    public static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("AutoSecretFeature.tick", AutoSecretFeature::tick));
        // START, after Auto Routes' tick and before the Interactive Map runner's (registration order): a route that ends
        // this tick hands over to the next trip this same tick, and a held first warp is let go the tick it may go.
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoSecretFeature.tickStart",
                AutoSecretFeature::tickStart));
        WitherDoorOpener.register();
        AutoStatusHud.register();
    }

    // ------------------------------------------------------------------------------------------- public API

    public static boolean isRunning() {
        return phase != Phase.IDLE;
    }

    /** Running as Dungeon Autopilot (rather than Auto Secret on its own). */
    public static boolean isAutopilot() {
        return phase != Phase.IDLE && pilot;
    }

    public static void toggleAutopilot() {
        if (isRunning()) {
            stop("you switched it off", true);
        } else {
            startAutopilot();
        }
    }

    /** Dungeon Autopilot: secrets, clears and puzzles under one planner ({@link Autopilot}). @return true when it started */
    public static boolean startAutopilot() {
        return start(true);
    }

    /** The autopilot's HUD lines: {mode + action, score + why}. */
    public static String[] autopilotHud() {
        AutoSecretConfig cfg = AutoSecretConfig.getInstance();
        String mode = cfg.getRunMode() == AutoSecretConfig.RunMode.PARTY ? "Party" : "Solo";
        if (cfg.isBloodFirst()) {
            mode += ", Blood First";
        }
        String why = phase == Phase.DOOR || phase == Phase.WAITING || phase == Phase.AUTO_CLEAR ? status : Autopilot.hudWhy();
        return new String[]{"[" + mode + "] " + Autopilot.hudAction(), Autopilot.hudScore() + (why.isEmpty() ? "" : " | " + why)};
    }

    /** For the testkit: rooms to treat as having a teammate in them. */
    public static void testSetTeammateRooms(java.util.Collection<String> names) {
        Autopilot.testSetTeammateRooms(names);
    }

    static void sayAutopilot(String text) {
        say(ModChat.dim(text));
    }

    private static String chatName() {
        return pilot ? "Autopilot" : CHAT;
    }

    // ---- read by the shared Status HUD (AutoStatusHud) on the client tick, never per frame ----

    static Phase hudPhase() {
        return phase;
    }

    static Trip hudTrip() {
        return trip;
    }

    /** The room (or key, door) the current step is for; null between steps. */
    static String hudTarget() {
        return target;
    }

    /** The room a finished route is still waiting on for its last secrets, or null. */
    static String hudLeaveWait() {
        return leaveWaitRoom;
    }

    /** Trips to the current target that already failed (above 0: this trip is a retry). */
    static int hudTripFailures() {
        Integer n = target == null ? null : tripFailures.get(target);
        return n == null ? 0 : n;
    }

    /** The door the current step is for is the blood door (else a wither door). */
    static boolean hudDoorBlood() {
        return doorBlood;
    }

    static boolean hudPilot() {
        return pilot;
    }

    /** {found, total} of {@code room}'s secrets as Auto Secret counts them, or null when it has none. */
    static int[] hudRoomCount(String room) {
        return room == null ? null : roomCount(room);
    }

    /** One line for the tab: what it is doing now. */
    public static String statusText() {
        return status;
    }

    /** The phase's name, for the testkit. */
    public static String phaseName() {
        return phase.name();
    }

    public static void toggle() {
        if (isRunning()) {
            stop("you switched it off", true);
        } else {
            start();
        }
    }

    /** @return true when it started */
    public static boolean start() {
        return start(false);
    }

    private static boolean start(boolean withPilot) {
        Minecraft client = Minecraft.getInstance();
        if (phase != Phase.IDLE) {
            return false;
        }
        pilot = withPilot;
        if (!AutoSecretConfig.allowed()) {
            say(ModChat.bad("Cheat build and Skyblock only."));
            return false;
        }
        if (client.player == null || !DungeonState.isInDungeon()) {
            say(ModChat.bad("Not in a dungeon."));
            return false;
        }
        if (LiveMapFeature.isInBoss()) {
            say(ModChat.bad("Not in the boss."));
            return false;
        }
        if (!withPilot && !AutoRoutesConfig.getInstance().isEnabled()) {
            say(ModChat.bad("Turn Auto Routes on first - Auto Secret plays your routes."));
            return false;
        }
        runId++;
        lastRoomName = null;
        secreted.clear();
        revisited.clear();
        planSaid.clear();
        skippedSecrets.clear();
        pendingSecret = null;
        puzzleHoldSaid = null;
        puzzleHoldRoom = null;
        puzzleHoldsSaid.clear();
        noRouteSaid.clear();
        instaTried.clear();
        puzzlesDone.clear();
        clearsDone.clear();
        tripFailures.clear();
        iceFillTried = false;
        QuizPlan.reset();
        quizIdleTicks = 0;
        lastWaiting = null;
        leaveWaitRoom = null;
        tourWaitTicks = 0;
        ClearExecutor.setHold(false);
        RoomTour.reset();
        resetDoor();
        lastDoorSaid = null;
        blockedSecrets = null;
        level = client.level;
        seenPositionPackets = ClearExecutor.positionPackets();
        seenCorrections = ClearExecutor.serverCorrections();
        quietTicks = 0;
        latchHeldKeys(client);
        AutoSecretConfig cfg = AutoSecretConfig.getInstance();
        bloodFirstMode = withPilot ? AutoSecretConfig.SecretBloodFirst.OFF : cfg.getSecretBloodFirst();
        bloodFirstDone = false;
        bloodFirstNote = null;
        heldPreBlood = null;
        preBloodSaid = null;
        if (bloodFirstMode == AutoSecretConfig.SecretBloodFirst.FULL_BLOOD_RUSH && !AutoClearFeature.isAvailable()) {
            bloodFirstMode = AutoSecretConfig.SecretBloodFirst.DOOR_CAMP;
            say(ModChat.bad("Auto Clear is off"), ModChat.dim(" - Full Blood Rush clears its rooms with it, so this run"
                    + " is Door Camp"));
        }
        if (withPilot || bloodFirstMode != AutoSecretConfig.SecretBloodFirst.OFF) {
            Autopilot.reset();   // its key memory (keys given up on) is Blood First's too
        }
        setPhase(Phase.DECIDE);
        status = "Starting";
        LOGGER.info("[AutoSecret] started (floor {}){}", DungeonState.getFloor(), withPilot ? " as Dungeon Autopilot: "
                + cfg.getRunMode() + (cfg.isBloodFirst() ? ", Blood First" : "") + (cfg.isDoPuzzles() ? ", puzzles" : "")
                : bloodFirstMode == AutoSecretConfig.SecretBloodFirst.OFF ? "" : ", Blood First: " + bloodFirstMode.label());
        say(ModChat.good("Started"), ModChat.dim((withPilot ? " (" + cfg.getRunMode().label()
                + (cfg.isBloodFirst() ? ", Blood First" : "") + ")" : bloodFirstMode == AutoSecretConfig.SecretBloodFirst.OFF
                ? "" : " (Blood First: " + bloodFirstMode.label() + ")") + " - press a movement key to take over"));
        return true;
    }

    /** Stops it, saying why. {@code cancelOurs}: also stop the warp / route / clear it started. */
    public static void stop(String reason, boolean cancelOurs) {
        if (phase == Phase.IDLE) {
            return;
        }
        Phase was = phase;
        phase = Phase.IDLE;
        com.killer560.hub.autoroutes.RunPlan.clear(com.killer560.hub.autoroutes.RunPlan.Source.AUTO_SECRET);
        com.killer560.hub.autoroutes.RunPlan.clear(com.killer560.hub.autoroutes.RunPlan.Source.AUTOPILOT);
        keyTarget = null;
        if (leaveWaitRoom != null) {
            leaveWaitRoom = null;
            ClearExecutor.setHold(false);
        }
        WitherDoorOpener.cancel();
        if (pilot) {
            Autopilot.onStopped();
            Autopilot.setHud("Stopped", reason);
        }
        AutoStatusHud.stopped(reason);
        runId++;
        if (cancelOurs) {
            if (was == Phase.TRAVEL && ClearExecutor.isBusy()) {
                ClearExecutor.cancel();
            }
            if (RouteExecutor.isRunning() && (was == Phase.ROUTE || was == Phase.AWAIT_ROUTE)) {
                RouteExecutor.stop("Auto Secret stopped");
            }
            if (was == Phase.AUTO_CLEAR && AutoClearFeature.isBusy()) {
                AutoClearFeature.cancel();
            }
        }
        if (was == Phase.TRAVEL) {
            ClearExecutor.setExternalOwner(false);
        }
        status = "Stopped: " + reason;
        LOGGER.info("[AutoSecret] stopped: {}", reason);
        if (reason.startsWith("nothing left to do")) {
            // The run finished on its own: log only (killer560, 2026-10-17: "remove the stopped: nothing left to do line").
            ModChat.log(chatName(), ModChat.bad("Stopped"), ModChat.dim(" - " + reason));
        } else {
            ModChat.send(chatName(), ModChat.bad("Stopped"), ModChat.dim(" - " + reason));
        }
    }

    // ------------------------------------------------------------------------------------------- ticking

    private static void tick(Minecraft client) {
        pollToggleKey(client);
        if (phase == Phase.IDLE) {
            return;
        }
        if (stopChecks(client)) {
            return;
        }
        if (playerTookOver(client)) {
            return;
        }
        watchCorrections(client);
        if (holdForPuzzle()) {
            return;
        }
        phaseTicks++;
        if (keyTarget != null && (phase == Phase.TRAVEL || phase == Phase.KEY_WAIT) && keyGone(client)) {
            return;
        }
        switch (phase) {
            case DECIDE -> decide(client);
            case TRAVEL -> tickTravel(client);
            case AWAIT_ROUTE -> tickAwaitRoute();
            case ROUTE -> tickRoute();
            case ICE_FILL -> tickIceFill();
            case PUZZLE_WAIT -> tickPuzzleWait();
            case QUIZ -> tickQuiz();
            case SETTLE -> {
                if (phaseTicks >= SETTLE_TICKS) {
                    setPhase(Phase.DECIDE);
                }
            }
            case DOOR -> tickDoor();
            case KEY_WAIT -> tickKeyWait();
            case WAITING -> {
                if (phaseTicks >= 20) {
                    setPhase(Phase.DECIDE);
                }
            }
            case AUTO_CLEAR -> {
                // Auto Clear calls back; nothing to poll.
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------------------------------- route -> next trip

    /**
     * START of the tick, after Auto Routes has ticked and before the Interactive Map's runner does: the route that just
     * ran to its end hands over to the next trip at once (decided and, when it was planned ahead, running this same tick),
     * and a first warp held for the room's last secrets is let go the tick they count.
     */
    private static void tickStart(Minecraft client) {
        if (phase == Phase.IDLE || client.player == null) {
            return;
        }
        if (leaveWaitRoom != null) {
            tickLeaveWait();
        }
        if (routeJustCompleted() && !com.killer560.hub.autopuzzles.AutoPuzzleActivity.isWorking()) {
            onRouteDone(client);
        }
    }

    /** The target's route ran to its end since its trip began (on its arming tick too, for a one-node route). */
    private static boolean routeJustCompleted() {
        boolean waiting = phase == Phase.ROUTE || phase == Phase.AWAIT_ROUTE
                || phase == Phase.TRAVEL && trip == Trip.START_NODE;
        return waiting && !RouteExecutor.isRunning() && RouteExecutor.completions() != routeCompletionsSeen
                && target != null && target.equals(RouteExecutor.lastCompletedRoom());
    }

    /**
     * The last-node wait (killer560's rule, 2026-10-20: wait at most 20 ticks, and only when a secret is close; when none
     * is, leave at once - it replaced 2026-10-15's flat 6-tick wait for any room not fully counted). The route is over: the
     * next trip is decided now. With nothing of the room's still being taken where he stands ({@link #takingHere}: a secret
     * Secret Aura has in reach and has not seen taken, an item by his feet, the mimic) its first warp goes this tick,
     * counted or not - a secret the route left out of reach does not come any nearer by waiting. With one, the first warp
     * is held ({@link ClearExecutor#setHold}) until it registers or {@link #TAKE_TIMEOUT_TICKS} have passed.
     */
    private static void onRouteDone(Minecraft client) {
        routeCompletionsSeen = RouteExecutor.completions();
        String room = target;
        if (phase != Phase.ROUTE) {
            LOGGER.info("[AutoSecret] route {} started", room);   // it armed and ended on the same tick
        }
        secreted.add(room);
        long tick = ClearExecutor.clientTick();
        LOGGER.info("[AutoSecret] route {} finished (tick {})", room, tick);
        note(ModChat.good("Route done"), ModChat.dim(" - " + room));
        // This room's own trip start, kept for the wait: decide() below starts the NEXT trip and overwrites the
        // fields, and the wait then counted the leaving room from the next room's found count (147 on main: "every
        // secret counted after 1 tick" with the room still 4/5).
        leaveStartClock = routeStartClock;
        leaveFoundAtStart = routeFoundAtStart;
        int[] counted = roomCount(room);
        String taking = takingHere(client, room, counted, 0);
        if (taking == null) {
            if (counted == null || counted[0] >= counted[1]) {
                LOGGER.info("[AutoSecret] {}: every secret counted at the route's end{} - leaving this tick", room,
                        counted == null ? " (no secrets to count)" : " (" + counted[0] + "/" + counted[1] + ": "
                                + countWhy(room) + ")");
            } else {
                LOGGER.info("[AutoSecret] {}: {}/{} secret(s) counted at the route's end and none close - leaving this"
                        + " tick ({})", room, counted[0], counted[1], countWhy(room));            }
            leaveWaitRoom = null;
            ClearExecutor.setHold(false);
        } else {
            LOGGER.info("[AutoSecret] {}: {} secret(s) counted at the route's end, and {} is close and still being taken"
                    + " - the next warp waits for it to register (up to {} tick(s))", room,
                    counted == null ? "all" : counted[0] + "/" + counted[1], taking, TAKE_TIMEOUT_TICKS);
            leaveWaitRoom = room;
            leaveWaitTicks = 0;
            leaveWaitLag = com.killer560.hub.util.ServerLag.mark();
            leaveTakingSaid = taking;
            ClearExecutor.setHold(true);
            ClearExecutor.setHoldSneak(!clickComing(client));
            status = "Waiting at the end of " + room + " for its last secrets";
        }
        permitLeaveHere();
        setPhase(Phase.DECIDE);
        decide(client);
    }

    /** One START tick of the last-node wait: let the held warp go once nothing close is still being taken, or at the cap. */
    private static void tickLeaveWait() {
        leaveWaitTicks++;
        Minecraft client = Minecraft.getInstance();
        int[] counted = roomCount(leaveWaitRoom);   // with this room's own start, see leaveStartClock
        boolean done = counted == null || counted[0] >= counted[1];
        String taking = takingHere(client, leaveWaitRoom, counted, leaveWaitTicks);
        boolean timedOut = leaveWaitTicks >= TAKE_TIMEOUT_TICKS + com.killer560.hub.util.ServerLag.ticksSince(leaveWaitLag);
        if (taking != null && !timedOut) {
            // A secret in reach is still being taken: no cap but the timeout, and no sneak under its click.
            ClearExecutor.setHoldSneak(!clickComing(client));
            if (!taking.equals(leaveTakingSaid)) {
                leaveTakingSaid = taking;
                LOGGER.info("[AutoSecret] {}: waiting for {} to register ({} tick(s) in)", leaveWaitRoom, taking,
                        leaveWaitTicks);
            }
            return;
        }
        ClearExecutor.setHoldSneak(true);
        String why;
        if (taking != null) {
            why = "not every secret registered: " + taking + " - the " + TAKE_TIMEOUT_TICKS + "-tick limit";
            ModChat.send(chatName(), ModChat.bad("Leaving " + leaveWaitRoom), ModChat.dim(" - " + taking
                    + " never registered in " + leaveWaitTicks + " ticks"));
        } else if (done) {
            why = "every secret counted";
        } else {
            why = "not every secret counted (" + counted[0] + "/" + counted[1] + ") and none close any more";
        }
        LOGGER.info("[AutoSecret] {}: {} after {} tick(s) of the last-node wait (tick {}) - the next warp goes now ({})",
                leaveWaitRoom, why, leaveWaitTicks, ClearExecutor.clientTick(), countWhy(leaveWaitRoom));
        leaveWaitRoom = null;
        leaveTakingSaid = null;
        ClearExecutor.setHold(false);
    }

    /**
     * A click of Secret Aura's is coming: the held warp lets its sneak up, since a sneaking right click with the AOTV in
     * hand is the AOTV's (the server warps him onto the block instead of opening it). Once the click has gone the sneak
     * goes back down, so the warp can go the tick the secret registers.
     */
    private static boolean clickComing(Minecraft client) {
        return com.killer560.hub.cheatutils.SecretAuraFeature.clickDue(client);
    }

    /**
     * Ticks at most that a secret close by and still being taken holds the next warp (plus any server stall since the
     * wait began) - killer560's 20 (2026-10-20). It was 50 so a Secret Aura re-try (1 s after a click that did not take)
     * could land too; a secret that has not registered in a second is left.
     */
    static final int TAKE_TIMEOUT_TICKS = 20;
    private static long leaveWaitLag;
    private static String leaveTakingSaid;
    /** An item secret is due when his body is this close to its block (the sim's spawn range, killer560's number). */
    private static final double ITEM_DUE_RANGE = 3.0;
    /** Ticks an item secret by his feet is waited for before it has appeared (it appears after 5 in range). */
    private static final int ITEM_APPEAR_TICKS = 15;

    /**
     * killer560, 2026-10-15: "it will leave a room before a secret is grabbed and registered". What, at the end of
     * {@code room}'s route, is still being taken where he stands, or null: a secret Secret Aura has in reach and has not
     * yet seen taken; with the room not fully counted, also one it has not clicked yet, or an item secret of the room's
     * database by his feet that he has not picked up (it appears a moment after he arrives and is picked up a moment
     * after that). A secret the route left out of reach is not waited for at all: the warp goes at once.
     */
    private static String takingHere(Minecraft client, String room, int[] counted, int waited) {
        if (client.player == null) {
            return null;
        }
        DungeonLayout layout = DungeonLayout.capture();
        int id = roomIdOf(layout, room);
        if (id < 0 || layout.roomAtWorld(client.player.getX(), client.player.getZ()) != id) {
            return null;
        }
        // The mimic of a trapped chest just opened here (Kill Mimic fighting it): the chest counts on the click, but its
        // +2 needs the kill, and warping away left the sceptre firing at the floor of the next room for its whole 5 s
        // (fullclear-1016 run 008, Arrow Trap). Waited like a secret still being taken.
        if (com.killer560.hub.autoroutes.MimicKiller.isBusy()) {
            return "the mimic (Kill Mimic is fighting it)";
        }
        boolean all = counted == null || counted[0] >= counted[1];
        String aura = com.killer560.hub.cheatutils.SecretAuraFeature.takingInReach(client, all);
        if (aura != null || all) {
            return aura;
        }
        // A secret chest in Secret Aura's reach that has not appeared yet: it spawns in once he has been near it a moment,
        // as a secret bat does (killer560, 2026-10-18), so at a route's end it can still be air while the aura has
        // nothing to see. Waited for as long as it takes to appear (ITEM_APPEAR_TICKS); once there, the aura's own scan
        // above holds the warp until it is opened. (147, 2026-10-20: with the warp going at once when nothing was close,
        // two of five such chests were left behind.)
        com.killer560.hub.cheatutils.CheatUtilsConfig aura2 = com.killer560.hub.cheatutils.CheatUtilsConfig.getInstance();
        if (waited < ITEM_APPEAR_TICKS && aura2.isSecretAuraEnabled() && aura2.isAuraChests() && client.level != null) {
            net.minecraft.world.phys.Vec3 eye = client.player.getEyePosition();
            double reachSq = aura2.getAuraRange() * aura2.getAuraRange();
            for (net.minecraft.core.BlockPos at : com.killer560.hub.secretwaypoints.SecretWaypointsFeature
                    .currentRoomChestSecrets()) {
                if (com.killer560.hub.util.BlockHits.boxDistanceSq(eye, at) > reachSq || !client.level.isLoaded(at)) {
                    continue;
                }
                net.minecraft.world.level.block.Block b = client.level.getBlockState(at).getBlock();
                if (b != net.minecraft.world.level.block.Blocks.CHEST
                        && b != net.minecraft.world.level.block.Blocks.TRAPPED_CHEST) {
                    return "the chest at " + at.toShortString() + " (not there yet)";
                }
            }
        }
        net.minecraft.world.phys.AABB body = client.player.getBoundingBox();
        for (net.minecraft.core.BlockPos at : com.killer560.hub.secretwaypoints.SecretWaypointsFeature
                .currentRoomItemSecrets()) {
            double dx = Math.max(0, Math.max(at.getX() - body.maxX, body.minX - (at.getX() + 1)));
            double dy = Math.max(0, Math.max(at.getY() - body.maxY, body.minY - (at.getY() + 1)));
            double dz = Math.max(0, Math.max(at.getZ() - body.maxZ, body.minZ - (at.getZ() + 1)));
            net.minecraft.world.phys.Vec3 centre = net.minecraft.world.phys.Vec3.atCenterOf(at);
            if (dx * dx + dy * dy + dz * dz > ITEM_DUE_RANGE * ITEM_DUE_RANGE
                    || com.killer560.hub.autoroutes.RouteRunMemory.gotNear("item", centre, 2.5)) {
                continue;
            }
            // Before it appears, only for as long as an item takes to appear: one that never does (taken by a teammate,
            // or a database position the room does not have) is not waited for past that.
            boolean lying = client.level != null && !client.level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(centre, centre).inflate(2.5)).isEmpty();
            if (lying || waited < ITEM_APPEAR_TICKS) {
                return "the item at " + at.toShortString() + (lying ? "" : " (not there yet)");
            }
        }
        return null;
    }

    /**
     * {found, total} of a room's secrets, or null when it has none to count (or is not on the map): the larger of the map's
     * count (the action bar's "x/y", or a green check) and the count when ITS trip began ({@link #leaveFoundAtStart},
     * never the next room's) plus every secret of his taken in the room since
     * ({@link com.killer560.hub.autoroutes.RouteRunMemory}, counted on the click or pickup itself - the action bar only
     * says so on its next update).
     */
    private static int[] roomCount(String name) {
        RoomStatus.Room r = roomNamed(name);
        if (r == null || r.secrets() <= 0) {
            return null;
        }
        if (DungeonInfoFeature.secretsFoundPercent() >= 100.0) {
            return new int[]{r.secrets(), r.secrets()};
        }
        int found = r.secrets() - r.unfound();
        DungeonLayout layout = DungeonLayout.capture();
        int id = roomIdOf(layout, name);
        int got = id < 0 ? 0 : com.killer560.hub.autoroutes.RouteRunMemory.secretsSince(leaveStartClock,
                p -> layout.roomAtWorld(p.x, p.z) == id);
        return new int[]{Math.min(r.secrets(), Math.max(found, leaveFoundAtStart + got)), r.secrets()};
    }

    /** Where {@link #roomCount}'s number came from, for the last-node wait's log lines. */
    private static String countWhy(String name) {
        RoomStatus.Room r = roomNamed(name);
        if (r == null) {
            return "not on the map";
        }
        DungeonLayout layout = DungeonLayout.capture();
        int id = roomIdOf(layout, name);
        List<String> got = id < 0 ? List.of() : com.killer560.hub.autoroutes.RouteRunMemory.secretsWhatSince(leaveStartClock,
                p -> layout.roomAtWorld(p.x, p.z) == id);
        return "map " + (r.secrets() - r.unfound()) + "/" + r.secrets() + ", run " + fmtPercent(
                DungeonInfoFeature.secretsFoundPercent()) + "%, at the trip's start " + leaveFoundAtStart + " + yours since "
                + got;
    }

    /** The auto puzzle last waited under, for the "is done - carrying on" log line. */
    private static String puzzleHoldSaid;
    /**
     * Every hold reason already said in chat for {@link #puzzleHoldRoom}: a reason is said once per room, however often
     * the hold comes and goes or the name it carries changes (killer560, 2026-10-15: "Waiting for Auto Ice Path chest to
     * finish" and "Waiting for Auto Puzzles to finish" posted to his chat every tick, alternating). The status line
     * ({@link #status}, the HUD) shows the current one throughout.
     */
    private static final java.util.Set<String> puzzleHoldsSaid = new java.util.HashSet<>();
    private static String puzzleHoldRoom;

    /**
     * While an auto puzzle is working, nothing here starts a trip, a route or a door, and no wait times out
     * (killer560, 2026-10-14: "auto clear related things need to realize when an auto puzzle is working to stop making
     * it etherwarp back to the start"). A trip already in flight, a playing route and a hand-off to Auto Clear are left
     * alone (Auto Routes and Auto Clear hold themselves). {@code phaseTicks} does not count under it, so the puzzle
     * wait, the Ice Fill wait and the start node's arm wait all start counting again where they were.
     * @return true while holding
     */
    private static boolean holdForPuzzle() {
        if (!com.killer560.hub.autopuzzles.AutoPuzzleActivity.isWorking()
                || phase == Phase.TRAVEL || phase == Phase.ROUTE || phase == Phase.AUTO_CLEAR) {
            if (puzzleHoldSaid != null && !com.killer560.hub.autopuzzles.AutoPuzzleActivity.isWorking()) {
                LOGGER.info("[AutoSecret] {} is done - carrying on ({})", puzzleHoldSaid, phase);
                puzzleHoldSaid = null;
            }
            return false;
        }
        String puzzle = com.killer560.hub.autopuzzles.AutoPuzzleActivity.what();
        status = "Waiting: " + puzzle + " is working";
        if (!java.util.Objects.equals(target, puzzleHoldRoom)) {
            puzzleHoldRoom = target;
            puzzleHoldsSaid.clear();
        }
        // Once per hold, not per name: Auto Water and Auto Puzzles mark the same run in turn, and saying so on every
        // change of name logged the wait several times a tick (his 2026-10-15 log, 22:43:19).
        if (puzzleHoldSaid == null) {
            puzzleHoldSaid = puzzle;
            if (puzzleHoldsSaid.add(puzzle)) {
                LOGGER.info("[AutoSecret] waiting: {} is working (phase {})", puzzle, phase);
                note(ModChat.dim("Waiting for " + puzzle + " to finish"));
            }
        }
        return true;
    }

    private static void pollToggleKey(Minecraft client) {
        int key = AutoSecretConfig.getInstance().getToggleKey();
        boolean down = key != KeyUtil.NONE && client.getWindow() != null && McCompat.screen(client) == null
                && KeyUtil.isBindDown(client.getWindow(), key);
        if (down && !toggleWasDown) {
            toggle();
        }
        toggleWasDown = down;
        int pkey = AutoSecretConfig.getInstance().getAutopilotKey();
        boolean pdown = pkey != KeyUtil.NONE && client.getWindow() != null && McCompat.screen(client) == null
                && KeyUtil.isBindDown(client.getWindow(), pkey);
        if (pdown && !pilotKeyWasDown) {
            toggleAutopilot();
        }
        pilotKeyWasDown = pdown;
    }

    /** @return true when it stopped */
    private static boolean stopChecks(Minecraft client) {
        if (!AutoSecretConfig.allowed()) {
            stop("cheat build and Skyblock only", true);
            return true;
        }
        if (client.player == null || client.level != level) {
            stop("the world changed", false);
            return true;
        }
        if (!DungeonState.isInDungeon()) {
            stop("the run ended", true);
            return true;
        }
        if (LiveMapFeature.isInBoss()) {
            stop("the boss was entered", true);
            return true;
        }
        double percent = DungeonInfoFeature.secretsFoundPercent();
        if (percent >= 100.0 && !pilot) {
            // Every room must be cleared too (300 needs them): with a mob room still to clear and Auto Clear on it carries
            // on - the endgame then takes the clears (and puzzles) and ends with "nothing left to do". Until 2026-10-20 it
            // stopped here and left them (sim floors with mobs: 15-16 rooms uncleared on two floors of four).
            if (AutoSecretConfig.getInstance().isAutoClearRooms() && AutoClearFeature.isAvailable()
                    && unclearedMobRoomLeft()) {
                if (planSaid.add("all-secrets")) {
                    LOGGER.info("[AutoSecret] every secret found ({}%) - carrying on for the rooms still to clear",
                            fmtPercent(percent));
                }
            } else {
                stop("all secrets found (" + fmtPercent(percent) + "%)", true);
                return true;
            }
        }
        if (client.player.isDeadOrDying()) {
            stop("you died", true);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------- player input

    private static KeyMapping[] movementKeys(Minecraft client) {
        var o = client.options;
        return new KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump};
    }

    private static void latchHeldKeys(Minecraft client) {
        KeyMapping[] keys = movementKeys(client);
        for (int i = 0; i < keys.length; i++) {
            latchedKeys[i] = physicallyDown(client, keys[i]);
            keyWasDown[i] = latchedKeys[i];
        }
    }

    /**
     * A FRESH press of a movement key is him taking over (a key already held when it started is ignored until he lets
     * go), and only with no screen open: the Return that ran the start command, typing in chat, the settings tab -
     * none of those is him moving (CLAUDE.md, the "any key stops it" lesson). Polled physically through GLFW, as
     * Auto Routes' fallback does, so a route holding the mappings cannot hide it.
     */
    private static boolean playerTookOver(Minecraft client) {
        if (McCompat.screen(client) != null) {
            return false;
        }
        KeyMapping[] keys = movementKeys(client);
        for (int i = 0; i < keys.length; i++) {
            boolean down = physicallyDown(client, keys[i]);
            if (!down) {
                latchedKeys[i] = false;
            }
            boolean fresh = down && !keyWasDown[i] && !latchedKeys[i];
            keyWasDown[i] = down;
            if (fresh) {
                stop("you pressed " + keyName(keys[i]), true);
                return true;
            }
        }
        // A route the player stopped himself ("you moved", the camera, /ar stop) is him taking over too.
        if (phase == Phase.ROUTE && !RouteExecutor.isRunning() && RouteExecutor.wasStoppedByUser()) {
            stop("you took over the route (" + RouteExecutor.stopReason() + ")", false);
            return true;
        }
        return false;
    }

    private static String keyName(KeyMapping mapping) {
        try {
            return ((com.killer560.hub.autoroutes.mixin.KeyMappingKeyAccessor) (Object) mapping).killer560smod$getKey()
                    .getDisplayName().getString();
        } catch (Throwable t) {
            return "a movement key";
        }
    }

    private static boolean physicallyDown(Minecraft client, KeyMapping mapping) {
        try {
            com.mojang.blaze3d.platform.InputConstants.Key key =
                    ((com.killer560.hub.autoroutes.mixin.KeyMappingKeyAccessor) (Object) mapping).killer560smod$getKey();
            if (key == null || key.getType() != com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM
                    || client.getWindow() == null) {
                return false;
            }
            return KeyUtil.isKeyDown(client.getWindow(), key.getValue());
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------- corrections

    /**
     * A server correction never stops anything (killer560, 2026-10-06). Two kinds are seen: one the Interactive Map's
     * executor caught on a path of ours (it plans again from where he was put, by itself), and a position packet while
     * nothing of ours was moving him. Both are said in chat with where he is, the alarm sounds, and the run re-plans.
     */
    private static void watchCorrections(Minecraft client) {
        boolean busy = ClearExecutor.isBusy() || RouteExecutor.isRunning() || phase == Phase.ICE_FILL
                || phase == Phase.PUZZLE_WAIT || phase == Phase.AUTO_CLEAR
                || com.killer560.hub.autopuzzles.AutoPuzzleActivity.isWorking();
        quietTicks = busy ? 0 : quietTicks + 1;
        int corrections = ClearExecutor.serverCorrections();
        if (corrections != seenCorrections) {
            seenCorrections = corrections;
            correction(client, "the server moved you off the warp path (it plans again from here)");
        }
        int packets = ClearExecutor.positionPackets();
        if (packets != seenPositionPackets) {
            seenPositionPackets = packets;
            if (!busy && quietTicks > CORRECTION_GRACE_TICKS) {
                correction(client, "the server moved you");
                if (phase == Phase.AWAIT_ROUTE || phase == Phase.SETTLE || phase == Phase.WAITING) {
                    setPhase(Phase.DECIDE);
                }
            }
        }
    }

    private static void correction(Minecraft client, String what) {
        String where = client.player == null ? "?" : client.player.blockPosition().toShortString();
        LOGGER.warn("[AutoSecret] correction: {} at {} (phase {}, target {})", what, where, phase, target);
        ModChat.send(chatName(), ModChat.bad("Server correction: "), ModChat.text(what), ModChat.dim(" at " + where
                + " - carrying on"));
        ModSounds.playCorrectionAlarm();
    }

    // ------------------------------------------------------------------------------------------- deciding

    private static void decide(Minecraft client) {
        blockedSecrets = null;
        // killer560, 2026-10-14: "if i have already used auto routes in a room during auto clear then it should treat
        // it as though I got every secret in the room even if it is missing one, since some rooms auto routes will
        // leave it missing a secret because it is so unoptimal to do." A room whose route ran to its end this run -
        // by this run, an earlier one of Auto Secret's, or by hand - is never a secret target again.
        for (String routed : com.killer560.hub.autoroutes.RouteRunMemory.routedRooms()) {
            if (!revisited.contains(routed) && secreted.add(routed)) {
                LOGGER.info("[AutoSecret] {}: its route already ran to the end this run - every secret counts as got",
                        routed);
            }
        }
        DungeonLayout layout = DungeonLayout.capture();
        int here = layout.currentRoom();
        if (here >= 0) {
            lastRoomName = layout.name(here);
        } else if (lastRoomName != null && client.player != null && client.player.onGround()) {
            // Standing in a doorway's connector cell (the spot two blocks back from a door it just opened, in
            // 142-sim-autopilot2) reads as no room, and it waited there for good. The warp planner works from where he
            // really stands; the room graph only needs the room he was in a moment ago.
            here = roomIdOf(layout, lastRoomName);
        }
        if (here >= 0 && layout.entry(here) != null && "TRAP".equalsIgnoreCase(layout.entry(here).type)) {
            // killer560 (docs/SIM.md "Trap rooms take your abilities"): no etherwarp, teleport or ability works in a trap
            // room, so no trip can start here - waiting would be forever. His trap routes are meant to end outside it.
            stop("in " + layout.name(here) + ", a trap room: no etherwarp or ability works in one, so it can't warp out -"
                    + " walk out and start it again (a trap route should end outside the room)", false);
            return;
        }
        if (here < 0 || !AutoClearUtils.canPath(layout, true)) {
            status = "Waiting to be able to path from here";
            if (phaseTicks == 100) {
                LOGGER.info("[AutoSecret] can't path from here: room {} ({}), on ground {}", here, here >= 0 ? layout.name(here)
                        : "none", client.player != null && client.player.onGround());
                say(ModChat.dim("Can't start a path from here yet (in the air, a maze, Boulder or past a trap's start)."));
            }
            return;
        }
        List<RoomStatus.Room> rooms = RoomStatus.rooms();
        Map<Integer, Integer> dist = new HashMap<>();
        for (RoomStatus.Room r : rooms) {
            if (r.room() == here) {
                dist.put(r.room(), 0);
                continue;
            }
            List<DungeonMapPathfinder.RoomStep> path = DungeonMapPathfinder.findPath(layout, here, r.room(), false);
            if (path != null) {
                dist.put(r.room(), path.size() - 1);
            }
        }

        if (pilot) {
            decidePilot(layout, rooms, dist, here);
            return;
        }
        if (decideBloodFirst(layout, rooms, dist)) {
            return;
        }
        if (decideQuiz(layout, rooms, dist)) {
            return;
        }
        if (decideIceFill(layout, rooms, dist)) {
            return;
        }
        if (decideClearHere(layout, rooms, here)) {
            return;
        }
        if (decideInstaClear(layout, rooms, dist, here)) {
            return;
        }
        if (decideSecrets(layout, rooms, dist)) {
            return;
        }
        decideEndgame(layout, rooms, dist);
    }

    /**
     * Blood First (killer560, 2026-10-17: "add a blood first where you can select door camp or full blood rush first then
     * it does only secrets"), until the blood door reads open; after that it is never looked at again this run, and the
     * run is secrets only (Auto Secret never goes into the blood room). Both modes take their doors and keys from the
     * Autopilot's Blood First rule ({@link Autopilot#bloodFirstStep}: the first locked door on the room path to the room
     * in front of the blood door, then the blood door; a key for it lying in a room it can reach first), opened by this
     * class's own door step. <b>Full Blood Rush</b> first hands each uncleared mob room of the Blood Rush Split path it can
     * reach to Auto Clear, in path order (Auto Clear's Blood Rush Split rule, {@link AutoClearFeature#bloodRushRooms}).
     * When neither has a step (the next door wants a key nobody has, or no door is on the map yet) it secrets meanwhile
     * and looks again at every decision, as the Autopilot does.
     * @return true when it started a Blood First step
     */
    private static boolean decideBloodFirst(DungeonLayout layout, List<RoomStatus.Room> rooms, Map<Integer, Integer> dist) {
        if (bloodFirstMode == AutoSecretConfig.SecretBloodFirst.OFF || bloodFirstDone) {
            return false;
        }
        int blood = layout.bloodDoor();
        if (blood >= 0 && !layout.isLocked(blood)) {
            bloodFirstDone = true;
            LOGGER.info("[AutoSecret] Blood First ({}): the blood door is open - secrets only from here",
                    bloodFirstMode.label());
            return false;
        }
        if (bloodFirstMode == AutoSecretConfig.SecretBloodFirst.FULL_BLOOD_RUSH) {
            List<Integer> rush = AutoClearFeature.bloodRushRooms(layout);
            if (rush != null) {
                for (int id : rush) {
                    if (!dist.containsKey(id) || !AutoClearFeature.isMobRoom(layout, id)) {
                        continue;
                    }
                    for (RoomStatus.Room r : rooms) {
                        if (r.room() == id && !r.cleared() && !"Unknown".equals(r.name()) && !clearsDone.contains(r.name())) {
                            LOGGER.info("[AutoSecret] Blood First (Full Blood Rush): clearing {} - room {} of the blood rush"
                                    + " path", r.name(), rush.indexOf(id) + 1);
                            goClear(layout, r, dist);
                            return true;
                        }
                    }
                }
            }
        }
        Autopilot.Order o = Autopilot.bloodFirstStep(layout, dist);
        if (o != null && o.type() == Autopilot.Type.KEY && o.key() != null) {
            bloodFirstNote = null;
            goKey(o.key(), o.why());
            return true;
        }
        if (o != null && o.type() == Autopilot.Type.DOOR) {
            boolean unreachable = secreted.contains(doorName(o.door()));
            if (unreachable || o.door() == doorCell && doorClicks >= DOOR_CLICKS) {
                // Its trip there failed three times (tripFailed left it out), or the door stayed shut under every click
                // the door step makes: the rest of the run is secrets rather than standing at it.
                bloodFirstDone = true;
                String kind = o.blood() ? "blood" : "wither";
                LOGGER.info("[AutoSecret] Blood First ({}): {} door at cell {} {} - secrets only from here",
                        bloodFirstMode.label(), kind, o.door(), unreachable ? "out of reach" : "stayed shut");
                say(ModChat.bad("Blood First: " + (unreachable ? "couldn't get to the " + kind + " door"
                        : "the " + kind + " door stayed shut")), ModChat.dim(" - secrets only from here"));
                resetDoor();
                return false;
            }
            bloodFirstNote = null;
            LOGGER.info("[AutoSecret] Blood First ({}): {} (cell {})", bloodFirstMode.label(), o.why(), o.door());
            goToDoor(layout, o.door(), o.blood(), o.why());
            return true;
        }
        String why = blood < 0 ? "the blood door is not on the map yet" : "the next door on the way wants a key nobody has";
        if (!why.equals(bloodFirstNote)) {
            bloodFirstNote = why;
            LOGGER.info("[AutoSecret] Blood First ({}): {} - secrets meanwhile, looking again each decision",
                    bloodFirstMode.label(), why);
        }
        return false;
    }

    /** The run's Blood First mode and whether it is over, for the testkit: "OFF", or e.g. "DOOR_CAMP done". */
    public static String bloodFirstState() {
        return bloodFirstMode.name() + (bloodFirstDone ? " done" : "");
    }

    /**
     * The room on the near side of the blood door (the room "before blood"), or -1 while the blood door is not on the map
     * or neither side of it reads as the blood room. A door cell sits between the two room cells it joins: across x when
     * its x is odd, across z when its z is odd.
     */
    static int preBloodRoom(DungeonLayout layout, List<RoomStatus.Room> rooms) {
        int door = layout.bloodDoor();
        if (door < 0) {
            return -1;
        }
        int gx = door % DungeonLayout.GRID;
        int a;
        int b;
        if (gx % 2 == 1) {
            a = layout.roomOfCell(door - 1);
            b = layout.roomOfCell(door + 1);
        } else {
            a = layout.roomOfCell(door - DungeonLayout.GRID);
            b = layout.roomOfCell(door + DungeonLayout.GRID);
        }
        boolean aBlood = isBloodRoom(layout, rooms, a);
        boolean bBlood = isBloodRoom(layout, rooms, b);
        if (aBlood == bBlood) {
            return -1;
        }
        return aBlood ? b : a;
    }

    private static boolean isBloodRoom(DungeonLayout layout, List<RoomStatus.Room> rooms, int room) {
        if (room < 0) {
            return false;
        }
        if (layout.entry(room) != null && "BLOOD".equalsIgnoreCase(layout.entry(room).type)) {
            return true;
        }
        for (RoomStatus.Room r : rooms) {
            if (r.room() == room && r.isType("BLOOD")) {
                return true;
            }
        }
        return false;
    }

    /** For the testkit: the room Auto Secret holds for last on the current floor, by name, or null. */
    public static String preBloodRoomName() {
        DungeonLayout layout = DungeonLayout.capture();
        int room = preBloodRoom(layout, RoomStatus.rooms());
        return room < 0 ? null : layout.name(room);
    }

    private static boolean eligibleForSecrets(RoomStatus.Room r) {
        // Trap rooms only through Auto Trap (killer560, 2026-10-06: "it will use auto trap to do trap rooms").
        return !"Unknown".equals(r.name()) && !r.isType("PUZZLE") && !r.isType("BLOOD")
                && (!r.isType("TRAP") || com.killer560.hub.autotrap.AutoTrap.usable(r.name()))
                && !r.isType("ENTRANCE") && r.unfound() > 0 && !secreted.contains(r.name());
    }

    private static boolean decideIceFill(DungeonLayout layout, List<RoomStatus.Room> rooms, Map<Integer, Integer> dist) {
        if (iceFillTried || !AutoSecretConfig.getInstance().isIceFillFirst()
                || !AutoPuzzlesConfig.getInstance().isAutoIceFillEnabled()) {
            return false;
        }
        for (RoomStatus.Room r : rooms) {
            if (!"Ice Fill".equals(r.name()) || r.cleared() || !dist.containsKey(r.room())) {
                continue;
            }
            iceFillTried = true;
            LOGGER.info("[AutoSecret] Ice Fill first: {} room(s) away", dist.get(r.room()));
            note(ModChat.text("Going to "), ModChat.value("Ice Fill"), ModChat.dim(" first - Auto Ice Fill does it"));
            if (layout.currentRoom() == r.room()) {
                beginIceFill();
                return true;
            }
            // Named before the trip: a failed Ice Fill trip was "trip to null", and its give-up never took Ice Fill
            // out, so Auto Secret went "Ice Fill first" for the whole test (fullclear-1016 run 036, 480 s).
            target = r.name();
            return pathToRoom(layout, r, Trip.ICE_FILL);
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------- the Quiz

    /**
     * killer560, 2026-10-17: "have it open quiz as its first goal if the setting is on. Then after that go do ice fill then
     * come back and answer it, then go secret a room then come back and answer it till it finishes". With Quiz First and
     * Auto Quiz on: first a step into the Quiz, which starts Oruo's countdown (killer560, 2026-10-18: "quiz starts once you
     * walk in"), and straight out again; then, while a question's cooldown runs
     * ({@link QuizState}), Ice Fill during the first, else the room with the most unfound secrets whose whole round trip -
     * the trip there, its route, the trip back to the answer spot, each by {@link RoomTour}'s estimates - fits in the time
     * left; with nothing that fits, back to the Quiz to wait. Re-decided after every step, so he is back by the time the
     * question opens, late only by what the estimates missed.
     *
     * @return true when this decision is the Quiz's
     */
    private static boolean decideQuiz(DungeonLayout layout, List<RoomStatus.Room> rooms, Map<Integer, Integer> dist) {
        if (!QuizPlan.wanted() || QuizPlan.gaveUp() || com.killer560.hub.puzzlesolvers.QuizState.done()) {
            return false;
        }
        RoomStatus.Room quiz = null;
        for (RoomStatus.Room r : rooms) {
            if ("Quiz".equals(r.name()) && !r.cleared() && !r.failed() && dist.containsKey(r.room())) {
                quiz = r;
            }
        }
        if (quiz == null) {
            return false;
        }
        boolean here = layout.currentRoom() == quiz.room();
        if (!com.killer560.hub.puzzlesolvers.QuizState.started()) {
            if (RoomStatus.teammateInside(quiz.cells()) && !here) {
                QuizPlan.giveUp("a teammate is in it");
                LOGGER.info("[AutoSecret] Quiz: a teammate is in it - left to them");
                return false;
            }
            if (!QuizPlan.tryStart()) {
                QuizPlan.giveUp("the Quiz never began");
                LOGGER.info("[AutoSecret] Quiz: Oruo never began after {} walk-ins - left to the rest of the run",
                        QuizPlan.MAX_START_TRIES);
                say(ModChat.bad("Couldn't start the Quiz"), ModChat.dim(" - Oruo never began"));
                return false;
            }
            target = quiz.name();
            targetRoomId = quiz.room();
            status = "Starting the Quiz";
            LOGGER.info("[AutoSecret] Quiz first: {} room(s) away - walking in starts it", dist.get(quiz.room()));
            note(ModChat.text("Going to the "), ModChat.value("Quiz"), ModChat.dim(" first - walking in starts it"));
            if (here) {
                beginQuizWait();
                return true;
            }
            return goToQuiz(layout, quiz);
        }
        long left = com.killer560.hub.puzzlesolvers.QuizState.ticksUntilAnswerable();
        // Ice Fill while the first question's long cooldown runs (it is done whatever the estimate: his order).
        if (com.killer560.hub.puzzlesolvers.QuizState.question() == 1
                && com.killer560.hub.puzzlesolvers.QuizState.awaitingAnswer() && left > 0
                && decideIceFill(layout, rooms, dist)) {
            LOGGER.info("[AutoSecret] Quiz: question 1 opens in {} tick(s) - Ice Fill meanwhile", left);
            return true;
        }
        if (left > 0 && quizGapClearHere(layout, rooms, quiz, left)) {
            return true;
        }
        if (left > 0) {
            RoomStatus.Room pick = quizGapRoom(layout, rooms, dist, quiz, left);
            if (pick != null && goSecret(layout, pick, dist, "-", "-")) {
                return true;
            }
        }
        target = quiz.name();
        targetRoomId = quiz.room();
        status = "At the Quiz: " + com.killer560.hub.puzzlesolvers.QuizState.describe();
        if (here && (AutoPuzzlesFeature.quizAnswerSpot() == null || atQuizSpot())) {
            beginQuizWait();
            return true;
        }
        LOGGER.info("[AutoSecret] Quiz: question {} opens in {} tick(s) - back to the Quiz",
                com.killer560.hub.puzzlesolvers.QuizState.nextQuestion(), left);
        return goToQuiz(layout, quiz);
    }

    /** Ticks kept in hand when a clear fills a Quiz gap (the clear's estimate is a straight-line one). */
    static final int QUIZ_GAP_CLEAR_SPARE_TICKS = 20;

    /**
     * Fastest Total between two Quiz answers: the room he just secreted in a gap, still uncleared, is cleared now when
     * the clear and the trip back to the Quiz fit in the ticks left (by {@link AutoClearFeature#estimateClear}, with
     * {@link #QUIZ_GAP_CLEAR_SPARE_TICKS} in hand) - else it waits for a later visit, as before.
     */
    private static boolean quizGapClearHere(DungeonLayout layout, List<RoomStatus.Room> rooms, RoomStatus.Room quiz,
                                            long left) {
        Minecraft client = Minecraft.getInstance();
        int here = layout.currentRoom();
        if (!fastestTotal() || client.player == null || here < 0 || here == quiz.room() || leaveWaitRoom != null) {
            return false;
        }
        RoomStatus.Room r = null;
        for (RoomStatus.Room x : rooms) {
            if (x.room() == here) {
                r = x;
            }
        }
        if (r == null || !needsClear(layout, r) || secretsPending(r)) {
            return false;
        }
        double[] est = AutoClearFeature.estimateClear(client.player.position(), r.name());
        if (est == null) {
            return false;
        }
        BlockPos spot = AutoPuzzlesFeature.quizAnswerSpot();
        BlockPos q = spot != null ? spot : DungeonLayout.cellCenter(quiz.mainTile());
        double back = AutoClearFeature.etherTicks(new net.minecraft.world.phys.Vec3(est[1], est[2], est[3]),
                net.minecraft.world.phys.Vec3.atBottomCenterOf(q.above()));
        boolean fits = est[0] + back + QUIZ_GAP_CLEAR_SPARE_TICKS <= left;
        LOGGER.info(String.format(Locale.US, "[AutoSecret] Quiz: %d tick(s) to the next question; clearing %s here ~%.0f"
                + " + back to the Quiz ~%.0f - %s", left, r.name(), est[0], back, fits ? "fits, clearing it now"
                : "does not fit"));
        if (!fits) {
            return false;
        }
        LOGGER.info("[AutoSecret] clear here: {} - between two Quiz answers, before he goes back", r.name());
        clearsDone.add(r.name());
        target = r.name();
        targetRoomId = r.room();
        status = "Clearing " + r.name() + " (Quiz gap)";
        arrived(Trip.CLEAR);
        return true;
    }

    /** He stands on Auto Quiz's answer spot (or a block from it). */
    private static boolean atQuizSpot() {
        BlockPos spot = AutoPuzzlesFeature.quizAnswerSpot();
        Minecraft client = Minecraft.getInstance();
        return spot != null && client.player != null && client.player.blockPosition().below().distSqr(spot) <= 2;
    }

    /** The trip to the Quiz: onto Auto Quiz's answer spot once it is known, else into the room. */
    private static boolean goToQuiz(DungeonLayout layout, RoomStatus.Room quiz) {
        BlockPos spot = AutoPuzzlesFeature.quizAnswerSpot();
        target = quiz.name();
        targetRoomId = quiz.room();
        if (spot != null) {
            AutoRoutesFeature.cancelForInteractiveMap("Auto Secret");
            ClearExecutor.setExternalOwner(true);
            ClearExecutor.etherPathExact(spot, null);
            beginTravel(Trip.QUIZ);
            return true;
        }
        return pathToRoom(layout, quiz, Trip.QUIZ);
    }

    /**
     * The secret room to do before the next Quiz question opens: of the rooms Auto Secret would secret, the one with the
     * most unfound secrets (then the quickest) whose trip there, route and trip back to the Quiz all fit in {@code left}
     * ticks by {@link RoomTour}'s estimates. Null when none fits. The trips are also handed to RoomTour's search, so a
     * later decision has them searched.
     */
    private static RoomStatus.Room quizGapRoom(DungeonLayout layout, List<RoomStatus.Room> rooms,
                                               Map<Integer, Integer> dist, RoomStatus.Room quiz, long left) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return null;
        }
        RoomTour.Site quizSite = quizSite(quiz);
        if (quizSite == null) {
            return null;
        }
        List<RoomTour.Site> sites = new ArrayList<>();
        List<RoomStatus.Room> cands = new ArrayList<>();
        for (RoomStatus.Room r : rooms) {
            if (!eligibleForSecrets(r) || !dist.containsKey(r.room())) {
                continue;
            }
            Route route = RouteStore.getInstance().forRoom(r.name());
            if (route == null || route.startNode() == null) {
                continue;
            }
            RoomTour.Site s = secretSite(layout, r, r.unfound(), Autopilot.keyOf(r));
            if (s != null) {
                sites.add(s);
                cands.add(r);
            }
        }
        if (sites.isEmpty()) {
            LOGGER.info("[AutoSecret] Quiz: {} tick(s) to question {} and no secret room left to fill them", left,
                    com.killer560.hub.puzzlesolvers.QuizState.nextQuestion());
            return null;
        }
        sites.add(quizSite);
        net.minecraft.world.phys.Vec3 from = client.player.position();
        if (!RoomTour.isBusy() && !RoomTour.fromReady(from, sites)) {
            RoomTour.request(from, sites, false, false, "the Quiz's cooldown");
        }
        RoomTour.Matrix m = RoomTour.matrix(from, sites, false, layout);
        int q = sites.size() - 1;
        RoomStatus.Room best = null;
        double bestTotal = 0;
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < q; i++) {
            double total = m.fromHere()[i] + m.work()[i] + m.between()[i][q];
            RoomStatus.Room r = cands.get(i);
            why.append(String.format(Locale.US, "%s %.0f+%.0f+%.0f=%.0f%s; ", r.name(), m.fromHere()[i], m.work()[i],
                    m.between()[i][q], total, total <= left ? "" : " (too long)"));
            if (total > left) {
                continue;
            }
            if (best == null || r.unfound() > best.unfound() || r.unfound() == best.unfound() && total < bestTotal) {
                best = r;
                bestTotal = total;
            }
        }
        LOGGER.info("[AutoSecret] Quiz: {} tick(s) until question {} opens; round trips (there + route + back): {}-> {}",
                left, com.killer560.hub.puzzlesolvers.QuizState.nextQuestion(), why,
                best == null ? "none fits, waiting at the Quiz" : best.name());
        return best;
    }

    /** The Quiz as a trip's end: Auto Quiz's answer spot (exact), else a landing in its tile. */
    private static RoomTour.Site quizSite(RoomStatus.Room quiz) {
        BlockPos spot = AutoPuzzlesFeature.quizAnswerSpot();
        if (spot != null) {
            return new RoomTour.Site("Quiz", spot, -1, true, net.minecraft.world.phys.Vec3.atBottomCenterOf(spot.above()),
                    0, 0);
        }
        BlockPos centre = DungeonLayout.cellCenter(quiz.mainTile());
        BlockPos land = com.killer560.hub.livemap.autoclear.TeleportUtils.etherwarpableInTile(centre,
                net.minecraft.world.phys.Vec3.atCenterOf(centre));
        return land == null ? null : new RoomTour.Site("Quiz", land, quiz.mainTile(), false,
                net.minecraft.world.phys.Vec3.atBottomCenterOf(land.above()), 0, 0);
    }

    /** {@link com.killer560.hub.puzzlesolvers.QuizState#answeredCount()} and whether Oruo had begun, when the wait began. */
    private static int quizWaitAnswered;
    private static boolean quizWaitStarted;
    private static long quizWaitLag;
    /** Ticks at the Quiz waiting for its cooldown with nothing to do, this run (for the testkit's idle bound). */
    private static int quizIdleTicks;
    /** Without Oruo's first line this long after walking in (his logs: 0-1 s), the start is tried again. */
    private static final int QUIZ_START_WAIT_TICKS = 5 * 20;

    private static void beginQuizWait() {
        target = "Quiz";
        quizWaitAnswered = com.killer560.hub.puzzlesolvers.QuizState.answeredCount();
        quizWaitStarted = com.killer560.hub.puzzlesolvers.QuizState.started();
        quizWaitLag = com.killer560.hub.util.ServerLag.mark();
        status = "At the Quiz: " + com.killer560.hub.puzzlesolvers.QuizState.describe();
        LOGGER.info("[AutoSecret] at the Quiz ({})", com.killer560.hub.puzzlesolvers.QuizState.describe());
        setPhase(Phase.QUIZ);
    }

    /**
     * At the Quiz: Auto Quiz answers. The next decision comes on the line that changes things - Oruo beginning as he walks
     * in (go and do Ice Fill), an answer counting (go and do a room) or the last answer.
     */
    private static void tickQuiz() {
        status = "At the Quiz: " + com.killer560.hub.puzzlesolvers.QuizState.describe();
        if (!quizWaitStarted && com.killer560.hub.puzzlesolvers.QuizState.started()) {
            LOGGER.info("[AutoSecret] Quiz: Oruo began after {} tick(s) here - {}", phaseTicks,
                    com.killer560.hub.puzzlesolvers.QuizState.describe());
            setPhase(Phase.DECIDE);
            return;
        }
        if (com.killer560.hub.puzzlesolvers.QuizState.done()
                || com.killer560.hub.puzzlesolvers.QuizState.answeredCount() > quizWaitAnswered) {
            LOGGER.info("[AutoSecret] Quiz: {} after {} tick(s) here", com.killer560.hub.puzzlesolvers.QuizState.done()
                    ? "every question answered" : "question " + com.killer560.hub.puzzlesolvers.QuizState.answeredCount()
                    + " answered", phaseTicks);
            setPhase(Phase.DECIDE);
            return;
        }
        RoomStatus.Room r = roomNamed("Quiz");
        if (r != null && (r.failed() || r.cleared())) {
            LOGGER.info("[AutoSecret] Quiz: the map shows it {} - carrying on", r.failed() ? "failed" : "done");
            setPhase(Phase.DECIDE);
            return;
        }
        if (com.killer560.hub.puzzlesolvers.QuizState.awaitingAnswer()
                && !com.killer560.hub.puzzlesolvers.QuizState.answerable()) {
            quizIdleTicks++;
        }
        if (!quizWaitStarted && phaseTicks > QUIZ_START_WAIT_TICKS + com.killer560.hub.util.ServerLag.ticksSince(quizWaitLag)) {
            LOGGER.info("[AutoSecret] Quiz: Oruo has not begun in {} s here - trying again", QUIZ_START_WAIT_TICKS / 20);
            setPhase(Phase.DECIDE);
        }
    }

    /** For the testkit: ticks this run stood at the Quiz waiting out a cooldown. */
    public static int quizIdleTicks() {
        return quizIdleTicks;
    }

    /**
     * Before secreting: every uncleared room the Insta Clear tracker has a KNOWN entry for (only an entry
     * {@link InstaClearTracker#knownToInstaClear} says works - never a guess). Each known key carries its own landing
     * ({@link InstaClearTracker#realLandingFor}); the from-room is the near room (one or two rooms off) whose
     * {@link InstaClearTracker#entryKeyFor} gives that same key back. Getting to the from-room is a map trip; the last
     * warp is planned by the map's planner from there, which normally goes straight in. Each room is tried once a run.
     */
    private static boolean decideInstaClear(DungeonLayout layout, List<RoomStatus.Room> rooms, Map<Integer, Integer> dist,
                                            int here) {
        if (!AutoSecretConfig.getInstance().isInstaClear()) {
            return false;
        }
        for (RoomStatus.Room r : rooms) {
            if (r.cleared() || "Unknown".equals(r.name()) || instaTried.contains(r.name()) || !dist.containsKey(r.room())
                    || r.isType("PUZZLE") || r.isType("TRAP") || r.isType("BLOOD") || r.isType("ENTRANCE")
                    || r.isType("FAIRY")) {
                continue;
            }
            if (InstaClearTracker.knownEntries(r.name()).isEmpty()) {
                continue;
            }
            instaTried.add(r.name());
            List<RoomStatus.Room> froms = new ArrayList<>();
            for (RoomStatus.Room f : rooms) {
                if (f.room() == r.room() || "Unknown".equals(f.name()) || !dist.containsKey(f.room())) {
                    continue;
                }
                List<DungeonMapPathfinder.RoomStep> p = DungeonMapPathfinder.findPath(layout, f.room(), r.room(), true);
                if (p != null && p.size() - 1 >= 1 && p.size() - 1 <= 2) {
                    froms.add(f);
                }
            }
            // Each known key names its own landing (realLandingFor); the from-room is whichever near room gives the
            // same key back, so the key compared is the tracker's own, built the tracker's way.
            for (String known : InstaClearTracker.knownEntries(r.name())) {
                BlockPos landing = InstaClearTracker.realLandingFor(r.name(), known);
                if (landing == null) {
                    continue; // a walk-in entry: needs a door, and Auto Secret only warps
                }
                for (RoomStatus.Room f : froms) {
                    String key = InstaClearTracker.entryKeyFor(r.name(), landing, f.name());
                    if (!known.equals(key) || !InstaClearTracker.knownToInstaClear(r.name(), key)) {
                        continue;
                    }
                    LOGGER.info("[AutoSecret] insta clear {}: entry {} (land {} from {})", r.name(), key, landing, f.name());
                    note(ModChat.text("Insta clearing "), ModChat.value(r.name()), ModChat.dim(" from " + f.name()));
                    instaLanding = landing;
                    instaFrom = f.name();
                    target = r.name();
                    targetRoomId = r.room();
                    if (f.room() == here) {
                        return etherPathTo(landing, Trip.INSTA_INTO);
                    }
                    return pathToRoom(layout, f, Trip.INSTA_FROM);
                }
            }
            LOGGER.info("[AutoSecret] insta clear {}: {} known entr(ies), none matched a landing reachable from a near room",
                    r.name(), InstaClearTracker.knownEntries(r.name()).size());
        }
        return false;
    }

    private static boolean decideSecrets(DungeonLayout layout, List<RoomStatus.Room> rooms, Map<Integer, Integer> dist) {
        List<RoomStatus.Room> open = new ArrayList<>();
        List<RoomStatus.Room> blocked = new ArrayList<>();
        heldPreBlood = null;
        int preBlood = preBloodRoom(layout, rooms);
        RoomStatus.Room preBloodRoom = null;
        for (RoomStatus.Room r : rooms) {
            if (!eligibleForSecrets(r)) {
                continue;
            }
            if (r.room() == preBlood) {
                preBloodRoom = r;
                continue;
            }
            (dist.containsKey(r.room()) ? open : blocked).add(r);
        }
        // The room in front of the blood door is ALWAYS the last room secreted (killer560, 2026-10-18: "never have it
        // secret the room before blood until very last under any circumstance"). It joins the lists only when no other
        // room with secrets is left, open or behind a door; until then it is held, and the endgame takes it only when the
        // one thing left would be waiting on a teammate for a door (decideEndgame).
        if (preBloodRoom != null) {
            if (open.isEmpty() && blocked.isEmpty()) {
                (dist.containsKey(preBloodRoom.room()) ? open : blocked).add(preBloodRoom);
            } else {
                heldPreBlood = preBloodRoom;
                if (!preBloodRoom.name().equals(preBloodSaid)) {
                    preBloodSaid = preBloodRoom.name();
                    LOGGER.info("[AutoSecret] {} is the room in front of the blood door - held until it is the last room"
                            + " with secrets ({} open, {} behind a door before it)", preBloodRoom.name(), open.size(),
                            blocked.size());
                }
            }
        }
        open.sort(Comparator.comparingInt((RoomStatus.Room r) -> -r.unfound()).thenComparingInt(r -> dist.get(r.room())));
        String blockedText = describe(blocked);
        // A room with no route is said once and left out, as goSecret does when it comes to one.
        List<RoomStatus.Room> routed = new ArrayList<>();
        for (RoomStatus.Room r : open) {
            Route route = RouteStore.getInstance().forRoom(r.name());
            if (route == null || route.startNode() == null) {
                goSecret(layout, r, dist, describe(open), blockedText);
            } else {
                routed.add(r);
            }
        }
        routed = skipUnneeded(layout, rooms, routed);
        // Fastest Total: the rooms with nothing left but a clear are stops on the same tour (not the endgame's, one by
        // one once every secret is done) - while there are secrets left to tour; after that the endgame clears them.
        List<RoomStatus.Room> clearOnly = routed.isEmpty() ? List.of() : clearOnlyRooms(layout, rooms, dist, routed);
        List<Stop> order = tourOrder(layout, routed, clearOnly);
        if (order == null) {
            return true;   // the trips from here are being searched (a few ticks at most)
        }
        List<RoomStatus.Room> secretOrder = new ArrayList<>();
        for (Stop s : order) {
            if (!s.clear()) {
                secretOrder.add(s.room());
            }
        }
        publishPlan(secretOrder, dist);   // the Progress HUD shows the tour order it will actually take
        for (Stop s : order) {
            if (s.clear()) {
                goClear(layout, s.room(), dist, "clear stop (its turn on the tour, Fastest Total): ");
                return true;
            }
            if (decideClearFirst(layout, s.room())) {
                return true;
            }
            if (goSecret(layout, s.room(), dist, describe(open), blockedText)) {
                return true;
            }
        }
        // Secret rooms behind a closed door are NOT waited on here (killer560, 2026-10-06: never wait at a door
        // while there is anything else useful to do) - the endgame does the useful things first, then the door.
        blockedSecrets = blocked.isEmpty() ? null : blockedText;
        return false;
    }

    /**
     * The rooms it will secret, in the order it will try them, for the Auto Routes Progress HUD
     * ({@link com.killer560.hub.autoroutes.RunPlan}): the open rooms {@link #decideSecrets} just sorted, minus any with no
     * recorded route (it skips those). Rooms done = those it has finished that have a route.
     */
    private static void publishPlan(List<RoomStatus.Room> open, Map<Integer, Integer> dist) {
        List<String> names = new ArrayList<>();
        // The time it all takes (killer560, 2026-10-18: "an estimated time till completion that counts down"): in tour
        // order, the etherwarp trip from where he is (then from room to room) as Autopilot and Auto Clear price one, and
        // each route at Autopilot's seconds per node (learned this run when Autopilot has run one, else its start value).
        double eta = 0.0;
        Minecraft client = Minecraft.getInstance();
        net.minecraft.world.phys.Vec3 at = client.player == null ? null : client.player.position();
        double hop = com.killer560.hub.livemap.autoclear.ClearExecutor.hopRange();
        for (RoomStatus.Room r : open) {
            Route route = RouteStore.getInstance().forRoom(r.name());
            if (route != null && route.startNode() != null) {
                names.add(r.name());
                net.minecraft.core.BlockPos c = com.killer560.hub.livemap.DungeonLayout.cellCenter(r.mainTile());
                net.minecraft.world.phys.Vec3 centre = new net.minecraft.world.phys.Vec3(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
                if (at != null) {
                    double dx = at.x - centre.x;
                    double dz = at.z - centre.z;
                    int steps = names.size() == 1 ? dist.getOrDefault(r.room(), 1) : 1;
                    eta += com.killer560.hub.autoroutes.RunPlan.tripSeconds(Math.sqrt(dx * dx + dz * dz), hop, steps);
                }
                eta += 1.0 + Autopilot.routeSecondsPerNode() * route.nodes().size();
                at = centre;
            }
        }
        int done = 0;
        for (String s : secreted) {
            if (RouteStore.getInstance().forRoom(s) != null) {
                done++;
            }
        }
        com.killer560.hub.autoroutes.RunPlan.publish(com.killer560.hub.autoroutes.RunPlan.Source.AUTO_SECRET, names, null,
                done, client.player == null ? Double.NaN : eta);
    }

    /**
     * The rooms in the order to secret them (killer560, 2026-10-15: "focus more on not etherwarping all the way across
     * the map"): {@link TourPlanner} over the Interactive Map's real trip costs between them ({@link RoomTour}), each
     * room worth its unfound secrets. Null while the trips from here are still being searched (at most
     * {@link #TOUR_WAIT_TICKS}; a trip planned ahead from here is taken at once).
     */
    /** One stop of Auto Secret's tour: a room's secrets (its route), or - Fastest Total - a room's clear alone. */
    record Stop(RoomStatus.Room room, boolean clear) {
    }

    private static List<Stop> stops(List<RoomStatus.Room> rooms, boolean clear) {
        List<Stop> out = new ArrayList<>();
        for (RoomStatus.Room r : rooms) {
            out.add(new Stop(r, clear));
        }
        return out;
    }

    private static List<Stop> tourOrder(DungeonLayout layout, List<RoomStatus.Room> routed, List<RoomStatus.Room> clearOnly) {
        Minecraft client = Minecraft.getInstance();
        if (routed.size() + clearOnly.size() <= 1 || client.player == null) {
            tourWaitTicks = 0;
            List<Stop> out = stops(pendingFirst(routed), false);
            out.addAll(stops(clearOnly, true));
            return out;
        }
        List<RoomTour.Site> sites = new ArrayList<>();
        List<Stop> withSite = new ArrayList<>();
        List<Stop> rest = new ArrayList<>();
        for (RoomStatus.Room r : routed) {
            RoomTour.Site s = secretSite(layout, r, r.unfound(), Autopilot.keyOf(r));
            if (s == null) {
                rest.add(new Stop(r, false));
            } else {
                sites.add(new RoomTour.Site(s.key(), s.arrive(), s.tile(), s.exact(), s.leave(), s.weight(), 0));
                withSite.add(new Stop(r, false));
            }
        }
        for (RoomStatus.Room r : clearOnly) {
            BlockPos c = DungeonLayout.cellCenter(r.mainTile());
            sites.add(new RoomTour.Site(CLEAR_KEY + Autopilot.keyOf(r), c, r.mainTile(), false,
                    net.minecraft.world.phys.Vec3.atBottomCenterOf(c), 0, 0));
            withSite.add(new Stop(r, true));
        }
        if (pendingSecret != null) {
            // The second half of a room it cleared first (decideClearFirst): its route now, whatever the tour says.
            for (int i = 0; i < withSite.size(); i++) {
                Stop s = withSite.get(i);
                if (!s.clear() && s.room().name().equals(pendingSecret)) {
                    LOGGER.info("[AutoSecret] {}: cleared first - its route goes now", pendingSecret);
                    List<Stop> out = new ArrayList<>();
                    out.add(s);
                    for (Stop o : withSite) {
                        if (o != s) {
                            out.add(o);
                        }
                    }
                    out.addAll(rest);
                    return out;
                }
            }
            pendingSecret = null;
        }
        net.minecraft.world.phys.Vec3 from = client.player.position();
        String ahead = RoomTour.aheadPick(from);
        if (ahead == null && !RoomTour.fromReady(from, sites)) {
            if (tourWaitTicks == 0) {
                RoomTour.request(from, sites, false, true, "deciding from here");
            }
            // Worth a few ticks only once the floor graph is warm (its searches are milliseconds then); before that the
            // straight-line estimate decides at once.
            if (com.killer560.hub.livemap.autoclear.EtherwarpPathfinder.graphWarm() && tourWaitTicks++ < TOUR_WAIT_TICKS) {
                status = "Working out the room order";
                return null;
            }
        }
        tourWaitTicks = 0;
        RoomTour.Matrix m = RoomTour.matrix(from, sites, true, layout);
        // The Room Order setting (killer560, 2026-10-18): Largest To Smallest, or Least Etherwarps through the whole floor;
        // with only rooms of 3 unfound or fewer left it is Least Etherwarps whatever the setting says.
        // Least Etherwarps (killer560, 2026-10-19): the best order its search has found so far - at the first decision the
        // nearest room next improved by moves, while the search (no time limit) goes on in the background; every later
        // decision, the room boundary, takes whatever better order it has found by then.
        RoomTour.Choice choice = RoomTour.secretOrder(m, sites);
        AutoSecretConfig.SecretOrder mode = choice.mode();
        int[] idx = choice.order().clone();
        String aheadNote = "";
        if (ahead != null) {
            // Planned ahead from here with every trip searched: that pick's trip is already waiting. Least Etherwarps
            // leaves it only for an order the search has since found with strictly less travel.
            for (int k = 0; k < idx.length; k++) {
                if (sites.get(idx[k]).key().equals(ahead) && k > 0) {
                    int[] aheadFirst = idx.clone();
                    int v = aheadFirst[k];
                    System.arraycopy(aheadFirst, 0, aheadFirst, 1, k);
                    aheadFirst[0] = v;
                    double tBest = TourPlanner.travel(idx, m.fromHere(), m.between());
                    double tAhead = TourPlanner.travel(aheadFirst, m.fromHere(), m.between());
                    if (mode == AutoSecretConfig.SecretOrder.LEAST_ETHERWARPS && tBest < tAhead - 1e-9) {
                        aheadNote = String.format(Locale.US, " | planned ahead: %s, left for the search's better order"
                                + " (%.0f ticks against %.0f)", ahead, tBest, tAhead);
                    } else {
                        idx = aheadFirst;
                    }
                    break;
                }
            }
            if (aheadNote.isEmpty()) {
                aheadNote = " | planned ahead: " + ahead;
            }
        }
        LOGGER.info("[AutoSecret] room order ({}): {} | travel {} ticks, {} ({}); {}{}", mode.label(),
                RoomTour.describe(sites, idx), Math.round(TourPlanner.travel(idx, m.fromHere(), m.between())),
                choice.proven() ? "proven optimal" : "not proven yet", choice.method(),
                RoomTour.compare(m, idx, RoomTour.byWeight(m), "most unfound first"), aheadNote);
        List<Stop> out = new ArrayList<>();
        for (int i : idx) {
            out.add(withSite.get(i));
        }
        out.addAll(rest);
        logPlan(layout, from, out, sites, idx, withSite);
        return out;
    }

    /** A clear-only stop's site key: the room's key with this in front (never a room's own key). */
    private static final String CLEAR_KEY = "clear:";

    /** The room cleared first whose route must go next (Fastest Total), or null. */
    private static String pendingSecret;

    private static List<RoomStatus.Room> pendingFirst(List<RoomStatus.Room> routed) {
        if (pendingSecret == null) {
            return routed;
        }
        List<RoomStatus.Room> out = new ArrayList<>();
        for (RoomStatus.Room r : routed) {
            if (r.name().equals(pendingSecret)) {
                out.add(0, r);
            } else {
                out.add(r);
            }
        }
        if (out.isEmpty() || !out.get(0).name().equals(pendingSecret)) {
            pendingSecret = null;
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- Fastest Total

    /**
     * The Room Plan is Fastest Total and Auto Clear can take rooms (killer560, 2026-10-19: "It doesn't need to clear a room
     * first or secret it first; it can do either, or skip secreting a room it deems slow to secret in exchange for
     * clearing or secreting other rooms, so it can get 300 faster").
     */
    private static boolean fastestTotal() {
        AutoSecretConfig cfg = AutoSecretConfig.getInstance();
        return cfg.getRoomPlan() == AutoSecretConfig.RoomPlan.FASTEST_TOTAL && cfg.isAutoClearRooms()
                && AutoClearFeature.isAvailable();
    }

    /** A mob room the map shows uncleared that Auto Clear has not been sent to this run. */
    private static boolean needsClear(DungeonLayout layout, RoomStatus.Room r) {
        return !r.cleared() && !"Unknown".equals(r.name()) && !clearsDone.contains(r.name())
                && !r.isType("PUZZLE") && !r.isType("TRAP") && !r.isType("BLOOD") && !r.isType("ENTRANCE")
                && !r.isType("FAIRY") && AutoClearFeature.isMobRoom(layout, r.room());
    }

    /** A mob room the map shows uncleared that Auto Clear has not been sent to yet (anywhere on the map). */
    private static boolean unclearedMobRoomLeft() {
        DungeonLayout layout = DungeonLayout.capture();
        for (RoomStatus.Room r : RoomStatus.rooms()) {
            if (needsClear(layout, r)) {
                return true;
            }
        }
        return false;
    }

    /** Its secrets are still to take through its route (it has one, and unfound secrets, and it is not skipped). */
    private static boolean secretsPending(RoomStatus.Room r) {
        if (!eligibleForSecrets(r) || skippedSecrets.contains(r.name())) {
            return false;
        }
        Route route = RouteStore.getInstance().forRoom(r.name());
        return route != null && route.startNode() != null;
    }

    /**
     * Fastest Total: the room he stands in, once its secrets are done with (its route ran, it has none to take, or they
     * are not needed), is cleared before he leaves it - the trip back into it later is what this saves. A finished
     * route's last-node wait (its last secrets registering) runs first. @return true when it is clearing it (or waiting
     * to)
     */
    private static boolean decideClearHere(DungeonLayout layout, List<RoomStatus.Room> rooms, int here) {
        if (here < 0 || !fastestTotal()) {
            return false;
        }
        RoomStatus.Room r = null;
        for (RoomStatus.Room x : rooms) {
            if (x.room() == here) {
                r = x;
            }
        }
        if (r == null || !needsClear(layout, r) || secretsPending(r)) {
            return false;
        }
        if (leaveWaitRoom != null) {
            status = "Waiting for the last secrets of " + leaveWaitRoom + ", then clearing " + r.name();
            return true;
        }
        LOGGER.info("[AutoSecret] clear here: {} - {}, so Auto Clear clears it before he leaves (no trip back into it)",
                r.name(), secreted.contains(r.name()) ? "its route is done" : skippedSecrets.contains(r.name())
                        ? "its secrets are not needed" : "it has no secrets to route");
        clearsDone.add(r.name());
        target = r.name();
        targetRoomId = r.room();
        status = "Clearing " + r.name();
        note(ModChat.text("Clearing "), ModChat.value(r.name()));
        arrived(Trip.CLEAR);
        return true;
    }

    /** Fastest Total: a clear this much cheaper by the estimate than the route-first order is worth going first. */
    static final double CLEAR_FIRST_MARGIN_TICKS = 4.0;

    /**
     * Fastest Total, before going to {@code r}'s route: secrets first (the route, then the clear from where it ends) or
     * the clear first (from here, then the trip to the start node), by {@link AutoClearFeature#estimateClear} over the
     * room's starred mobs in sight. Mobs not in sight yet: secrets first. Logged either way. @return true when it is
     * clearing first
     */
    private static boolean decideClearFirst(DungeonLayout layout, RoomStatus.Room r) {
        Minecraft client = Minecraft.getInstance();
        if (!fastestTotal() || client.player == null || !needsClear(layout, r) || r.name().equals(pendingSecret)) {
            return false;
        }
        AutoRoutesFeature.RoomRoute rr = AutoRoutesFeature.roomRoute(layout, r.room());
        BlockPos goal = rr == null ? null : rr.goal();
        net.minecraft.world.phys.Vec3 end = rr == null ? null : rr.predictedEnd();
        if (goal == null || end == null) {
            return false;
        }
        net.minecraft.world.phys.Vec3 here = client.player.position();
        net.minecraft.world.phys.Vec3 start = net.minecraft.world.phys.Vec3.atBottomCenterOf(goal.above());
        double[] fromHere = AutoClearFeature.estimateClear(here, r.name());
        double[] fromEnd = AutoClearFeature.estimateClear(end, r.name());
        if (fromHere == null || fromEnd == null) {
            if (planSaid.add("first:" + r.name())) {
                LOGGER.info("[AutoSecret] plan for {}: secrets first - its starred mobs are not in sight to price a clear"
                        + " first", r.name());
            }
            return false;
        }
        double toStart = AutoClearFeature.etherTicks(here, start);
        double secretFirst = toStart + fromEnd[0];
        net.minecraft.world.phys.Vec3 clearEnd = new net.minecraft.world.phys.Vec3(fromHere[1], fromHere[2], fromHere[3]);
        double clearFirst = fromHere[0] + AutoClearFeature.etherTicks(clearEnd, start);
        boolean first = clearFirst + CLEAR_FIRST_MARGIN_TICKS < secretFirst;
        LOGGER.info(String.format(Locale.US, "[AutoSecret] plan for %s (%d mob(s) in sight): %s - clear first ~%.0f ticks"
                        + " (clear from here %.0f + trip to the start node %.0f) vs secrets first ~%.0f (trip to the start"
                        + " node %.0f + clear from the route's end %.0f), the route itself the same either way", r.name(),
                (int) fromHere[4], first ? "CLEAR FIRST" : "secrets first", clearFirst, fromHere[0], clearFirst - fromHere[0],
                secretFirst, toStart, fromEnd[0]));
        if (!first) {
            return false;
        }
        LOGGER.info("[AutoSecret] clear first: {} - Auto Clear, then its route", r.name());
        pendingSecret = r.name();
        clearsDone.add(r.name());
        target = r.name();
        targetRoomId = r.room();
        status = "Clearing " + r.name() + " first";
        note(ModChat.text("Clearing "), ModChat.value(r.name()), ModChat.dim(" first"));
        // Auto Clear goes to the mobs itself (straight to the block that reaches them, no trip into the room first).
        AutoRoutesFeature.cancelForInteractiveMap("Auto Secret");
        handToAutoClear();
        return true;
    }

    /** Plan lines said once a run (by key), so a decision repeated every tick logs once. */
    private static final Set<String> planSaid = new HashSet<>();
    /** Rooms whose secrets Fastest Total leaves out for now (an S+ does not need them); recomputed every decision. */
    private static final Set<String> skippedSecrets = new HashSet<>();

    /**
     * Fastest Total's clear-only stops: uncleared mob rooms it can reach with nothing left to route (or whose secrets are
     * left out), other than the one he stands in (its clear is {@link #decideClearHere}'s) and the routed rooms (cleared
     * in the same visit).
     */
    private static List<RoomStatus.Room> clearOnlyRooms(DungeonLayout layout, List<RoomStatus.Room> rooms,
                                                       Map<Integer, Integer> dist, List<RoomStatus.Room> routed) {
        if (!fastestTotal()) {
            return List.of();
        }
        int here = layout.currentRoom();
        List<RoomStatus.Room> out = new ArrayList<>();
        for (RoomStatus.Room r : rooms) {
            if (r.room() == here || !dist.containsKey(r.room()) || !needsClear(layout, r) || routed.contains(r)) {
                continue;
            }
            if (secretsPending(r) && !skippedSecrets.contains(r.name())) {
                continue;   // held (the room before blood) or behind the tour's own rules: its clear goes with its route
            }
            out.add(r);
        }
        return out;
    }

    /**
     * Fastest Total with Skip Unneeded Secrets: of the routed rooms, the cheapest set whose unfound secrets still cover
     * what an S+ needs (the Score Calculator's need, {@link AutopilotScore#usefulSecrets}, which keeps a margin of one);
     * the rest are left out for now. A room's price is its route's work, and - when it needs no clear visit anyway - the
     * trip to it and on (twice the trip from the nearest other stop). Every decision prices it again, so a short count
     * (a route that missed one) brings a left-out room back. No reading from the calculator: nothing is left out.
     */
    private static List<RoomStatus.Room> skipUnneeded(DungeonLayout layout, List<RoomStatus.Room> rooms,
                                                      List<RoomStatus.Room> routed) {
        skippedSecrets.clear();
        if (routed.isEmpty() || !fastestTotal() || !AutoSecretConfig.getInstance().isSkipUnneededSecrets()
                || com.killer560.hub.scorecalc.ScoreCalculatorFeature.currentResult() == null) {
            return routed;
        }
        AutopilotScore.State s = Autopilot.scoreState(layout, rooms);
        if (s.currentTotal() < 0 || s.totalSecrets() <= 0) {
            return routed;
        }
        int need = AutopilotScore.usefulSecrets(s);
        int have = 0;
        for (RoomStatus.Room r : routed) {
            have += Math.max(0, r.unfound());
        }
        if (need >= have) {
            return routed;
        }
        int n = routed.size();
        double[] cost = new double[n];
        int[] gain = new int[n];
        Minecraft client = Minecraft.getInstance();
        List<net.minecraft.world.phys.Vec3> starts = new ArrayList<>();
        for (RoomStatus.Room r : routed) {
            AutoRoutesFeature.RoomRoute rr = AutoRoutesFeature.roomRoute(layout, r.room());
            starts.add(rr == null || rr.goal() == null ? null
                    : net.minecraft.world.phys.Vec3.atBottomCenterOf(rr.goal().above()));
        }
        for (int i = 0; i < n; i++) {
            RoomStatus.Room r = routed.get(i);
            gain[i] = Math.max(0, r.unfound());
            Route route = RouteStore.getInstance().forRoom(r.name());
            cost[i] = routeWorkTicks(route);
            if (!needsClear(layout, r) && starts.get(i) != null) {
                double near = client.player == null ? Double.MAX_VALUE
                        : AutoClearFeature.etherTicks(client.player.position(), starts.get(i));
                for (int j = 0; j < n; j++) {
                    if (j != i && starts.get(j) != null) {
                        near = Math.min(near, AutoClearFeature.etherTicks(starts.get(j), starts.get(i)));
                    }
                }
                cost[i] += near == Double.MAX_VALUE ? 0 : 2 * near;
            }
        }
        boolean[] keep = cheapestCover(cost, gain, need);
        if (keep == null) {
            return routed;
        }
        List<RoomStatus.Room> out = new ArrayList<>();
        List<String> left = new ArrayList<>();
        double saved = 0;
        for (int i = 0; i < n; i++) {
            RoomStatus.Room r = routed.get(i);
            if (keep[i]) {
                out.add(r);
            } else {
                skippedSecrets.add(r.name());
                left.add(String.format(Locale.US, "%s (%d unfound, ~%.0f ticks)", r.name(), gain[i], cost[i]));
                saved += cost[i];
            }
        }
        String key = "skip:" + String.join(",", left) + "|" + need;
        if (!left.isEmpty() && planSaid.add(key)) {
            LOGGER.info(String.format(Locale.US, "[AutoSecret] skipping the secrets of %s - the S+ needs %d more (with a"
                            + " margin of %d; %d of %d found, bonus %d, score %d), the rooms kept hold %d of the %d"
                            + " routed; ~%.0f ticks saved", String.join(", ", left), need, AutopilotScore.SECRET_MARGIN,
                    s.secretsFound(), s.totalSecrets(), s.bonus(), s.currentTotal(), kept(gain, keep), have, saved));
        }
        return out;
    }

    private static int kept(int[] gain, boolean[] keep) {
        int t = 0;
        for (int i = 0; i < gain.length; i++) {
            if (keep[i]) {
                t += gain[i];
            }
        }
        return t;
    }

    /**
     * The cheapest set of items whose gains add up to at least {@code need} (a 0/1 knapsack over the count, capped at
     * {@code need}). @return which to keep, or null when even all of them fall short
     */
    static boolean[] cheapestCover(double[] cost, int[] gain, int need) {
        int n = cost.length;
        double[][] best = new double[n + 1][need + 1];
        boolean[][] took = new boolean[n + 1][need + 1];
        int[][] prev = new int[n + 1][need + 1];
        for (double[] row : best) {
            java.util.Arrays.fill(row, Double.MAX_VALUE);
        }
        best[0][0] = 0;
        for (int i = 0; i < n; i++) {
            for (int c = 0; c <= need; c++) {
                if (best[i][c] == Double.MAX_VALUE) {
                    continue;
                }
                if (best[i][c] < best[i + 1][c]) {
                    best[i + 1][c] = best[i][c];
                    took[i + 1][c] = false;
                    prev[i + 1][c] = c;
                }
                int nc = Math.min(need, c + Math.max(0, gain[i]));
                if (best[i][c] + cost[i] < best[i + 1][nc]) {
                    best[i + 1][nc] = best[i][c] + cost[i];
                    took[i + 1][nc] = true;
                    prev[i + 1][nc] = c;
                }
            }
        }
        if (best[n][need] == Double.MAX_VALUE) {
            return null;
        }
        boolean[] keep = new boolean[n];
        int c = need;
        for (int i = n; i > 0; i--) {
            keep[i - 1] = took[i][c];
            c = prev[i][c];
        }
        return keep;
    }

    /**
     * The plan's estimate beside the old one's, once per new order (killer560, 2026-10-19: "Print the estimate for the
     * chosen plan and for the best alternative in the log"): Fastest Total's tour - its trips, every routed room's clear
     * in the same visit - against Secrets Then Clears, the same secret rooms in the same order and then every uncleared
     * room again, nearest first, from where the last route ends. Trips at Auto Clear's etherwarp price
     * ({@link AutoClearFeature#etherTicks}), straight lines; route work and clear work are the same in both and left out.
     */
    private static void logPlan(DungeonLayout layout, net.minecraft.world.phys.Vec3 from, List<Stop> order,
                                List<RoomTour.Site> sites, int[] idx, List<Stop> withSite) {
        if (!fastestTotal()) {
            return;
        }
        double mine = 0;
        double old = 0;
        net.minecraft.world.phys.Vec3 at = from;
        net.minecraft.world.phys.Vec3 oldAt = from;
        List<net.minecraft.world.phys.Vec3> clearsLater = new ArrayList<>();
        int inPlace = 0;
        StringBuilder names = new StringBuilder();
        for (int i : idx) {
            RoomTour.Site s = sites.get(i);
            Stop st = withSite.get(i);
            net.minecraft.world.phys.Vec3 in = net.minecraft.world.phys.Vec3.atBottomCenterOf(s.arrive().above());
            mine += AutoClearFeature.etherTicks(at, in);
            at = s.leave();
            names.append(names.length() == 0 ? "" : " > ").append(st.clear() ? "clear " : "").append(st.room().name());
            if (st.clear()) {
                clearsLater.add(in);
                continue;
            }
            old += AutoClearFeature.etherTicks(oldAt, in);
            oldAt = s.leave();
            if (needsClear(layout, st.room())) {
                inPlace++;
                BlockPos c = DungeonLayout.cellCenter(st.room().mainTile());
                clearsLater.add(net.minecraft.world.phys.Vec3.atBottomCenterOf(c));
            }
        }
        // Secrets Then Clears: the uncleared rooms afterwards, nearest first.
        List<net.minecraft.world.phys.Vec3> left = new ArrayList<>(clearsLater);
        while (!left.isEmpty()) {
            net.minecraft.world.phys.Vec3 next = null;
            double bestT = Double.MAX_VALUE;
            for (net.minecraft.world.phys.Vec3 v : left) {
                double t = AutoClearFeature.etherTicks(oldAt, v);
                if (t < bestT) {
                    bestT = t;
                    next = v;
                }
            }
            old += bestT;
            oldAt = next;
            left.remove(next);
        }
        String key = "plan:" + names;
        if (planSaid.add(key)) {
            LOGGER.info(String.format(Locale.US, "[AutoSecret] plan (Fastest Total): %s - ~%.0f ticks of trips, %d clear(s) in"
                            + " the same visit as the route; Secrets Then Clears: ~%.0f ticks of trips (%d room(s) entered"
                            + " again to clear) - ~%.0f ticks saved", names, mine, inPlace, old, clearsLater.size(),
                    old - mine));
        }
    }

    /**
     * Warps to {@code r}'s route start node (the route then plays). @return false when it cannot (no route, no warp) -
     * the room is then left out this run, with a chat line
     */
    private static boolean goSecret(DungeonLayout layout, RoomStatus.Room r, Map<Integer, Integer> dist, String openText,
                                    String blockedText) {
        Route route = RouteStore.getInstance().forRoom(r.name());
        if (route == null || route.startNode() == null) {
            secreted.add(r.name());
            if (noRouteSaid.add(r.name())) {
                LOGGER.info("[AutoSecret] no route for {} ({} unfound) - skipped", r.name(), r.unfound());
                say(ModChat.bad("No route for "), ModChat.value(r.name()), ModChat.dim(" (" + r.unfound()
                        + " unfound) - skipped"));
            }
            return false;
        }
        target = r.name();
        targetRoomId = r.room();
        LOGGER.info("[AutoSecret] target {}: {} unfound, {} room(s) away; open {}; behind a closed door {}", r.name(),
                r.unfound(), dist.get(r.room()), openText, blockedText);
        status = "Secreting " + r.name() + " (" + r.unfound() + " unfound)";
        note(ModChat.text("Going to "), ModChat.value(r.name()), ModChat.dim(" (" + r.unfound() + " unfound)"));
        // Exactly what a Go + Secret press does before its goal runs (InteractiveMapFeature.queue): it clears the
        // "route just finished here" latch the last room's route left, which otherwise holds the next start node
        // un-armed under him (found in 102-sim-autosecret: every room after the first "did not arm").
        AutoRoutesFeature.cancelForInteractiveMap("Auto Secret");
        routeCompletionsSeen = RouteExecutor.completions();
        routeStartClock = com.killer560.hub.autoroutes.RouteRunMemory.clock();
        routeFoundAtStart = Math.max(0, r.secrets() - r.unfound());
        if (!AutoRoutesFeature.warpToStartNode(layout, r.room())) {
            secreted.add(r.name());
            LOGGER.info("[AutoSecret] {}: could not warp to its start node - skipped", r.name());
            say(ModChat.bad("Couldn't warp to the start node of "), ModChat.value(r.name()), ModChat.dim(" - skipped"));
            return false;
        }
        beginTravel(Trip.START_NODE);
        planAhead(layout, r, dist);
        return true;
    }

    /**
     * While he travels to {@code r} and its route runs: the trips from where that route will leave him to every room he
     * could take next, and the next room's trip in full, on the map's planner thread ({@link RoomTour}) - so the warp
     * out goes the tick the route is over.
     */
    private static void planAhead(DungeonLayout layout, RoomStatus.Room r, Map<Integer, Integer> dist) {
        if (QuizPlan.running()) {
            return;   // between two Quiz answers the trip after this room is the one back to the Quiz
        }
        AutoRoutesFeature.RoomRoute rr = AutoRoutesFeature.roomRoute(layout, r.room());
        net.minecraft.world.phys.Vec3 end = rr == null ? null : rr.predictedEnd();
        if (end == null) {
            return;
        }
        List<RoomTour.Site> sites = pilot ? Autopilot.sitesExcept(Autopilot.keyOf(r)) : secretSites(layout,
                openSecretRooms(RoomStatus.rooms(), dist, r.name()));
        if (sites.isEmpty()) {
            return;
        }
        LOGGER.info(String.format(Locale.US, "[AutoSecret] planning ahead from the end of %s's route (%.1f %.1f %.1f):"
                + " %d room(s) to choose from", r.name(), end.x, end.y, end.z, sites.size()));
        RoomTour.request(end, sites, true, !pilot, "while " + r.name() + "'s route runs");
    }

    /** Rooms Auto Secret could secret next: eligible, reachable, other than {@code except}. */
    private static List<RoomStatus.Room> openSecretRooms(List<RoomStatus.Room> rooms, Map<Integer, Integer> dist,
                                                         String except) {
        List<RoomStatus.Room> out = new ArrayList<>();
        for (RoomStatus.Room r : rooms) {
            if (eligibleForSecrets(r) && dist.containsKey(r.room()) && !r.name().equals(except)) {
                out.add(r);
            }
        }
        // The room before blood is never planned ahead for while another room is left (decideSecrets holds it last).
        int preBlood = preBloodRoom(DungeonLayout.capture(), rooms);
        if (out.size() > 1) {
            out.removeIf(r -> r.room() == preBlood);
        }
        return out;
    }

    /** Ticks a route takes, travel excluded, before Dungeon Autopilot has learnt better: 1 s plus 1 s a node. */
    static double routeWorkTicks(Route route) {
        return 20.0 * (1.0 + Autopilot.routeSecondsPerNode() * (route == null ? 0 : route.nodes().size()));
    }

    /** One {@link RoomTour.Site} per room with a route: its start node block in, its route's end out, its secrets. */
    static List<RoomTour.Site> secretSites(DungeonLayout layout, List<RoomStatus.Room> rooms) {
        List<RoomTour.Site> out = new ArrayList<>();
        for (RoomStatus.Room r : rooms) {
            RoomTour.Site s = secretSite(layout, r, r.unfound(), Autopilot.keyOf(r));
            if (s != null) {
                // Auto Secret alone: secrets per tick of travel - every route takes as long whatever the order.
                out.add(new RoomTour.Site(s.key(), s.arrive(), s.tile(), s.exact(), s.leave(), s.weight(), 0));
            }
        }
        return out;
    }

    static RoomTour.Site secretSite(DungeonLayout layout, RoomStatus.Room r, double weight, String key) {
        AutoRoutesFeature.RoomRoute rr = AutoRoutesFeature.roomRoute(layout, r.room());
        BlockPos goal = rr == null ? null : rr.goal();
        net.minecraft.world.phys.Vec3 end = rr == null ? null : rr.predictedEnd();
        if (goal == null || end == null) {
            return null;
        }
        return new RoomTour.Site(key, goal, -1, true, end, weight, routeWorkTicks(rr.route()));
    }

    // ------------------------------------------------------------------------------------------- Dungeon Autopilot

    /** One decision as Dungeon Autopilot: a known insta clear first (Auto Secret's), then whatever {@link Autopilot} says. */
    private static void decidePilot(DungeonLayout layout, List<RoomStatus.Room> rooms, Map<Integer, Integer> dist, int here) {
        if (decideInstaClear(layout, rooms, dist, here)) {
            Autopilot.setHud("Insta clearing " + target, "a known insta-clear entry is a clear for one warp");
            return;
        }
        Autopilot.Order o = Autopilot.decide(layout, rooms, dist, secreted, clearsDone, puzzlesDone);
        RoomStatus.Room r = o.room();
        switch (o.type()) {
            case SECRET -> {
                if (!goSecret(layout, r, dist, "-", "-")) {
                    setPhase(Phase.SETTLE);
                }
            }
            case CLEAR -> goClear(layout, r, dist);
            case PUZZLE -> goPuzzle(layout, r, dist);
            case EXPLORE -> {
                target = Autopilot.keyOf(r);
                targetRoomId = r.room();
                status = "Exploring an unidentified room";
                LOGGER.info("[AutoSecret] autopilot: exploring the unidentified room at tile {}", r.mainTile());
                note(ModChat.text("Exploring an unidentified room"));
                if (here == r.room()) {
                    setPhase(Phase.SETTLE);
                } else {
                    pathToRoom(layout, r, Trip.EXPLORE);
                }
            }
            case DOOR -> goToDoor(layout, o.door(), o.blood(), o.why());
            case FINISH -> finishPilot(layout, rooms, dist, o);
            case KEY -> goKey(o.key(), o.why());
            default -> setPhase(Phase.SETTLE);
        }
    }

    /** Nothing left the planner wants: a wither door with something behind it, then (Solo) the blood door, then hand back. */
    private static void finishPilot(DungeonLayout layout, List<RoomStatus.Room> rooms, Map<Integer, Integer> dist,
                                    Autopilot.Order o) {
        if (o.tryWither() && decideWitherDoor(layout, rooms, dist)) {
            return;
        }
        int blood = layout.bloodDoor();
        if (o.openBlood() && blood >= 0 && layout.isLocked(blood) && !secreted.contains(doorName(blood))) {
            LOGGER.info("[AutoSecret] autopilot: {} - the blood door is the last thing (it starts the Watcher)", o.why());
            note(ModChat.text(o.why() + " - "), ModChat.value("opening the blood door"),
                    ModChat.dim(" (the last step: it starts the Watcher)"));
            goToDoor(layout, blood, true, o.why() + ": the blood door last");
            return;
        }
        String why = o.why() + (Autopilot.bloodOpened() ? " - blood door open, over to you for the blood camp"
                : " - over to you");
        stop(why, false);
    }

    // ------------------------------------------------------------------------------------------- dropped keys

    /**
     * Goes to pick up a dropped key: an Interactive Map warp onto the standable block nearest to it
     * ({@link com.killer560.hub.livemap.autoclear.TeleportUtils#nearestEtherwarpable}), then waits there for the pickup.
     * Hypixel picks a key up by being near it; the wiki gives no range (only a minister perk's +5 blocks), so it stands
     * as close as a warp can put it rather than trusting a number, and logs how far it was when the key went - that
     * distance on Hypixel is the measurement.
     */
    private static void goKey(com.killer560.hub.doorkeys.DungeonKeys.Dropped k, String why) {
        Minecraft client = Minecraft.getInstance();
        keyTarget = k;
        keyLastDist = -1;
        keyWitherBefore = com.killer560.hub.doorkeys.DungeonKeys.witherKeys();
        keyBloodBefore = com.killer560.hub.doorkeys.DungeonKeys.bloodKey();
        target = (k.blood() ? "the Blood Key" : "the Wither Key") + " #" + k.id();
        targetRoomId = -1;
        status = "Picking up " + target;
        note(ModChat.text("Picking up the "), ModChat.value(k.blood() ? "Blood Key" : "Wither Key"), ModChat.dim(" - " + why));
        BlockPos at = BlockPos.containing(k.x(), k.y(), k.z());
        net.minecraft.world.phys.Vec3 keyPos = new net.minecraft.world.phys.Vec3(k.x(), k.y(), k.z());
        double d = client.player == null ? 99 : client.player.position().distanceTo(keyPos);
        AutoSecretConfig cfg = AutoSecretConfig.getInstance();
        double range = cfg.keyPickupRange();
        double reach = Math.max(1.0, range - KEY_RANGE_MARGIN);
        if (d <= reach) {
            // Already within the pickup range: no warp, just wait for it to be picked up.
            keyGoal = client.player.blockPosition().below();
            LOGGER.info(String.format(java.util.Locale.US, "[AutoSecret] key: the %s key is %.1f blocks away, inside the"
                    + " %.1f-block pickup range - no warp", k.blood() ? "blood" : "wither", d, range));
            beginKeyWait();
            return;
        }
        keyGoal = standWithin(keyPos, reach, client.player.position());
        if (keyGoal == null) {
            keyGoal = com.killer560.hub.livemap.autoclear.TeleportUtils.nearestEtherwarpable(at);
        }
        LOGGER.info(String.format(java.util.Locale.US, "[AutoSecret] key: going for the %s key at %.1f %.1f %.1f (%.1f blocks"
                        + " away, pickup range %.1f), standing on %s (%.1f from the key)",
                k.blood() ? "blood" : "wither", k.x(), k.y(), k.z(), d, range,
                keyGoal == null ? "nothing found" : keyGoal.toShortString(), keyGoal == null ? -1.0
                        : keyPos.distanceTo(new net.minecraft.world.phys.Vec3(keyGoal.getX() + 0.5, keyGoal.getY() + 1.0,
                        keyGoal.getZ() + 0.5))));
        if (keyGoal == null) {
            beginKeyWait();
            return;
        }
        etherPathTo(keyGoal, Trip.KEY);
    }

    /** Stay this far inside the pickup range: the landing is a block, the range a measurement of feet. */
    private static final double KEY_RANGE_MARGIN = com.killer560.hub.doorkeys.DungeonKeys.STAND_MARGIN;

    private static BlockPos standWithin(net.minecraft.world.phys.Vec3 key, double reach, net.minecraft.world.phys.Vec3 from) {
        return com.killer560.hub.doorkeys.DungeonKeys.standWithin(key, reach, from);
    }

    private static void beginKeyWait() {
        setPhase(Phase.KEY_WAIT);
    }

    private static void tickKeyWait() {
        if (phaseTicks > KEY_WAIT_TICKS) {
            LOGGER.info(String.format(java.util.Locale.US, "[AutoSecret] key: still lying there after %d ticks %.2f blocks"
                    + " away - not picked up from here", KEY_WAIT_TICKS, keyLastDist));
            say(ModChat.bad("The key was not picked up"), ModChat.dim(String.format(java.util.Locale.US,
                    " from %.1f blocks - trying something else", keyLastDist)));
            Autopilot.keyNotTaken(keyTarget.id());
            keyTarget = null;
            setPhase(Phase.SETTLE);
        }
    }

    /**
     * While going for a key: true (and the trip ends) once the key is gone from the world or the team's count went up -
     * picked up, by him or a teammate. Records the distance it went at.
     */
    private static boolean keyGone(Minecraft client) {
        net.minecraft.world.entity.Entity e = client.level == null ? null : client.level.getEntity(keyTarget.id());
        boolean counted = keyTarget.blood() ? com.killer560.hub.doorkeys.DungeonKeys.bloodKey() == 1 && keyBloodBefore != 1
                : com.killer560.hub.doorkeys.DungeonKeys.witherKeys() > keyWitherBefore;
        if (e != null && !e.isRemoved() && !counted) {
            keyLastDist = client.player.position().distanceTo(e.position());
            return false;
        }
        // Gone from the CLIENT is not picked up: a stand far off simply leaves the client's tracking range while the
        // path swings away (142-sim-autopilot2 run 2: "picked up" from 102 blocks, five times over). Only near it - well
        // inside any pickup range, a minister perk's +5 included - or with the team's count up is it taken.
        double gone = Math.max(KEY_GONE_NEAR, AutoSecretConfig.getInstance().keyPickupRange() + 4.0);
        if (!counted && (keyLastDist < 0 || keyLastDist > gone)) {
            return false;
        }
        String line = com.killer560.hub.doorkeys.DungeonKeys.lastPickupLine();
        // Where he stands NOW against where the key lay: mid-warp the tick before is a whole hop back (run 1 logged
        // 38.17 for a pickup the sim made at 6.32). The server took it somewhere between the two.
        double now = client.player.position().distanceTo(new net.minecraft.world.phys.Vec3(keyTarget.x(), keyTarget.y(),
                keyTarget.z()));
        LOGGER.info(String.format(java.util.Locale.US, "[AutoSecret] key: picked up (%s) - %.2f blocks from it now, %.2f the"
                        + " tick before (pickup range %.1f); team now %d wither key(s), blood %d; chat: %s",
                counted ? "the team's count went up" : "the key left the world", now, keyLastDist,
                AutoSecretConfig.getInstance().keyPickupRange(), com.killer560.hub.doorkeys.DungeonKeys.witherKeys(),
                com.killer560.hub.doorkeys.DungeonKeys.bloodKey(), line));
        note(ModChat.good(keyTarget.blood() ? "Blood Key" : "Wither Key"), ModChat.dim(" picked up"));
        if (phase == Phase.TRAVEL && ClearExecutor.isBusy()) {
            ClearExecutor.cancel();
        }
        ClearExecutor.setExternalOwner(false);
        keyTarget = null;
        setPhase(Phase.SETTLE);
        return true;
    }

    private static void goClear(DungeonLayout layout, RoomStatus.Room r, Map<Integer, Integer> dist) {
        goClear(layout, r, dist, pilot ? "autopilot: " : "Blood First: ");
    }

    private static void goClear(DungeonLayout layout, RoomStatus.Room r, Map<Integer, Integer> dist, String label) {
        clearsDone.add(r.name());
        target = r.name();
        targetRoomId = r.room();
        status = "Going to clear " + r.name();
        LOGGER.info("[AutoSecret] {}clear {} ({} room(s) away) - Auto Clear", label, r.name(), dist.get(r.room()));
        note(ModChat.text("Clearing "), ModChat.value(r.name()));
        if (layout.currentRoom() == r.room()) {
            arrived(Trip.CLEAR);
            return;
        }
        pathToRoom(layout, r, Trip.CLEAR);
    }

    private static void goPuzzle(DungeonLayout layout, RoomStatus.Room r, Map<Integer, Integer> dist) {
        puzzlesDone.add(r.name());
        target = r.name();
        targetRoomId = r.room();
        status = "Going to the puzzle " + r.name();
        LOGGER.info("[AutoSecret] autopilot: puzzle {} ({} room(s) away) - its Auto Puzzles auto", r.name(),
                dist.get(r.room()));
        note(ModChat.text("Puzzle: "), ModChat.value(r.name()));
        if (layout.currentRoom() == r.room()) {
            setPhase(Phase.PUZZLE_WAIT);
            return;
        }
        pathToRoom(layout, r, Trip.PUZZLE);
    }

    /** Waits where he is for a door someone else must open (a blood door, a wither door with no reachable side). */
    private static void waitForTeammate(String what) {
        if (!what.equals(lastWaiting)) {
            lastWaiting = what;
            LOGGER.info("[AutoSecret] waiting: {} behind a closed door", what);
            say(ModChat.text("Waiting for a teammate to open the way to "), ModChat.value(what));
        }
        status = "Waiting for a door: " + what;
        setPhase(Phase.WAITING);
    }

    private static void decideEndgame(DungeonLayout layout, List<RoomStatus.Room> rooms, Map<Integer, Integer> dist) {
        AutoSecretConfig cfg = AutoSecretConfig.getInstance();
        // Unfinished puzzles nobody else is doing, nearest first.
        List<RoomStatus.Room> puzzles = new ArrayList<>();
        for (RoomStatus.Room r : rooms) {
            if (r.isType("PUZZLE") && !r.cleared() && !r.failed() && !"Unknown".equals(r.name())
                    && !puzzlesDone.contains(r.name()) && dist.containsKey(r.room())) {
                puzzles.add(r);
            }
        }
        puzzles.sort(Comparator.comparingInt(r -> dist.get(r.room())));
        // Fastest Total: puzzles and clears are one list, nearest first (an uncleared room next door before a puzzle across
        // the map, then back), instead of every puzzle first and the clears after.
        if (fastestTotal() && cfg.isAutoClearRooms()) {
            RoomStatus.Room nearClear = null;
            for (RoomStatus.Room r : rooms) {
                if (dist.containsKey(r.room()) && needsClear(layout, r)
                        && (nearClear == null || dist.get(r.room()) < dist.get(nearClear.room()))) {
                    nearClear = r;
                }
            }
            int nearPuzzle = Integer.MAX_VALUE;
            for (RoomStatus.Room p : puzzles) {
                if (!RoomStatus.teammateInside(p.cells())) {
                    nearPuzzle = Math.min(nearPuzzle, dist.get(p.room()));
                }
            }
            if (nearClear != null && dist.get(nearClear.room()) < nearPuzzle) {
                LOGGER.info("[AutoSecret] endgame: uncleared room {} ({} room(s) away) before the puzzles (nearest {} away)"
                        + " - Auto Clear (Fastest Total)", nearClear.name(), dist.get(nearClear.room()),
                        nearPuzzle == Integer.MAX_VALUE ? "-" : String.valueOf(nearPuzzle));
                goClear(layout, nearClear, dist, "endgame (Fastest Total): ");
                return;
            }
        }
        for (RoomStatus.Room r : puzzles) {
            puzzlesDone.add(r.name());
            if (RoomStatus.teammateInside(r.cells())) {
                LOGGER.info("[AutoSecret] puzzle {}: a teammate is in it - left to them", r.name());
                note(ModChat.value(r.name()), ModChat.dim(": a teammate is on it"));
                continue;
            }
            target = r.name();
            targetRoomId = r.room();
            LOGGER.info("[AutoSecret] endgame: puzzle {} ({} room(s) away)", r.name(), dist.get(r.room()));
            status = "Going to the puzzle " + r.name();
            note(ModChat.text("Secrets done - going to the puzzle "), ModChat.value(r.name()));
            if (layout.currentRoom() == r.room()) {
                setPhase(Phase.PUZZLE_WAIT);
                return;
            }
            if (pathToRoom(layout, r, Trip.PUZZLE)) {
                return;
            }
        }
        // An uncleared room.
        List<RoomStatus.Room> uncleared = new ArrayList<>();
        for (RoomStatus.Room r : rooms) {
            if (!r.cleared() && !"Unknown".equals(r.name()) && !clearsDone.contains(r.name()) && dist.containsKey(r.room())
                    && !r.isType("PUZZLE") && !r.isType("TRAP") && !r.isType("BLOOD") && !r.isType("ENTRANCE")
                    && !r.isType("FAIRY")) {
                uncleared.add(r);
            }
        }
        uncleared.sort(Comparator.comparingInt(r -> dist.get(r.room())));
        boolean handOffRooms = cfg.isAutoClearRooms() && AutoClearFeature.isAvailable();
        if (!handOffRooms || uncleared.isEmpty()) {
            // Nothing left on this side but a closed door: open it (killer560, 2026-10-06).
            if (decideWitherDoor(layout, rooms, dist)) {
                return;
            }
            // The room before blood, held back for last: everything else is done or waits on a teammate's door, so it
            // is the last thing Auto Secret itself can do - now it goes.
            if (heldPreBlood != null && dist.containsKey(heldPreBlood.room())) {
                RoomStatus.Room last = heldPreBlood;
                heldPreBlood = null;
                LOGGER.info("[AutoSecret] {} (in front of the blood door): the only rooms left wait behind a door - it is "
                        + "the last room now", last.name());
                if (goSecret(layout, last, dist, last.name(), blockedSecrets == null ? "none" : blockedSecrets)) {
                    return;
                }
            }
            if (blockedSecrets != null) {
                waitForTeammate(blockedSecrets);
                return;
            }
        }
        if (!uncleared.isEmpty()) {
            RoomStatus.Room r = uncleared.get(0);
            clearsDone.add(r.name());
            target = r.name();
            targetRoomId = r.room();
            boolean handOff = cfg.isAutoClearRooms() && AutoClearFeature.isAvailable();
            LOGGER.info("[AutoSecret] endgame: uncleared room {} ({} room(s) away) - {}", r.name(), dist.get(r.room()),
                    handOff ? "Auto Clear" : "then hand back");
            status = "Going to the uncleared room " + r.name();
            note(ModChat.text("Going to the uncleared room "), ModChat.value(r.name()),
                    ModChat.dim(handOff ? " - Auto Clear clears it" : ""));
            Trip t = handOff ? Trip.CLEAR : Trip.FINAL;
            if (layout.currentRoom() == r.room()) {
                arrived(t);
                return;
            }
            if (pathToRoom(layout, r, t)) {
                return;
            }
        }
        if (goBackForMissed(layout, rooms, dist)) {
            return;
        }
        stop("nothing left to do - over to you", false);
    }

    /** Rooms sent back to once each, for a secret their route ended without (see {@link #goBackForMissed}). */
    private static final Set<String> revisited = new HashSet<>();

    /**
     * killer560, 2026-10-19: "if it gets to the end of a run for the auto clearer, has done puzzles and everything and not
     * enough secrets are collected go back to rooms it thinks it missed a secret in." Once the rest of the floor is done,
     * a room whose route ended with secrets still uncounted (the route ran to its end, so it was skipped as "got") is
     * visited once more for them, nearest first. Each room is sent back to at most once.
     */
    private static boolean goBackForMissed(DungeonLayout layout, List<RoomStatus.Room> rooms, Map<Integer, Integer> dist) {
        if (fastestTotal() && AutoSecretConfig.getInstance().isSkipUnneededSecrets()
                && com.killer560.hub.scorecalc.ScoreCalculatorFeature.currentResult() != null) {
            AutopilotScore.State s = Autopilot.scoreState(layout, rooms);
            if (s.currentTotal() >= 0 && s.totalSecrets() > 0 && AutopilotScore.usefulSecrets(s) <= 0) {
                if (planSaid.add("goBack")) {
                    LOGGER.info("[AutoSecret] endgame: no going back for missed secrets - the S+ needs no more ({} of {}"
                            + " found, score {})", s.secretsFound(), s.totalSecrets(), s.currentTotal());
                }
                return false;
            }
        }
        RoomStatus.Room best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RoomStatus.Room r : rooms) {
            if (!secreted.contains(r.name()) || revisited.contains(r.name()) || r.secrets() <= 0
                    || !dist.containsKey(r.room())) {
                continue;
            }
            int[] counted = roomCount(r.name());
            if (counted == null || counted[0] >= counted[1]) {
                continue;
            }
            int d = dist.get(r.room());
            if (d < bestDist) {
                best = r;
                bestDist = d;
            }
        }
        if (best == null) {
            return false;
        }
        revisited.add(best.name());
        secreted.remove(best.name());
        LOGGER.info("[AutoSecret] endgame: {} ended its route short of its secrets - going back for them ({} room(s) away)",
                best.name(), bestDist);
        note(ModChat.text("Going back for the missed secrets in "), ModChat.value(best.name()));
        return goSecret(layout, best, dist, best.name(), "none");
    }

    // ------------------------------------------------------------------------------------------- wither doors

    /**
     * killer560, 2026-10-06: never wait at a door while anything else is useful; but when the ONLY thing left before it
     * can go further is a closed wither door, Auto Secret opens it - goes to it with the Interactive Map's own door
     * pathing ({@link AutoClearUtils#pathToDoor}, the Locked Door key's call, which stands two blocks back on this side
     * of a closed door) and right-clicks it ({@link WitherDoorOpener}) with the team's wither key; with no key it waits
     * there and says so; a teammate opening it first just lets it carry on. Of the closed wither doors it can reach,
     * the one whose far side holds the most: unfound secrets of identified rooms beyond it, plus one per unidentified or
     * uncleared room there, plus one when the map shows nothing past it yet; nearest first on a tie.
     *
     * @return true when it is going to a door or is at one
     */
    private static boolean decideWitherDoor(DungeonLayout layout, List<RoomStatus.Room> rooms, Map<Integer, Integer> dist) {
        int here = layout.currentRoom();
        int best = -1;
        int bestScore = 0;
        int bestDist = Integer.MAX_VALUE;
        String bestWhat = null;
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            if (layout.doorType(idx) != DungeonLayout.DOOR_WITHER || !layout.isLocked(idx)
                    || secreted.contains(doorName(idx))) {
                continue;
            }
            int[] near = DungeonMapPathfinder.resolve(layout, here, idx, false);
            if (near == null || near[2] == Integer.MAX_VALUE) {
                continue; // another closed door is in front of this one
            }
            int farCell = 2 * idx - near[1];
            int far = farCell >= 0 && farCell < DungeonLayout.GRID * DungeonLayout.GRID ? layout.roomOfCell(farCell) : -1;
            int unfound = 0;
            int others = 0;
            if (far < 0) {
                others = 1; // the map shows nothing past it yet: assume more rooms
            } else {
                for (RoomStatus.Room r : rooms) {
                    if (dist.containsKey(r.room()) || r.isType("BLOOD")) {
                        continue;
                    }
                    if (r.room() != far && DungeonMapPathfinder.findPath(layout, far, r.room(), true) == null) {
                        continue;
                    }
                    if ("Unknown".equals(r.name())) {
                        others++;
                    } else {
                        unfound += r.unfound();
                        others += r.cleared() ? 0 : 1;
                    }
                }
            }
            int score = unfound + others;
            if (score > bestScore || score == bestScore && score > 0 && near[2] < bestDist) {
                best = idx;
                bestScore = score;
                bestDist = near[2];
                bestWhat = unfound + " unfound secret(s), " + others + " room(s) to explore or clear";
            }
        }
        if (best < 0) {
            return false;
        }
        if (DungeonMapPathfinder.getDoorPos(layout, here, best) == null) {
            return false;
        }
        String said = best + "|" + bestWhat;
        if (!said.equals(lastDoorSaid)) {
            lastDoorSaid = said;
            LOGGER.info("[AutoSecret] wither door at cell {}: {} beyond it, {} room(s) away - the only way on", best,
                    bestWhat, bestDist);
            note(ModChat.text("Nothing left on this side - going to the wither door"), ModChat.dim(" (" + bestWhat
                    + " beyond it)"));
        }
        goToDoor(layout, best, false, null);
        return true;
    }

    /**
     * Goes to a door's near side with the Interactive Map's door pathing ({@link AutoClearUtils#pathToDoor}, two blocks
     * back from it) and then opens it in {@link Phase#DOOR}. {@code blood}: the blood door (Dungeon Autopilot only).
     */
    private static void goToDoor(DungeonLayout layout, int cell, boolean blood, String why) {
        int here = layout.currentRoom();
        BlockPos goal = here < 0 ? null : DungeonMapPathfinder.getDoorPos(layout, here, cell);
        if (cell != doorCell || blood != doorBlood) {
            resetDoor();
        }
        doorCell = cell;
        doorBlood = blood;
        doorGoal = goal;
        doorLock = DungeonLayout.doorBlock(cell);
        target = doorName(cell);
        targetRoomId = -1;
        if (why != null) {
            LOGGER.info("[AutoSecret] going to the {} door at cell {}: {}", blood ? "blood" : "wither", cell, why);
        }
        if (goal == null) {
            tripFailed("no way to the door from here");
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.player.blockPosition().distSqr(goal) <= 9) {
            beginDoor();
            return;
        }
        AutoRoutesFeature.cancelForInteractiveMap("Auto Secret");
        ClearExecutor.setExternalOwner(true);
        if (!AutoClearUtils.pathToDoor(layout, cell, false)) {
            ClearExecutor.setExternalOwner(false);
            tripFailed("the map would not path to the door");
            return;
        }
        status = blood ? "Going to the blood door" : "Going to the wither door";
        beginTravel(Trip.DOOR);
    }

    private static String doorName(int cell) {
        return "the door at cell " + cell;
    }

    private static void resetDoor() {
        WitherDoorOpener.cancel();
        doorCell = -1;
        doorBlood = false;
        doorGoal = null;
        doorLock = null;
        doorClicks = 0;
        doorLastClickTick = -1000;
        noKeySaid = false;
    }

    private static void beginDoor() {
        LOGGER.info("[AutoSecret] at the {} door at cell {}", doorBlood ? "blood" : "wither", doorCell);
        setPhase(Phase.DOOR);
        doorLastClickTick = -1000;
    }

    /** Clicks to try before waiting for a teammate instead, and the ticks to let each one show. */
    private static final int DOOR_CLICKS = 3;
    private static final int DOOR_CLICK_GAP_TICKS = 40;

    /** At the door: open it if the team has a key; otherwise wait, saying so; carry on the moment it reads open. */
    private static void tickDoor() {
        Minecraft client = Minecraft.getInstance();
        DungeonLayout layout = DungeonLayout.capture();
        if (doorCell < 0 || !layout.isLocked(doorCell)) {
            LOGGER.info("[AutoSecret] the {} door at cell {} is open ({} click(s) of ours) - carrying on",
                    doorBlood ? "blood" : "wither", doorCell, doorClicks);
            note(ModChat.good(doorBlood ? "Blood door open" : "Wither door open"), ModChat.dim(" - carrying on"));
            if (doorBlood) {
                Autopilot.onBloodDoorOpened();
            }
            resetDoor();
            lastDoorSaid = null;
            setPhase(Phase.SETTLE);
            return;
        }
        if (WitherDoorOpener.isBusy()) {
            return;
        }
        // The blood key is the team's sidebar tick; with no Keys line to read, one click is how to find out.
        boolean key = doorBlood ? com.killer560.hub.doorkeys.DungeonKeys.bloodKey() != 0
                : WitherDoorOpener.haveKey(client.player);
        String kind = doorBlood ? "blood" : "wither";
        if (key && doorClicks < DOOR_CLICKS && phaseTicks - doorLastClickTick >= DOOR_CLICK_GAP_TICKS) {
            doorClicks++;
            doorLastClickTick = phaseTicks;
            status = "Opening the " + kind + " door";
            LOGGER.info("[AutoSecret] opening the {} door at cell {} (click {} of {})", kind, doorCell, doorClicks,
                    DOOR_CLICKS);
            if (doorClicks == 1) {
                note(ModChat.text("Opening the " + kind + " door"));
            }
            WitherDoorOpener.click(doorLock, doorBlood);
            return;
        }
        if (!key && doorClicks > 0 && phaseTicks - doorLastClickTick < DOOR_CLICK_GAP_TICKS) {
            // Our click spent the key ("opened a WITHER door!") and the door is still on its way out.
            status = "Opening the " + kind + " door";
        } else if (!key) {
            status = "Waiting at " + kind + " door (no key)";
            if (!noKeySaid) {
                noKeySaid = true;
                LOGGER.info("[AutoSecret] waiting at the {} door at cell {}: no {} key", kind, doorCell, kind);
                say(ModChat.bad("No " + kind + " key"), ModChat.dim(" - waiting at the " + kind
                        + " door for a key or a teammate"));
            }
        } else if (doorClicks >= DOOR_CLICKS && phaseTicks - doorLastClickTick >= DOOR_CLICK_GAP_TICKS) {
            status = "Waiting at " + kind + " door";
            if (!noKeySaid) {
                noKeySaid = true;
                String why = WitherDoorOpener.lastResult();
                LOGGER.info("[AutoSecret] the {} door at cell {} stayed shut after {} click(s){}", kind, doorCell, doorClicks,
                        why == null ? "" : " (" + why + ")");
                say(ModChat.bad("The " + kind + " door stayed shut"), ModChat.dim(" - waiting there for a teammate"));
            }
        }
        if (phaseTicks % 100 == 99) {
            // Something else may have become useful (a teammate opened another way, the map showed more).
            setPhase(Phase.DECIDE);
        }
    }

    private static String describe(List<RoomStatus.Room> rooms) {
        List<String> parts = new ArrayList<>();
        for (RoomStatus.Room r : rooms) {
            parts.add(r.name() + "=" + r.unfound());
        }
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    // ------------------------------------------------------------------------------------------- trips

    private static boolean pathToRoom(DungeonLayout layout, RoomStatus.Room r, Trip t) {
        AutoRoutesFeature.cancelForInteractiveMap("Auto Secret");
        ClearExecutor.setExternalOwner(true);
        if (!AutoClearUtils.pathToRoom(layout, r.room(), r.mainTile(), 0)) {
            ClearExecutor.setExternalOwner(false);
            tripFailed("the map would not path to " + r.name());
            return false;
        }
        beginTravel(t);
        return true;
    }

    private static boolean etherPathTo(BlockPos landing, Trip t) {
        AutoRoutesFeature.cancelForInteractiveMap("Auto Secret");
        ClearExecutor.setExternalOwner(true);
        ClearExecutor.etherPath(landing, null);
        beginTravel(t);
        return true;
    }

    private static void beginTravel(Trip t) {
        // The insta-clear recorder labels the room entry this trip makes as ours, not his.
        InstaClearTracker.noteAutomatedEntry("Auto Secret");
        trip = t;
        tripQuizStarted = com.killer560.hub.puzzlesolvers.QuizState.started();
        busySeen = false;
        arrivalSeqAtStart = ClearExecutor.arrivalSeq();
        setPhase(Phase.TRAVEL);
    }

    /** Whether Oruo had begun when this trip began (a Quiz trip that starts him ends there). */
    private static boolean tripQuizStarted;

    private static void tickTravel(Minecraft client) {
        if (trip == Trip.QUIZ && !tripQuizStarted && com.killer560.hub.puzzlesolvers.QuizState.started()) {
            // Walking in started the countdown (killer560, 2026-10-18): out again at once - the rest of the trip, onto the
            // answer spot, is for when a question opens.
            if (ClearExecutor.isBusy()) {
                ClearExecutor.cancel();
            }
            ClearExecutor.setExternalOwner(false);
            tripFailures.remove(target);
            LOGGER.info("[AutoSecret] Quiz: Oruo began {} tick(s) into the trip, as he walked in - leaving at once ({})",
                    phaseTicks, com.killer560.hub.puzzlesolvers.QuizState.describe());
            setPhase(Phase.DECIDE);
            return;
        }
        if (trip == Trip.START_NODE && RouteExecutor.isRunning()) {
            // Already standing on it (the warp had nothing to do) and the route armed at once.
            beginRoute();
            return;
        }
        if (routeJustCompleted()) {
            onRouteDone(client);   // it armed and ran to its end before the trip's arrival was seen
            return;
        }
        boolean busy = ClearExecutor.isBusy();
        busySeen |= busy;
        if (busy && phaseTicks < TRAVEL_TIMEOUT_TICKS) {
            return;
        }
        if (busy) {
            ClearExecutor.cancel();
            ClearExecutor.setExternalOwner(false);
            tripFailed("the trip took over " + TRAVEL_TIMEOUT_TICKS / 20 + " s");
            return;
        }
        if (!busySeen && phaseTicks < 10) {
            return; // the search is handed to the planner thread; give it a few ticks to show up as busy
        }
        boolean arrived = ClearExecutor.arrivalSeq() != arrivalSeqAtStart;
        if (!arrived) {
            DungeonLayout layout = DungeonLayout.capture();
            int here = layout.currentRoom();
            arrived = trip == Trip.DOOR ? doorGoal != null && client.player != null
                    && client.player.blockPosition().distSqr(doorGoal) <= 9
                    : trip == Trip.KEY ? keyGoal != null && client.player != null
                    && client.player.blockPosition().below().distSqr(keyGoal) <= 4
                    : trip == Trip.INSTA_INTO ? instaLanding != null && client.player != null
                    && client.player.blockPosition().below().distSqr(instaLanding) <= 4
                    : here >= 0 && here == roomIdOf(layout, trip == Trip.INSTA_FROM ? instaFrom : target);
        }
        if (trip != Trip.START_NODE) {
            ClearExecutor.setExternalOwner(false);
        }
        if (!arrived && trip == Trip.EXPLORE && !ClearExecutor.lastPathFailed()) {
            arrived = true;   // the room it went to may have regrouped once identified; the trip ran, that is enough
        }
        if (!arrived) {
            tripFailed(ClearExecutor.lastPathFailed() ? "no etherwarp path" : "the warp ended short");
            return;
        }
        arrived(trip);
    }

    private static int roomIdOf(DungeonLayout layout, String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < layout.roomCount(); i++) {
            if (name.equals(layout.name(i))) {
                return i;
            }
        }
        return -1;
    }

    private static void arrived(Trip t) {
        tripFailures.remove(target);
        switch (t) {
            case START_NODE -> setPhase(Phase.AWAIT_ROUTE);
            case ICE_FILL -> beginIceFill();
            case INSTA_FROM -> etherPathTo(instaLanding, Trip.INSTA_INTO);
            case INSTA_INTO -> {
                LOGGER.info("[AutoSecret] insta clear {}: landed", target);
                setPhase(Phase.SETTLE);
            }
            case PUZZLE -> setPhase(Phase.PUZZLE_WAIT);
            case QUIZ -> beginQuizWait();
            case CLEAR -> handToAutoClear();
            case DOOR -> beginDoor();
            case FINAL -> stop("in " + target + ", which is left to clear - over to you", false);
            case EXPLORE -> setPhase(Phase.SETTLE);
            case KEY -> beginKeyWait();
            default -> setPhase(Phase.DECIDE);
        }
    }

    /** A trip that did not get there. Two more tries from wherever he is, then that room is left out this run. */
    private static void tripFailed(String why) {
        if (pilot) {
            Autopilot.noteFailed();
        }
        if (trip == Trip.KEY && keyTarget != null) {
            Autopilot.keyNotTaken(keyTarget.id());
            keyTarget = null;
        }
        int n = tripFailures.merge(String.valueOf(target), 1, Integer::sum);
        boolean giveUp = n > MAX_TRIP_FAILURES;
        LOGGER.info("[AutoSecret] trip to {} failed ({}){}", target, why, giveUp ? " - leaving it out" : " - trying again");
        if (giveUp) { // a retry is logged above, not said (killer560, 2026-10-16: start/stop lines only)
            say(ModChat.bad("Couldn't get to "), ModChat.value(String.valueOf(target)), ModChat.dim(" (" + why + ") - skipped"));
        }
        if (trip == Trip.QUIZ) {
            QuizPlan.tripFailed(giveUp);   // the Quiz is a puzzle: given up on, it is left to the endgame, not the secret list
            setPhase(Phase.SETTLE);
            return;
        }
        if (giveUp && trip == Trip.ICE_FILL) {
            iceFillTried = true;   // Ice Fill is a puzzle: left to the rest of the run, not to the secret list
        } else if (giveUp && target != null) {
            secreted.add(target);
            instaTried.add(target);
        } else if (trip == Trip.ICE_FILL) {
            iceFillTried = false;
        } else if (trip == Trip.PUZZLE && target != null) {
            puzzlesDone.remove(target);
        } else if ((trip == Trip.CLEAR || trip == Trip.FINAL) && target != null) {
            clearsDone.remove(target);
        }
        setPhase(Phase.SETTLE);
    }

    // ------------------------------------------------------------------------------------------- routes

    private static void tickAwaitRoute() {
        if (routeJustCompleted()) {
            onRouteDone(Minecraft.getInstance());
            return;
        }
        if (RouteExecutor.isRunning()) {
            beginRoute();
            return;
        }
        if (phaseTicks > ARM_TIMEOUT_TICKS) {
            permitLeaveHere();
            secreted.add(target);
            LOGGER.info("[AutoSecret] {}: landed, but its start node did not arm - skipped", target);
            say(ModChat.bad("The start node of "), ModChat.value(target), ModChat.dim(" did not arm - skipped"));
            setPhase(Phase.DECIDE);
        }
    }

    private static void beginRoute() {
        routeActions = RouteExecutor.actionsBegun();
        routeIdleTicks = 0;
        status = "Running the route in " + target;
        LOGGER.info("[AutoSecret] route {} started", target);
        setPhase(Phase.ROUTE);
    }

    private static void tickRoute() {
        if (RouteExecutor.isRunning()) {
            int actions = RouteExecutor.actionsBegun();
            if (actions != routeActions) {
                routeActions = actions;
                routeIdleTicks = 0;
            } else if (++routeIdleTicks > AutoSecretConfig.getInstance().getRouteStallSeconds() * 20) {
                // A path-less route waits for him to walk to its next node; he is not going to while this runs it.
                RouteExecutor.stop("Auto Secret: no node for " + AutoSecretConfig.getInstance().getRouteStallSeconds() + " s");
                secreted.add(target);
                LOGGER.info("[AutoSecret] route {} stalled (no node begun) - moving on", target);
                say(ModChat.bad("The route in "), ModChat.value(target), ModChat.dim(" stalled - moving on"));
                setPhase(Phase.SETTLE);
            }
            return;
        }
        if (routeJustCompleted() && !com.killer560.hub.autopuzzles.AutoPuzzleActivity.isWorking()) {
            onRouteDone(Minecraft.getInstance());   // normally seen at START already (tickStart)
            return;
        }
        routeCompletionsSeen = RouteExecutor.completions();
        secreted.add(target);
        String reason = RouteExecutor.stopReason();
        if (reason == null) {
            LOGGER.info("[AutoSecret] route {} finished", target);
            note(ModChat.good("Route done"), ModChat.dim(" - " + target));
        } else if (reason.startsWith("etherwarp didn't land")) {
            LOGGER.info("[AutoSecret] route {} ended: {}", target, reason);
            correction(Minecraft.getInstance(), "a route etherwarp landed somewhere else (" + reason + ")");
        } else {
            LOGGER.info("[AutoSecret] route {} ended: {}", target, reason);
            say(ModChat.bad("The route in "), ModChat.value(target), ModChat.dim(" ended: " + reason + " - moving on"));
        }
        setPhase(Phase.SETTLE);
    }

    // ------------------------------------------------------------------------------------------- puzzles

    private static void beginIceFill() {
        target = "Ice Fill";
        status = "Ice Fill (Auto Ice Fill)";
        setPhase(Phase.ICE_FILL);
    }

    private static void tickIceFill() {
        boolean done = AutoPuzzlesFeature.isIceFillDone() || roomCleared("Ice Fill");
        if (done) {
            LOGGER.info("[AutoSecret] Ice Fill done after {} tick(s)", phaseTicks);
            note(ModChat.good("Ice Fill done"));
            setPhase(Phase.SETTLE);
            return;
        }
        if (phaseTicks > ICE_FILL_TIMEOUT_TICKS) {
            LOGGER.info("[AutoSecret] Ice Fill not done in {} s - moving on", ICE_FILL_TIMEOUT_TICKS / 20);
            say(ModChat.bad("Ice Fill not done in " + ICE_FILL_TIMEOUT_TICKS / 20 + " s"), ModChat.dim(" - moving on"));
            setPhase(Phase.SETTLE);
        }
    }

    private static void tickPuzzleWait() {
        status = "At the puzzle " + target;
        if (pilot) {
            RoomStatus.Room r = roomNamed(target);
            boolean finished = r != null && Autopilot.puzzleFinished(r);
            if (finished || phaseTicks > Autopilot.puzzleTimeoutSeconds(target) * 20) {
                LOGGER.info("[AutoSecret] puzzle {}: {} after {} tick(s)", target,
                        finished ? "finished" : "not finished in time - moving on", phaseTicks);
                setPhase(Phase.SETTLE);
            }
            return;
        }
        boolean solved = roomCleared(target) || com.killer560.hub.autopuzzles.AutoPuzzleActivity.solvedByHim(target);
        if (solved || phaseTicks > AutoSecretConfig.getInstance().getPuzzleWaitSeconds() * 20) {
            LOGGER.info("[AutoSecret] puzzle {}: {} after {} tick(s)", target, solved ? "done" : "moving on",
                    phaseTicks);
            if (solved) {
                // The map (or the auto) already says it is done: the next decision now, not after a settle.
                permitLeaveHere();
                setPhase(Phase.DECIDE);
            } else {
                setPhase(Phase.SETTLE);
            }
        }
    }

    private static RoomStatus.Room roomNamed(String name) {
        for (RoomStatus.Room r : RoomStatus.rooms()) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return null;
    }

    private static boolean roomCleared(String name) {
        for (RoomStatus.Room r : RoomStatus.rooms()) {
            if (r.name().equals(name)) {
                return r.cleared();
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------- Auto Clear

    private static void handToAutoClear() {
        int id = runId;
        String room = target;
        status = "Auto Clear: " + room;
        setPhase(Phase.AUTO_CLEAR);
        LOGGER.info("[AutoSecret] handing {} to Auto Clear", room);
        AutoClearFeature.clearRoom(room, () -> Minecraft.getInstance().execute(() -> {
            if (id == runId && phase == Phase.AUTO_CLEAR) {
                LOGGER.info("[AutoSecret] Auto Clear cleared {} - back to secrets (tick {})", room, ClearExecutor.clientTick());
                note(ModChat.good("Cleared "), ModChat.value(room), ModChat.dim(" - back to secrets"));
                // Auto Clear ended on the map's own clear: nothing to settle (the 10-tick SETTLE before 2026-10-20 was
                // half a second of standing still after every room). The room is done with, so the map may path out.
                permitLeaveHere();
                setPhase(Phase.DECIDE);
            }
        }), why -> Minecraft.getInstance().execute(() -> {
            if (id == runId && phase == Phase.AUTO_CLEAR) {
                LOGGER.info("[AutoSecret] Auto Clear gave up on {}: {}", room, why);
                say(ModChat.bad("Auto Clear gave up on "), ModChat.value(room), ModChat.dim(": " + why));
                setPhase(Phase.SETTLE);
            }
        }));
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static void setPhase(Phase p) {
        if (p == Phase.SETTLE) {
            permitLeaveHere();
        }
        phase = p;
        phaseTicks = 0;
    }

    /**
     * Every SETTLE follows a step of ours that is over (a route, a puzzle, a clear, a landing): the room he is in is done
     * with, so the map may path out of it even when its name (Maze, Boulder, Trap) would refuse
     * ({@link AutoClearUtils#permitLeave}). Without this a trap route or a Teleport Maze stranded the run.
     */
    private static void permitLeaveHere() {
        DungeonLayout layout = DungeonLayout.capture();
        String name = layout.name(layout.currentRoom());
        if (name != null) {
            AutoClearUtils.permitLeave(name);
        }
    }

    private static void say(Component... parts) {
        if (AutoSecretConfig.getInstance().isChatFeedback()) {
            ModChat.send(chatName(), parts);
        }
    }

    /**
     * A progress line: logged word for word, never shown. killer560 (2026-10-16): "only have a message for started auto
     * clear or stopped auto clear type things" - chat keeps the start, the stop, corrections and real failures.
     */
    private static void note(Component... parts) {
        ModChat.log(chatName(), parts);
    }

    private static String fmtPercent(double p) {
        return String.format(Locale.US, "%.1f", p);
    }
}
