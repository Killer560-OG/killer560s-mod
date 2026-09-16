package com.killer560.hub.autoroutes;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.InteractiveMapScreen;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.BloodRush;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Routes - per-room recorded routes that play back when you etherwarp onto their start node. CHEAT BUILD
 * ONLY, default off; every entry point is behind {@link AutoRoutesConfig#isEnabled()}.
 * <p>
 * This class owns registration, the tick wiring ({@link RouteRecorder} while recording, {@link RouteExecutor}
 * while a route runs, arming otherwise), world / room change handling, edit mode, and every interlock killer560
 * asked for:
 * <ol>
 * <li><b>Interactive Map open</b> - every node is hidden and inert: nothing renders, nothing arms, a running route
 * stops ("While the Interactive Map menu is open, hide all auto route nodes and make them non-applicable").
 * <li><b>Map click on the room you are already in</b> - {@link #onMapRoomClicked} etherwarps to that room's START
 * node instead of the Interactive Map's own room spot (the main session calls it from
 * {@code InteractiveMapScreen.onClick} before {@code AutoClearUtils.pathToRoom}).
 * <li><b>Map closed</b> - the room's route arms again from its start node ("When I close the menu, the route for
 * the room I'm in starts again").
 * <li><b>Never enter a route mid-way via the map</b> - after a map close / map teleport / Blood Rush, only the
 * START node can arm until the player has been through it (or leaves the room), even with "start from start node
 * only" off ("I shouldn't be able to use Interactive Map to go across the map and randomly hit a node halfway
 * through").
 * <li><b>Auto Blood Rush running</b> (or any Interactive Map teleport in flight) - Auto Routes is inert for the
 * duration.
 * <li>"Start from start node only" - forced on in legit mode ({@link AutoRoutesConfig#isStartFromStartNodeOnly}).
 * </ol>
 * A tick or render exception never escapes: it is logged, the feature switches itself off (saved) and says so in
 * chat, so a broken route can't take the frame down.
 */
public final class AutoRoutesFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autoroutes");
    private static final String CHAT = "Auto Routes";
    /** NoammAddons {@code ActionBarParser} / {@code LiveMapFeature.ACTION_BAR_SECRETS}. */
    private static final Pattern ACTION_BAR_SECRETS = Pattern.compile("(\\d+)/(\\d+) Secrets");
    /** QUOI DB editor: a block further than this (squared) from the breaker node is refused. */
    private static final double EDIT_MAX_DIST_SQ = 30.0;

    private static boolean editMode;
    /** The DUNGEON_BREAKER node {@code /ar edit db} right-clicks add blocks to (chosen when edit mode turns on). */
    private static RouteNode editBreakerNode;
    private static boolean mapWasOpen;
    private static boolean hidden;
    /** Interlock 4: set by any map / Blood Rush teleport, cleared once the START node arms or the room changes. */
    private static boolean mapArrivalGuard;
    /** Set while a map / Blood Rush teleport is in flight, so the room change it causes doesn't clear the guard. */
    private static boolean arrivedByTeleport;
    /** Ticks after a teleport finishes before {@link #arrivedByTeleport} is dropped, to outlast LiveMap's room-identity lag. */
    private static final int TELEPORT_SETTLE_TICKS = 5;
    private static int teleportSettleTicks;
    private static boolean externalTeleport;
    /** The node the player is currently standing in (so a finished route doesn't instantly re-arm underfoot). */
    private static RouteNode latchedNode;
    private static String lastRoom;
    private static Object lastLevel;
    private static boolean renderFailed;

    private AutoRoutesFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoRoutesFeature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(AutoRoutesFeature::onRenderFrame);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) {
                onActionBar(message.getString());
            }
        });
        LOGGER.info("[AutoRoutes] Registered (cheatBuild={})", com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED);
    }

    // ------------------------------------------------------------------------------------------- public API

    public static void setEditMode(boolean on) {
        if (on && !AutoRoutesConfig.getInstance().isEnabled()) {
            chatBad("Auto Routes is off (cheat build + Skyblock only).");
            return;
        }
        if (on == editMode) {
            if (on) {
                pickEditBreakerNode();
            }
            return;
        }
        editMode = on;
        if (on) {
            if (RouteExecutor.isRunning()) {
                RouteExecutor.stop("edit mode");
            }
            pickEditBreakerNode();
            // Only the part AutoRoutesCommands can't say: WHICH breaker node the clicks will land on.
            chat(editBreakerNode == null
                    ? ModChat.bad("No dungeon breaker node in this room")
                    : ModChat.text("Editing breaker "),
                    editBreakerNode == null
                            ? ModChat.dim(" - add one with /ar add breaker")
                            : ModChat.value("#" + breakerIndex()));
        } else {
            editBreakerNode = null;
        }
    }

    public static boolean isEditMode() {
        return editMode;
    }

    /**
     * Edit-mode right click on a block (the UI's {@code UseBlockCallback}): adds it to the breaker node being
     * edited, shift removes it. @return true when consumed - the caller then suppresses the real interaction so
     * "right-clicking doesn't also use your item".
     */
    public static boolean onEditRightClick(BlockPos pos, boolean shift) {
        if (!editMode || pos == null || !AutoRoutesConfig.getInstance().isEnabled()) {
            return false;
        }
        RouteCoords.Frame frame = RouteCoords.Frame.current();
        if (frame == null) {
            return true;
        }
        if (editBreakerNode == null) {
            pickEditBreakerNode();
            if (editBreakerNode == null) {
                chatBad("No dungeon breaker node in this room - /ar add breaker first.");
                return true;
            }
        }
        BlockPos rel = RouteCoords.toRelativeBlock(frame, pos);
        Vec3 nodeReal = RouteCoords.toReal(frame, editBreakerNode.relativePos());
        if (pos.distToCenterSqr(nodeReal.x, nodeReal.y + 1.6, nodeReal.z) > EDIT_MAX_DIST_SQ) {
            chatBad("Block is too far from breaker #" + breakerIndex() + ".");
            return true;
        }
        if (shift) {
            if (editBreakerNode.breakerBlocks.remove(rel)) {
                RouteStore.getInstance().save();
                chat(ModChat.text("Removed "), ModChat.value(pos.toShortString()),
                        ModChat.dim(" from breaker #" + breakerIndex()));
            }
            return true;
        }
        if (editBreakerNode.breakerBlocks.contains(rel)) {
            return true;
        }
        if (editBreakerNode.breakerBlocks.size() >= RouteStore.MAX_BREAKER_BLOCKS) {
            chatBad("Breaker #" + breakerIndex() + " already has " + RouteStore.MAX_BREAKER_BLOCKS + " blocks.");
            return true;
        }
        editBreakerNode.breakerBlocks.add(rel);
        RouteStore.getInstance().save();
        chat(ModChat.text("Added "), ModChat.value(pos.toShortString()),
                ModChat.dim(" to breaker #" + breakerIndex() + " (" + editBreakerNode.breakerBlocks.size() + ")"));
        return true;
    }

    /** The nodes of the route for the room you are in (the live list), or an empty list. */
    public static List<RouteNode> currentRouteNodes() {
        Route route = currentRoute();
        return route == null ? Collections.emptyList() : route.nodes();
    }

    /** Deletes node {@code index} of the current room's route. @return true when something was deleted. */
    public static boolean deleteNode(int index) {
        Route route = currentRoute();
        if (route == null || index < 0 || index >= route.nodes().size()) {
            chatBad("No node #" + (index + 1) + " in this room.");
            return false;
        }
        RouteNode removed = route.nodes().remove(index);
        if (removed == editBreakerNode) {
            editBreakerNode = null;
        }
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("route edited");
        }
        RouteStore.getInstance().save();
        return true;
    }

    /** Removes the whole route (nodes + recording) for the room you are in. @return true when one existed. */
    public static boolean clearCurrentRoute() {
        RouteCoords.Frame frame = RouteCoords.Frame.current();
        if (frame == null) {
            chatBad("Room not identified yet.");
            return false;
        }
        if (RouteRecorder.isRecording()) {
            RouteRecorder.discard();
        // A stop the player made must not outlive the run it happened in: it used to persist into the next
        // dungeon and silently refuse to arm until they stepped off and back onto a node (2026-09-16 review).
        RouteExecutor.clearStoppedByUser();
        }
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("route cleared");
        }
        editBreakerNode = null;
        boolean removed = RouteStore.getInstance().remove(frame.roomName());
        RouteStore.getInstance().save();
        if (!removed) {
            chatBad(frame.roomName() + " has no route.");
        }
        return removed;
    }

    /**
     * Interlock 2 - the Interactive Map was clicked on the room the player is already in: etherwarp to that room's
     * START node (the Interactive Map's own {@code ClearExecutor.etherPath}, owned externally the way Auto Fairy
     * Souls does it) instead of the map's generic room spot. Integration point: call from
     * {@code InteractiveMapScreen.onClick} right before {@code AutoClearUtils.pathToRoom(layout, room, ...)}.
     * @return true when consumed (the caller skips its own pathing).
     */
    public static boolean onMapRoomClicked(DungeonLayout layout, int room) {
        // Asking to be taken to the start node is as deliberate as typing /ar start: a stop the player made
        // earlier must not make the route refuse to arm when they land (2026-09-16 review).
        RouteExecutor.clearStoppedByUser();
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        if (!cfg.isEnabled() || layout == null || room < 0 || room != layout.currentRoom()) {
            return false;
        }
        RouteCoords.Frame frame = RouteCoords.Frame.current();
        Route route = frame == null ? null : RouteStore.getInstance().forRoom(frame.roomName());
        RouteNode start = route == null ? null : route.startNode();
        if (start == null) {
            return false;
        }
        Vec3 real = RouteCoords.toReal(frame, start.relativePos());
        BlockPos below = BlockPos.containing(real.x, real.y - 0.5, real.z);
        BlockPos goal = TeleportUtils.etherwarpable(below) ? below : TeleportUtils.nearestEtherwarpable(below);
        if (goal == null) {
            chatBad("No etherwarpable block under the start node.");
            return false;
        }
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("Interactive Map");
        }
        mapArrivalGuard = true;
        externalTeleport = true;
        ClearExecutor.setExternalOwner(true);
        ClearExecutor.etherPath(goal, () -> {
            ClearExecutor.setExternalOwner(false);
            externalTeleport = false;
            latchedNode = null; // landing ON the start node must arm it
        });
        chat(ModChat.text("Warping to the start node of "), ModChat.value(frame.roomName()));
        return true;
    }

    /** {@link RouteStore#reload()} swapped the routes: drop anything pointing at the old objects. */
    static void onRoutesReloaded() {
        editBreakerNode = null;
        latchedNode = null;
        if (editMode) {
            pickEditBreakerNode();
        }
    }

    /** True while nodes must not render (Interactive Map open). */
    static boolean isRenderHidden() {
        return hidden;
    }

    // ------------------------------------------------------------------------------------------- ticking

    private static void tick(Minecraft client) {
        try {
            tickInner(client);
        } catch (Exception e) {
            LOGGER.error("[AutoRoutes] Tick error - disabling Auto Routes", e);
            disableAfterError("tick error (see log)");
        }
    }

    private static void tickInner(Minecraft client) {
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        LocalPlayer player = client.player;

        if (client.level != lastLevel) {
            lastLevel = client.level;
            resetForWorld("world change");
        }
        if (!cfg.isEnabled()) {
            if (RouteExecutor.isRunning() || RouteRecorder.isRecording() || editMode) {
                resetForWorld("Auto Routes turned off");
                editMode = false;
            }
            hidden = true;
            return;
        }
        if (player == null || client.level == null) {
            hidden = true;
            return;
        }

        // Interlock 1: Interactive Map open -> hidden and inert.
        boolean mapOpen = client.screen instanceof InteractiveMapScreen;
        if (mapOpen) {
            if (!mapWasOpen && RouteExecutor.isRunning()) {
                RouteExecutor.stop("Interactive Map opened");
            }
            mapWasOpen = true;
            hidden = true;
            return;
        }
        if (mapWasOpen) {
            // Interlock 3 + 4: closing the map re-arms from the START node only, and only the START node.
            mapWasOpen = false;
            mapArrivalGuard = true;
            // The map is only closed by its key, so in Repress mode the whole warp can happen while it is
            // still open - and the room change then arrived with arrivedByTeleport false, which cleared the
            // very guard it should have kept. You cannot walk with a screen open, so any room change seen
            // across a map-open span is a teleport (2026-09-16 review).
            arrivedByTeleport = true;
            latchedNode = null;
        }
        hidden = false;

        // Interlock 5: Blood Rush (or any Interactive Map teleport in flight) -> inert.
        if (BloodRush.isRunning() || ClearExecutor.isBusy()) {
            if (RouteExecutor.isRunning()) {
                RouteExecutor.stop(BloodRush.isRunning() ? "Auto Blood Rush" : "Interactive Map teleport");
            }
            mapArrivalGuard = true;
            // The teleport we are waiting on IS a room change, and the room-change branch below used to
            // clear the guard the moment it landed - handing back exactly the mid-route entry the guard
            // exists to prevent (2026-09-16 review). Latch it so that branch knows the arrival was a
            // teleport, not walking in through a door.
            arrivedByTeleport = true;
            teleportSettleTicks = TELEPORT_SETTLE_TICKS;
            return;
        }
        if (teleportSettleTicks > 0 && --teleportSettleTicks == 0) {
            // A same-room warp (the map click that takes you to your own start node) never produces a room
            // change, so the flag had nothing to clear it and start-only stayed enforced for one extra room
            // afterwards. Clear it once the teleport has settled instead (2026-09-16 review).
            arrivedByTeleport = false;
        }
        if (externalTeleport) {
            // Our start-node warp ended without its completion callback (no path found): release the queue.
            externalTeleport = false;
            ClearExecutor.setExternalOwner(false);
        }

        boolean inClear = DungeonState.isInDungeon() && !LiveMapFeature.isInBoss();
        RouteCoords.Frame frame = inClear ? RouteCoords.Frame.current() : null;
        if (frame == null) {
            if (RouteExecutor.isRunning()) {
                RouteExecutor.stop(inClear ? "room unknown" : "not in a dungeon room");
            }
            if (RouteRecorder.isRecording()) {
                RouteRecorder.tick(client); // lets the recorder end itself cleanly on a room change
            }
            lastRoom = null;
            return;
        }
        if (!frame.roomName().equals(lastRoom)) {
            if (lastRoom != null && RouteExecutor.isRunning()) {
                RouteExecutor.stop("left the room");
            }
            lastRoom = frame.roomName();
            latchedNode = null;
            // New room, clean slate - see resetForWorld.
            RouteExecutor.clearStoppedByUser();
            if (!externalTeleport && !arrivedByTeleport) {
                mapArrivalGuard = false; // walked in through a door: mid-route entry is the toggle's call again
            }
            arrivedByTeleport = false;
            if (editMode) {
                pickEditBreakerNode();
            }
        }

        if (RouteRecorder.isRecording()) {
            RouteRecorder.tick(client);
            return;
        }
        if (RouteExecutor.isRunning()) {
            RouteExecutor.tick(client);
            return;
        }
        if (editMode) {
            return;
        }
        arm(client, player, frame, cfg);
    }

    /** Starts the room's route when the player stands in its START node (or, when allowed, any node). */
    private static void arm(Minecraft client, LocalPlayer player, RouteCoords.Frame frame, AutoRoutesConfig cfg) {
        Route route = RouteStore.getInstance().forRoom(frame.roomName());
        if (route == null || route.nodes().isEmpty() || client.screen != null) {
            latchedNode = null;
            return;
        }
        AABB playerBox = player.getBoundingBox();
        double height = cfg.getHeight();
        boolean startOnly = cfg.isStartFromStartNodeOnly() || mapArrivalGuard;
        RouteNode inside = null;
        for (RouteNode node : route.nodesInPathOrder()) {
            if (startOnly && node.type != RouteNode.Type.START) {
                continue;
            }
            if (node.contains(RouteCoords.toReal(frame, node.relativePos()), height, playerBox)) {
                inside = node;
                break;
            }
        }
        if (inside == null) {
            latchedNode = null;
            // Clear of every node: a route the player stopped by hand - or that just finished on top of one -
            // may arm again from here.
            RouteExecutor.clearStoppedByUser();
            RouteExecutor.clearJustFinished();
            return;
        }
        if (inside == latchedNode) {
            return; // still standing where the last run started / ended
        }
        if (RouteExecutor.justFinished()) {
            // Latch where the route ended without starting anything: otherwise the last node's action runs
            // a second time the moment it finishes (2026-09-16 review).
            latchedNode = inside;
            return;
        }
        if (RouteExecutor.wasStoppedByUser()) {
            // The player took the controls back while standing inside a node. Re-arming here would mean
            // every W tap stops the route and every release restarts it (2026-09-16 review) - they have to
            // walk clear of the route first.
            latchedNode = inside;
            return;
        }
        latchedNode = inside;
        if (inside.type == RouteNode.Type.START) {
            mapArrivalGuard = false;
        }
        RouteExecutor.start(route, frame, inside);
    }

    private static void onRenderFrame(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext ctx) {
        if (renderFailed) {
            return;
        }
        try {
            AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
            if (!cfg.isEnabled()) {
                return;
            }
            RouteExecutor.tickFrame();
            if (hidden) {
                return;
            }
            RouteCoords.Frame frame = RouteRecorder.isRecording() ? RouteRecorder.recordingFrame() : RouteCoords.Frame.current();
            if (frame == null) {
                return;
            }
            Route route = RouteRecorder.isRecording() ? RouteRecorder.recordingRoute()
                    : RouteStore.getInstance().forRoom(frame.roomName());
            if (route == null) {
                return;
            }
            AutoRoutesRenderer.render(ctx, route, frame, editMode, RouteExecutor.activeNode());
        } catch (Exception e) {
            renderFailed = true;
            LOGGER.error("[AutoRoutes] Render error - disabling Auto Routes", e);
            disableAfterError("render error (see log)");
        }
    }

    private static void onActionBar(String text) {
        try {
            if (text == null || !text.contains("Secrets")) {
                return;
            }
            Matcher m = ACTION_BAR_SECRETS.matcher(text.replaceAll("§.", ""));
            if (m.find()) {
                RouteExecutor.onSecretsCount(Integer.parseInt(m.group(1)));
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static void resetForWorld(String reason) {
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop(reason);
        }
        RouteRecorder.discard();
        // Edit mode swallows every block right-click, so leaving it on across a world change meant walking
        // into the next dungeon unable to open a chest or flip a lever (2026-09-16 review).
        editMode = false;
        AutoRoutesEditInput.reset();
        editBreakerNode = null;
        arrivedByTeleport = false;
        teleportSettleTicks = 0;
        latchedNode = null;
        lastRoom = null;
        mapWasOpen = false;
        mapArrivalGuard = false;
        externalTeleport = false;
        renderFailed = false;
    }

    /** Never let a tick/render exception take the frame down: switch the feature off (persisted) and say so. */
    static void disableAfterError(String reason) {
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        resetForWorld(reason);
        editMode = false;
        cfg.setEnabled(false);
        cfg.save();
        chat(ModChat.bad("Disabled"), ModChat.dim(" - " + reason));
    }

    private static Route currentRoute() {
        // While recording, the live route is the recorder's - reading the saved one made /ar list show the
        // previous route's nodes and /ar clear delete the saved route while the recording carried on
        // (2026-09-16 review).
        Route recording = RouteRecorder.recordingRoute();
        if (recording != null) {
            return recording;
        }
        RouteCoords.Frame frame = RouteCoords.Frame.current();
        return frame == null ? null : RouteStore.getInstance().forRoom(frame.roomName());
    }

    /** The breaker node edit mode targets: the one the player stands in, else the nearest in the room. */
    private static void pickEditBreakerNode() {
        editBreakerNode = null;
        Minecraft client = Minecraft.getInstance();
        RouteCoords.Frame frame = RouteCoords.Frame.current();
        Route route = frame == null ? null : RouteStore.getInstance().forRoom(frame.roomName());
        if (route == null || client.player == null) {
            return;
        }
        Vec3 pos = client.player.position();
        double best = Double.MAX_VALUE;
        for (RouteNode node : route.nodes()) {
            if (node.type != RouteNode.Type.DUNGEON_BREAKER) {
                continue;
            }
            double d = RouteCoords.toReal(frame, node.relativePos()).distanceTo(pos);
            if (d < best) {
                best = d;
                editBreakerNode = node;
            }
        }
    }

    /** 1-BASED, to match /ar list, /ar delete and the settings tab. Every number the player ever sees for a
     *  node has to agree, or reading "#3" off a world label and typing "/ar delete 3" deletes a different
     *  node (2026-09-16 review). */
    private static int breakerIndex() {
        Route route = currentRoute();
        return route == null || editBreakerNode == null ? -1 : route.indexOf(editBreakerNode) + 1;
    }

    static void chat(Component... parts) {
        ModChat.send(CHAT, parts);
    }

    static void chatInfo(String text) {
        ModChat.send(CHAT, ModChat.text(text));
    }

    static void chatBad(String text) {
        ModChat.send(CHAT, ModChat.bad(text));
    }
}
