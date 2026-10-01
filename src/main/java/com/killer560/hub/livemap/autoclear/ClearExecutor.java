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

    /** QUOI {@code etherPath}: search on a background thread, then run the smoothed path. */
    public static void etherPath(BlockPos to, Runnable complete) {
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
        // THE RANGE THE HOP WILL ACTUALLY HAVE. killer560 (2026-10-01): "if it is far away then it fails."
        // The planner has always used 60, but Hypixel's etherwarp is 57 (see CLAUDE.md on EtherwarpHopper)
        // and the sim's SimAbilities enforces exactly that - so a hop planned at 58-60 blocks was accepted by
        // the search and refused by the ability, leaving the queue stuck on a node that could never fire. The
        // real game is left on the number it has always used; only the sim plans to its own limit.
        double hopRange = com.killer560.hub.roomsim.SimState.isActive()
                ? com.killer560.hub.roomsim.SimAbilities.etherwarpRange() : 60.0;
        int gen = generation;
        pathPending = true;
        lastPathFailed = false;
        PLANNER.submit(() -> {
            long start = System.currentTimeMillis();
            List<EtherwarpPathfinder.Node> path = null;
            try {
                path = EtherwarpPathfinder.findDungeonPath(from, to, cfg, hopRange, true, layout);
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
                if (result == null || result.isEmpty()) {
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
            // IN THE SIM, WAIT TO LAND. doTeleport has just moved the cached position to the predicted landing
            // so the next hop can fire next tick - zero-ping chaining, which works on Hypixel because the aim
            // travels inside the packet. The sim's etherwarp resolves from where the client player actually IS,
            // and the teleport has not arrived yet, so every chained hop missed ("no etherwarp target there" in
            // bursts in his 2026-10-01 log) and the path stalled partway: "the interactive map etherwarp gets stuck
            // alot partway through". Dropping the cache makes the next tick read his real position, and the next
            // node is only inside that once he has landed.
            if (com.killer560.hub.roomsim.SimState.isActive()) {
                position = null;
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
        // THE SIM HAS NO SERVER-SIDE ETHERWARP, so a raw use PACKET teleports nobody.
        //
        // killer560 (2026-10-01): "Etherwarp is now saying found path but not actually etherwarping." The
        // path was fine; the hop was not. startPrediction below builds a ServerboundUseItemPacket and hands
        // it to the connection - on Hypixel that IS the ability, because Hypixel's server implements it. The
        // sim's ability lives in SimAbilities behind Fabric's UseItemCallback, and nothing on the integrated
        // server turns an inbound use packet into one. So the hop was sent, accepted and did nothing.
        //
        // gameMode.useItem is the client-side path that callback is injected into, and it is already the
        // fallback branch below for a missing mixin - so the sim takes that branch deliberately rather than
        // getting a third copy of it. AutoPuzzleUtil.useItemRotated fires its shots the same way, which is
        // why the puzzle autos worked in here when this did not.
        boolean sim = com.killer560.hub.roomsim.SimState.isActive();
        if (!sim && client.gameMode instanceof MultiPlayerGameModeInvoker invoker) {
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
