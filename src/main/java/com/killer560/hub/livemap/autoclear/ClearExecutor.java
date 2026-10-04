package com.killer560.hub.livemap.autoclear;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapConfig;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Port of QUOI's {@code ClearExecutor} (+ the one-swap-per-tick guard of {@code SwapManager} and
 * {@code PlayerUtils.useItem(yaw, pitch)}): runs a list of {@link ClearNode}s.
 * <ul>
 * <li>Client tick start: send the queued rotated item use, tick the delays, then execute whichever node the predicted
 * position sits on (one hop per tick, positions predicted from the raycast, not waited on).
 * <li>While an etherwarp node is current or next, sneak is forced (QUOI {@code KeyEvent.Input} shift = true) through
 * {@code livemap.mixin.LiveMapKeyboardInputMixin}.
 * <li>After the last node: wait for the server's position packet ({@code LiveMapPacketListenerMixin}), then ~8 more
 * ticks, then run the completion callback (e.g. "Face door on arrival").
 * </ul>
 * The item use sends the target yaw as an equivalent of the player's current running yaw (never wrapped to 0-360),
 * see the Rotation 360 rule.
 */
public final class ClearExecutor {

    static final String CHAT = "Interactive Map";
    private static final Logger LOGGER = ModLog.get("killer560smod-interactivemap");
    private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-etherplanner");
        t.setDaemon(true);
        return t;
    });

    private static List<ClearNode> nodes = null;
    private static int syncDelay = 0;
    private static int compDelay = 0;
    private static int globalDelay = 0;
    private static int hypeDelay = 0;
    private static boolean active = false;
    private static float[] pendingInteract = null;
    private static double[] position = null;
    private static ClearNode activeNode = null;
    private static Runnable onComplete = null;
    private static Runnable pendingCompletion = null;
    private static volatile boolean positionPacketSeen = false;
    private static int syncWaitTicks = 0;
    private static volatile boolean pathPending = false;
    private static volatile boolean lastPathFailed = false;
    private static int generation = 0;
    private static boolean hasSwappedThisTick = false;
    private static Object lastLevel = null;
    private static volatile boolean sneakMixinApplied = false;
    private static boolean forcedSneakKey = false;
    // 2026-09-15: set while another feature (Pathfinding's Auto Fairy Souls) owns the queue, so its hops are not
    // cancelled just because the Interactive Map's own "Teleport Pathing"/"Auto Blood Rush" toggles are off.
    // Nothing else changes: when it is false this class behaves exactly as before.
    private static volatile boolean externalOwner = false;

    private ClearExecutor() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("ClearExecutor.onTickStart", ClearExecutor::onTickStart));
        // START: this sends the interactions at the end of a walk leg. Named onTickEnd from when it ran at
        // the end of the tick; the name is left alone so every reference to it keeps working.
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("ClearExecutor.onTickEnd", ClearExecutor::onTickEnd));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(ctx -> {
            List<ClearNode> current = nodes;
            if (current == null || current.isEmpty()) {
                return;
            }
            for (ClearNode node : new ArrayList<>(current)) {
                node.render(ctx);
            }
        });
    }

    // ------------------------------------------------------------------------------------------- public API

    public static boolean isActive() {
        return active;
    }

    /** True while a path is being searched, queued, executed or waiting for its completion sync. */
    public static boolean isBusy() {
        return pathPending || (nodes != null && !nodes.isEmpty()) || syncDelay != 0 || pendingCompletion != null;
    }

    public static boolean lastPathFailed() {
        return lastPathFailed;
    }

    public static EtherwarpPathfinder.PathConfig pathConfig() {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        return new EtherwarpPathfinder.PathConfig(cfg.getYawStep(), cfg.getPitchStep(), cfg.getHWeight(),
                cfg.getTimeoutMs());
    }

    /** killer560: "make it so ... it shows a small circle at each etherwarp spot ... and a line from one spot
     *  to another" - the world positions of the currently queued hops, in order, for
     *  {@link com.killer560.hub.livemap.MapPainter} to draw on the Interactive Map. Snapshotted (not the live
     *  list) since it is read from the render thread while {@link #nodes} can be mutated by the tick handler. */
    public static List<Vec3> plannedHopPositions() {
        List<ClearNode> current = nodes;
        if (current == null || current.isEmpty()) {
            return List.of();
        }
        List<Vec3> out = new ArrayList<>(current.size());
        for (ClearNode node : current) {
            out.add(node.pos);
        }
        return out;
    }

    /**
     * The planner's hop range: the held etherwarp item's own reach (57 plus one per Transmission Tuner, read the
     * way {@link com.killer560.hub.pathfinding.EtherwarpHopper#range} reads it) less one block of margin, and never
     * more than 60. It used to be 60 for everyone, which is 61 - 1 for a fully tuned item; with fewer tuners a
     * 58-60 block hop was planned that Hypixel refuses, and the floor-wide planner reaches for long hops far more
     * often than the room-by-room one did. The sim's server applies the same 57 + tuners rule.
     */
    public static double hopRange() {
        double item = com.killer560.hub.pathfinding.EtherwarpHopper.range();
        return item > 0 ? Math.min(60.0, item - 1.0) : 56.0;
    }

    /** QUOI {@code etherPath}: search on a background thread, then run the smoothed path. */
    public static void etherPath(BlockPos to, Runnable complete) {
        etherPath(to, -1, complete);
    }

    /**
     * A map click on a tile: fewest warps to ANY landing in that tile at its floor height (see
     * {@link EtherwarpPathfinder#findDungeonPathToTile}); {@code to} is the tile's own block, used if none of
     * them can be reached.
     */
    public static void etherPathToTile(BlockPos to, int tileIdx, Runnable complete) {
        etherPath(to, tileIdx, complete);
    }

    private static void etherPath(BlockPos to, int tileIdx, Runnable complete) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        if (BlockPos.containing(player.position()).equals(to)) {
            if (complete != null) {
                complete.run();
            }
            ModChat.send(CHAT, ModChat.text("Already there"));
            return;
        }
        if (pathPending) {
            return;
        }
        Vec3 from = player.position();
        DungeonLayout layout = DungeonLayout.capture();
        EtherwarpPathfinder.PathConfig cfg = pathConfig();
        // The planner's hop range. One number in the sim and on Hypixel: the sim's server gives an etherwarp
        // 57 blocks plus one per Transmission Tuner, as Hypixel does - see hopRange().
        double hopRange = hopRange();
        int gen = generation;
        pathPending = true;
        lastPathFailed = false;
        PLANNER.submit(() -> {
            long start = System.currentTimeMillis();
            List<EtherwarpPathfinder.Node> path = null;
            try {
                // Landings are planned at block top + 1.05, QUOI's figure for Hypixel - and the sim's server now
                // lands an etherwarp there too (SimAbilities.ETHERWARP_LANDING_OFFSET), so there is one value.
                path = tileIdx >= 0
                        ? EtherwarpPathfinder.findDungeonPathToTile(from, to, tileIdx, cfg, hopRange, layout)
                        : EtherwarpPathfinder.findDungeonPath(from, to, cfg, hopRange, layout);
            } catch (RuntimeException e) {
                LOGGER.warn("[InteractiveMap] Path search failed: {}", e.toString());
            }
            long took = System.currentTimeMillis() - start;
            List<EtherwarpPathfinder.Node> result = path;
            client.execute(() -> {
                pathPending = false;
                if (gen != generation) {
                    return;
                }
                if (result != null && result.isEmpty()) {
                    // Already standing where the click asked for (in the clicked tile, or on the block).
                    ModChat.send(CHAT, ModChat.text("Already there"));
                    if (complete != null) {
                        complete.run();
                    }
                    return;
                }
                if (result == null) {
                    lastPathFailed = true;
                    ModChat.send(CHAT, ModChat.bad("Failed"), ModChat.dim(" after "), ModChat.value(took + "ms"));
                    return;
                }
                List<ClearNode> list = new ArrayList<>();
                for (EtherwarpPathfinder.Node n : result) {
                    list.add(ClearNode.toEther(n));
                }
                ModChat.send(CHAT, ModChat.text("Found path in "), ModChat.value(took + "ms"), ModChat.dim(" ("
                        + result.size() + " warps)"));
                clearPath(list, complete);
            });
        });
    }

    public static void clearPath(List<ClearNode> path, Runnable complete) {
        nodes = new ArrayList<>(path);
        position = null;
        pendingInteract = null;
        onComplete = complete;
        pendingCompletion = null;
    }

    /** Lets another feature run its own hop queue here while the Interactive Map's own toggles are off. */
    public static void setExternalOwner(boolean value) {
        externalOwner = value;
    }

    public static void queueInteract(float yaw, float pitch) {
        pendingInteract = new float[]{yaw, pitch};
    }

    public static void cancel() {
        nodes = null;
        position = null;
        compDelay = 2;
        onComplete = null;
        pendingCompletion = null;
        // The arrival sync belongs to the path being cancelled, so drop it too. Left running it kept
        // isBusy() true for up to 49 more ticks (the 40-tick position-packet wait plus the 9-tick settle)
        // with no completion callback left to run - long enough that the Interactive Map's retarget
        // (killer560, 2026-09-29: "If I click a different room mid path...") would look like it did nothing.
        syncDelay = 0;
        syncWaitTicks = 0;
        generation++;
    }

    /** Called from the position-packet mixin (network and client thread). */
    public static void onServerPositionPacket() {
        positionPacketSeen = true;
    }

    /** Called from the keyboard-input mixin: QUOI forces shift while an etherwarp node is current or next. */
    public static boolean shouldForceSneak() {
        sneakMixinApplied = true;
        return forceSneakNow();
    }

    private static boolean forceSneakNow() {
        if (!active) {
            return false;
        }
        List<ClearNode> current = nodes;
        return activeNode instanceof ClearNode.EtherNode
                || (current != null && !current.isEmpty() && current.get(0) instanceof ClearNode.EtherNode);
    }

    // ------------------------------------------------------------------------------------------- ticking

    private static void onTickStart(Minecraft client) {
        hasSwappedThisTick = false;
        if (client.level != lastLevel) {
            lastLevel = client.level;
            reset();
        }
        if (client.player == null) {
            return;
        }
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        // killer560: "the entire portion of interactive map is the teleport pathing" - there is no separate
        // pathing toggle any more, so Interactive Map itself being on is what keeps a queued path alive.
        if (!externalOwner && !cfg.isInteractiveMapEnabled() && !cfg.isBloodRushEnabled() && (nodes != null || pathPending)) {
            cancel();
        }
        // Keep the floor-wide etherwarp graph warm while he is in a dungeon, so a click only has to search it.
        if (cfg.isInteractiveMapEnabled() && client.level != null && DungeonLayout.current().roomCount() > 0) {
            EtherwarpPathfinder.tickWarm(PLANNER, () -> pathPending, hopRange());
        }
        doInteract(client);
        updateDelays();
        applySneakFallback(client);
        if (!canNext()) {
            return;
        }
        if (position == null) {
            Vec3 p = client.player.position();
            position = new double[]{p.x, p.y, p.z};
        }
        handleQueue(position, nodes);
    }

    /** QUOI's server-tick handler, on client ticks: after the position packet, wait ~8 ticks, then complete. */
    private static void onTickEnd(Minecraft client) {
        // Position packet seen (or, if the packet mixin is missing / the server sent none, after 2s anyway).
        if (syncDelay == 1 && (positionPacketSeen || ++syncWaitTicks > 40)) {
            syncDelay = 2;
            syncWaitTicks = 0;
        }
        if (syncDelay < 2) {
            return;
        }
        if (syncDelay++ > 9) {
            syncDelay = 0;
            Runnable callback = pendingCompletion;
            pendingCompletion = null;
            if (callback != null) {
                callback.run();
            }
        }
    }

    private static void reset() {
        nodes = null;
        syncDelay = 0;
        compDelay = 0;
        globalDelay = 0;
        hypeDelay = 0;
        position = null;
        active = false;
        activeNode = null;
        onComplete = null;
        pendingCompletion = null;
        pendingInteract = null;
        // A world change drops the queue and its completion callback, so no external feature owns it any more.
        // (Already false whenever nothing external is running, so this changes nothing for the Interactive Map.)
        externalOwner = false;
        generation++;
    }

    private static void handleQueue(double[] playerPos, List<ClearNode> clearNodes) {
        ClearNode node = null;
        for (ClearNode n : clearNodes) {
            if (n.inside(playerPos) && (node == null || n.priority() > node.priority())) {
                node = n;
            }
        }
        if (node == null) {
            position = null;
            return;
        }
        active = true;
        activeNode = node;
        if (node instanceof ClearNode.HypeNode && hypeDelay > 0) {
            return;
        }
        if (node.execute(playerPos)) {
            if (nodes == null) {
                return; // cancelled inside execute
            }
            clearNodes.remove(node);
            if (node instanceof ClearNode.HypeNode) {
                hypeDelay = 3;
            }
            if (clearNodes.isEmpty()) {
                nodes = null;
                position = null;
                compDelay = 2;
                pendingCompletion = onComplete;
                onComplete = null;
                positionPacketSeen = false;
                syncWaitTicks = 0;
                syncDelay = 1;
            }
        }
    }

    private static void doInteract(Minecraft client) {
        float[] interact = pendingInteract;
        pendingInteract = null;
        if (interact == null || client.player == null || client.level == null || client.gameMode == null) {
            return;
        }
        LocalPlayer player = client.player;
        // Same direction as the target, expressed relative to the running (unwrapped) yaw.
        float yaw = player.getYRot() + Mth.wrapDegrees(interact[0] - player.getYRot());
        float pitch = Mth.clamp(interact[1], -90f, 90f);
        // The same packet in the dungeon sim: its integrated server answers a use packet the way Hypixel's does
        // (roomsim.SimAbilities), resolving the hop from its own copy of him, so hops chain from the prediction
        // one a tick there too.
        if (client.gameMode instanceof MultiPlayerGameModeInvoker invoker) {
            invoker.killer560smod$invokeStartPrediction(client.level,
                    sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, yaw, pitch));
        } else {
            float oldYaw = player.getYRot();
            float oldPitch = player.getXRot();
            player.setYRot(yaw);
            player.setXRot(pitch);
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }

    private static void updateDelays() {
        if (compDelay > 0 && --compDelay == 0) {
            active = false;
            activeNode = null;
        }
        if (globalDelay > 0) {
            globalDelay--;
        }
        if (hypeDelay > 0) {
            hypeDelay--;
        }
    }

    private static boolean canNext() {
        if (syncDelay != 0 || globalDelay > 0 || nodes == null || nodes.isEmpty()) {
            return false;
        }
        if (compDelay == 0) {
            active = false;
        }
        return true;
    }

    /** Without the keyboard-input mixin (mixin config not loaded) hold the sneak key mapping instead. */
    private static void applySneakFallback(Minecraft client) {
        if (sneakMixinApplied) {
            return;
        }
        boolean want = forceSneakNow() || (nodes != null && !nodes.isEmpty() && nodes.get(0) instanceof ClearNode.EtherNode);
        if (want) {
            client.options.keyShift.setDown(true);
            forcedSneakKey = true;
        } else if (forcedSneakKey) {
            forcedSneakKey = false;
            client.options.keyShift.setDown(false);
        }
    }

    // ------------------------------------------------------------------------------------------- items

    static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.getStringOr("id", null);
    }

    static boolean holdingAny(String[] ids) {
        LocalPlayer player = Minecraft.getInstance().player;
        String id = player == null ? null : skyblockId(player.getMainHandItem());
        if (id == null) {
            return false;
        }
        for (String s : ids) {
            if (s.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    /** QUOI {@code SwapManager.swapById}: hotbar only, at most one swap per tick. */
    static boolean swapById(String[] ids) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) {
            return false;
        }
        for (int i = 0; i <= 8; i++) {
            String id = skyblockId(player.getInventory().getItem(i));
            if (id == null) {
                continue;
            }
            for (String s : ids) {
                if (s.equalsIgnoreCase(id)) {
                    if (player.getInventory().getSelectedSlot() == i) {
                        return true;
                    }
                    if (hasSwappedThisTick) {
                        return false;
                    }
                    player.getInventory().setSelectedSlot(i);
                    player.connection.send(new ServerboundSetCarriedItemPacket(i));
                    hasSwappedThisTick = true;
                    return true;
                }
            }
        }
        ModChat.send(CHAT, ModChat.bad("Could not find "), ModChat.value(String.join(", ", ids)));
        return false;
    }
}
