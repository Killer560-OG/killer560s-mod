package com.killer560.hub.zpew;

import com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature;
import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Zero Ping Etherwarp (both builds since 2026-10-26, being camera only; killer560, 2026-10-25: "0 ping etherwarping", and "I want 0 ping etherwarp to
 * work with multiple etherwarps per tick"). The moment an etherwarp is SENT, the camera is drawn from where it will land,
 * instead of waiting a round trip for the server's teleport. RSM's 1.21 "Zpew" works the same way, camera only.
 *
 * <p><b>Purely visual.</b> Nothing here writes his position, velocity or rotation, and nothing is sent: the player stays
 * where the server has him until the server's own position packet moves him there (the mod's rule: never setPos). The
 * only write is the camera position at the end of {@code Camera.alignWithEntity} ({@code mixin/ZpewCameraMixin}), in
 * first person. Aiming is unaffected by where the camera is drawn (rotation is the same everywhere), and the server
 * casts the next warp from where it has already put him, so a warp aimed from the predicted landing lands where the
 * prediction says - which is what makes chaining work at zero ping.
 *
 * <p><b>Prediction.</b> Every {@code ServerboundUseItemPacket} with an etherwarp item (ethermerge or Etherwarp Conduit)
 * while the server has him sneaking (the last input packet sent) is predicted with the same voxel walk Auto Routes aims
 * with ({@link TeleportUtils#getEtherPos}), from the sneaking eye along the packet's own rotation, 57 + tuners blocks.
 * A use sent while earlier ones are still pending - in a later tick or the same one - is cast from the last pending
 * landing (Chain From Prediction). A use with no landing predicts nothing.
 *
 * <p><b>The server's answer.</b> Each server position packet is matched against the oldest pending prediction: within
 * {@link #MATCH_BLOCKS} it is a hit (logged with the error and the round trip); anything else is a miss or a correction,
 * and every pending prediction is dropped - the camera is where he really is on the next frame. A prediction with no
 * answer after the timeout is dropped the same way.
 *
 * <p><b>Works For</b> (2026-10-27): every sender of an etherwarp marks its use with {@link #sending} around the send, and
 * a use is predicted only when its {@link ZpewSource}'s switch is on (an unmarked use is {@link ZpewSource#MANUAL}). The
 * master switch still turns all of it off.
 *
 * <p>Everything is logged (logger killer560smod-zpew): every use seen and why it was or was not predicted, every
 * prediction, every server answer with its error and latency, every drop.
 */
public final class ZeroPingEtherwarp {

    private static final Logger LOGGER = ModLog.get("killer560smod-zpew");

    /** A server teleport within this of the predicted landing is the warp we predicted. */
    public static final double MATCH_BLOCKS = 1.0;

    private record Prediction(long sentMs, long sentTick, Vec3 from, Vec3 landing, BlockPos block, float yaw, float pitch,
                              ZpewSource source) {
    }

    /** The sender marking the use packets it sends right now (render thread only), or null: the player's own click. */
    private static ZpewSource current;
    /** Per source: uses seen with an etherwarp item, predictions made, server hits, and uses skipped by Works For. */
    private static final long[] SEEN = new long[ZpewSource.values().length];
    private static final long[] PREDICTED = new long[ZpewSource.values().length];
    private static final long[] HIT = new long[ZpewSource.values().length];
    private static final long[] SKIPPED = new long[ZpewSource.values().length];

    private static final ArrayDeque<Prediction> PENDING = new ArrayDeque<>();
    private static long clientTick;

    /** The landing a server packet was just matched to, so Smooth Teleport (whose hook may run first or after) can ask. */
    private static volatile Vec3 lastMatchedLanding;
    private static volatile long lastMatchedNanos;

    // counters, for the testkit and the log
    private static long usesSeen;
    private static long predictions;
    private static long hits;
    private static long misses;
    private static long timeouts;
    private static long noLanding;
    private static long cameraFrames;
    private static double lastErrorBlocks = -1;
    private static long lastLatencyMs = -1;
    private static int maxChained;

    private ZeroPingEtherwarp() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            clientTick++;
            expire();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            synchronized (PENDING) {
                PENDING.clear();
            }
        });
    }

    private static boolean on() {
        return ZeroPingEtherwarpConfig.getInstance().isEnabled();
    }

    /**
     * Marks every use packet sent until {@link #done} as {@code source}'s. Call it around the send, in a try/finally:
     * {@code ZpewSource prev = ZeroPingEtherwarp.sending(ZpewSource.AUTO_ROUTES); try { ...send... } finally {
     * ZeroPingEtherwarp.done(prev); }}. Nests (the previous marker comes back).
     */
    public static ZpewSource sending(ZpewSource source) {
        ZpewSource prev = current;
        current = source;
        return prev;
    }

    public static void done(ZpewSource previous) {
        current = previous;
    }

    // ---- outbound ---------------------------------------------------------------------------------------------

    /** {@code mixin/ZpewSendMixin}: every packet this client makes, read-only, render thread. */
    public static void onPacketSent(Packet<?> packet) {
        if (!on()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !mc.isSameThread()) {
            return;
        }
        if (packet instanceof ServerboundUseItemOnPacket useOn) {
            ItemStack held = player.getItemInHand(useOn.getHand());
            if (etherwarpRange(held) > 0) {
                LOGGER.info("[ZPEW] use-on-block {} with an etherwarp item (not predicted: the use packet that follows is)",
                        useOn.getHitResult().getBlockPos().toShortString());
            }
            return;
        }
        if (!(packet instanceof ServerboundUseItemPacket use)) {
            return;
        }
        ItemStack held = player.getItemInHand(use.getHand());
        double range = etherwarpRange(held);
        if (range <= 0) {
            return;
        }
        usesSeen++;
        ZpewSource source = current == null ? ZpewSource.MANUAL : current;
        SEEN[source.ordinal()]++;
        if (!ZeroPingEtherwarpConfig.getInstance().worksFor(source)) {
            SKIPPED[source.ordinal()]++;
            LOGGER.info("[ZPEW] etherwarp use by {} - not predicted (Works For: {} is off)", source.label(), source.label());
            return;
        }
        boolean sneaking = player.getLastSentInput().shift();
        if (!sneaking) {
            LOGGER.info("[ZPEW] etherwarp item used without the server having sneak (Instant Transmission) - not predicted");
            return;
        }
        Prediction last;
        synchronized (PENDING) {
            last = PENDING.peekLast();
        }
        boolean chained = last != null && ZeroPingEtherwarpConfig.getInstance().isChain();
        Vec3 from = chained ? last.landing() : player.position();
        Vec3 eye = new Vec3(from.x, from.y + TeleportUtils.eyeHeight(true), from.z);
        float yaw = use.getYRot();
        float pitch = Mth.clamp(use.getXRot(), -90f, 90f);
        TeleportUtils.RaycastResult shot = TeleportUtils.getEtherPos(eye, yaw, pitch, range);
        if (shot == null || !shot.succeeded() || shot.pos() == null) {
            noLanding++;
            LOGGER.info(String.format(Locale.ROOT, "[ZPEW] use from %s%s yaw %.2f pitch %.2f range %.0f: no landing - not predicted",
                    fmt(from), chained ? " (chained)" : "", Mth.wrapDegrees(yaw), pitch, range));
            return;
        }
        double standY = EtherwarpOverlayFeature.standYOn(mc.level, shot.pos());
        Vec3 landing = new Vec3(shot.pos().getX() + 0.5, standY + 0.05, shot.pos().getZ() + 0.5);
        int depth;
        synchronized (PENDING) {
            PENDING.addLast(new Prediction(System.currentTimeMillis(), clientTick, from, landing, shot.pos(), yaw, pitch, source));
            depth = PENDING.size();
        }
        predictions++;
        PREDICTED[source.ordinal()]++;
        maxChained = Math.max(maxChained, depth);
        LOGGER.info(String.format(Locale.ROOT, "[ZPEW] predicted #%d (%s): from %s%s yaw %.2f pitch %.2f -> block %s, land %s "
                        + "(%.1f blocks); %d pending, tick %d", predictions, source.label(), fmt(from), chained ? " (chained)" : "",
                Mth.wrapDegrees(yaw), pitch, shot.pos().toShortString(), fmt(landing), from.distanceTo(landing), depth,
                clientTick));
    }

    /** 57 + tuners for an etherwarp-capable item, 0 for anything else. */
    static double etherwarpRange(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return 0;
        }
        CompoundTag tag = data.copyTag();
        String id = tag.getStringOr("id", "");
        if (tag.getIntOr("ethermerge", 0) != 1 && !"ETHERWARP_CONDUIT".equals(id)) {
            return 0;
        }
        int tuners = Math.max(0, Math.min(4, tag.getIntOr("tuned_transmission", 0)));
        return 57 + tuners;
    }

    // ---- inbound ----------------------------------------------------------------------------------------------

    /** {@code mixin/ZpewPositionMixin}: a server position packet, render thread, before vanilla applies it. */
    public static void onServerPosition(Vec3 from, Vec3 to) {
        Prediction head;
        int left;
        synchronized (PENDING) {
            head = PENDING.peekFirst();
            if (head == null) {
                return;
            }
            double err = to == null ? Double.MAX_VALUE : head.landing().distanceTo(to);
            long latency = System.currentTimeMillis() - head.sentMs();
            if (err <= MATCH_BLOCKS) {
                PENDING.removeFirst();
                left = PENDING.size();
                hits++;
                HIT[head.source().ordinal()]++;
                lastErrorBlocks = err;
                lastLatencyMs = latency;
                lastMatchedLanding = to;
                lastMatchedNanos = System.nanoTime();
                LOGGER.info(String.format(Locale.ROOT, "[ZPEW] server landed him at %s, predicted %s: hit, %.3f blocks off, "
                        + "%d ms after the use, %d tick(s); %d still pending", fmt(to), fmt(head.landing()), err, latency,
                        clientTick - head.sentTick(), left));
                return;
            }
            misses++;
            int dropped = PENDING.size();
            PENDING.clear();
            LOGGER.warn(String.format(Locale.ROOT, "[ZPEW] server put him at %s, predicted %s (%.2f blocks off, %d ms after the "
                            + "use): not the warp we predicted - %d prediction(s) dropped, camera back on him",
                    to == null ? "-" : fmt(to), fmt(head.landing()), err == Double.MAX_VALUE ? -1 : err, latency, dropped));
        }
    }

    /** True when this server teleport is (or was just) a predicted warp, so Smooth Teleport does not glide it. */
    public static boolean claims(Vec3 to) {
        if (!on() || to == null) {
            return false;
        }
        synchronized (PENDING) {
            Prediction head = PENDING.peekFirst();
            if (head != null && head.landing().distanceTo(to) <= MATCH_BLOCKS) {
                return true;
            }
        }
        Vec3 m = lastMatchedLanding;
        return m != null && System.nanoTime() - lastMatchedNanos < 200_000_000L && m.distanceTo(to) <= 0.01;
    }

    private static void expire() {
        long now = System.currentTimeMillis();
        int timeout = ZeroPingEtherwarpConfig.getInstance().getTimeoutMs();
        synchronized (PENDING) {
            Prediction head = PENDING.peekFirst();
            if (head != null && (now - head.sentMs() > timeout || !on())) {
                int dropped = PENDING.size();
                PENDING.clear();
                if (on()) {
                    timeouts++;
                    LOGGER.warn("[ZPEW] no teleport {} ms after the use predicted to land at {} - {} prediction(s) dropped, "
                            + "camera back on him", now - head.sentMs(), fmt(head.landing()), dropped);
                }
            }
        }
    }

    // ---- the camera (render thread, every frame) --------------------------------------------------------------

    /** {@code mixin/ZpewCameraMixin}: where to draw from this frame, or {@code vanilla} when nothing is pending. */
    public static Vec3 cameraPosition(Entity cameraEntity, boolean detached, Vec3 vanilla) {
        if (!on() || detached) {
            return vanilla;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || cameraEntity != player) {
            return vanilla;
        }
        Prediction last;
        synchronized (PENDING) {
            last = PENDING.peekLast();
        }
        if (last == null) {
            return vanilla;
        }
        cameraFrames++;
        // The eye at the predicted landing, at his current eye height (sneaking or not).
        Vec3 landing = last.landing();
        return new Vec3(landing.x, landing.y + player.getEyeHeight(), landing.z);
    }

    /** Where the player will be once every pending warp lands, or null with nothing pending (for the overlay). */
    public static Vec3 predictedFeet() {
        if (!on() || !ZeroPingEtherwarpConfig.getInstance().isOverlayFromPrediction()) {
            return null;
        }
        synchronized (PENDING) {
            Prediction last = PENDING.peekLast();
            return last == null ? null : last.landing();
        }
    }

    // ---- state ------------------------------------------------------------------------------------------------

    public static int pending() {
        synchronized (PENDING) {
            return PENDING.size();
        }
    }

    public static long usesSeen() {
        return usesSeen;
    }

    public static long predictions() {
        return predictions;
    }

    public static long hits() {
        return hits;
    }

    public static long misses() {
        return misses;
    }

    public static long timeouts() {
        return timeouts;
    }

    public static long noLanding() {
        return noLanding;
    }

    public static long cameraFrames() {
        return cameraFrames;
    }

    public static double lastErrorBlocks() {
        return lastErrorBlocks;
    }

    public static long lastLatencyMs() {
        return lastLatencyMs;
    }

    /** Per-source counters by {@link ZpewSource} name, for the testkit: uses seen, predicted, hit, skipped by Works For. */
    public static long seenBy(String source) {
        return SEEN[ZpewSource.valueOf(source).ordinal()];
    }

    public static long predictedBy(String source) {
        return PREDICTED[ZpewSource.valueOf(source).ordinal()];
    }

    public static long hitsBy(String source) {
        return HIT[ZpewSource.valueOf(source).ordinal()];
    }

    public static long skippedBy(String source) {
        return SKIPPED[ZpewSource.valueOf(source).ordinal()];
    }

    public static int maxChained() {
        return maxChained;
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}
