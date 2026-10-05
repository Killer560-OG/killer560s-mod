package com.killer560.hub.autoroutes;

import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.pathfinding.EtherwarpHopper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.Locale;
import com.killer560.hub.compat.McCompat;

/**
 * {@code /ar start record} / {@code /ar stop record} - killer560: "I etherwarp to a block and type /ar start record
 * / /ar stop record. That records my movements exactly and plays them back when I etherwarp onto that starting node
 * again."
 * <p>
 * Every client tick while recording adds one {@link RoutePath.Sample} (room-relative position and look, the raw
 * movement keys straight from {@code LocalPlayer.input.keyPresses}, on-ground). Discrete events are captured too,
 * anchored to the sample they happened on: a sneaking right-click with an etherwarp item that is followed by a
 * position jump becomes an {@code ETHERWARP} node (with the landing spot as its confirmation), any other right-click
 * a {@code USE_ITEM} node (a jump after it - Hyperion, AOTE - records the landing), a left-click holding a Superboom
 * a {@code BOOM} node, and a left-click on a block holding the Dungeon Breaker adds that block to the breaker node
 * being built. Everything else ({@code walk}, {@code await}, {@code rotate}, ...) is {@code /ar add <type>}.
 * <p>
 * Nodes can also be added when NOT recording ({@code /ar add ew} exactly like QUOI): they anchor to the nearest
 * recorded sample, or to a bare route with no path (which then plays back QUOI-style, node by node).
 */
public final class RouteRecorder {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoroutes");
    /** A position change this large in one tick is a teleport, not a step (sprint-jumping moves ~0.6/tick). */
    private static final double TELEPORT_JUMP = 3.0;
    /** How long after a sneaking etherwarp click the teleport may arrive (server lag) before the click is dropped. */
    private static final int ETHERWARP_PENDING_TICKS = 20;
    private static final int LANDING_WINDOW_TICKS = 20;
    private static final String[] BOOM_IDS = {"INFINITE_SUPERBOOM_TNT", "SUPERBOOM_TNT"};
    private static final String BREAKER_ID = "DUNGEONBREAKER";

    private static boolean recording;
    private static Route route;
    private static RouteCoords.Frame frame;
    private static Vec3 lastRealPos;
    private static boolean useWasDown;
    private static boolean attackWasDown;
    private static int pendingEtherwarpTicks;
    private static RouteNode pendingEtherwarp;
    private static RouteNode lastActionNode;
    private static int lastActionSample = -1;
    private static RouteNode breakerBeingBuilt;

    private RouteRecorder() {
    }

    public static boolean isRecording() {
        return recording;
    }

    /** The route being recorded, for the renderer (null when idle). */
    static Route recordingRoute() {
        return recording ? route : null;
    }

    static RouteCoords.Frame recordingFrame() {
        return recording ? frame : null;
    }

    /** The room the rotation warning was last given for, so /ar add in the same room says it once. */
    private static String rotationWarnedRoom;

    /**
     * SIM ONLY: one chat warning when a route is started (or a node added) in a room whose capture rotation the sim
     * could not determine with confidence - see {@link #simCaptureRotationUncertain}. A route stores coordinates
     * relative to the room's rotation, so in such a room what the sim calls "relative" may be a quarter or half
     * turn off what Hypixel will call it.
     *
     * @param always true for a recording start, which always says it; false for /ar add, which says it once a room
     */
    private static void warnIfSimRotationUncertain(String roomName, boolean always) {
        if (!simCaptureRotationUncertain(roomName)) {
            return;
        }
        if (!always && roomName.equals(rotationWarnedRoom)) {
            return;
        }
        rotationWarnedRoom = roomName;
        AutoRoutesFeature.chat(com.killer560.hub.util.ModChat.bad("Warning: "),
                com.killer560.hub.util.ModChat.text("the sim is not sure which way " + roomName
                        + "'s capture is turned. A route recorded here may come out rotated on Hypixel until the "
                        + "room's capture is fixed."));
    }

    /**
     * Whether the sim is running and {@code roomName}'s capture rotation is uncertain (no roof marker, or an
     * ambiguous or overruled one). The one place Auto Routes asks; it reads RoomCaptureRotation's own verdict
     * rather than a list of names.
     */
    static boolean simCaptureRotationUncertain(String roomName) {
        if (roomName == null || !com.killer560.hub.roomsim.SimState.isActive()) {
            return false;
        }
        com.killer560.hub.roomsim.RoomLibrary.Room room = com.killer560.hub.roomsim.RoomLibrary.get(roomName);
        return room != null && com.killer560.hub.roomsim.RoomCaptureRotation.uncertainForRecording(room);
    }

    /** @return chat status, or null when recording could not start (the message says why via ModChat). */
    public static String startRecording() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        if (!cfg.isEnabled()) {
            AutoRoutesFeature.chatBad("Auto Routes is off (cheat build + Skyblock only).");
            return null;
        }
        if (player == null || client.level == null) {
            // Only reachable while the world isn't fully loaded - see AutoRoutesCommands#ready()'s identical
            // check (2026-09-2x review: this and addNode's own copy were the only two silent refusals /ar
            // start record and /ar add could hit without saying why).
            AutoRoutesFeature.chatBad("Not fully loaded into the world yet - try again in a moment.");
            return null;
        }
        if (recording) {
            AutoRoutesFeature.chatBad("Already recording - /ar stop record first.");
            return null;
        }
        RouteCoords.Frame f = RouteCoords.Frame.current();
        if (f == null) {
            AutoRoutesFeature.chatBad("Room not identified yet - stand in a scanned room.");
            return null;
        }
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("recording started");
        }
        Route existing = RouteStore.getInstance().forRoom(f.roomName());
        route = new Route(f.roomName());
        frame = f;
        recording = true;
        lastRealPos = null;
        useWasDown = client.options.keyUse.isDown();
        attackWasDown = client.options.keyAttack.isDown();
        pendingEtherwarpTicks = 0;
        pendingEtherwarp = null;
        lastActionNode = null;
        lastActionSample = -1;
        breakerBeingBuilt = null;
        // The first sample and the start node are the block killer560 etherwarped onto before typing the
        // command. A plain WALK node carrying the `start` flag, not a dedicated START type any more - it
        // behaves exactly the same way in the executor (a no-op pass-through node), see RouteNode.Type's doc.
        sample(client, player);
        Vec3 rel = RouteCoords.toRelative(f, snappedFeet(player));
        RouteNode startNode = new RouteNode(RouteNode.Type.WALK, rel.x, rel.y, rel.z,
                RouteCoords.toRelativeYaw(f, player.getYRot()), player.getXRot(), 0);
        startNode.start = true;
        route.nodes().add(startNode);
        warnIfSimRotationUncertain(f.roomName(), true);
        return "Recording " + f.roomName() + (existing != null ? " (will replace the saved route)" : "")
                + " - move, then /ar stop record";
    }

    public static String stopRecording() {
        if (!recording) {
            AutoRoutesFeature.chatBad("Not recording.");
            return null;
        }
        Route done = route;
        recording = false;
        route = null;
        pendingEtherwarp = null;
        breakerBeingBuilt = null;
        done.clampNodeAnchors();
        RouteStore store = RouteStore.getInstance();
        store.put(done);
        store.save();
        return String.format(Locale.US, "Saved %s - %.1fs of movement, %d node(s)", done.roomName(),
                done.path().size() / 20.0, done.nodes().size());
    }

    /** Drops an in-progress recording without saving (world change, reload, feature turned off). */
    public static void discard() {
        if (recording) {
            LOGGER.info("[AutoRoutes] Recording discarded");
        }
        recording = false;
        route = null;
        pendingEtherwarp = null;
        breakerBeingBuilt = null;
    }

    // ------------------------------------------------------------------------------------------- per tick

    /** Called by {@link AutoRoutesFeature} every client tick while {@link #isRecording()}. */
    static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (!recording || player == null || client.level == null) {
            return;
        }
        RouteCoords.Frame now = RouteCoords.Frame.current();
        if (now == null || !now.sameRoom(frame)) {
            // Walked (or warped) out of the room: the route is per-room, so that is the end of it.
            String msg = stopRecording();
            if (msg != null) {
                AutoRoutesFeature.chatInfo(msg + " (left the room)");
            }
            return;
        }
        if (route.path().size() >= RouteStore.MAX_SAMPLES) {
            AutoRoutesFeature.chatInfo(stopRecording() + " (recording limit reached)");
            return;
        }
        Vec3 pos = player.position();
        int sampleIndex = sample(client, player);

        // ---- teleport detection: an etherwarp / item teleport shows up as one big jump between two samples ----
        if (lastRealPos != null && pos.distanceTo(lastRealPos) > TELEPORT_JUMP) {
            Vec3 landing = RouteCoords.toRelative(frame, pos);
            if (pendingEtherwarp != null) {
                pendingEtherwarp.setLanding(landing);
                route.nodes().add(pendingEtherwarp);
                lastActionNode = pendingEtherwarp;
                lastActionSample = sampleIndex;
                pendingEtherwarp = null;
                pendingEtherwarpTicks = 0;
            } else if (lastActionNode != null && !lastActionNode.hasLanding
                    && sampleIndex - lastActionSample <= LANDING_WINDOW_TICKS) {
                lastActionNode.setLanding(landing); // Hyperion / AOTE: the use node's own teleport
            }
            // Any other jump is the server moving us (a rubber-band, a leap) - nothing to record.
        }
        lastRealPos = pos;

        if (pendingEtherwarpTicks > 0 && --pendingEtherwarpTicks == 0) {
            pendingEtherwarp = null; // the etherwarp click never teleported (no valid target) - not a node
        }

        // ---- clicks (only with no screen open, so GUI clicks don't count) ----
        boolean useDown = client.options.keyUse.isDown();
        boolean attackDown = client.options.keyAttack.isDown();
        if (McCompat.screen(client) == null && !AutoRoutesFeature.isEditMode()) {
            if (useDown && !useWasDown) {
                onRightClick(player, sampleIndex);
            }
            if (attackDown && !attackWasDown) {
                onLeftClick(client, player, sampleIndex);
            }
        }
        useWasDown = useDown;
        attackWasDown = attackDown;
    }

    private static int sample(Minecraft client, LocalPlayer player) {
        Vec3 rel = RouteCoords.toRelative(frame, player.position());
        Input keys = player.input != null ? player.input.keyPresses : null;
        int bits = keys == null ? 0 : RoutePath.keysOf(keys.forward(), keys.backward(), keys.left(), keys.right(),
                keys.jump(), keys.shift(), keys.sprint());
        route.path().add(new RoutePath.Sample(rel.x, rel.y, rel.z, RouteCoords.toRelativeYaw(frame, player.getYRot()),
                player.getXRot(), bits, player.onGround()));
        return route.path().size() - 1;
    }

    private static void onRightClick(LocalPlayer player, int sampleIndex) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return;
        }
        RouteNode node = nodeAtPlayer(player, sampleIndex);
        if (ItemIdentity.isEtherwarpItem(held) && player.isShiftKeyDown()) {
            node.type = RouteNode.Type.ETHERWARP;
            pendingEtherwarp = node;
            pendingEtherwarpTicks = ETHERWARP_PENDING_TICKS;
            return;
        }
        node.type = RouteNode.Type.USE_ITEM;
        node.item = ItemIdentity.of(held);
        route.nodes().add(node);
        lastActionNode = node;
        lastActionSample = sampleIndex;
    }

    private static void onLeftClick(Minecraft client, LocalPlayer player, int sampleIndex) {
        String id = ItemIdentity.skyblockId(player.getMainHandItem());
        if (id == null) {
            return;
        }
        if (BOOM_IDS[0].equalsIgnoreCase(id) || BOOM_IDS[1].equalsIgnoreCase(id)) {
            RouteNode node = nodeAtPlayer(player, sampleIndex);
            node.type = RouteNode.Type.BOOM;
            route.nodes().add(node);
            lastActionNode = node;
            lastActionSample = sampleIndex;
            return;
        }
        if (BREAKER_ID.equalsIgnoreCase(id)) {
            HitResult hit = client.hitResult;
            if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
                return;
            }
            // Consecutive breaker clicks from roughly the same spot build one node; a new spot starts a new one.
            if (breakerBeingBuilt == null || sampleIndex - breakerBeingBuilt.pathIndex > 60
                    || breakerBeingBuilt.breakerBlocks.size() >= RouteStore.MAX_BREAKER_BLOCKS) {
                breakerBeingBuilt = nodeAtPlayer(player, sampleIndex);
                breakerBeingBuilt.type = RouteNode.Type.DUNGEON_BREAKER;
                route.nodes().add(breakerBeingBuilt);
            }
            BlockPos rel = RouteCoords.toRelativeBlock(frame, blockHit.getBlockPos());
            if (!breakerBeingBuilt.breakerBlocks.contains(rel)) {
                breakerBeingBuilt.breakerBlocks.add(rel);
            }
        }
    }

    /**
     * Where a node goes: the centre of the block the player's feet are in (killer560, 2026-10-04: "Make it so nodes
     * need to snap to a whole block for the autoroutes" - it had been AP3's half-block grid), y kept at the feet to a
     * thousandth so a node on carpet or a slab sits on its top ({@code Ap3Node.snapY}). Snapped in WORLD space and then
     * made room-relative, so the grid it lands on is the world's; see {@link RouteNode#snapBlockCentre}.
     */
    static Vec3 snappedFeet(LocalPlayer player) {
        Vec3 p = player.position();
        return new Vec3(RouteNode.snapBlockCentre(p.x), com.killer560.hub.ap3.Ap3Node.snapY(p.y),
                RouteNode.snapBlockCentre(p.z));
    }

    private static RouteNode nodeAtPlayer(LocalPlayer player, int sampleIndex) {
        Vec3 rel = RouteCoords.toRelative(frame, snappedFeet(player));
        return new RouteNode(RouteNode.Type.WALK, rel.x, rel.y, rel.z,
                RouteCoords.toRelativeYaw(frame, player.getYRot()), player.getXRot(), Math.max(0, sampleIndex));
    }

    // ------------------------------------------------------------------------------------------- /ar add

    /**
     * The {@code start} / {@code await:<n>} modifiers of {@code /ar add <type> [start] [await:<n>]}
     * (killer560: "start should not be a node ... So i could do /ar add etherwarp start await:2. This means it
     * is an etherwarp node that is also a start node that will wait for two secrets before it etherwarps.").
     * {@link AutoRoutesCommands#parseModifiers} builds one of these from the command's free-form modifiers text;
     * {@link #addNode(RouteNode.Type, NodeModifiers)} applies it to the node it builds.
     */
    public static final class NodeModifiers {
        /** No modifiers - the plain {@code /ar add <type>}. */
        public static final NodeModifiers NONE =
                new NodeModifiers(false, false, RouteNode.AwaitCondition.SECRET, 1);

        public final boolean start;
        public final boolean awaitEnabled;
        public final RouteNode.AwaitCondition awaitCondition;
        public final int awaitAmount;

        public NodeModifiers(boolean start, boolean awaitEnabled, RouteNode.AwaitCondition awaitCondition,
                              int awaitAmount) {
            this.start = start;
            this.awaitEnabled = awaitEnabled;
            this.awaitCondition = awaitCondition;
            this.awaitAmount = awaitAmount;
        }
    }

    /** {@code /ar add <type>}: captures from the current look / position / held item. @return chat status. */
    public static String addNode(RouteNode.Type type) {
        return addNode(type, NodeModifiers.NONE);
    }

    /** As {@link #addNode(RouteNode.Type)}, plus the {@code start} / {@code await:<n>} modifiers. */
    public static String addNode(RouteNode.Type type, NodeModifiers modifiers) {
        return addNode(type, modifiers, null);
    }

    /** As above, plus a free-text argument for node types that need one ({@code COMMAND}'s command line - not
     *  currently reachable from {@code /ar add}, kept for parity with the type's own data). */
    public static String addNode(RouteNode.Type type, NodeModifiers modifiers, String argument) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        NodeModifiers mods = modifiers == null ? NodeModifiers.NONE : modifiers;
        if (type == null) {
            return bad("Unknown node type. Types: walk, ew, use, breaker, boom - add start / await:<n> as modifiers.");
        }
        if (!cfg.isEnabled()) {
            return bad("Auto Routes is off (cheat build + Skyblock only).");
        }
        if (player == null || client.level == null) {
            // Only reachable while the world isn't fully loaded - this used to return null here with nothing
            // said, which was indistinguishable in chat from "the command silently did nothing" (2026-09-2x
            // review, chasing exactly that report). Every other refusal in this method already said why.
            return bad("Not fully loaded into the world yet - try again in a moment.");
        }
        RouteCoords.Frame f = recording ? frame : RouteCoords.Frame.current();
        if (f == null) {
            return bad("Room not identified yet - stand in a scanned room.");
        }
        Route target = recording ? route : RouteStore.getInstance().forRoomOrCreate(f.roomName());
        if (!recording) {
            warnIfSimRotationUncertain(f.roomName(), false);
        }
        if (target.nodes().size() >= RouteStore.MAX_NODES) {
            return bad("This route already has " + RouteStore.MAX_NODES + " nodes.");
        }
        Vec3 rel = RouteCoords.toRelative(f, snappedFeet(player));
        int anchor;
        if (recording) {
            anchor = Math.max(0, target.path().size() - 1);
        } else if (target.path().isEmpty()) {
            anchor = 0;
        } else {
            anchor = target.path().nearest(0, target.path().size() - 1, rel.x, rel.y, rel.z);
        }
        RouteNode node = new RouteNode(type, rel.x, rel.y, rel.z, RouteCoords.toRelativeYaw(f, player.getYRot()),
                player.getXRot(), anchor);
        String extra = "";
        switch (type) {
            case ETHERWARP -> {
                // Same prediction the etherwarp overlay / Interactive Map use, so playback can confirm the landing.
                Vec3 eye = new Vec3(player.getX(), player.getY() + TeleportUtils.eyeHeight(true), player.getZ());
                double range = Math.max(1.0, EtherwarpHopper.range());
                TeleportUtils.RaycastResult hit = TeleportUtils.getEtherPos(eye, player.getYRot(), player.getXRot(), range);
                if (hit.succeeded() && hit.pos() != null) {
                    node.setLanding(RouteCoords.toRelative(f, new Vec3(hit.pos().getX() + 0.5, hit.pos().getY() + 1.05,
                            hit.pos().getZ() + 0.5)));
                } else {
                    extra = " (no etherwarpable block in sight - playback will only check that you moved)";
                }
            }
            case USE_ITEM -> {
                String id = ItemIdentity.of(player.getMainHandItem());
                if (id == null) {
                    return bad("Hold the item to use first.");
                }
                node.item = id;
                extra = " [" + id + "]";
            }
            case DUNGEON_BREAKER -> extra = " - now /ar edit db and right-click the blocks it should break";
            case COMMAND -> {
                String cmd = RouteStore.cleanString(argument, RouteStore.MAX_COMMAND);
                if (cmd == null) {
                    return bad("Usage: /ar add command <command>");
                }
                node.command = cmd;
                extra = " [" + cmd + "]";
            }
            default -> {
            }
        }
        // ---- modifiers: start and await apply on top of the type-specific handling above, to any node type ----
        boolean movedStart = false;
        RouteNode previousStart = null;
        if (mods.start) {
            for (RouteNode n : target.nodes()) {
                if (n.start) {
                    n.start = false;
                    movedStart = true;
                    previousStart = n;
                }
            }
            node.start = true;
        }
        if (mods.awaitEnabled) {
            node.awaitEnabled = true;
            node.awaitCondition = mods.awaitCondition;
            node.awaitAmount = mods.awaitAmount;
        }
        target.nodes().add(node);
        // /ar undo takes it back off (and puts the start flag back where it was).
        RouteHistory.added(target, node, previousStart);
        if (type == RouteNode.Type.DUNGEON_BREAKER) {
            breakerBeingBuilt = node;
        }
        boolean firing = false;
        if (!recording) {
            RouteStore.getInstance().save();
            // He is standing on the node he just placed, and it goes off now (killer560, 2026-10-04: "once I add a
            // node it performs that action immediately, so if I add an etherwarp it will instantly warp"): the
            // feature starts the route from it on the first tick the chat is closed, exactly as walking onto it
            // would. While a route is already running it only latches, as before.
            firing = AutoRoutesFeature.fireAddedNode(target, node);
            if (type == RouteNode.Type.PATH) {
                RouteNode src = target.pathSource(node);
                if (src != null) {
                    extra += " - pairs with path #" + (target.indexOf(src) + 1) + ", planning its warps";
                } else {
                    extra += " - add a second path node where it should take you";
                }
                // Planned once, now, and saved - not every time the route runs.
                RoutePathPlanner.refreshStale(target, f);
            }
        }
        if (mods.start) {
            // "setting it on a new node clears it from whatever had it (and say so in chat)" (task spec).
            extra += movedStart ? " [start - moved off the previous start node]" : " [start]";
        }
        if (mods.awaitEnabled) {
            extra += " [await " + node.awaitCondition.name().toLowerCase(Locale.ROOT) + " " + node.awaitAmount + "]";
        }
        return "Added " + type.label() + extra + " to " + f.roomName() + (firing ? " - firing it" : "");
    }

    private static String bad(String message) {
        AutoRoutesFeature.chatBad(message);
        return null;
    }
}
