package com.killer560.hub.livemap;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.leapmenu.LeapMenuFeature;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.livemap.autoclear.AutoClearUtils;
import com.killer560.hub.livemap.autoclear.BloodRush;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.killer560.hub.compat.McCompat;

/**
 * Interactive Map - port of QUOI's {@code InteractiveMap} module (plus NoammAddons/Odin map UX): keybinds, the HUD peek,
 * clicking the HUD map from chat, per-room "cleared by" tracking and the player marker list the
 * {@link InteractiveMapScreen} draws. Teleport pathing ({@code livemap.autoclear}) and Auto Blood Rush are cheat-gated.
 */
public final class InteractiveMapFeature {

    static final String CHAT = "Interactive Map";

    private static boolean openWasDown = false;
    private static boolean startWasDown = false;
    private static boolean lockedWasDown = false;
    private static boolean goSecretWasDown = false;
    private static boolean bloodRushWasDown = false;
    private static boolean peeking = false;

    /** Per-room (group main tile) last map state, for cleared-by tracking. */
    private static final Map<Integer, Integer> lastRoomState = new HashMap<>();
    /** Room key -> players who were in it when it turned cleared/green. */
    private static final Map<String, List<String>> clearedBy = new HashMap<>();
    private static int seenGeneration = -1;
    private static int clearTick = 0;

    private InteractiveMapFeature() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("InteractiveMapFeature.tick", InteractiveMapFeature::tick));
        ClearExecutor.register();
        BloodRush.register();
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) {
                return;
            }
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !tryOpenFromHud(event.x(), event.y()));
        });
    }

    // ------------------------------------------------------------------------------------------- ticking

    private static void tick(Minecraft client) {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        boolean hasWindow = client.getWindow() != null && client.player != null;
        boolean inClear = DungeonState.isInDungeon() && !LiveMapFeature.isInBoss();

        // HUD peek: held key enlarges the HUD map.
        peeking = hasWindow && cfg.isEnabled() && cfg.getPeekKeyCode() >= 0 && McCompat.screen(client) == null
                && com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), cfg.getPeekKeyCode());

        // QUOI open key: press opens (only from no screen); Release closes on release, Repress closes on the next press.
        // killer560: "I should be able to set keybinds to mouse buttons as well" - isBindDown polls either a
        // keyboard key or a mouse button, whichever this code encodes (see KeyUtil).
        boolean openDown = hasWindow && cfg.getOpenKeyCode() != com.killer560.hub.util.KeyUtil.NONE
                && com.killer560.hub.util.KeyUtil.isBindDown(client.getWindow(), cfg.getOpenKeyCode());
        boolean mapOpen = McCompat.screen(client) instanceof InteractiveMapScreen;
        if (cfg.isInteractiveMapEnabled() && inClear && !isDead(client)) {
            if (openDown && !openWasDown) {
                if (mapOpen && cfg.isCloseOnRepress()) {
                    McCompat.setScreen(client, null);
                } else if (McCompat.screen(client) == null) {
                    McCompat.setScreen(client, new InteractiveMapScreen(true));
                }
            } else if (!openDown && openWasDown && !cfg.isCloseOnRepress() && mapOpen
                    && ((InteractiveMapScreen) McCompat.screen(client)).openedByKey()) {
                McCompat.setScreen(client, null);
            }
        }
        openWasDown = openDown;
        mapOpen = McCompat.screen(client) instanceof InteractiveMapScreen;

        // QUOI start / locked door keys only act while the map is open. killer560: "The button prebound as
        // lmb and rmb should be customizable in settings, those are the ones currently under start key and
        // locked door key" - both now poll isBindDown so either one can be set to a mouse button too.
        //
        // killer560, 2026-09-29: "Remember this isn't a left or right click but based off of my key binds."
        // So the Start Key no longer means "the room I am standing in": it means "the room under the cursor on
        // the open map", and falls back to the current room only when the cursor is off the map. A bind set to
        // a MOUSE button is deliberately NOT polled here while the map is open - the screen already receives
        // that button as a real click, and polling it as well would count one press twice and turn every single
        // press into a double press.
        boolean pollMouseBinds = !mapOpen;
        boolean startDown = hasWindow && bindPollable(cfg.getStartKeyCode(), pollMouseBinds)
                && com.killer560.hub.util.KeyUtil.isBindDown(client.getWindow(), cfg.getStartKeyCode());
        if (startDown && !startWasDown && mapOpen && inClear && cfg.isInteractiveMapEnabled() && !isDead(client)) {
            onMapPress(cellUnderCursor(client));
        }
        startWasDown = startDown;
        boolean lockedDown = hasWindow && bindPollable(cfg.getLockedDoorKeyCode(), pollMouseBinds)
                && com.killer560.hub.util.KeyUtil.isBindDown(client.getWindow(), cfg.getLockedDoorKeyCode());
        if (lockedDown && !lockedWasDown && mapOpen && inClear && cfg.isInteractiveMapEnabled() && !isDead(client)) {
            pathToLockedDoor();
        }
        lockedWasDown = lockedDown;
        // Go + Secret key: same polling rules as the two above (only with the map open, mouse binds left to the
        // screen's own click delivery), and the same dispatcher, told to take the start-node branch outright.
        boolean goSecretDown = hasWindow && bindPollable(cfg.getGoSecretKeyCode(), pollMouseBinds)
                && com.killer560.hub.util.KeyUtil.isBindDown(client.getWindow(), cfg.getGoSecretKeyCode());
        if (goSecretDown && !goSecretWasDown && mapOpen && inClear && cfg.isInteractiveMapEnabled() && !isDead(client)) {
            onMapSecretPress(cellUnderCursor(client));
        }
        goSecretWasDown = goSecretDown;
        tickPendingGoal(client);

        boolean bloodDown = hasWindow && cfg.getBloodRushKeyCode() != com.killer560.hub.util.KeyUtil.NONE
                && com.killer560.hub.util.KeyUtil.isBindDown(client.getWindow(), cfg.getBloodRushKeyCode());
        if (bloodDown && !bloodRushWasDown && (McCompat.screen(client) == null || mapOpen) && cfg.isBloodRushEnabled()) {
            BloodRush.toggle();
        }
        bloodRushWasDown = bloodDown;

        trackClears(client);
    }

    private static boolean isDead(Minecraft client) {
        return client.player == null || client.player.isDeadOrDying()
                || PartyTracker.isDead(client.player.getGameProfile().name());
    }

    /** A bind is polled here unless it is a mouse button the open map screen is already delivering as a click. */
    private static boolean bindPollable(int code, boolean pollMouseBinds) {
        return code != com.killer560.hub.util.KeyUtil.NONE
                && (pollMouseBinds || !com.killer560.hub.util.KeyUtil.isMouseCode(code));
    }

    // ------------------------------------------------------------------------------------------- map presses

    /** Last map press, for the double-press test. The mod had no double-press helper to reuse, so this is the
     *  whole of it: same room twice inside the configured window. */
    private static long lastPressMs = 0;
    private static int lastPressRoom = -1;

    /**
     * Every Interactive Map "go here" press lands here, whichever input made it - the Start Key polled above, or
     * a click on the screen (killer560, 2026-09-29: "Remember this isn't a left or right click but based off of
     * my key binds", so neither input may have rules of its own).
     *
     * <p>His three cases, in the order they are tested:
     * <ol>
     * <li>"If I double click a room then it should auto pathfind to the start node to start secreting" - a second
     * press on the same room inside the double-press window goes to that room's Auto Routes START node.
     * <li>"If I click a room I am already in once then it does its secrets still" - a single press on the room
     * you are standing in is {@link #activateRoom}, i.e. Auto Routes runs that room's own route.
     * <li>"If I click a different room mid path then it goes doesn't have to be a double click same with
     * dooring" - any other single press just goes there, cancelling a path already in flight if there is one.
     * </ol>
     *
     * @param cell grid cell under the cursor, or -1 when the cursor is not over the map
     */
    static void onMapPress(int cell) {
        onMapPress(cell, false);
    }

    /**
     * killer560, 2026-09-29: "Make a third button bind for go to a room and secret it." The dedicated bind: the
     * double press's action (path to the room's START node, then secret it) on a single press, whatever the
     * Double-Press Secrets toggle says. Same queue as every other map goal, so it retargets a path in flight and
     * cancels a playing secret route exactly as they do.
     */
    static void onMapSecretPress(int cell) {
        onMapPress(cell, true);
    }

    private static void onMapPress(int cell, boolean forceStartNode) {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        if (!cfg.isInteractiveMapEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            // Used to quietly fall back to toggling waypoints, which is why a press sometimes did something
            // entirely unrelated to what it means. Say why instead.
            ModChat.send(CHAT, ModChat.dim(LiveMapFeature.isInBoss()
                    ? "Not during the boss." : "Interactive Map is off, or you are not in a dungeon."));
            return;
        }
        DungeonLayout layout = DungeonLayout.capture();
        int gid = cell < 0 ? -1 : LiveMapFeature.groupIdAt(cell);
        if (gid < 0) {
            lastPressRoom = -1;
            if (cell >= 0 && MapPainter.isDoorCell(cell)) {
                queue(cell, (l, room, doorCell) -> AutoClearUtils.pathToDoor(l, doorCell, cfg.isFaceDoorOnArrival()));
            } else {
                // Cursor is off the map entirely: the key keeps its old "start the room I am in" meaning.
                if (forceStartNode) {
                    pathToCurrentRoomStartNode();
                } else {
                    pathToCurrentRoomStart();
                }
            }
            return;
        }
        int room = layout.roomOfCell(cell);
        if (room < 0) {
            ModChat.send(CHAT, ModChat.bad("That room is unknown"));
            return;
        }
        int gx = cell % LiveMapFeature.GRID;
        int gz = cell / LiveMapFeature.GRID;
        int tile = gx % 2 == 0 && gz % 2 == 0 ? cell : LiveMapFeature.groupsView().get(gid).mainIdx;

        if (forceStartNode) {
            // Not a press the double-press counter should see: it would leave this room half-counted, and a
            // following ordinary press on it could then read as the second of a double press.
            lastPressRoom = -1;
            queue(tile, InteractiveMapFeature::startNodeGoal);
            return;
        }
        if (registerPress(room, cfg) && cfg.isMapDoublePressStartNode()) {
            queue(tile, InteractiveMapFeature::startNodeGoal);
            return;
        }
        // Both remaining cases are the same call: activateRoom hands the click to Auto Routes when it is the
        // room you are standing in ("If I click a room I am already in once then it does its secrets still")
        // and paths there otherwise. Queueing it is what makes the mid-path press a retarget.
        queue(tile, InteractiveMapFeature::activateRoom);
    }

    /** @return true when this press is the second of a double press. */
    private static boolean registerPress(int room, LiveMapConfig cfg) {
        long now = System.currentTimeMillis();
        boolean doublePress = room == lastPressRoom && now - lastPressMs <= cfg.getMapDoublePressMs();
        lastPressMs = now;
        // A third press must not read as another double press off the second one's timestamp.
        lastPressRoom = doublePress ? -1 : room;
        return doublePress;
    }

    /** The double-press action: this room's Auto Routes START node, or the room's own spot when it has none. */
    private static void startNodeGoal(DungeonLayout layout, int room, int tile) {
        if (com.killer560.hub.autoroutes.AutoRoutesFeature.warpToStartNode(layout, room)) {
            return;
        }
        // Auto Routes is the only place this mod stores "where a secret route for this room begins", so a room
        // with no recorded route has no start node to aim at. Falling back to the room's own standing spot is
        // the closest thing the mod actually knows, rather than inventing a second notion of where a room starts.
        ModChat.send(CHAT, ModChat.dim("No route start node for "), ModChat.value(layout.name(room)),
                ModChat.dim(" - pathing to the room"));
        activateRoom(layout, room, tile);
    }

    // ------------------------------------------------------------------------------------------- goal queue

    /** A queued map goal. It is re-resolved against a FRESH layout when it finally runs, because it can sit for
     *  a few ticks waiting for the cancelled path to stop and a room INDEX is only valid for the capture it came
     *  from - the grid CELL it was pressed on is what stays meaningful across captures. */
    @FunctionalInterface
    private interface MapGoal {
        void run(DungeonLayout layout, int room, int tile);
    }

    /** ~3s. Long enough to cover a cancelled search finishing plus a landing, short enough that a goal which can
     *  never start (standing in a Boulder room, say - {@link AutoClearUtils#canPath}) says so instead of hanging. */
    private static final int PENDING_GOAL_TIMEOUT_TICKS = 60;

    private static MapGoal pendingGoal = null;
    private static int pendingGoalCell = -1;
    private static int pendingGoalTicks = 0;

    /**
     * killer560, 2026-09-29: "If I click a different room mid path then it goes doesn't have to be a double click
     * same with dooring." A press while something is already running has to CANCEL first and then re-plan, and
     * it cannot do both in one call: {@code ClearExecutor.etherPath} refuses a second search while one is in
     * flight, and even if it did not, a path planned from the position you were at when you pressed can never
     * execute once you have warped off it ({@code ClearNode.inside} wants you within 0.32 blocks of its first
     * hop). So the goal is held and issued from the tick once the queue has actually stopped.
     */
    /** True while a map goal is waiting for a cancelled path to stop. {@code ClearExecutor.isBusy()} is false
     *  through that window by design, so anything that must not steer against the map (Auto Routes' interlock 5)
     *  has to ask this as well. */
    public static boolean isSteering() {
        return pendingGoal != null;
    }

    private static void queue(int cell, MapGoal goal) {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        // killer560, 2026-09-29: "if I am in the middle of a secret route and use it then it'll use the
        // interactive map portion instead of the secret route portion". Every map goal comes through here, so
        // this is the one place the precedence has to be stated - before the goal runs, and before the decision
        // below about whether it can run now, since a route left playing would steer against either answer.
        // No setting: he asked for the map to win.
        com.killer560.hub.autoroutes.AutoRoutesFeature.cancelForInteractiveMap("Interactive Map");
        boolean busy = ClearExecutor.isBusy() || BloodRush.isRunning() || pendingGoal != null;
        if (!busy) {
            DungeonLayout layout = DungeonLayout.capture();
            goal.run(layout, layout.roomOfCell(cell), cell);
            return;
        }
        if (!cfg.isMapRetargetMidPath()) {
            ModChat.send(CHAT, ModChat.dim("Already pathing - turn on Retarget Mid-Path to change the goal."));
            return;
        }
        if (BloodRush.isRunning()) {
            // It re-paths to its own next door the moment the queue empties, so leaving it running would
            // simply undo the retarget a tick later.
            BloodRush.stop("Stopped (new map goal)");
        }
        ClearExecutor.cancel();
        pendingGoal = goal;
        pendingGoalCell = cell;
        pendingGoalTicks = PENDING_GOAL_TIMEOUT_TICKS;
    }

    private static void tickPendingGoal(Minecraft client) {
        if (pendingGoal == null) {
            return;
        }
        // Dying, the boss starting, or leaving the dungeon all mean the goal no longer refers to anything -
        // drop it silently rather than warping somewhere on a run he is no longer in.
        if (!DungeonState.isInDungeon() || LiveMapFeature.isInBoss() || isDead(client)
                || !LiveMapConfig.getInstance().isInteractiveMapEnabled()) {
            pendingGoal = null;
            pendingGoalCell = -1;
            return;
        }
        DungeonLayout layout = DungeonLayout.capture();
        // canPath is what every other entry point checks anyway; here it also covers the airborne tick or two
        // right after the cancelled path's last warp, which is exactly when a retarget tends to be pressed.
        if (!ClearExecutor.isBusy() && AutoClearUtils.canPath(layout)) {
            MapGoal goal = pendingGoal;
            int cell = pendingGoalCell;
            pendingGoal = null;
            pendingGoalCell = -1;
            goal.run(layout, layout.roomOfCell(cell), cell);
            return;
        }
        if (--pendingGoalTicks <= 0) {
            pendingGoal = null;
            pendingGoalCell = -1;
            ModChat.send(CHAT, ModChat.bad("Could not start from here"));
        }
    }

    /** The grid cell the cursor is over on the open map, or -1. No mouse events are needed for this: the
     *  Interactive Map is a real {@code Screen}, so it is handed the cursor position every frame and caches it -
     *  the key poll above just asks it what is under there. */
    private static int cellUnderCursor(Minecraft client) {
        return McCompat.screen(client) instanceof InteractiveMapScreen map ? map.cellUnderCursor() : -1;
    }

    /**
     * What clicking a room on the map does, and what the Start key does - ONE action, because killer560 asked for
     * exactly that (2026-09-27): "The path / teleport should be the same as the start. How it works is if I click
     * on a room I am not in then it pathfinds to it. If I click on a room I am in then it'll do secrets in that
     * room."
     * <p>
     * Auto Routes claims the click only for the room he is already standing in, where it runs that room's own
     * route - which is what doing its secrets means. Every other room falls through to pathing there. The Start
     * key is simply this with the CURRENT room, so it always lands on the second half.
     */
    static void activateRoom(DungeonLayout layout, int room, int tile) {
        if (room < 0) {
            ModChat.send(CHAT, ModChat.bad("That room is unknown"));
            return;
        }
        if (com.killer560.hub.autoroutes.AutoRoutesFeature.onMapRoomClicked(layout, room)) {
            return;
        }
        AutoClearUtils.pathToRoom(layout, room, tile, 0);
    }

    static void pathToCurrentRoomStart() {
        queue(-1, (layout, ignoredRoom, ignoredCell) -> {
            int room = layout.currentRoom();
            if (room < 0) {
                ModChat.send(CHAT, ModChat.bad("Current room is unknown"));
                return;
            }
            activateRoom(layout, room, layout.tiles(room)[0]);
        });
    }

    /** The Go + Secret bind with the cursor off the map: the room you are standing in, start node first. */
    static void pathToCurrentRoomStartNode() {
        queue(-1, (layout, ignoredRoom, ignoredCell) -> {
            int room = layout.currentRoom();
            if (room < 0) {
                ModChat.send(CHAT, ModChat.bad("Current room is unknown"));
                return;
            }
            startNodeGoal(layout, room, layout.tiles(room)[0]);
        });
    }

    /** killer560, 2026-09-29: "same with dooring" - the Locked Door Key retargets a path already in flight too,
     *  so it goes through the same queue as a room press rather than being swallowed while the executor is busy.
     *  The door is re-picked against the fresh layout when the goal runs, since "nearest locked door" is measured
     *  from the room you are in and cancelling a path can leave you in a different one. */
    static void pathToLockedDoor() {
        queue(-1, (layout, room, cell) -> {
            int door = AutoClearUtils.getLockedDoor(layout);
            if (door < 0) {
                ModChat.send(CHAT, ModChat.bad("No locked doors found."));
                return;
            }
            AutoClearUtils.pathToDoor(layout, door, LiveMapConfig.getInstance().isFaceDoorOnArrival());
        });
    }

    static boolean isPeeking() {
        return peeking;
    }

    // ------------------------------------------------------------------------------------------- HUD click

    private static boolean tryOpenFromHud(double mouseX, double mouseY) {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        if (!cfg.isOpenFromHudClick() || !cfg.isInteractiveMapEnabled() || !cfg.isEnabled() || !DungeonState.isInDungeon()) {
            return false;
        }
        for (HudElement element : HudElementRegistry.all()) {
            if (!"live_map".equals(element.id())) {
                continue;
            }
            int[] pos = HudElementRegistry.resolvePosition(element);
            float scale = HudElementRegistry.resolveScale(element);
            if (mouseX >= pos[0] && mouseY >= pos[1] && mouseX < pos[0] + element.width() * scale
                    && mouseY < pos[1] + element.height() * scale) {
                Minecraft client = Minecraft.getInstance();
                client.execute(() -> McCompat.setScreen(client, new InteractiveMapScreen(false)));
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------- cleared by

    private static void trackClears(Minecraft client) {
        if (seenGeneration != LiveMapFeature.resetGeneration()) {
            seenGeneration = LiveMapFeature.resetGeneration();
            lastRoomState.clear();
            clearedBy.clear();
        }
        if (++clearTick < 5 || !DungeonState.isInDungeon() || !DungeonMapScanner.isCalibrated()
                || !LiveMapConfig.getInstance().isInteractiveMapEnabled()) {
            return;
        }
        clearTick = 0;
        List<MapPlayer> players = null;
        for (LiveMapFeature.RoomGroup group : LiveMapFeature.groupsView()) {
            int state = DungeonMapScanner.stateAt(group.mainIdx);
            Integer before = lastRoomState.put(group.mainIdx, state);
            if (before == null || before == state) {
                continue;
            }
            boolean wasOpen = before == DungeonMapScanner.STATE_UNDISCOVERED || before == DungeonMapScanner.STATE_DISCOVERED
                    || before == DungeonMapScanner.STATE_UNOPENED;
            boolean nowDone = state == DungeonMapScanner.STATE_CLEARED || state == DungeonMapScanner.STATE_GREEN;
            if (!wasOpen || !nowDone) {
                continue;
            }
            if (players == null) {
                players = playersCached(client);
            }
            Set<Integer> cells = new HashSet<>();
            for (int c : group.cells) {
                cells.add(c);
            }
            List<String> inside = new ArrayList<>();
            for (MapPlayer p : players) {
                int[] cell = LiveMapFeature.gridCellFor(new net.minecraft.world.phys.Vec3(p.worldX(), 70, p.worldZ()));
                if (cells.contains(cell[0] + cell[1] * LiveMapFeature.GRID)) {
                    inside.add(p.name());
                }
            }
            if (!inside.isEmpty()) {
                clearedBy.put(roomKey(group), inside);
            }
        }
    }

    static String roomKey(LiveMapFeature.RoomGroup group) {
        return group.entry != null && group.entry.name != null ? group.entry.name : "cell:" + group.mainIdx;
    }

    static List<String> clearedBy(LiveMapFeature.RoomGroup group) {
        return clearedBy.get(roomKey(group));
    }

    // ------------------------------------------------------------------------------------------- players

    /** One marker on the map: world position, heading, and how to draw it. No skin field any more - killer560,
     *  2026-09-20: "i do not want it showing the white heads for mobs", so every marker is always the arrow. */
    record MapPlayer(String name, double worldX, double worldZ, float yaw, boolean self, DungeonClass dungeonClass) {
    }

    private static List<MapPlayer> cachedPlayers = List.of();
    private static int cachedPlayersTick = Integer.MIN_VALUE;

    /** Per-tick cached {@link #players}, for anything that runs per frame. {@link #players} scans the whole party
     *  ({@code level.players()} plus a {@code getName().getString()} per member) and reads the map item's
     *  decorations; the fps report (2026-09-20) caught the HUD map doing that on every frame. Entity positions only
     *  move on a tick, so a per-tick snapshot is the same picture. */
    static List<MapPlayer> playersCached(Minecraft client) {
        int tick = LiveMapFeature.tickCount();
        if (tick != cachedPlayersTick) {
            cachedPlayersTick = tick;
            cachedPlayers = players(client);
        }
        return cachedPlayers;
    }

    /** Self first, then teammates: loaded player entities are exact; the rest come from the map item's markers in
     *  Hypixel's order (QUOI/NoammAddons assign non-self decorations to living teammates in tab order).
     *  killer560: "on the map it still shows heads for mobs/things besides teammates which it shouldn't." -
     *  {@link LeapMenuFeature#currentPartyMembers()} returns every {@code Player}-typed entity in the level
     *  except yourself, with no check at all; several dungeon mobs (and, on p3sim, other real people sharing
     *  the world) are also {@code Player}-typed entities, so the old "names.isEmpty() -> trust every one of
     *  them" bootstrap drew markers for them too whenever {@link PartyTracker#teammates()} had nothing yet
     *  (always, on p3sim - see {@link #isTeammate} below). Entities are now filtered through the same
     *  teammate test {@code teammates.TeammatesFeature.isTeammate} already uses (that feature is verified
     *  live), before anything is added to {@code entities}/{@code names}. */
    static List<MapPlayer> players(Minecraft client) {
        List<MapPlayer> out = new ArrayList<>();
        if (client.player == null || client.level == null) {
            return out;
        }
        String selfName = client.player.getGameProfile().name();
        out.add(new MapPlayer(selfName, client.player.getX(), client.player.getZ(), client.player.getYRot(), true,
                PartyTracker.selfClass()));

        List<String> party = PartyTracker.teammates();
        Map<String, Player> entities = new HashMap<>();
        for (Player p : LeapMenuFeature.currentPartyMembers()) {
            if (isTeammate(p, party)) {
                entities.put(p.getGameProfile().name().toLowerCase(Locale.US), p);
            }
        }
        Set<String> names = new LinkedHashSet<>(party);
        if (names.isEmpty()) {
            for (Player p : entities.values()) {
                names.add(p.getGameProfile().name());
            }
        }
        List<double[]> markers = new ArrayList<>();
        for (double[] m : DungeonMapScanner.playerMarkers(client)) {
            if (m[3] == 0) {
                markers.add(m);
            }
        }
        int markerIdx = 0;
        for (String name : names) {
            if (PartyTracker.isDead(name)) {
                continue;
            }
            double[] marker = markerIdx < markers.size() ? markers.get(markerIdx) : null;
            markerIdx++;
            Player entity = entities.get(name.toLowerCase(Locale.US));
            DungeonClass cls = PartyTracker.classOf(name);
            if (entity != null) {
                out.add(new MapPlayer(name, entity.getX(), entity.getZ(), entity.getYRot(), false, cls));
            } else if (marker != null) {
                out.add(new MapPlayer(name, marker[0], marker[1], (float) marker[2], false, cls));
            }
        }
        return out;
    }

    /** Same rule as {@code teammates.TeammatesFeature.isTeammate} (that class is not mine to import from - it's
     *  {@code private} there too, so this is a deliberate mirror, not a copy-paste accident): a real (v4-UUID)
     *  player the party tracker can vouch for. Rejects disguised-mob {@code Player} entities outright (a
     *  non-v4 UUID), which is exactly what let mobs onto the map before - {@link PartyTracker#teammates()}
     *  only ever lists real IGNs, so a mob could only get in via the raw {@code level.players()} scan. */
    private static boolean isTeammate(Player player, List<String> party) {
        if (player.getUUID().version() != 4) {
            return false;
        }
        String name = player.getGameProfile().name();
        for (String member : party) {
            if (member.equalsIgnoreCase(name)) {
                return true;
            }
        }
        if (PartyTracker.classOf(name) != null) {
            return true;
        }
        // Nothing known at all (p3sim.net's tab list has no class entries and there is no party) - in a real
        // dungeon the only other real players present are your party, so fall back to "every real player",
        // same reasoning teammates.TeammatesFeature uses for its own p3sim fallback.
        return party.isEmpty();
    }
}
