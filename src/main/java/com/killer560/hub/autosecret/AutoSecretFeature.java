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
import com.killer560.hub.livemap.autoclear.TeleportUtils;
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

    private enum Phase { IDLE, DECIDE, TRAVEL, AWAIT_ROUTE, ROUTE, ICE_FILL, PUZZLE_WAIT, SETTLE, AUTO_CLEAR, WAITING }

    /** What a TRAVEL is for, so its arrival knows what comes next. */
    private enum Trip { START_NODE, ICE_FILL, INSTA_FROM, INSTA_INTO, PUZZLE, CLEAR, FINAL }

    /** Ticks to wait for a route to arm once he has landed on its start node. */
    private static final int ARM_TIMEOUT_TICKS = 40;
    /** A trip that has not finished in this long is given up on. */
    private static final int TRAVEL_TIMEOUT_TICKS = 30 * 20;
    private static final int ICE_FILL_TIMEOUT_TICKS = 60 * 20;
    /** Position packets this soon after anything of ours moved him are that thing's own landings. */
    private static final int CORRECTION_GRACE_TICKS = 20;
    private static final int MAX_TRIP_FAILURES = 2;
    private static final int SETTLE_TICKS = 10;
    /** Insta-clear candidate landings looked at per room, at most. */
    private static final int MAX_LANDINGS = 2500;

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
    private static String instaFrom;
    private static int runId;
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

    private static int seenPositionPackets;
    private static int seenCorrections;
    private static int quietTicks;

    private static boolean toggleWasDown;
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
    }

    // ------------------------------------------------------------------------------------------- public API

    public static boolean isRunning() {
        return phase != Phase.IDLE;
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
        Minecraft client = Minecraft.getInstance();
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
        if (!AutoRoutesConfig.getInstance().isEnabled()) {
            say(ModChat.bad("Turn Auto Routes on first - Auto Secret plays your routes."));
            return false;
        }
        runId++;
        secreted.clear();
        noRouteSaid.clear();
        instaTried.clear();
        puzzlesDone.clear();
        clearsDone.clear();
        tripFailures.clear();
        iceFillTried = false;
        lastWaiting = null;
        level = client.level;
        seenPositionPackets = ClearExecutor.positionPackets();
        seenCorrections = ClearExecutor.serverCorrections();
        quietTicks = 0;
        latchHeldKeys(client);
        setPhase(Phase.DECIDE);
        status = "Starting";
        LOGGER.info("[AutoSecret] started (floor {})", DungeonState.getFloor());
        say(ModChat.good("Started"), ModChat.dim(" - press a movement key to take over"));
        return true;
    }

    /** Stops it, saying why. {@code cancelOurs}: also stop the warp / route / clear it started. */
    public static void stop(String reason, boolean cancelOurs) {
        if (phase == Phase.IDLE) {
            return;
        }
        Phase was = phase;
        phase = Phase.IDLE;
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
        ModChat.send(CHAT, ModChat.bad("Stopped"), ModChat.dim(" - " + reason));
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
        phaseTicks++;
        switch (phase) {
            case DECIDE -> decide(client);
            case TRAVEL -> tickTravel(client);
            case AWAIT_ROUTE -> tickAwaitRoute();
            case ROUTE -> tickRoute();
            case ICE_FILL -> tickIceFill();
            case PUZZLE_WAIT -> tickPuzzleWait();
            case SETTLE -> {
                if (phaseTicks >= SETTLE_TICKS) {
                    setPhase(Phase.DECIDE);
                }
            }
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

    private static void pollToggleKey(Minecraft client) {
        int key = AutoSecretConfig.getInstance().getToggleKey();
        boolean down = key != KeyUtil.NONE && client.getWindow() != null && McCompat.screen(client) == null
                && KeyUtil.isBindDown(client.getWindow(), key);
        if (down && !toggleWasDown) {
            toggle();
        }
        toggleWasDown = down;
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
        if (percent >= 100.0) {
            stop("all secrets found (" + fmtPercent(percent) + "%)", true);
            return true;
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
                || phase == Phase.PUZZLE_WAIT || phase == Phase.AUTO_CLEAR;
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
        ModChat.send(CHAT, ModChat.bad("Server correction: "), ModChat.text(what), ModChat.dim(" at " + where
                + " - carrying on"));
        ModSounds.playCorrectionAlarm();
    }

    // ------------------------------------------------------------------------------------------- deciding

    private static void decide(Minecraft client) {
        DungeonLayout layout = DungeonLayout.capture();
        int here = layout.currentRoom();
        if (here < 0 || !AutoClearUtils.canPath(layout)) {
            status = "Waiting to be able to path from here";
            if (phaseTicks == 100) {
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

        if (decideIceFill(layout, rooms, dist)) {
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

    private static boolean eligibleForSecrets(RoomStatus.Room r) {
        return !"Unknown".equals(r.name()) && !r.isType("PUZZLE") && !r.isType("TRAP") && !r.isType("BLOOD")
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
            say(ModChat.text("Going to "), ModChat.value("Ice Fill"), ModChat.dim(" first - Auto Ice Fill does it"));
            if (layout.currentRoom() == r.room()) {
                beginIceFill();
                return true;
            }
            return pathToRoom(layout, r, Trip.ICE_FILL);
        }
        return false;
    }

    /**
     * Before secreting: every uncleared room the Insta Clear tracker has a KNOWN entry for (only an entry
     * {@link InstaClearTracker#knownToInstaClear} says works - never a guess). An entry is a landing inside the room
     * warped to from a room next to it or one beyond it; the key is the tracker's own ({@code entryKeyFor}), so the
     * candidates are the room's standable blocks against its near rooms, and the first known one is taken. Getting to
     * the from-room is a map trip; the last warp is planned by the map's planner from there, which normally goes
     * straight in. Each room is tried once a run.
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
            for (BlockPos landing : landings(r)) {
                for (RoomStatus.Room f : froms) {
                    String key = InstaClearTracker.entryKeyFor(r.name(), landing, f.name());
                    if (!InstaClearTracker.knownToInstaClear(r.name(), key)) {
                        continue;
                    }
                    LOGGER.info("[AutoSecret] insta clear {}: entry {} (land {} from {})", r.name(), key, landing, f.name());
                    say(ModChat.text("Insta clearing "), ModChat.value(r.name()), ModChat.dim(" from " + f.name()));
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

    /** Standable blocks in the room's tiles, nearest its centre first (bounded). */
    private static List<BlockPos> landings(RoomStatus.Room r) {
        List<BlockPos> out = new ArrayList<>();
        for (int tile : r.tiles()) {
            BlockPos c = DungeonLayout.cellCenter(tile);
            for (int dx = -15; dx <= 15 && out.size() < MAX_LANDINGS; dx++) {
                for (int dz = -15; dz <= 15 && out.size() < MAX_LANDINGS; dz++) {
                    for (int dy = 20; dy >= -12; dy--) {
                        BlockPos p = c.offset(dx, dy, dz);
                        if (TeleportUtils.etherwarpable(p)) {
                            out.add(p);
                            break;
                        }
                    }
                }
            }
        }
        BlockPos centre = DungeonLayout.cellCenter(r.mainTile());
        out.sort(Comparator.comparingDouble(p -> p.distSqr(centre)));
        return out;
    }

    private static boolean decideSecrets(DungeonLayout layout, List<RoomStatus.Room> rooms, Map<Integer, Integer> dist) {
        List<RoomStatus.Room> open = new ArrayList<>();
        List<RoomStatus.Room> blocked = new ArrayList<>();
        for (RoomStatus.Room r : rooms) {
            if (!eligibleForSecrets(r)) {
                continue;
            }
            (dist.containsKey(r.room()) ? open : blocked).add(r);
        }
        open.sort(Comparator.comparingInt((RoomStatus.Room r) -> -r.unfound()).thenComparingInt(r -> dist.get(r.room())));
        String blockedText = describe(blocked);
        for (RoomStatus.Room r : open) {
            Route route = RouteStore.getInstance().forRoom(r.name());
            if (route == null || route.startNode() == null) {
                secreted.add(r.name());
                if (noRouteSaid.add(r.name())) {
                    LOGGER.info("[AutoSecret] no route for {} ({} unfound) - skipped", r.name(), r.unfound());
                    say(ModChat.bad("No route for "), ModChat.value(r.name()), ModChat.dim(" (" + r.unfound()
                            + " unfound) - skipped"));
                }
                continue;
            }
            target = r.name();
            targetRoomId = r.room();
            LOGGER.info("[AutoSecret] target {}: {} unfound, {} room(s) away; open {}; behind a closed door {}", r.name(),
                    r.unfound(), dist.get(r.room()), describe(open), blockedText);
            status = "Secreting " + r.name() + " (" + r.unfound() + " unfound)";
            say(ModChat.text("Going to "), ModChat.value(r.name()), ModChat.dim(" (" + r.unfound() + " unfound)"));
            // Exactly what a Go + Secret press does before its goal runs (InteractiveMapFeature.queue): it clears the
            // "route just finished here" latch the last room's route left, which otherwise holds the next start node
            // un-armed under him (found in 102-sim-autosecret: every room after the first "did not arm").
            AutoRoutesFeature.cancelForInteractiveMap("Auto Secret");
            if (!AutoRoutesFeature.warpToStartNode(layout, r.room())) {
                secreted.add(r.name());
                LOGGER.info("[AutoSecret] {}: could not warp to its start node - skipped", r.name());
                say(ModChat.bad("Couldn't warp to the start node of "), ModChat.value(r.name()), ModChat.dim(" - skipped"));
                continue;
            }
            beginTravel(Trip.START_NODE);
            return true;
        }
        if (!blocked.isEmpty()) {
            if (!blockedText.equals(lastWaiting)) {
                lastWaiting = blockedText;
                LOGGER.info("[AutoSecret] waiting: {} behind a closed door", blockedText);
                say(ModChat.text("Waiting for a teammate to open the way to "), ModChat.value(blockedText));
            }
            status = "Waiting for a door: " + blockedText;
            setPhase(Phase.WAITING);
            return true;
        }
        return false;
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
        for (RoomStatus.Room r : puzzles) {
            puzzlesDone.add(r.name());
            if (RoomStatus.teammateInside(r.cells())) {
                LOGGER.info("[AutoSecret] puzzle {}: a teammate is in it - left to them", r.name());
                say(ModChat.value(r.name()), ModChat.dim(": a teammate is on it"));
                continue;
            }
            target = r.name();
            targetRoomId = r.room();
            LOGGER.info("[AutoSecret] endgame: puzzle {} ({} room(s) away)", r.name(), dist.get(r.room()));
            status = "Going to the puzzle " + r.name();
            say(ModChat.text("Secrets done - going to the puzzle "), ModChat.value(r.name()));
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
        if (!uncleared.isEmpty()) {
            RoomStatus.Room r = uncleared.get(0);
            clearsDone.add(r.name());
            target = r.name();
            targetRoomId = r.room();
            boolean handOff = cfg.isAutoClearRooms() && AutoClearFeature.isAvailable();
            LOGGER.info("[AutoSecret] endgame: uncleared room {} ({} room(s) away) - {}", r.name(), dist.get(r.room()),
                    handOff ? "Auto Clear" : "then hand back");
            status = "Going to the uncleared room " + r.name();
            say(ModChat.text("Going to the uncleared room "), ModChat.value(r.name()),
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
        stop("nothing left to do - over to you", false);
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
        trip = t;
        busySeen = false;
        arrivalSeqAtStart = ClearExecutor.arrivalSeq();
        setPhase(Phase.TRAVEL);
    }

    private static void tickTravel(Minecraft client) {
        if (trip == Trip.START_NODE && RouteExecutor.isRunning()) {
            // Already standing on it (the warp had nothing to do) and the route armed at once.
            beginRoute();
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
            arrived = trip == Trip.INSTA_INTO ? instaLanding != null && client.player != null
                    && client.player.blockPosition().below().distSqr(instaLanding) <= 4
                    : here >= 0 && here == roomIdOf(layout, trip == Trip.INSTA_FROM ? instaFrom : target);
        }
        if (trip != Trip.START_NODE) {
            ClearExecutor.setExternalOwner(false);
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
            case CLEAR -> handToAutoClear();
            case FINAL -> stop("in " + target + ", which is left to clear - over to you", false);
            default -> setPhase(Phase.DECIDE);
        }
    }

    /** A trip that did not get there. Two more tries from wherever he is, then that room is left out this run. */
    private static void tripFailed(String why) {
        int n = tripFailures.merge(String.valueOf(target), 1, Integer::sum);
        boolean giveUp = n > MAX_TRIP_FAILURES;
        LOGGER.info("[AutoSecret] trip to {} failed ({}){}", target, why, giveUp ? " - leaving it out" : " - trying again");
        say(ModChat.bad("Couldn't get to "), ModChat.value(String.valueOf(target)),
                ModChat.dim(" (" + why + ")" + (giveUp ? " - skipped" : " - trying again")));
        if (giveUp && target != null) {
            secreted.add(target);
            instaTried.add(target);
            if (trip == Trip.ICE_FILL) {
                iceFillTried = true;
            }
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
        if (RouteExecutor.isRunning()) {
            beginRoute();
            return;
        }
        if (phaseTicks > ARM_TIMEOUT_TICKS) {
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
        secreted.add(target);
        String reason = RouteExecutor.stopReason();
        if (reason == null) {
            LOGGER.info("[AutoSecret] route {} finished", target);
            say(ModChat.good("Route done"), ModChat.dim(" - " + target));
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
            say(ModChat.good("Ice Fill done"));
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
        if (roomCleared(target) || phaseTicks > AutoSecretConfig.getInstance().getPuzzleWaitSeconds() * 20) {
            LOGGER.info("[AutoSecret] puzzle {}: {} after {} tick(s)", target, roomCleared(target) ? "done" : "moving on",
                    phaseTicks);
            setPhase(Phase.SETTLE);
        }
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
                LOGGER.info("[AutoSecret] Auto Clear cleared {} - back to secrets", room);
                say(ModChat.good("Cleared "), ModChat.value(room), ModChat.dim(" - back to secrets"));
                setPhase(Phase.SETTLE);
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
        phase = p;
        phaseTicks = 0;
    }

    private static void say(Component... parts) {
        if (AutoSecretConfig.getInstance().isChatFeedback()) {
            ModChat.send(CHAT, parts);
        }
    }

    private static String fmtPercent(double p) {
        return String.format(Locale.US, "%.1f", p);
    }
}
