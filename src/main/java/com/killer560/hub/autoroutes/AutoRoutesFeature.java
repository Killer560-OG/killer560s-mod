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
    /** DB editor: a block further than this (squared) from his eye, to the box, is refused - the measured block reach. */
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
    /** Ticks a start node that does not open with a warp waits after the map's warp onto it (the interact delay). */
    private static int arrivalHoldTicks;
    private static boolean externalTeleport;
    /** The node the player is currently standing in (so a finished route doesn't instantly re-arm underfoot). */
    private static RouteNode latchedNode;
    /** The node whose ring he was last seen in, and the client tick he entered it - for the "armed N tick(s) after you
     *  entered its ring" log line (killer560, 2026-10-14: breaker nodes "take a really long time to actually break";
     *  the log only had whole seconds and every node acted on its firing tick, so where the wait was could not be told). */
    private static RouteNode ringNode;
    private static int ringEnteredAt;
    private static String lastRoom;
    /** The room frame (rotation and clay origin) last written to the log. */
    private static RouteCoords.Frame lastLoggedFrame;
    private static Object lastLevel;
    /** {@link LiveMapFeature#resetGeneration} at the last tick: a change is a new run in the same world. */
    private static int lastMapGeneration = -1;
    private static boolean renderFailed;
    /** The last arming state logged by {@link #gate}, so each gate decision is logged once per change, not per tick. */
    private static String lastGate;

    private AutoRoutesFeature() {
    }

    public static void register() {
        // A boom's settle waits out the measured round trip (RouteExecutor.boomSettleTicks).
        com.killer560.hub.util.PingTracker.register();
        com.killer560.hub.util.PingTracker.addDemand(() -> AutoRoutesConfig.getInstance().isEnabled());
        // START, not END (docs/AP3.md: interaction features tick on START_CLIENT_TICK). A node's held-item / use /
        // break packets then go out ahead of this tick's input and movement packets, as vanilla's handleKeybinds
        // sends a click, and the sneak or keys the executor asks for are read by the input mixin in this same tick.
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoRoutesFeature.tick", AutoRoutesFeature::tick));
        // Breaker Block Display "Waypoint" draws through walls; the pipelines must exist before the first frame.
        com.killer560.hub.util.WorldRenderUtils.initThroughWalls();
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(com.killer560.hub.util.FeatureGuard.afterFeatures(
                com.killer560.hub.util.FeatureBits.register("Auto Routes render", () -> AutoRoutesConfig.getInstance().isEnabledRaw()),
                AutoRoutesFeature::onRenderFrame));
        AwaitEvents.register();
        AutoRoutesProgressHud.register();
        LOGGER.debug("[AutoRoutes] Registered (cheatBuild={})", com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED);
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
            LOGGER.info("[AutoRoutes] Edit mode already {}: breaker node {}", on ? "on" : "off", breakerIndex());
            return;
        }
        editMode = on;
        LOGGER.info("[AutoRoutes] Edit mode {}: running={} tick={}", on ? "ON" : "OFF", RouteExecutor.isRunning(),
                AwaitEvents.now());
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
            // Picking breaks nothing (killer560, 2026-10-11), and that includes the tick edit mode closes: standing on the
            // node he just picked blocks for, the breaker re-fire read the picks as "blocks grown back" and broke them
            // on the next tick (testkit 96-ar-add). Every breaker node of this room counts as already fired for the
            // blocks it has now - the re-fire's own once-per-stand rule, so it fires again only once all of them were
            // seen gone (a real regrow after that), or after he steps off every node and back on.
            Route route = currentRoute();
            if (route != null) {
                for (RouteNode n : route.nodes()) {
                    if (n.type == RouteNode.Type.DUNGEON_BREAKER && !n.breakerBlocks.isEmpty()) {
                        refired.add(n);
                    }
                }
            }
            LOGGER.info("[AutoRoutes] Edit mode closed: {} breaker node(s) marked already re-fired so picks do not break",
                    refired.size());
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
            LOGGER.info("[AutoRoutes] Edit click at {} swallowed: room not identified", pos.toShortString());
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
        // killer560, 2026-10-09: "I can manually click on blocks but it says they are too far for my breaker." This
        // measured from the NODE's spot (feet + 1.6), wherever he stood, so a block in his own reach was refused
        // whenever he was not on the node. Now from his EYE to the block's BOX, the measure the executor and a vanilla
        // dig use, at the Dungeon Breaker's range - a plain dig, so the 4.5 block reach (the wiki gives the item no
        // range of its own). Any block his crosshair picks is inside it.
        LocalPlayer me = Minecraft.getInstance().player;
        if (me != null && com.killer560.hub.util.BlockHits.boxDistanceSq(me.getEyePosition(), pos) > EDIT_MAX_DIST_SQ) {
            LOGGER.info("[AutoRoutes] Edit click at {} refused: out of reach (distSq={} > {})", pos.toShortString(),
                    String.format(java.util.Locale.ROOT, "%.2f",
                            com.killer560.hub.util.BlockHits.boxDistanceSq(me.getEyePosition(), pos)),
                    String.format(java.util.Locale.ROOT, "%.2f", EDIT_MAX_DIST_SQ));
            chatBad("Block is out of your reach (" + com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH
                    + " blocks).");
            return true;
        }
        // Said, not refused: playback breaks from wherever the route has him when the node fires.
        Vec3 nodeReal = RouteCoords.toReal(frame, editBreakerNode.relativePos());
        boolean farFromNode = !shift && com.killer560.hub.util.BlockHits.boxDistanceSq(
                new Vec3(nodeReal.x, nodeReal.y + 1.62, nodeReal.z), pos) > EDIT_MAX_DIST_SQ;
        Route editRoute = RouteStore.getInstance().forRoom(frame.roomName());
        if (shift) {
            if (editBreakerNode.breakerBlocks.contains(rel)) {
                RouteHistory.edited(editRoute, editBreakerNode);
            }
            boolean removedBlock = editBreakerNode.breakerBlocks.remove(rel);
            LOGGER.info("[AutoRoutes] Edit shift-click at {} (rel {}) on breaker #{}: {}", pos.toShortString(),
                    rel.toShortString(), breakerIndex(), removedBlock ? "removed" : "not in the node, nothing removed");
            if (removedBlock) {
                RouteStore.getInstance().save();
                chat(ModChat.text("Removed "), ModChat.value(pos.toShortString()),
                        ModChat.dim(" from breaker #" + breakerIndex()));
            }
            return true;
        }
        if (editBreakerNode.breakerBlocks.contains(rel)) {
            LOGGER.info("[AutoRoutes] Edit click at {} (rel {}): already in breaker #{}", pos.toShortString(),
                    rel.toShortString(), breakerIndex());
            return true;
        }
        if (editBreakerNode.breakerBlocks.size() >= RouteStore.MAX_BREAKER_BLOCKS) {
            LOGGER.info("[AutoRoutes] Edit click at {} refused: breaker #{} full ({} blocks)", pos.toShortString(),
                    breakerIndex(), RouteStore.MAX_BREAKER_BLOCKS);
            chatBad("Breaker #" + breakerIndex() + " already has " + RouteStore.MAX_BREAKER_BLOCKS + " blocks.");
            return true;
        }
        RouteHistory.edited(editRoute, editBreakerNode);
        editBreakerNode.breakerBlocks.add(rel);
        LOGGER.info("[AutoRoutes] Edit click at {} (rel {}): added to breaker #{} ({} blocks, farFromNode={})",
                pos.toShortString(), rel.toShortString(), breakerIndex(), editBreakerNode.breakerBlocks.size(), farFromNode);
        RouteStore.getInstance().save();
        chat(ModChat.text("Added "), ModChat.value(pos.toShortString()),
                ModChat.dim(" to breaker #" + breakerIndex() + " (" + editBreakerNode.breakerBlocks.size() + ")"
                        + (farFromNode ? " - further than " + com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH
                        + " from the node's spot, so it breaks only if the route has you closer" : "")));
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
        LOGGER.info("[AutoRoutes] Deleted node #{} ({}) from {}: {} node(s) left, undoable={}, routeWasRunning={}",
                index + 1, removed.type, route.roomName(), route.nodes().size(), remember, RouteExecutor.isRunning());
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
        RoutePathPlanner.refreshStale(route, editableFrame());
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
            // Nothing undone to redo: bring back the newest /ar delete, /ar remove or /ar clear instead.
            done = RouteHistory.restoreNewestRemoval();
        }
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
        RoutePathPlanner.refreshStale(currentRoute(), editableFrame());
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
            LOGGER.info("[AutoRoutes] Node edit refused: route null={} nodeInRoute={} sameAsCurrent={}", route == null,
                    route != null && route.indexOf(node) >= 0, route == currentRoute());
            chatBad("That node is gone - the route changed or you left the room. Nothing was saved.");
            return false;
        }
        if (node.sameData(edited)) {
            LOGGER.info("[AutoRoutes] Node edit #{} saved nothing: no field changed", route.indexOf(node) + 1);
            return true;
        }
        LOGGER.info("[AutoRoutes] Node edit #{} in {}: {} -> {}", route.indexOf(node) + 1, route.roomName(),
                node.type, edited.type);
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
        RoutePathPlanner.refreshStale(route, editableFrame());
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
                        : ModChat.dim(" - hold your Dungeon Breaker: right-click adds blocks to breaker #" + breakerIndex()
                        + ", shift-right-click removes. Other items click normally. /ar edit db to finish."));
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
            LOGGER.info("[AutoRoutes] Go To refused: route null={} node null={} frame null={} - node gone or room unknown",
                    route == null, node == null, frame == null);
            chatBad("That node is gone - the route changed or you left the room.");
            return false;
        }
        int number = route.indexOf(node) + 1;
        if (RouteRecorder.isRecording()) {
            LOGGER.info("[AutoRoutes] Go To #{} refused: recording", number);
            chatBad("Stop recording first (/ar stop record) - a warp would be recorded into the route.");
            return false;
        }
        if (ClearExecutor.isBusy() || BloodRush.isRunning()) {
            LOGGER.info("[AutoRoutes] Go To #{} refused: map warp busy={} bloodRush={}", number, ClearExecutor.isBusy(),
                    BloodRush.isRunning());
            chatBad("An Interactive Map warp is already running - let it finish, then press Go To again.");
            return false;
        }
        Vec3 real = RouteCoords.toReal(frame, node.relativePos());
        BlockPos below = BlockPos.containing(real.x, real.y - 0.5, real.z);
        BlockPos goal = TeleportUtils.etherwarpable(below) ? below : TeleportUtils.nearestEtherwarpable(below);
        if (goal == null) {
            LOGGER.info("[AutoRoutes] Go To #{} refused: no etherwarpable block at or near {}", number, below.toShortString());
            chatBad("No etherwarpable block at or near node #" + number + " - no path to it.");
            return false;
        }
        LOGGER.info("[AutoRoutes] Go To #{} ({}) in {}: goal {} (node block {}), stopping running route={} tick={}", number,
                node.type, route.roomName(), goal.toShortString(), below.toShortString(), RouteExecutor.isRunning(),
                AwaitEvents.now());
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
        // EXACT: a node arms within its ring (1 block across by default), so the floor graph's "near" landing - up to
        // five blocks off, where its 2x2 buckets miss the block's own landings - can never arm it (see warpToStartNode).
        ClearExecutor.etherPathExact(goal, () -> {
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
            LOGGER.info("[AutoRoutes] Go To arrival with no target node pending - ignored");
            return;
        }
        if (route == null || route.indexOf(node) < 0 || route != currentRoute()) {
            LOGGER.info("[AutoRoutes] Go To arrived but node is no longer in this room's route - edit mode left off");
            chatBad("Arrived, but that node is no longer in this room's route - edit mode left off.");
            return;
        }
        Minecraft client = Minecraft.getInstance();
        RouteCoords.Frame frame = editableFrame();
        if (client.player != null && frame != null) {
            double off = RouteCoords.toReal(frame, node.relativePos()).distanceTo(client.player.position());
            LOGGER.info("[AutoRoutes] Go To arrived at #{}: {} blocks off the node, tick={}", route.indexOf(node) + 1,
                    String.format(java.util.Locale.ROOT, "%.2f", off), AwaitEvents.now());
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
        LOGGER.info("[AutoRoutes] Go To #{} ended without arrival: lastPathFailed={} tick={}", number,
                ClearExecutor.lastPathFailed(), AwaitEvents.now());
        chatBad((ClearExecutor.lastPathFailed() ? "No etherwarp path to node " : "Go To cancelled before reaching node ")
                + (number > 0 ? "#" + number : "") + " - edit mode not turned on.");
    }

    /** After an edit he may be standing in a node that is new, renumbered or back again: like AP3's
     *  {@code suppressAutoArm}, the node he is in when arming next looks counts as already stood in, so it fires
     *  when he walks back onto it, not under him the moment chat closes. */
    private static boolean suppressArm;

    private static void suppressAutoArm() {
        LOGGER.info("[AutoRoutes] Route edited: node underfoot will be latched on the next arm check, map-arrival start-only"
                + " hold dropped (was {})", mapArrivalGuard);
        suppressArm = true;
        // An edit means he is building this room's route where he stands, so a map arrival's "start node only" is
        // over: it outlived a map warp into a route with no start node and silently skipped every node he then added
        // (his Museum breaker nodes, Mod Only Test 2026-10-12).
        mapArrivalGuard = false;
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
        LOGGER.info("[AutoRoutes] Clear route for {}: removed={} ({} node(s))", frame.roomName(), removed,
                saved == null ? 0 : saved.nodes().size());
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
            LOGGER.info("[AutoRoutes] Map click on room {}: not the room you are in (current {}) - map handles it", room,
                    layout == null ? -1 : layout.currentRoom());
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
    /** The room's route and its frame, or null (no identified room, no rotation, no route with a start node). */
    public record RoomRoute(RouteCoords.Frame frame, Route route, RouteNode start) {
        /** The block {@link #warpToStartNode} warps onto: under the start node, or the nearest etherwarpable to it. */
        public BlockPos goal() {
            Vec3 real = RouteCoords.toReal(frame, start.relativePos());
            BlockPos below = BlockPos.containing(real.x, real.y - 0.5, real.z);
            return TeleportUtils.etherwarpable(below) ? below : TeleportUtils.nearestEtherwarpable(below);
        }

        /** Where a run of the route leaves him ({@link RouteExecutor#predictedEnd}). */
        public Vec3 predictedEnd() {
            return RouteExecutor.predictedEnd(route, frame);
        }
    }

    /** {@link RoomRoute} of a map room. Client thread. */
    public static RoomRoute roomRoute(DungeonLayout layout, int room) {
        if (layout == null || room < 0) {
            return null;
        }
        String name = layout.name(room);
        int[] clayRotation = layout.clayRotation(room);
        if (name == null || name.isBlank() || "Unknown".equals(name) || clayRotation == null) {
            return null;
        }
        RouteCoords.Frame frame = new RouteCoords.Frame(name, clayRotation[0], clayRotation[1], clayRotation[2]);
        Route route = RouteStore.getInstance().forRoom(frame.roomName());
        RouteNode start = route == null ? null : route.startNode();
        return start == null ? null : new RoomRoute(frame, route, start);
    }

    public static boolean warpToStartNode(DungeonLayout layout, int room) {
        // Asking to be taken to the start node is as deliberate as typing /ar start: a stop the player made
        // earlier must not make the route refuse to arm when they land (2026-09-16 review).
        RouteExecutor.clearStoppedByUser();
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        if (!cfg.isEnabled() || layout == null || room < 0) {
            LOGGER.info("[AutoRoutes] Start-node warp to room {} not taken: enabled={} layout null={}", room,
                    cfg.isEnabled(), layout == null);
            return false;
        }
        String name = layout.name(room);
        int[] clayRotation = layout.clayRotation(room);
        if (name == null || name.isBlank() || "Unknown".equals(name) || clayRotation == null) {
            LOGGER.info("[AutoRoutes] Start-node warp to room {} not taken: room \"{}\" not identified or no rotation"
                    + " (rotation known={})", room, name, clayRotation != null);
            return false;
        }
        RouteCoords.Frame frame = new RouteCoords.Frame(name, clayRotation[0], clayRotation[1], clayRotation[2]);
        Route route = RouteStore.getInstance().forRoom(frame.roomName());
        RouteNode start = route == null ? null : route.startNode();
        if (start == null) {
            LOGGER.info("[AutoRoutes] Start-node warp to {} not taken: {} - map falls back to its own spot",
                    frame.roomName(), route == null ? "no route saved" : "route has no start node");
            return false;
        }
        Vec3 real = RouteCoords.toReal(frame, start.relativePos());
        BlockPos below = BlockPos.containing(real.x, real.y - 0.5, real.z);
        BlockPos goal = TeleportUtils.etherwarpable(below) ? below : TeleportUtils.nearestEtherwarpable(below);
        if (goal == null) {
            LOGGER.info("[AutoRoutes] Start-node warp to {} refused: nothing etherwarpable at or near {}",
                    frame.roomName(), below.toShortString());
            chatBad("No etherwarpable block under the start node.");
            return false;
        }
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("Interactive Map");
        }
        mapArrivalGuard = true;
        externalTeleport = true;
        ClearExecutor.setExternalOwner(true);
        // EXACT (2026-10-07, testkit 102-sim-autosecret): the start node arms only inside its ring, so a landing NEAR the
        // block is worth nothing here. With the plain request the floor graph's near landing (its 2x2 buckets missed the
        // block's own landing) was taken as is, he stood off the node, and Auto Secret skipped Silver Sword as "did not
        // arm" - the one near landing in 151 start-node warps logged. Now the room-by-room planner aims at the block
        // itself first, as for Auto Blaze's ledge chest.
        // The trip ends on the tick the server confirms its last landing (ClearExecutor.etherPathArrive), so the start node
        // arms then, not ~9 ticks later (killer560, 2026-10-15: routes as fast as possible). A start stack that opens with
        // a warp (warp into warp, as a route's own chain does) or a plain movement goes on that very tick; one that opens
        // with a block or item action (a breaker, a boom, a use) keeps the interact delay a route keeps after a landing:
        // a breaker fired on the landing tick broke nothing on the dedicated server (LESSONS-AUTOMATION, chain timing).
        RouteNode first = route.stackOf(start).get(0);
        // A turn, a walk, a jump or an unsneak sends nothing the server has to have the landing for first either.
        boolean warpFirst = switch (first.type) {
            case ETHERWARP, PATH, ROTATE, WALK, JUMP, UNSNEAK -> true;
            default -> false;
        };
        LOGGER.info("[AutoRoutes] Start-node warp to {} (rotation {}, clay {},{}): start #{} ({}), goal {} (node block {}),"
                        + " first action {} so hold after landing={} tick={}", frame.roomName(), frame.rotation(),
                frame.clayX(), frame.clayZ(), route.indexOf(start) + 1, start.type, goal.toShortString(),
                below.toShortString(), first.type, warpFirst || !cfg.isLegitMode() ? "none" : "interact delay (legit)",
                AwaitEvents.now());
        ClearExecutor.etherPathArrive(goal, () -> {
            ClearExecutor.setExternalOwner(false);
            externalTeleport = false;
            latchedNode = null; // landing ON the start node must arm it
            // Obvious mode holds nothing (killer560, 2026-10-19: "the very next tick the start node goes off"): a breaker
            // or boom start node re-sends what the server did not take (RouteExecutor.tickBreaker / tickBoom), which is
            // the only thing the old interact-delay hold guarded. Legit mode keeps the delay.
            arrivalHoldTicks = warpFirst || !AutoRoutesConfig.getInstance().isLegitMode() ? 0
                    : AutoRoutesConfig.getInstance().getInteractDelayTicks();
            LOGGER.info("[AutoRoutes] Start-node warp landed: latch cleared, arrival hold {} tick(s), tick={}",
                    arrivalHoldTicks, AwaitEvents.now());
        });
        note(ModChat.text("Warping to the start node of "), ModChat.value(frame.roomName()));
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
        LOGGER.info("[AutoRoutes] Interactive Map press ({}): stopping route={}, clearing stoppedByUser={} justFinished={},"
                        + " start-only hold on, tick={}", why, RouteExecutor.isRunning(), RouteExecutor.wasStoppedByUser(),
                RouteExecutor.justFinished(), AwaitEvents.now());
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

    /** The node {@code /ar add} just placed, waiting to fire on the first tick nothing is in the way (see
     *  {@link #fireAddedNode}), and its route. */
    private static RouteNode addedToFire;
    private static Route addedToFireRoute;

    /**
     * {@code /ar add} placed {@code node} at his feet: it fires as if he had just walked onto it (killer560,
     * 2026-10-04: "Make it so once I add a node it performs that action immediately, so if I add an etherwarp it will
     * instantly warp"). Not here and now - the command runs inside the chat screen's key handler, outside the
     * START-of-tick slot every route action is sent from, and a route refuses to run under a screen - but on the first
     * tick the chat is closed, through {@link RouteExecutor#start}, the normal firing path: its whole stack, the
     * route carrying on from it, a landing re-fire (ping-pong) included. The start-only setting, the latch and a
     * finished or user-stopped run do not hold it back: he asked for this one. It is latched meanwhile, so the normal
     * arming never fires it a second time. While a route is already running it is only latched, as before.
     *
     * @return true when it will fire
     */
    static boolean fireAddedNode(Route route, RouteNode node) {
        latchedNode = node;
        mapArrivalGuard = false; // see suppressAutoArm: adding a node ends a map arrival's start-only hold
        if (RouteExecutor.isRunning() || RouteRecorder.isRecording()) {
            LOGGER.info("[AutoRoutes] Added node #{} ({}) latched, not fired: running={} recording={}",
                    route == null ? -1 : route.indexOf(node) + 1, node == null ? null : node.type,
                    RouteExecutor.isRunning(), RouteRecorder.isRecording());
            addedToFire = null;
            addedToFireRoute = null;
            return false;
        }
        LOGGER.info("[AutoRoutes] Added node #{} ({}) queued to fire once the screen closes, tick={}",
                route == null ? -1 : route.indexOf(node) + 1, node == null ? null : node.type, AwaitEvents.now());
        addedToFire = node;
        addedToFireRoute = route;
        return true;
    }

    /** Fires the node {@link #fireAddedNode} queued, once no screen is open. @return true when this tick is spent
     *  on it (fired, or still waiting for the chat to close). */
    private static boolean tickAddedNode(Minecraft client, LocalPlayer player, RouteCoords.Frame frame,
                                         AutoRoutesConfig cfg) {
        RouteNode node = addedToFire;
        Route route = addedToFireRoute;
        if (node == null) {
            return false;
        }
        if (screenBlocks(client)) {
            gate("the node you just added fires when the screen closes");
            return true;
        }
        addedToFire = null;
        addedToFireRoute = null;
        if (route != RouteStore.getInstance().forRoom(frame.roomName()) || route.indexOf(node) < 0) {
            LOGGER.info("[AutoRoutes] Added node not fired: the route changed before the chat closed");
            return false;
        }
        if (!node.contains(RouteCoords.toReal(frame, node.relativePos()), cfg.getHeight(), player.getBoundingBox())) {
            LOGGER.info("[AutoRoutes] Added node #{} not fired: you left it before the chat closed", route.indexOf(node) + 1);
            return false;
        }
        latchedNode = node;
        RouteExecutor.clearStoppedByUser();
        RouteExecutor.clearJustFinished();
        gate("firing node #" + (route.indexOf(node) + 1) + " (" + node.type + ") - just added");
        RouteExecutor.start(route, frame, node);
        return true;
    }

    /** {@link RouteStore#reload()} swapped the routes: drop anything pointing at the old objects. */
    static void onRoutesReloaded() {
        LOGGER.info("[AutoRoutes] Routes reloaded: latch, queued added node and undo history dropped, tick={}",
                AwaitEvents.now());
        editBreakerNode = null;
        latchedNode = null;
        addedToFire = null;
        addedToFireRoute = null;
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
        // First, whatever else returns early: the camera the route held while it turned his body comes back here.
        RouteExecutor.tickView(client);

        if (client.level != lastLevel) {
            lastLevel = client.level;
            resetForWorld("world change");
            RoutePathPlanner.cancelProofs();
        }
        int mapGeneration = LiveMapFeature.resetGeneration();
        if (mapGeneration != lastMapGeneration) {
            LOGGER.info("[AutoRoutes] Live map reset (generation {} -> {}): new run, latches cleared", lastMapGeneration,
                    mapGeneration);
            lastMapGeneration = mapGeneration;
            resetForRun();
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
        // Before the route's own tick: what an await counts, and Kill Mimic, which holds the route while it works.
        AwaitEvents.tick(client);
        MimicKiller.tick(client);

        // Interlock 1: Interactive Map open -> hidden and inert - unless Run While Map Open is on, when the map's
        // screen is no screen at all (its warps are still interlock 5 below).
        boolean mapOpen = McCompat.screen(client) instanceof InteractiveMapScreen && !cfg.isRunWhileMapOpen();
        if (mapOpen) {
            gate("Interactive Map open");
            if (!mapWasOpen && RouteExecutor.isRunning()) {
                RouteExecutor.stop("Interactive Map opened");
            }
            mapWasOpen = true;
            hidden = true;
            addedToFire = null;
            return;
        }
        if (mapWasOpen) {
            // Interlock 3 + 4: closing the map re-arms from the START node only, and only the START node.
            LOGGER.info("[AutoRoutes] Interactive Map closed: start-only hold on, latch cleared, tick={}", AwaitEvents.now());
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

        // A start-node trip whose last landing the server has just confirmed ends now, so the node can arm this tick.
        ClearExecutor.finishArrivalEarly(client);
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
            addedToFire = null;
            // The teleport we are waiting on IS a room change, and the room-change branch below used to
            // clear the guard the moment it landed - handing back exactly the mid-route entry the guard
            // exists to prevent (2026-09-16 review). Latch it so that branch knows the arrival was a
            // teleport, not walking in through a door.
            arrivedByTeleport = true;
            teleportSettleTicks = TELEPORT_SETTLE_TICKS;
            return;
        }
        // Interlock 6: an auto puzzle is working -> inert (killer560, 2026-10-14: "auto clear related things need to
        // realize when an auto puzzle is working to stop making it etherwarp back to the start"). A node the puzzle
        // walks or warps him through must not arm and warp him off the puzzle; it arms as usual once it is quiet.
        if (com.killer560.hub.autopuzzles.AutoPuzzleActivity.isWorking()) {
            String puzzle = com.killer560.hub.autopuzzles.AutoPuzzleActivity.what();
            gate("inert: " + puzzle + " is working");
            if (RouteExecutor.isRunning()) {
                RouteExecutor.stop(puzzle + " is working");
            }
            addedToFire = null;
            return;
        }
        if (teleportSettleTicks > 0 && --teleportSettleTicks == 0) {
            // A same-room warp (the map click that takes you to your own start node) never produces a room
            // change, so the flag had nothing to clear it and start-only stayed enforced for one extra room
            // afterwards. Clear it once the teleport has settled instead (2026-09-16 review).
            arrivedByTeleport = false;
            LOGGER.info("[AutoRoutes] Map teleport settled: arrivedByTeleport cleared, start-only hold still {}, tick={}",
                    mapArrivalGuard, AwaitEvents.now());
        }
        if (arrivalHoldTicks > 0) {
            arrivalHoldTicks--;
            gate("settling after the map's warp onto the start node (it does not open with a warp)");
            return;
        }
        if (externalTeleport) {
            // Our start-node / Go-to warp ended without its completion callback (no path found): release the queue.
            LOGGER.info("[AutoRoutes] Start-node / Go To warp ended without its arrival callback: lastPathFailed={} tick={}",
                    ClearExecutor.lastPathFailed(), AwaitEvents.now());
            externalTeleport = false;
            ClearExecutor.setExternalOwner(false);
            reportGotoFailed();
        }

        boolean inClear = DungeonState.isInDungeon() && !LiveMapFeature.isInBoss();
        RouteCoords.Frame frame = inClear ? RouteCoords.Frame.current() : null;
        if (frame == null) {
            gate(inClear ? "room not identified (no Frame from the live map)"
                    : notInClearGate(DungeonState.isInDungeon(), LiveMapFeature.isInBoss()));
            if (RouteExecutor.isRunning()) {
                RouteExecutor.stop(inClear ? "room unknown" : "not in a dungeon room");
            }
            if (RouteRecorder.isRecording()) {
                RouteRecorder.tick(client); // lets the recorder end itself cleanly on a room change
            }
            lastRoom = null;
            RouteExecutor.clearBreakerLinger("left the dungeon room");
            return;
        }
        if (frame.roomName().equals(lastRoom) && !frame.sameRoom(lastLoggedFrame)) {
            // Same room, but its rotation or origin moved under us: say so, since every node moves with it.
            lastLoggedFrame = frame;
            LOGGER.info("[AutoRoutes] Room {} frame changed - rotation {}, origin (clay) {},{}", frame.roomName(),
                    frame.rotation(), frame.clayX(), frame.clayZ());
        }
        if (!frame.roomName().equals(lastRoom)) {
            RouteExecutor.clearBreakerLinger("room change");
            // A trap route walking him out goes on past the map's edge: Auto Trap ends it once he is clear of the
            // room by its margin (RouteExecutor.trapExitPending), where the server no longer has him in the trap.
            if (lastRoom != null && RouteExecutor.isRunning() && !RouteExecutor.trapExitPending()) {
                RouteExecutor.stop("left the room");
            }
            lastRoom = frame.roomName();
            lastLoggedFrame = frame;
            LOGGER.info("[AutoRoutes] Room {} - rotation {}, origin (clay) {},{}, sim y offset {}", frame.roomName(),
                    frame.rotation(), frame.clayX(), frame.clayZ(), DungeonLayout.simYOffset());
            // One line per room entry so he can see in latest.log whether a route was found for the room (the store
            // is keyed by the room database name exactly as the map gives it).
            Route entered = RouteStore.getInstance().forRoom(frame.roomName());
            LOGGER.info("[AutoRoutes] Route lookup on entry: room \"{}\", key \"{}\" - {}", frame.roomName(),
                    frame.roomName(), entered == null || entered.nodes().isEmpty() ? "no route saved"
                            : "route found (" + entered.nodes().size() + " node(s), start node "
                            + (entered.startNode() == null ? "none" : "#" + (entered.indexOf(entered.startNode()) + 1))
                            + ")");
            latchedNode = null;
            addedToFire = null;
            addedToFireRoute = null;
            // New room, clean slate - see resetForWorld.
            boolean wasStopped = RouteExecutor.wasStoppedByUser();
            RouteExecutor.clearStoppedByUser();
            boolean guardBefore = mapArrivalGuard;
            if (!externalTeleport && !arrivedByTeleport) {
                mapArrivalGuard = false; // walked in through a door: mid-route entry is the toggle's call again
            }
            LOGGER.info("[AutoRoutes] Room entry {} by {}: start-only hold {} -> {}, stoppedByUser {} -> false,"
                            + " justFinished={}, tick={}", frame.roomName(),
                    externalTeleport || arrivedByTeleport ? "teleport (map / Blood Rush)" : "walking in",
                    guardBefore, mapArrivalGuard, wasStopped, RouteExecutor.justFinished(), AwaitEvents.now());
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
        // killer560, 2026-10-11: "in edit mode the node should not break blocks but I should be able to." Nothing of the
        // route acts while breaker edit mode is open - no arming, no just-added node firing, and anything that started
        // a route meanwhile is stopped - so the only digs are his own clicks.
        if (editMode) {
            RouteExecutor.clearBreakerLinger("edit mode");
            if (RouteExecutor.isRunning()) {
                RouteExecutor.stop("edit mode");
            }
            addedToFire = null;
            addedToFireRoute = null;
            gate("edit mode");
            return;
        }
        // A finished breaker node keeps mining its blocks as they come into reach (falling through the hole it made)
        // until another node fires - RouteExecutor.tickBreakerLinger.
        RouteExecutor.tickBreakerLinger(client);
        if (RouteExecutor.isRunning()) {
            addedToFire = null;
            gate("running");
            RouteExecutor.tick(client);
            return;
        }
        if (tickAddedNode(client, player, frame, cfg)) {
            return;
        }
        arm(client, player, frame, cfg);
    }

    /**
     * Whether the open screen holds Auto Routes back. Every screen does, except the Interactive Map's own while Run
     * While Map Open is on (killer560, 2026-10-05) - chests, inventories, chat and the node editor still pause it.
     */
    public static boolean screenBlocks(Minecraft client) {
        net.minecraft.client.gui.screens.Screen screen = McCompat.screen(client);
        return screen != null
                && !(screen instanceof InteractiveMapScreen && AutoRoutesConfig.getInstance().isRunWhileMapOpen());
    }

    /** Breaker nodes re-fired during the current stand on their tile (cleared when he steps off every node). */
    private static final java.util.Set<RouteNode> refired =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * killer560 (2026-10-12), on blocks a breaker node broke growing back: "Only if I am on the node and there are blocks
     * to be broke then it should break them." Where the normal arming would NOT fire (the tile is latched: the last run
     * ended or was stopped here, or he just placed it), a DUNGEON_BREAKER node on the tile he stands on fires again - on
     * its own, not the rest of the route - when at least one of its blocks is standing. Once per stand: it fires again
     * only after all its blocks have been seen gone (they grew back after it broke them) or he stepped off. One dig per
     * standing block per fire (the node's own queue), and the fire takes the tick through ActionGate. Nothing fires
     * when every block is already gone. @return true when it fired
     */
    private static boolean breakerRefire(Minecraft client, Route route, RouteCoords.Frame frame, RouteNode inside) {
        if (RouteExecutor.wasStoppedByUser()) {
            // He took the controls back (a movement key, /ar stop) - typically while the node waited for charges, its
            // blocks still standing. Re-firing here restarted it the next tick (testkit 96-ar-breakerwait): nothing
            // fires again until he steps off every node, which clears the stop, and back on.
            return false;
        }
        boolean latched = inside == latchedNode || inside.sameTile(latchedNode) || RouteExecutor.justFinished();
        for (RouteNode n : route.stackOf(inside)) {
            if (n.type != RouteNode.Type.DUNGEON_BREAKER || n.breakerBlocks.isEmpty()) {
                continue;
            }
            int standing = 0;
            for (BlockPos rel : n.breakerBlocks) {
                BlockPos p = RouteCoords.toRealBlock(frame, rel);
                if (client.level != null && client.level.isLoaded(p) && !client.level.getBlockState(p).isAir()) {
                    standing++;
                }
            }
            if (standing == 0) {
                refired.remove(n);   // all gone: the next regrow may fire it again
                continue;
            }
            if (!latched || refired.contains(n)) {
                continue;   // the normal arming fires it, or it already fired for these blocks
            }
            if (!com.killer560.hub.util.ActionGate.tryAct(com.killer560.hub.util.ActionGate.Actor.ROUTE)) {
                logRefireHeld(AwaitEvents.now(), "gate#" + (route.indexOf(n) + 1), "breaker node #" + (route.indexOf(n) + 1)
                        + " re-fire waits: another feature has this tick's action (" + standing + " block(s) standing)");
                return true;   // someone else has this tick; try again next one
            }
            refired.add(n);
            Route single = new Route(route.roomName());
            single.nodes().add(n);
            gate("re-firing breaker node #" + (route.indexOf(n) + 1) + " - " + standing + " of its block(s) standing");
            LOGGER.info("[AutoRoutes] Breaker node #{}: {} of its {} block(s) standing while he is on it - breaking them"
                    + " again", route.indexOf(n) + 1, standing, n.breakerBlocks.size());
            RouteExecutor.start(single, frame, n);
            return true;
        }
        return false;
    }

    /** Starts the room's route when the player stands in its START node (or, when allowed, any node). */
    private static void arm(Minecraft client, LocalPlayer player, RouteCoords.Frame frame, AutoRoutesConfig cfg) {
        Route route = RouteStore.getInstance().forRoom(frame.roomName());
        if (route == null || route.nodes().isEmpty()) {
            gate(noRouteGate(frame.roomName()));
            latchedNode = null;
            suppressArm = false;
            return;
        }
        if (screenBlocks(client)) {
            gate("a screen is open");
            // Nothing arms under a screen, but the latch is kept: clearing it here undid /ar add's latch every time
            // the chat that typed "/ar add" was still open, so a node fired under him the moment chat closed.
            return;
        }
        AABB playerBox = player.getBoundingBox();
        double height = cfg.getHeight();
        // "Start node only" (the toggle, or a map arrival's hold) applies only to a route that HAS a start node. On one
        // without, it used to mean "never", so nothing in that room fired again; killer560 (2026-10-12): "they should
        // work without a start node anyways incase I dont want one treated as a start node" - so every node arms.
        List<RouteNode> nodes = route.nodes();
        boolean hasStartNode = false;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).start) {
                hasStartNode = true;
                break;
            }
        }
        boolean startOnly = hasStartNode && (cfg.isStartFromStartNodeOnly() || mapArrivalGuard);
        // The first node (path order) whose ring he is in. Every node on that node's tile then fires with it, as one
        // stack in the approved type order - RouteExecutor.beginStack / Route#stackOf.
        // Path order is Route#nodesInPathOrder's stable sort by pathIndex; the first ring hit in it is the hit with the
        // lowest pathIndex, ties to the earlier node in the list - found here in one pass over the list, with no copy,
        // sort, stream or per-node Vec3/AABB (fps-auto-1015; this runs every tick he stands in a room with a route).
        RouteNode inside = null;
        for (int i = 0; i < nodes.size(); i++) {
            RouteNode node = nodes.get(i);
            if (startOnly && !node.start) {
                continue;
            }
            if ((inside == null || node.pathIndex < inside.pathIndex) && node.containsIn(frame, height, playerBox)) {
                inside = node;
            }
        }
        if (inside == null) {
            ringNode = null;
        } else if (ringNode == null || !inside.sameTile(ringNode)) {
            ringNode = inside;
            ringEnteredAt = AwaitEvents.now();
            LOGGER.info("[AutoRoutes] Entered node #{}'s ring ({}) at tick {}", route.indexOf(inside) + 1, inside.type,
                    ringEnteredAt);
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
            refired.clear();
            // Clear of every node: a route the player stopped by hand - or that just finished on top of one -
            // may arm again from here.
            RouteExecutor.clearStoppedByUser();
            RouteExecutor.clearJustFinished();
            return;
        }
        // A jump node that just finished holds its tile for 3 s (RouteExecutor.JUMP_COOLDOWN_MS), in addition to the
        // latch rules below: the jump lifts him out of the ring, which clears them, and he lands back in it.
        long cooldown = RouteExecutor.jumpCooldownLeft(inside);
        if (cooldown > 0) {
            String prefix = "in node #" + (route.indexOf(inside) + 1) + " - jump cooldown ";
            if (lastGate == null || !lastGate.startsWith(prefix)) {   // once per cooldown, not every tick's countdown
                gate(prefix + String.format(java.util.Locale.US, "%.1fs left", cooldown / 1000.0));
            }
            return;
        }
        if (breakerRefire(client, route, frame, inside)) {
            return;
        }
        // Latched per TILE: every node on it fires as one stack (Route#stackOf), and the node found first above
        // need not be the one latched - "/ar add" latches the node it just placed, which on an occupied tile is
        // the second one there, and comparing nodes fired the whole tile under him the moment chat closed.
        if (inside == latchedNode || inside.sameTile(latchedNode)) {
            gate("in node #" + (route.indexOf(inside) + 1) + " but latched (just placed it, or the last run "
                    + "started / ended here) - step off and back on [latched #" + (route.indexOf(latchedNode) + 1)
                    + ", justFinished=" + RouteExecutor.justFinished() + ", stoppedByUser="
                    + RouteExecutor.wasStoppedByUser() + ", startOnly=" + startOnly + "]");
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
        RouteNode previousLatch = latchedNode;
        latchedNode = inside;
        boolean guardBefore = mapArrivalGuard;
        if (inside.start || !hasStartNode) {
            mapArrivalGuard = false;
        }
        gate("armed node #" + (route.indexOf(inside) + 1) + " (" + inside.type + (inside.start ? ", start" : "") + ")");
        LOGGER.info("[AutoRoutes] Node #{} armed {} tick(s) after you entered its ring", route.indexOf(inside) + 1,
                AwaitEvents.now() - ringEnteredAt);
        Vec3 armedAt = RouteCoords.toReal(frame, inside.relativePos());
        LOGGER.info("[AutoRoutes] Fire: room {} rotation {} clay {},{} node #{} ({}{}) at {} stack={} player {}"
                        + " startOnly={} (toggle={} mapHold {} -> {}) hasStartNode={} previousLatch={} tick={}",
                frame.roomName(), frame.rotation(), frame.clayX(), frame.clayZ(), route.indexOf(inside) + 1, inside.type,
                inside.start ? ", start" : "",
                String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", armedAt.x, armedAt.y, armedAt.z),
                route.stackOf(inside).size(),
                String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", player.getX(), player.getY(), player.getZ()),
                startOnly, cfg.isStartFromStartNodeOnly(), guardBefore, mapArrivalGuard, hasStartNode,
                previousLatch == null ? "none" : "#" + (route.indexOf(previousLatch) + 1), AwaitEvents.now());
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

    // ------------------------------------------------------------------------------------------- helpers

    /** Logs an arming decision once per change - every gate between "standing on a node" and "the route started"
     *  says which one it is, so a node that never fires names its own reason in the log. */
    private static void gate(String state) {
        if (!state.equals(lastGate)) {
            lastGate = state;
            LOGGER.info("[AutoRoutes] Arming: {} (tick {})", state, AwaitEvents.now());
        }
    }

    private static String lastRefireKey;
    private static long lastRefireTick;

    /** A breaker re-fire held back by another feature's tick: once per key per 20 ticks. */
    private static void logRefireHeld(long now, String key, String detail) {
        if (key.equals(lastRefireKey) && now - lastRefireTick < 20) {
            return;
        }
        lastRefireKey = key;
        lastRefireTick = now;
        LOGGER.info("[AutoRoutes] Re-fire held: {} (tick {})", detail, now);
    }

    /** Standing in no node: name the start node and where it really is, so a coordinate mismatch shows. Built only
     *  from the route, never the player's position, so it does not change every tick. */
    private static String idleGate(Route route, RouteCoords.Frame frame, boolean startOnly) {
        RouteNode start = route.startNode();
        // Rebuilt only when one of its inputs changed (fps-auto-1015): it was a String.format, a Vec3 and a
        // concatenation every tick he stood clear of every node, thrown away by gate() as unchanged.
        int startIdx = start == null ? -1 : route.indexOf(start);
        int shift = com.killer560.hub.livemap.DungeonLayout.simYOffset();
        if (idleGateText != null && route == idleGateRoute && frame.equals(idleGateFrame) && startOnly == idleGateStartOnly
                && route.nodes().size() == idleGateCount && start == idleGateStart && startIdx == idleGateStartIdx
                && shift == idleGateShift
                && (start == null || start.x == idleGateSX && start.y == idleGateSY && start.z == idleGateSZ)) {
            return idleGateText;
        }
        idleGateRoute = route;
        idleGateFrame = frame;
        idleGateStartOnly = startOnly;
        idleGateCount = route.nodes().size();
        idleGateStart = start;
        idleGateStartIdx = startIdx;
        idleGateShift = shift;
        if (start != null) {
            idleGateSX = start.x;
            idleGateSY = start.y;
            idleGateSZ = start.z;
        }
        idleGateText = buildIdleGate(route, frame, startOnly, start);
        return idleGateText;
    }

    /** The "not in a dungeon clear" gate text, one constant per case instead of a concatenation every tick. */
    private static String notInClearGate(boolean inDungeon, boolean boss) {
        return inDungeon ? (boss ? "not in a dungeon clear (isInDungeon=true, boss=true)"
                : "not in a dungeon clear (isInDungeon=true, boss=false)")
                : (boss ? "not in a dungeon clear (isInDungeon=false, boss=true)"
                : "not in a dungeon clear (isInDungeon=false, boss=false)");
    }

    /** "no route for <room>", rebuilt only when the room changes (it ran every tick in every room without a route). */
    private static String noRouteGate(String room) {
        if (!room.equals(noRouteRoom)) {
            noRouteRoom = room;
            noRouteText = "no route for " + room;
        }
        return noRouteText;
    }

    private static String noRouteRoom;
    private static String noRouteText;

    private static String idleGateText;
    private static Route idleGateRoute;
    private static RouteCoords.Frame idleGateFrame;
    private static boolean idleGateStartOnly;
    private static int idleGateCount;
    private static RouteNode idleGateStart;
    private static int idleGateStartIdx;
    private static int idleGateShift;
    private static double idleGateSX;
    private static double idleGateSY;
    private static double idleGateSZ;

    private static String buildIdleGate(Route route, RouteCoords.Frame frame, boolean startOnly, RouteNode start) {
        String where = start == null ? "no start node, so every node arms"
                : String.format(java.util.Locale.US, "start node #%d at %s", route.indexOf(start) + 1,
                        RouteCoords.toReal(frame, start.relativePos()));
        return "in no node of " + frame.roomName() + " (" + route.nodes().size() + " node(s), "
                + (startOnly ? "start only" : "any node") + ", " + where + ")";
    }

    /**
     * A new run without a world change: the live map was reset (a dungeon entered, a sim floor or room built - the sim's
     * {@code /reset} rebuilds the room he is standing in). The rooms are new, so no node he stood in is still "the one
     * he stopped or finished in": the latches go, and a route still running stops. Edit mode and a recording are his
     * and stay; a world change ({@link #resetForWorld}) still drops those.
     */
    private static void resetForRun() {
        LOGGER.info("[AutoRoutes] Reset for new run: running={} stoppedByUser={} justFinished={} lastRoom={} tick={}",
                RouteExecutor.isRunning(), RouteExecutor.wasStoppedByUser(), RouteExecutor.justFinished(), lastRoom,
                AwaitEvents.now());
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("new run");
        }
        RouteExecutor.clearStoppedByUser();
        RouteExecutor.clearJustFinished();
        latchedNode = null;
        lastRoom = null;
        gotoNode = null;
        gotoRoute = null;
        addedToFire = null;
        addedToFireRoute = null;
    }

    private static void resetForWorld(String reason) {
        LOGGER.info("[AutoRoutes] Reset ({}): running={} recording={} editMode={} mapHold={} lastRoom={} tick={}", reason,
                RouteExecutor.isRunning(), RouteRecorder.isRecording(), editMode, mapArrivalGuard, lastRoom,
                AwaitEvents.now());
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
        addedToFire = null;
        addedToFireRoute = null;
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

    /** The breaker node most recently placed with /ar add breaker (killer560, 2026-10-06: "once I add the auto route
     *  node i should do the command /ar editdb or /ar db and that adds breaker blocks [...] from that node
     *  specifically"). */
    private static RouteNode lastAddedBreaker;

    static void noteAddedBreaker(RouteNode node) {
        lastAddedBreaker = node;
    }

    /** The breaker node edit mode targets: the breaker just added with /ar add breaker while it is still in this
     *  room's route, else the nearest in the room. */
    private static void pickEditBreakerNode() {
        editBreakerNode = null;
        Minecraft client = Minecraft.getInstance();
        RouteCoords.Frame frame = RouteCoords.Frame.current();
        Route route = frame == null ? null : RouteStore.getInstance().forRoom(frame.roomName());
        if (route == null || client.player == null) {
            return;
        }
        if (lastAddedBreaker != null && lastAddedBreaker.type == RouteNode.Type.DUNGEON_BREAKER
                && route.indexOf(lastAddedBreaker) >= 0) {
            editBreakerNode = lastAddedBreaker;
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

    /** A progress line: logged word for word, never shown (killer560, 2026-10-16: start/stop lines only in chat). */
    static void note(Component... parts) {
        ModChat.log(CHAT, parts);
    }

    static void chatInfo(String text) {
        ModChat.send(CHAT, ModChat.text(text));
    }

    static void chatBad(String text) {
        ModChat.send(CHAT, ModChat.bad(text));
    }
}
