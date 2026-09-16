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
import org.slf4j.LoggerFactory;

import java.util.Locale;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autoroutes");
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
        // The first sample and the START node are the block killer560 etherwarped onto before typing the command.
        sample(client, player);
        Vec3 rel = RouteCoords.toRelative(f, player.position());
        route.nodes().add(new RouteNode(RouteNode.Type.START, rel.x, rel.y, rel.z,
                RouteCoords.toRelativeYaw(f, player.getYRot()), player.getXRot(), 0));
        LOGGER.info("[AutoRoutes] Recording started in \"{}\" (replacing existing: {})", f.roomName(), existing != null);
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
        LOGGER.info("[AutoRoutes] Recording stopped in \"{}\": {} sample(s), {} node(s)", done.roomName(),
                done.path().size(), done.nodes().size());
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
        if (client.screen == null && !AutoRoutesFeature.isEditMode()) {
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

    private static RouteNode nodeAtPlayer(LocalPlayer player, int sampleIndex) {
        Vec3 rel = RouteCoords.toRelative(frame, player.position());
        return new RouteNode(RouteNode.Type.WALK, rel.x, rel.y, rel.z,
                RouteCoords.toRelativeYaw(frame, player.getYRot()), player.getXRot(), Math.max(0, sampleIndex));
    }

    // ------------------------------------------------------------------------------------------- /ar add

    /** {@code /ar add <type>}: captures from the current look / position / held item. @return chat status. */
    public static String addNode(RouteNode.Type type) {
        return addNode(type, null);
    }

    /** As {@link #addNode(RouteNode.Type)} with an argument: the command text for {@code COMMAND}, or
     *  {@code "secret [n]"} / {@code "delay <ms>"} / a bare count for {@code AWAIT}. */
    public static String addNode(RouteNode.Type type, String argument) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        if (type == null) {
            return bad("Unknown node type. Types: start, walk, ew, use, breaker, boom, await, rotate, unsneak, command");
        }
        if (!cfg.isEnabled()) {
            return bad("Auto Routes is off (cheat build + Skyblock only).");
        }
        if (player == null || client.level == null) {
            return null;
        }
        RouteCoords.Frame f = recording ? frame : RouteCoords.Frame.current();
        if (f == null) {
            return bad("Room not identified yet - stand in a scanned room.");
        }
        Route target = recording ? route : RouteStore.getInstance().forRoomOrCreate(f.roomName());
        if (target.nodes().size() >= RouteStore.MAX_NODES) {
            return bad("This route already has " + RouteStore.MAX_NODES + " nodes.");
        }
        Vec3 rel = RouteCoords.toRelative(f, player.position());
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
            case START -> target.nodes().removeIf(n -> n.type == RouteNode.Type.START);
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
            case AWAIT -> {
                parseAwait(node, argument);
                extra = " [" + node.awaitCondition.name().toLowerCase(Locale.ROOT) + " " + node.awaitAmount + "]";
            }
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
        target.nodes().add(node);
        if (type == RouteNode.Type.DUNGEON_BREAKER) {
            breakerBeingBuilt = node;
        }
        if (!recording) {
            RouteStore.getInstance().save();
        }
        return "Added " + type.label() + extra + " to " + f.roomName();
    }

    private static void parseAwait(RouteNode node, String argument) {
        node.awaitCondition = RouteNode.AwaitCondition.SECRET;
        node.awaitAmount = 1;
        if (argument == null || argument.isBlank()) {
            return;
        }
        String[] parts = argument.trim().toLowerCase(Locale.ROOT).split("\\s+");
        int numberAt = 0;
        if (parts[0].startsWith("delay") || parts[0].startsWith("ms")) {
            node.awaitCondition = RouteNode.AwaitCondition.DELAY;
            node.awaitAmount = 500;
            numberAt = 1;
        } else if (parts[0].startsWith("secret") || parts[0].startsWith("bat")) {
            numberAt = 1;
        }
        if (parts.length > numberAt) {
            try {
                node.awaitAmount = Math.max(0, Math.min(600_000, Integer.parseInt(parts[numberAt])));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static String bad(String message) {
        AutoRoutesFeature.chatBad(message);
        return null;
    }
}
