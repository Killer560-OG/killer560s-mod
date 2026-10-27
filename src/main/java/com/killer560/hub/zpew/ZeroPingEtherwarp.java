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
import net.minecraft.world.InteractionHand;
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
 * <p><b>Warps Per Tick</b> (cheat build only, 2026-10-27; killer560: "did you find a way to get multiple ether warps done in a
 * single tick?"): the one thing here that SENDS. With it above 1, each of his own predicted warps (right click or Left
 * Click Etherwarp) is followed in the same tick by N - 1 more use packets at the same look, each cast by the server from
 * the last one's landing ({@link #flushExtraWarps}). Default 1: nothing extra, and in a legit build always 1.
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

    // ---- Warps Per Tick (cheat build) ----
    /** Extra warps the last predicted use of his owes, sent when its {@code useItem} returns (same tick only). */
    private static int owed;
    private static InteractionHand owedHand;
    private static float owedYaw;
    private static float owedPitch;
    private static long owedTick = -1;
    /** True while {@link #flushExtraWarps} sends: the tap chains them from the last landing and owes nothing for them. */
    private static boolean extraSending;
    private static long extraSent;
    private static long extraStopped;
    /** Etherwarp uses seen in the current client tick, and the most ever seen in one tick. */
    private static long usesTick = -1;
    private static int usesThisTick;
    private static int maxUsesInOneTick;

    // ---- Aimed Warps (cheat build) ----
    /** A press of the use key waiting for this tick, with the look he had when it was pressed. */
    private record ClickLook(float yaw, float pitch) {
    }
    /** One per waiting press of the use key (its clickCount), oldest first. Render thread. */
    private static final java.util.ArrayList<ClickLook> CLICKS = new java.util.ArrayList<>();
    /** The body turns of a burst, the camera held still meanwhile (one owner, like Auto Routes' own). */
    private static final com.killer560.hub.util.BodyAim BODY = new com.killer560.hub.util.BodyAim(() -> {
    });
    private static long aimedBursts;
    private static long aimedWarpsSent;
    private static long aimedDropped;
    private static long aimedUnfunded;
    private static int lastAimedBurst;

    private ZeroPingEtherwarp() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
                return;
            }
            try {
                BODY.tick(client.player, false);
                pollClicks();
                aimedPending(client);
                if (pendingLooks == null) {
                    aimedBurst(client);
                }
            } catch (RuntimeException e) {
                LOGGER.error("[ZPEW] Aimed Warps threw - the clicks go to vanilla", e);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            pollClicks();
            clientTick++;
            owed = 0; // owed warps never go in a later tick
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
        if (usesTick != clientTick) {
            usesTick = clientTick;
            usesThisTick = 0;
        }
        usesThisTick++;
        maxUsesInOneTick = Math.max(maxUsesInOneTick, usesThisTick);
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
        // An extra warp of Warps Per Tick is always cast from the one before it: that is where the server will cast it from.
        boolean chained = last != null && (ZeroPingEtherwarpConfig.getInstance().isChain() || extraSending);
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
        // Warps Per Tick: his own warp (a right click or Left Click Etherwarp) owes N - 1 more, this tick, at this look.
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && !extraSending
                && (source == ZpewSource.MANUAL || source == ZpewSource.LEFT_CLICK)) {
            int n = ZeroPingEtherwarpConfig.getInstance().effectiveWarpsPerTick();
            if (n > 1) {
                owed = n - 1;
                owedHand = use.getHand();
                owedYaw = use.getYRot();
                owedPitch = use.getXRot();
                owedTick = clientTick;
            }
        }
    }

    /**
     * Warps Per Tick (cheat build; {@code mixin/ZpewUseItemMixin}, when his use's {@code useItem} returns): sends the extra
     * warps his last use owes, right now, in the same client tick - each a {@code use_item} with the SAME yaw and pitch as
     * his own, through vanilla's sequenced send. The server casts each from where the one before put him, so they walk on
     * along his look. Same look on purpose: GrimAC BadPacketsJ (javap 2.3.74) flags a second use in a tick whose rotation
     * differs from the first, and checks every use's rotation against the tick's one movement packet - which a vanilla
     * client also sends after two clicks in one tick ({@code Minecraft.handleKeybinds}: {@code while (keyUse.consumeClick())
     * startUseItem()}). Each extra is cast here first from the last predicted landing; one that would land nowhere is not
     * sent and ends the burst (the server would do nothing with it but spend his mana).
     */
    public static void flushExtraWarps() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED || owed <= 0 || extraSending) {
            return;
        }
        int n = owed;
        owed = 0;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (owedTick != clientTick || !on() || player == null || mc.level == null || !mc.isSameThread()
                || !(mc.gameMode instanceof com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker invoker)) {
            return;
        }
        int sent = 0;
        String stop = null;
        extraSending = true;
        try {
            for (int i = 0; i < n; i++) {
                double range = etherwarpRange(player.getItemInHand(owedHand));
                if (range <= 0) {
                    stop = "the etherwarp item is no longer in hand";
                    break;
                }
                if (!player.getLastSentInput().shift()) {
                    stop = "the server no longer has him sneaking";
                    break;
                }
                Prediction last;
                synchronized (PENDING) {
                    last = PENDING.peekLast();
                }
                if (last == null) {
                    stop = "nothing pending to chain from";
                    break;
                }
                Vec3 eye = new Vec3(last.landing().x, last.landing().y + TeleportUtils.eyeHeight(true), last.landing().z);
                TeleportUtils.RaycastResult shot = TeleportUtils.getEtherPos(eye, owedYaw, Mth.clamp(owedPitch, -90f, 90f),
                        range);
                if (shot == null || !shot.succeeded() || shot.pos() == null) {
                    stop = "the next warp from " + fmt(last.landing()) + " would land nowhere";
                    break;
                }
                long before = predictions;
                final InteractionHand hand = owedHand;
                final float yaw = owedYaw;
                final float pitch = owedPitch;
                invoker.killer560smod$invokeStartPrediction(mc.level,
                        sequence -> new ServerboundUseItemPacket(hand, sequence, yaw, pitch));
                sent++;
                extraSent++;
                if (predictions == before) {
                    stop = "the extra warp was not predicted";
                    break;
                }
            }
        } finally {
            extraSending = false;
        }
        if (stop != null) {
            extraStopped++;
        }
        LOGGER.info(String.format(Locale.ROOT, "[ZPEW] Warps Per Tick: %d extra warp(s) sent in tick %d at yaw %.2f pitch %.2f "
                        + "(%d owed)%s; %d etherwarp use(s) this tick", sent, clientTick, Mth.wrapDegrees(owedYaw), owedPitch, n,
                stop == null ? "" : " - stopped: " + stop, usesThisTick));
    }

    /**
     * Aimed Warps: gives every press of the use key that has arrived since the last call the look he has now (the held
     * view while a burst holds the camera). Called every frame from the camera hook and at each tick's start and end, so
     * each press keeps the look of the frame it was made in. Cheat build only.
     */
    public static void pollClicks() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !((Object) mc.options.keyUse instanceof com.killer560.hub.zpew.mixin.ZpewKeyMappingAccessor key)) {
            CLICKS.clear();
            return;
        }
        int waiting = key.killer560smod$getClickCount();
        while (CLICKS.size() > waiting) {
            CLICKS.remove(0); // vanilla used the oldest
        }
        if (CLICKS.size() < waiting) {
            boolean held = com.killer560.hub.util.ViewFreeze.isHeld();
            float yaw = held ? com.killer560.hub.util.ViewFreeze.viewYaw() : player.getYRot();
            float pitch = held ? com.killer560.hub.util.ViewFreeze.viewPitch() : player.getXRot();
            while (CLICKS.size() < waiting) {
                CLICKS.add(new ClickLook(yaw, pitch));
            }
        }
    }

    /**
     * Aimed Warps (cheat build; killer560, 2026-10-27: "Can you make it etherwarp different directions during the same
     * tick?"). At a tick's start, two or more waiting presses of the use key with an etherwarp item, sneak on the server and
     * not all at one look go out NOW, each at its own look, all inside this one real tick - and each still its own tick
     * on the wire, because that is the only way GrimAC takes two looks: BadPacketsJ (javap 2.3.74) flags a use whose look
     * differs from the previous use's since the last tick packet, and checks the last use's look against the NEXT tick
     * packet's. So press i (all but the last) spends one banked Blink Charge tick ({@code BlinkCharge.spendTick}): the body
     * turns to its look, its use goes, then his own movement tick (whose move reports that look) and its tick-end; the last
     * press goes in this real tick with the body at its look, and this tick's own move reports it. The camera is held still
     * throughout (BodyAim) and given back a tick later. Every tick packet costs GrimAC 50 ms of Timer, which is what the
     * bank is: with no Charge bank the presses are left to vanilla (one look, as a vanilla client sends them), and presses
     * past the bank are dropped rather than sent at a look he did not click at.
     */
    static void aimedBurst(Minecraft mc) {
        if (CLICKS.size() < 2 || !on() || !ZeroPingEtherwarpConfig.getInstance().effectiveAimedWarps()) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || com.killer560.hub.compat.McCompat.screen(mc) != null
                || !(mc.gameMode instanceof com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker invoker)
                || !((Object) mc.options.keyUse instanceof com.killer560.hub.zpew.mixin.ZpewKeyMappingAccessor key)) {
            return;
        }
        InteractionHand hand = InteractionHand.MAIN_HAND;
        if (etherwarpRange(player.getItemInHand(hand)) <= 0 || !player.getLastSentInput().shift()
                || !ZeroPingEtherwarpConfig.getInstance().worksFor(ZpewSource.MANUAL)) {
            return;
        }
        boolean differ = false;
        for (ClickLook c : CLICKS) {
            differ |= c.yaw() != CLICKS.get(0).yaw() || c.pitch() != CLICKS.get(0).pitch();
        }
        if (!differ) {
            return; // one look: vanilla sends them (and Warps Per Tick adds to them)
        }
        int bank = com.killer560.hub.blink.BlinkCharge.isActive() ? com.killer560.hub.blink.BlinkCharge.banked() : 0;
        if (bank < 1) {
            aimedUnfunded++;
            LOGGER.info("[ZPEW] Aimed Warps: {} presses at different looks this tick but no Blink Charge bank - left to "
                    + "vanilla (all at the look he has now)", CLICKS.size());
            return;
        }
        java.util.List<ClickLook> looks = new java.util.ArrayList<>(CLICKS);
        key.killer560smod$setClickCount(0);
        CLICKS.clear();
        if ((Object) mc instanceof com.killer560.hub.zpew.mixin.ZpewMinecraftAccessor m) {
            m.killer560smod$setRightClickDelay(4); // as startUseItem does: a held button repeats only after 4 ticks
        }
        if (!com.killer560.hub.blink.BlinkCharge.shotCovered()) {
            // GrimAC does not back the kept bank yet (a teleport's confirm still held behind its ping answer, or ticks
            // unbacked since): spending it now draws TimerLimit (1027-zpewlk-0, n=5 after a burst). Wait for cover, as
            // Blink's own shot does.
            pendingLooks = looks;
            pendingAtMs = System.currentTimeMillis();
            aimedWaited++;
            LOGGER.info("[ZPEW] Aimed Warps: {} press(es) wait for Blink's bank to be covered (bank {})", looks.size(), bank);
            return;
        }
        fireAimed(mc, player, invoker, hand, looks);
    }

    /** Presses waiting for Blink's bank to be covered, and since when. */
    private static java.util.List<ClickLook> pendingLooks;
    private static long pendingAtMs;
    private static long aimedWaited;
    /** The longest presses wait for cover; past it they are dropped (never sent where GrimAC would flag them). */
    private static final long AIMED_WAIT_MAX_MS = 3000L;

    /** At a tick's start: waiting presses fire once the bank is covered (conditions checked again), or go after 3 s. */
    static void aimedPending(Minecraft mc) {
        java.util.List<ClickLook> looks = pendingLooks;
        if (looks == null) {
            return;
        }
        // Presses made while these wait join them, in order (never fired by vanilla ahead of the ones before them).
        if (!CLICKS.isEmpty() && (Object) mc.options.keyUse instanceof com.killer560.hub.zpew.mixin.ZpewKeyMappingAccessor key) {
            looks.addAll(CLICKS);
            CLICKS.clear();
            key.killer560smod$setClickCount(0);
        }
        LocalPlayer player = mc.player;
        boolean stillOk = player != null && mc.level != null && on()
                && ZeroPingEtherwarpConfig.getInstance().effectiveAimedWarps()
                && com.killer560.hub.blink.BlinkCharge.isActive() && com.killer560.hub.blink.BlinkCharge.banked() >= 1
                && etherwarpRange(player.getItemInHand(InteractionHand.MAIN_HAND)) > 0 && player.getLastSentInput().shift()
                && mc.gameMode instanceof com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker;
        if (!stillOk || System.currentTimeMillis() - pendingAtMs > AIMED_WAIT_MAX_MS) {
            pendingLooks = null;
            aimedDropped += looks.size();
            LOGGER.info("[ZPEW] Aimed Warps: {} waiting press(es) dropped ({})", looks.size(),
                    stillOk ? "the bank was not covered within " + AIMED_WAIT_MAX_MS + " ms" : "sneak, item, Charge or bank gone");
            return;
        }
        if (!com.killer560.hub.blink.BlinkCharge.shotCovered()) {
            return;
        }
        pendingLooks = null;
        LOGGER.info("[ZPEW] Aimed Warps: the bank is covered after {} ms - firing", System.currentTimeMillis() - pendingAtMs);
        fireAimed(mc, player, (com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker) mc.gameMode,
                InteractionHand.MAIN_HAND, looks);
    }

    private static void fireAimed(Minecraft mc, LocalPlayer player,
                                  com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker invoker, InteractionHand hand,
                                  java.util.List<ClickLook> looks) {
        int bank = com.killer560.hub.blink.BlinkCharge.banked();
        int n = Math.min(looks.size(), bank + 1);
        int dropped = looks.size() - n;
        int sent = 0;
        ZpewSource prev = sending(ZpewSource.MANUAL);
        extraSending = true; // chained from the last landing, and owes no Warps Per Tick extras
        try {
            for (int i = 0; i < n; i++) {
                ClickLook l = looks.get(i);
                float pitch = Mth.clamp(l.pitch(), -90f, 90f);
                Runnable use = () -> {
                    BODY.turn(player, l.yaw(), pitch);
                    invoker.killer560smod$invokeStartPrediction(mc.level,
                            sequence -> new ServerboundUseItemPacket(hand, sequence, l.yaw(), pitch));
                };
                if (i < n - 1) {
                    if (!com.killer560.hub.blink.BlinkCharge.spendTick(use)) {
                        dropped += n - i;
                        break;
                    }
                } else {
                    use.run(); // this real tick's own move reports it
                }
                sent++;
            }
        } finally {
            extraSending = false;
            done(prev);
            com.killer560.hub.blink.BlinkCharge.endSpend();
        }
        aimedBursts++;
        aimedWarpsSent += sent;
        aimedDropped += dropped;
        lastAimedBurst = sent;
        LOGGER.info("[ZPEW] Aimed Warps: {} warp(s), each at its own look, sent in tick {} ({} banked Blink tick(s) spent, "
                        + "{} left){}", sent, clientTick, Math.max(0, sent - 1), com.killer560.hub.blink.BlinkCharge.banked(),
                dropped > 0 ? "; " + dropped + " press(es) past the bank dropped" : "");
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
        pollClicks();
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

    /** Warps Per Tick, for the testkit: extra warps sent, bursts cut short, and the most etherwarp uses seen in one tick. */
    public static long extraWarpsSent() {
        return extraSent;
    }

    public static long extraWarpsStopped() {
        return extraStopped;
    }

    public static int maxUsesInOneTick() {
        return maxUsesInOneTick;
    }

    /** Aimed Warps, for the testkit: bursts, warps sent, presses dropped past the bank, bursts left to vanilla (no bank). */
    public static long aimedBursts() {
        return aimedBursts;
    }

    public static long aimedWarpsSent() {
        return aimedWarpsSent;
    }

    public static long aimedDropped() {
        return aimedDropped;
    }

    public static long aimedUnfunded() {
        return aimedUnfunded;
    }

    /** Bursts that waited for Blink's bank to be covered before firing (or being dropped). */
    public static long aimedWaited() {
        return aimedWaited;
    }

    public static int lastAimedBurst() {
        return lastAimedBurst;
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}
