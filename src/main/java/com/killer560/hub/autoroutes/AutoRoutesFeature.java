package com.killer560.hub.autoroutes;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.InteractiveMapFeature;
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
import com.killer560.hub.util.ModLog;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.killer560.hub.compat.McCompat;

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

    private static final Logger LOGGER = ModLog.get("killer560smod-autoroutes");
    private static final String CHAT = "Auto Routes";
    /** NoammAddons {@code ActionBarParser} / {@code LiveMapFeature.ACTION_BAR_SECRETS}.
     *
     *  <p>Bounded, like {@code SelfDerivation.ACTION_BAR_SECRETS} already is. Only the action bar reaches this
     *  (the listener below drops everything with {@code overlay == false}), so nothing a player types gets here
     *  and the onActionBar try/catch is the real safety net - but the house rule after the "SS 99999999999/5"
     *  disconnect is that no quantifier feeding parseInt is left unbounded, whatever the source. */
    private static final Pattern ACTION_BAR_SECRETS = Pattern.compile("(\\d{1,3})/(\\d{1,3}) Secrets");
    /** QUOI DB editor: a block further than this (squared) from the breaker node is refused. */
    /** Measured block reach, squared - was 30.0 (5.48 blocks) to the centre. */
    private static final double EDIT_MAX_DIST_SQ = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH * com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH;

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
    /** The last arming state logged by {@link #gate}, so each gate decision is logged once per change, not per tick. */
    private static String lastGate;

    private AutoRoutesFeature() {
    }

    public static void register() {
        // START, not END (docs/AP3.md: interaction features tick on START_CLIENT_TICK). A node's held-item / use /
        // break packets then go out ahead of this tick's input and movement packets, as vanilla's handleKeybinds
        // sends a click, and the sneak or keys the executor asks for are read by the input mixin in this same tick.
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoRoutesFeature.tick", AutoRoutesFeature::tick));
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
        // To the BOX, like RouteExecutor's own breaker gate. These two constants are meant to agree, so the
        // measures have to as well: measuring the editor to the centre refused picks the executor would then
        // have been perfectly happy to break.
        if (com.killer560.hub.util.BlockHits.boxDistanceSq(
                new Vec3(nodeReal.x, nodeReal.y + 1.6, nodeReal.z), pos) > EDIT_MAX_DIST_SQ) {
            chatBad("Block is too far from breaker #" + breakerIndex() + ".");
            return true;
        }
        Route editRoute = RouteStore.getInstance().forRoom(frame.roomName());
        if (shift) {
            if (editBreakerNode.breakerBlocks.contains(rel)) {
                RouteHistory.edited(editRoute, editBreakerNode);
            }
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
        RouteHistory.edited(editRoute, editBreakerNode);
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

    /** Deletes node {@code index} (0-BASED; shown as {@code index + 1}) of the current room's route, remembered
     *  for {@code /ar undo}. Every later node's number drops by one - numbers are positions in the room's list, the
     *  same as AP3's. @return true when something was deleted. */
    public static boolean deleteNode(int index) {
        return deleteNode(index, true);
    }

    private static boolean deleteNode(int index, boolean remember) {
        Route route = currentRoute();
        if (route == null || index < 0 || index >= route.nodes().size()) {
            chatBad("No node #" + (index + 1) + " in this room.");
            return false;
        }
        RouteNode removed = route.nodes().remove(index);
        if (remember) {
            RouteHistory.removed(route, removed, index);
        }
        if (removed == editBreakerNode) {
            editBreakerNode = null;
        }
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("route edited");
        }
        RouteStore.getInstance().save();
        suppressAutoArm();
        return true;
    }

    /** {@code /ar delete} with no number: nearest node within this many blocks... */
    private static final double NEAREST_MAX = 3.0;
    /** ...and every other node at least this much further away, or it is ambiguous. AP3's two constants. */
    private static final double NEAREST_MARGIN = 1.0;

    /**
     * {@code /ar delete} / {@code /ar remove} with no number - AP3's {@code deleteNearestNode}: the node you stand
     * clearly nearest, within {@value #NEAREST_MAX} blocks and every other node at least {@value #NEAREST_MARGIN}
     * further, else it says why (with AP3's wording) and deletes nothing.
     * @return the 0-based index to delete, or -1 after saying why not
     */
    public static int nearestNodeIndex() {
        Route route = currentRoute();
        RouteCoords.Frame frame = RouteRecorder.isRecording() ? RouteRecorder.recordingFrame() : RouteCoords.Frame.current();
        Minecraft client = Minecraft.getInstance();
        if (route == null || route.nodes().isEmpty() || frame == null || client.player == null) {
            chatBad("No nodes in " + AutoRoutesCommands.roomName() + ".");
            return -1;
        }
        Vec3 pos = client.player.position();
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        double second = Double.MAX_VALUE;
        List<RouteNode> nodes = route.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            double d = RouteCoords.toReal(frame, nodes.get(i).relativePos()).distanceTo(pos);
            if (d < bestDist) {
                second = bestDist;
                bestDist = d;
                best = i;
            } else if (d < second) {
                second = d;
            }
        }
        if (best < 0 || bestDist > NEAREST_MAX) {
            chatBad(String.format(java.util.Locale.US, "No node within %.0f blocks - stand next to one or give its number.", NEAREST_MAX));
            return -1;
        }
        if (second < bestDist + NEAREST_MARGIN) {
            chatBad(String.format(java.util.Locale.US, "Two nodes are about as close (#%d and one %.1f blocks off) - give the number.",
                    best + 1, second));
            return -1;
        }
        return best;
    }

    /**
     * {@code /ar undo} - AP3's {@code undoLastAdded}, widened to a history: reverts the most recent add, remove,
     * breaker edit or clear, whatever room it was in ({@link RouteHistory}). With nothing left to undo it does
     * what AP3's does then - deletes the last node of the room you stand in (that delete is not itself undoable,
     * or undo would just put it back on the next press).
     */
    public static boolean undo() {
        RouteHistory.Undone done = RouteHistory.undo();
        if (done == null) {
            List<RouteNode> nodes = currentRouteNodes();
            if (nodes.isEmpty()) {
                chatBad("Nothing to undo in " + AutoRoutesCommands.roomName() + ".");
                return false;
            }
            int last = nodes.size() - 1;
            Route route = currentRoute();
            RouteNode node = nodes.get(last);
            if (!deleteNode(last, false)) {
                return false;
            }
            // Not an undo step of its own (or the next undo would just put it back), but /ar redo restores it.
            RouteHistory.fallbackDeleted(route, node, last);
            chat(ModChat.text("Deleted "), ModChat.value("#" + (last + 1) + " " + node.type.label()),
                    ModChat.dim(" from " + AutoRoutesCommands.roomName()));
            return true;
        }
        afterHistoryStep(done);
        return true;
    }

    /**
     * {@code /ar redo} (killer560, 2026-10-04: "the opposite of undo and will restore things I just deleted or
     * undid"): re-applies the most recent undone change. Anything new done since that undo has emptied the redo
     * history, so this never replays a change onto a route it was not made against.
     */
    public static boolean redo() {
        RouteHistory.Undone done = RouteHistory.redo();
        if (done == null) {
            chatBad("Nothing to redo.");
            return false;
        }
        afterHistoryStep(done);
        return true;
    }

    /** Shared tail of undo and redo: stop a running route, re-point breaker edit mode, save, and say what happened. */
    private static void afterHistoryStep(RouteHistory.Undone done) {
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("route edited");
        }
        Route here = currentRoute();
        if (editBreakerNode != null && (here == null || here.indexOf(editBreakerNode) < 0
                || editBreakerNode.type != RouteNode.Type.DUNGEON_BREAKER)) {
            editBreakerNode = null;
        }
        if (editMode && editBreakerNode == null) {
            pickEditBreakerNode();
        }
        RouteStore.getInstance().save();
        suppressAutoArm();
        if (done.node() == null) {
            int n = done.route().nodes().size();
            chat(ModChat.text(done.verb() + " "), ModChat.value(done.route().roomName()),
                    ModChat.dim(" (" + n + " node" + (n == 1 ? "" : "s") + ")"));
        } else {
            chat(ModChat.text(done.verb() + " "), ModChat.value("#" + done.number() + " " + done.node().type.label()),
                    ModChat.dim(" in " + done.route().roomName()));
        }
    }

    // ------------------------------------------------------------------------------- /ar edit <n> (node editor)

    /** The live route the node editor works on (the recording, else the saved route of the room you stand in). */
    static Route editableRoute() {
        return currentRoute();
    }

    /** The frame that route's room-relative coordinates resolve through, or null when the room is unknown. */
    static RouteCoords.Frame editableFrame() {
        return RouteRecorder.isRecording() ? RouteRecorder.recordingFrame() : RouteCoords.Frame.current();
    }

    /**
     * The node editor's Save ({@link AutoRoutesEditScreen}): copies every field of {@code edited} onto the live
     * {@code node} in place, through {@link RouteHistory#changed} so {@code /ar undo} reverts the whole edit and
     * {@code /ar redo} re-applies it. Setting the start flag moves it off whichever node had it (the rule
     * {@code /ar add ... start} follows). An edit that changed nothing records nothing.
     * @return false (after saying why) when the node is no longer in the route
     */
    static boolean applyNodeEdit(Route route, RouteNode node, RouteNode edited) {
        if (route == null || route.indexOf(node) < 0 || (route != currentRoute())) {
            chatBad("That node is gone - the route changed or you left the room. Nothing was saved.");
            return false;
        }
        if (node.sameData(edited)) {
            return true;
        }
        RouteNode before = node.copy();
        RouteNode previousStart = null;
        if (edited.start) {
            for (RouteNode n : route.nodes()) {
                if (n != node && n.start) {
                    n.start = false;
                    previousStart = n;
                }
            }
        }
        node.copyFrom(edited);
        RouteHistory.changed(route, node, before, previousStart);
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("route edited");
        }
        if (editBreakerNode == node && node.type != RouteNode.Type.DUNGEON_BREAKER) {
            editBreakerNode = null;
            if (editMode) {
                pickEditBreakerNode();
            }
        }
        RouteStore.getInstance().save();
        suppressAutoArm();
        chat(ModChat.text("Saved "), ModChat.value("#" + (route.indexOf(node) + 1) + " " + node.describe()),
                previousStart == null ? ModChat.dim(" in " + route.roomName())
                        : ModChat.dim(" - start moved off #" + (route.indexOf(previousStart) + 1)));
        return true;
    }

    /**
     * Breaker edit mode aimed at {@code node} (the editor's "Pick Blocks", and the end of a Go-to): the same edit
     * mode {@code /ar edit db} toggles - nothing arms while it is on - with the right-clicks landing on this node
     * when it is a breaker, else on the nearest breaker as {@code /ar edit db} picks. {@code node} is latched, so
     * turning edit mode off while still standing in it does not fire it under him.
     */
    static void enterEditModeAt(RouteNode node) {
        if (!AutoRoutesConfig.getInstance().isEnabled()) {
            chatBad("Auto Routes is off (cheat build + Skyblock only).");
            return;
        }
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("edit mode");
        }
        editMode = true;
        if (node != null && node.type == RouteNode.Type.DUNGEON_BREAKER) {
            editBreakerNode = node;
        } else {
            pickEditBreakerNode();
        }
        latchedNode = node;
        Route route = currentRoute();
        int number = route == null || node == null ? -1 : route.indexOf(node) + 1;
        chat(ModChat.text("Edit mode "), ModChat.good("ON"),
                ModChat.text(number > 0 ? " at #" + number + " " + node.type.label() : ""),
                editBreakerNode == null ? ModChat.dim(" - no breaker node in this room to right-click blocks into.")
                        : ModChat.dim(" - right-click blocks for breaker #" + breakerIndex()
                        + ", shift-right-click removes. /ar edit db to finish."));
    }

    /** The node a Go-to is travelling to, until it lands or the path fails. */
    private static RouteNode gotoNode;
    private static Route gotoRoute;

    /**
     * The editor's "Go To" (killer560, 2026-10-04: "If I press that it should etherwarp pathfind to the node, same
     * logic as the interactive map, and once it gets there it should put itself into edit mode"). The warp is the
     * Interactive Map's own {@link ClearExecutor#etherPath} - the call {@link #warpToStartNode} and the map's room
     * clicks make - so it plans with whatever planner the map has. Goal: the block under the node, or the nearest
     * etherwarpable block to it. On arrival {@link #onGotoArrived} turns edit mode on and latches the node; a search
     * that finds nothing is reported by the tick once {@code ClearExecutor} goes idle without calling back.
     * @return true when the warp was started (or he was already there)
     */
    static boolean goToNode(Route route, RouteNode node) {
        RouteCoords.Frame frame = editableFrame();
        Minecraft client = Minecraft.getInstance();
        if (route == null || node == null || route.indexOf(node) < 0 || frame == null || client.player == null) {
            chatBad("That node is gone - the route changed or you left the room.");
            return false;
        }
        int number = route.indexOf(node) + 1;
        if (RouteRecorder.isRecording()) {
            chatBad("Stop recording first (/ar stop record) - a warp would be recorded into the route.");
            return false;
        }
        if (ClearExecutor.isBusy() || BloodRush.isRunning()) {
            chatBad("An Interactive Map warp is already running - let it finish, then press Go To again.");
            return false;
        }
        Vec3 real = RouteCoords.toReal(frame, node.relativePos());
        BlockPos below = BlockPos.containing(real.x, real.y - 0.5, real.z);
        BlockPos goal = TeleportUtils.etherwarpable(below) ? below : TeleportUtils.nearestEtherwarpable(below);
        if (goal == null) {
            chatBad("No etherwarpable block at or near node #" + number + " - no path to it.");
            return false;
        }
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("Go To node");
        }
        RouteExecutor.clearStoppedByUser();
        RouteExecutor.clearJustFinished();
        // Edit mode swallows block right-clicks; off for the trip, and the arrival turns it back on.
        if (editMode) {
            editMode = false;
            editBreakerNode = null;
            AutoRoutesEditInput.reset();
        }
        gotoNode = node;
        gotoRoute = route;
        mapArrivalGuard = true;
        externalTeleport = true;
        ClearExecutor.setExternalOwner(true);
        chat(ModChat.text("Warping to "), ModChat.value("#" + number + " " + node.type.label()),
                ModChat.dim(" at " + goal.toShortString()));
        ClearExecutor.etherPath(goal, () -> {
            ClearExecutor.setExternalOwner(false);
            externalTeleport = false;
            onGotoArrived();
        });
        return true;
    }

    private static void onGotoArrived() {
        RouteNode node = gotoNode;
        Route route = gotoRoute;
        gotoNode = null;
        gotoRoute = null;
        if (node == null) {
            return;
        }
        if (route == null || route.indexOf(node) < 0 || route != currentRoute()) {
            chatBad("Arrived, but that node is no longer in this room's route - edit mode left off.");
            return;
        }
        Minecraft client = Minecraft.getInstance();
        RouteCoords.Frame frame = editableFrame();
        if (client.player != null && frame != null) {
            double off = RouteCoords.toReal(frame, node.relativePos()).distanceTo(client.player.position());
            if (off > 1.5) {
                chat(ModChat.dim(String.format(java.util.Locale.US,
                        "Landed %.1f blocks from #%d (nearest etherwarpable block to it).", off, route.indexOf(node) + 1)));
            }
        }
        enterEditModeAt(node);
    }

    /** A Go-to whose warp ended without the arrival callback: the planner found no path (or it was cancelled). */
    private static void reportGotoFailed() {
        RouteNode node = gotoNode;
        Route route = gotoRoute;
        gotoNode = null;
        gotoRoute = null;
        if (node == null) {
            return;
        }
        int number = route == null ? -1 : route.indexOf(node) + 1;
        chatBad((ClearExecutor.lastPathFailed() ? "No etherwarp path to node " : "Go To cancelled before reaching node ")
                + (number > 0 ? "#" + number : "") + " - edit mode not turned on.");
    }

    /** After an edit he may be standing in a node that is new, renumbered or back again: like AP3's
     *  {@code suppressAutoArm}, the node he is in when arming next looks counts as already stood in, so it fires
     *  when he walks back onto it, not under him the moment chat closes. */
    private static boolean suppressArm;

    private static void suppressAutoArm() {
        suppressArm = true;
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
        Route saved = RouteStore.getInstance().forRoom(frame.roomName());
        boolean removed = RouteStore.getInstance().remove(frame.roomName());
        if (removed) {
            RouteHistory.cleared(saved); // /ar undo puts the whole route back
        }
        suppressAutoArm();
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
        if (layout == null || room < 0 || room != layout.currentRoom()) {
            return false;
        }
        return warpToStartNode(layout, room);
    }

    /**
     * killer560, 2026-09-29: "If I double click a room then it should auto pathfind to the start node to start
     * secreting." - the same START-node warp {@link #onMapRoomClicked} does, for ANY room on the map rather than
     * only the one under your feet.
     * <p>
     * A {@link RouteCoords.Frame} is nothing but a room name plus its clay corner and rotation, and
     * {@link DungeonLayout} already carries both for every room it has identified, so a room across the floor
     * resolves exactly like the current one - {@code Frame.current()} is just the live-map shortcut for the room
     * you are standing in. The room's name is checked against the {@code "Unknown"} placeholder
     * {@link DungeonLayout#name} hands back for a room it has not identified yet: every unidentified room shares
     * that one string, so looking a route up by it would hand back whatever route happens to be saved under it.
     *
     * @return true when the warp was started; false when this room has no recorded route (the caller falls back).
     */
    public static boolean warpToStartNode(DungeonLayout layout, int room) {
        // Asking to be taken to the start node is as deliberate as typing /ar start: a stop the player made
        // earlier must not make the route refuse to arm when they land (2026-09-16 review).
        RouteExecutor.clearStoppedByUser();
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        if (!cfg.isEnabled() || layout == null || room < 0) {
            return false;
        }
        String name = layout.name(room);
        int[] clayRotation = layout.clayRotation(room);
        if (name == null || name.isBlank() || "Unknown".equals(name) || clayRotation == null) {
            return false;
        }
        RouteCoords.Frame frame = new RouteCoords.Frame(name, clayRotation[0], clayRotation[1], clayRotation[2]);
        Route route = RouteStore.getInstance().forRoom(frame.roomName());
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

    /**
     * killer560, 2026-09-29: "For the map make it such that if I am in the middle of a secret route and use it
     * then it'll use the interactive map portion instead of the secret route portion." The Interactive Map wins
     * outright - it is about to move him, and two things steering at once is the one outcome neither of them
     * can recover from.
     *
     * <p>Interlock 1 already stops a route when the map SCREEN opens, and interlock 5 keeps it stopped while a
     * map teleport is in flight. This is the press itself saying so, which matters for two cases neither covers:
     * a press landing in the same tick the screen opened (the two features tick in different phases of the
     * client tick, so which one sees the other first is not something to rely on), and the ticks a map goal spends QUEUED, where
     * {@code ClearExecutor.isBusy()} is deliberately false - see {@link InteractiveMapFeature#isSteering()},
     * which interlock 5 now also checks.
     *
     * <p>{@link RouteExecutor#stop} is a real cancel and needs nothing added: it drops the step machine, calls
     * {@code releaseKeys()} (which zeroes the want-flags the input mixin reads, so it works whether the mixin
     * applied or not) and {@code RouteRotation.clear()} (which releases the camera). Everything a node builds
     * up - the breaker queue, the boom block snapshot, the swap and await state - is rebuilt from scratch by
     * {@code beginAction}, so a cancel mid-node cannot leak into the next route. What it does NOT clear is
     * {@code stoppedByUser}, and that one would make the next route refuse to arm, so it is cleared here for the
     * same reason {@code onMapRoomClicked} already cleared it: being taken somewhere by the map is as deliberate
     * as typing {@code /ar start}.
     */
    public static void cancelForInteractiveMap(String why) {
        if (!AutoRoutesConfig.getInstance().isEnabled()) {
            return;
        }
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop(why);
        }
        RouteExecutor.clearStoppedByUser();
        RouteExecutor.clearJustFinished();
        // The map is about to move him, so hold the feature in the same state a map teleport puts it in: only
        // the START node may arm until he has been through it. latchedNode is deliberately left alone - nulling
        // it is what lets a node arm UNDER him, which is the arrival callback's job, not the press's.
        mapArrivalGuard = true;
        arrivedByTeleport = true;
        teleportSettleTicks = TELEPORT_SETTLE_TICKS;
    }

    /** A node was just placed at his feet ({@code /ar add}): treat him as already standing in it, so it fires when
     *  he walks back onto it rather than the instant chat closes. Leaving it clears the latch as usual. */
    static void latchUnderfoot(RouteNode node) {
        latchedNode = node;
    }

    /** {@link RouteStore#reload()} swapped the routes: drop anything pointing at the old objects. */
    static void onRoutesReloaded() {
        editBreakerNode = null;
        latchedNode = null;
        RouteHistory.reset();
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
            gate("Auto Routes is off");
            if (RouteExecutor.isRunning() || RouteRecorder.isRecording() || editMode) {
                resetForWorld("Auto Routes turned off");
                editMode = false;
            }
            hidden = true;
            return;
        }
        if (player == null || client.level == null) {
            hidden = true;
            gate("no player / level");
            return;
        }

        // Interlock 1: Interactive Map open -> hidden and inert.
        boolean mapOpen = McCompat.screen(client) instanceof InteractiveMapScreen;
        if (mapOpen) {
            gate("Interactive Map open");
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
        // isSteering() covers the ticks a map goal spends QUEUED behind a cancelled path, where isBusy() is
        // false by design and a node underfoot would otherwise arm in the gap and steer against the warp that
        // is about to start (killer560, 2026-09-29: "it'll use the interactive map portion instead of the
        // secret route portion").
        if (BloodRush.isRunning() || ClearExecutor.isBusy() || InteractiveMapFeature.isSteering()) {
            gate("inert: " + (BloodRush.isRunning() ? "Auto Blood Rush" : ClearExecutor.isBusy()
                    ? "Interactive Map teleport in flight" : "Interactive Map steering"));
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
            // Our start-node / Go-to warp ended without its completion callback (no path found): release the queue.
            externalTeleport = false;
            ClearExecutor.setExternalOwner(false);
            reportGotoFailed();
        }

        boolean inClear = DungeonState.isInDungeon() && !LiveMapFeature.isInBoss();
        RouteCoords.Frame frame = inClear ? RouteCoords.Frame.current() : null;
        if (frame == null) {
            gate(inClear ? "room not identified (no Frame from the live map)"
                    : "not in a dungeon clear (isInDungeon=" + DungeonState.isInDungeon() + ", boss="
                    + LiveMapFeature.isInBoss() + ")");
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
            LOGGER.info("[AutoRoutes] Room {} - clay {},{} rotation {}, sim y offset {}", frame.roomName(),
                    frame.clayX(), frame.clayZ(), frame.rotation(), DungeonLayout.simYOffset());
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
            gate("recording");
            RouteRecorder.tick(client);
            return;
        }
        if (RouteExecutor.isRunning()) {
            gate("running");
            RouteExecutor.tick(client);
            return;
        }
        if (editMode) {
            gate("edit mode");
            return;
        }
        arm(client, player, frame, cfg);
    }

    /** Starts the room's route when the player stands in its START node (or, when allowed, any node). */
    private static void arm(Minecraft client, LocalPlayer player, RouteCoords.Frame frame, AutoRoutesConfig cfg) {
        Route route = RouteStore.getInstance().forRoom(frame.roomName());
        if (route == null || route.nodes().isEmpty()) {
            gate("no route for " + frame.roomName());
            latchedNode = null;
            suppressArm = false;
            return;
        }
        if (McCompat.screen(client) != null) {
            gate("a screen is open");
            // Nothing arms under a screen, but the latch is kept: clearing it here undid latchUnderfoot every time
            // the chat that typed "/ar add" was still open, so a node fired under him the moment chat closed.
            return;
        }
        AABB playerBox = player.getBoundingBox();
        double height = cfg.getHeight();
        boolean startOnly = cfg.isStartFromStartNodeOnly() || mapArrivalGuard;
        // The first node (path order) whose ring he is in. Every node on that node's tile then fires with it, as one
        // stack in the approved type order - RouteExecutor.beginStack / Route#stackOf.
        RouteNode inside = null;
        for (RouteNode node : route.nodesInPathOrder()) {
            if (startOnly && !node.start) {
                continue;
            }
            if (node.contains(RouteCoords.toReal(frame, node.relativePos()), height, playerBox)) {
                inside = node;
                break;
            }
        }
        if (suppressArm) {
            suppressArm = false;
            if (inside != null) {
                latchedNode = inside;
                gate("in node #" + (route.indexOf(inside) + 1) + " right after an edit - latched until you step off");
                return;
            }
        }
        if (inside == null) {
            gate(idleGate(route, frame, startOnly));
            latchedNode = null;
            // Clear of every node: a route the player stopped by hand - or that just finished on top of one -
            // may arm again from here.
            RouteExecutor.clearStoppedByUser();
            RouteExecutor.clearJustFinished();
            return;
        }
        // Latched per TILE: every node on it fires as one stack (Route#stackOf), and the node found first above
        // need not be the one latched - "/ar add" latches the node it just placed, which on an occupied tile is
        // the second one there, and comparing nodes fired the whole tile under him the moment chat closed.
        if (inside == latchedNode || inside.sameTile(latchedNode)) {
            gate("in node #" + (route.indexOf(inside) + 1) + " but latched (just placed it, or the last run "
                    + "started / ended here) - step off and back on");
            return; // still standing where the last run started / ended
        }
        if (RouteExecutor.justFinished()) {
            gate("in node #" + (route.indexOf(inside) + 1) + " where the last run finished - latched");
            // Latch where the route ended without starting anything: otherwise the last node's action runs
            // a second time the moment it finishes (2026-09-16 review).
            latchedNode = inside;
            return;
        }
        if (RouteExecutor.wasStoppedByUser()) {
            gate("in node #" + (route.indexOf(inside) + 1) + " after you stopped the route - walk clear first");
            // The player took the controls back while standing inside a node. Re-arming here would mean
            // every W tap stops the route and every release restarts it (2026-09-16 review) - they have to
            // walk clear of the route first.
            latchedNode = inside;
            return;
        }
        latchedNode = inside;
        if (inside.start) {
            mapArrivalGuard = false;
        }
        gate("armed node #" + (route.indexOf(inside) + 1) + " (" + inside.type + (inside.start ? ", start" : "") + ")");
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

    /** Logs an arming decision once per change - every gate between "standing on a node" and "the route started"
     *  says which one it is, so a node that never fires names its own reason in the log. */
    private static void gate(String state) {
        if (!state.equals(lastGate)) {
            lastGate = state;
            LOGGER.info("[AutoRoutes] Arming: {}", state);
        }
    }

    /** Standing in no node: name the start node and where it really is, so a coordinate mismatch shows. Built only
     *  from the route, never the player's position, so it does not change every tick. */
    private static String idleGate(Route route, RouteCoords.Frame frame, boolean startOnly) {
        RouteNode start = route.startNode();
        String where = start == null ? "no start node"
                : String.format(java.util.Locale.US, "start node #%d at %s", route.indexOf(start) + 1,
                        RouteCoords.toReal(frame, start.relativePos()));
        return "in no node of " + frame.roomName() + " (" + route.nodes().size() + " node(s), "
                + (startOnly ? "start only" : "any node") + ", " + where + ")";
    }

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
        gotoNode = null;
        gotoRoute = null;
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
