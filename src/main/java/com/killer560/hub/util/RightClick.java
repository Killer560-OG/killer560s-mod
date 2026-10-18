package com.killer560.hub.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Sends an item use the way a vanilla client's right click reaches Hypixel's 1.8 server, so that server never takes it
 * as the tail of an earlier right click on a block.
 * <p>
 * <b>What Hypixel does</b> (CraftBukkit 1.8 {@code PlayerConnection.a(PacketPlayInBlockPlace)}, the face-255 branch,
 * and {@code PlayerInteractManager.firedInteract/interactResult}): a 1.8 client's right click on a block is TWO packets,
 * the click on the block and then a use, and the server pairs them. A right click on a block stores its verdict
 * ({@code firedInteract = true}, {@code interactResult} = whether the item was denied). A bare use first traces 4.5 blocks
 * from the eye along the player's rotation <i>as the server last heard it</i> - the last movement packet's, since a 1.8
 * use carries none - and when that ray meets a block while a click's verdict is still stored, the use is taken as that
 * click's second packet: it fires no interact event (no ability), only replays the stored verdict, and the verdict is
 * cleared. A use whose ray meets nothing fires as a right click in the air and leaves the verdict where it is.
 * <p>
 * <b>Why the mod's etherwarps vanished</b> (killer560's Mod Only Test log of a real run, 2026-10-14 17:22-17:29): a modern
 * client's click on a chest, lever or skull is ONE packet (the block takes it), so its verdict - the Aspect of the Void
 * denied, it opened a chest - stays stored. Auto Routes then turned the body to the warp and sent the use in the same tick,
 * so the movement packet carrying the warp's look went AFTER the use, and the server's look for that use was still the one
 * Secret Aura (or he) had clicked the chest with: its 4.5-block ray met the chest, the use replayed "denied", and nothing
 * happened - "no landing after 61 ticks", no chat line, while every retry 3 s later landed (the verdict was gone). The six
 * that failed were all a tick after a chest or lever click with the AOTV in hand; along the warp's own look their rays
 * meet nothing within 4.5 (Red Blue #5 and #6, measured against the room capture), so it was the click's look the server
 * traced, not the warp's. A human looks at the warp's block before he clicks, so the server already has that look; and
 * when that block is within reach his client sends the click on it first, which pairs the use with a click of its own.
 * <p>
 * <b>What this does</b>, for every feature that sends an item use: {@link #mustTurnFirst} is true only while a block
 * click's verdict may be stored AND the server's last-heard look would meet a block - then the caller turns its body to the
 * use's look this tick (the movement packet reports it) and sends next tick; and {@link #beforeUse} sends the block half of
 * a vanilla right click first when the use's own look meets a block within reach. Nothing waits otherwise, and nothing
 * here keeps a timer: after a chest click the warp goes the next tick, as his own does.
 * <p>
 * Fed by {@code RightClickSendMixin} from the packets as they leave the client (vanilla's, every feature's, the sim's).
 * Client thread only. The client cannot see the verdict itself, so any unpaired block click made with an item in hand
 * counts (an empty hand cannot be denied an item).
 */
public final class RightClick {

    private static final Logger LOGGER = ModLog.get("killer560smod-rightclick");

    /** CraftBukkit 1.8's survival trace length for a bare use. */
    public static final double SERVER_TRACE = 4.5;
    /** Sneaking eye heights the server may trace from: the modern pose's and 1.8's (1.62 - 0.08). */
    private static final double[] SNEAK_EYES = {1.27, 1.54};
    private static final double[] STAND_EYES = {1.62};
    /** A use this many ticks or fewer after a block click gets its INFO line even once the click is paired. */
    private static final long LOG_AFTER_CLICK_TICKS = 40;
    /** How close the use's look must be to the last reported one to count as already told. */
    private static final float SAME_LOOK_DEGREES = 0.05f;

    /** What the server was last told by a movement packet. */
    private static boolean sentKnown;
    private static double sentX;
    private static double sentY;
    private static double sentZ;
    private static float sentYaw;
    private static float sentPitch;

    /** A right click on a block whose verdict the server may still hold. */
    private static boolean unpaired;
    private static long clickTick = Long.MIN_VALUE / 2;
    private static BlockPos clickPos;
    private static volatile int lastUseOnSequence = -1;

    /** The prediction sequence of the last block click that left this client (the server acks it, util/ChestWindows). */
    public static int lastUseOnSequence() {
        return lastUseOnSequence;
    }
    private static String clickItem;
    private static Level level;

    /** For the once-per-click "turned first" line. */
    private static long loggedTurnClick = Long.MIN_VALUE;
    private static String loggedTurnWho;

    /** Uses that went out bare while a verdict was stored and the server's ray met a block - read by the testkit. */
    private static int barePairedUses;
    /** Block halves this class sent ahead of a use. */
    private static int blockHalvesSent;

    private RightClick() {
    }

    // ------------------------------------------------------------------------------------------- fed by the send tap

    /** A movement packet is leaving: what the server will know of his position and look. */
    public static void onMove(LocalPlayer player, ServerboundMovePlayerPacket p) {
        checkLevel(player);
        if (p.hasPosition()) {
            sentX = p.getX(sentX);
            sentY = p.getY(sentY);
            sentZ = p.getZ(sentZ);
        }
        if (p.hasRotation()) {
            sentYaw = p.getYRot(sentYaw);
            sentPitch = p.getXRot(sentPitch);
        }
        if (p.hasPosition() && p.hasRotation()) {
            sentKnown = true;
        }
    }

    /**
     * The server has just teleported him ({@code ClientPacketListener.handleMovePlayer}, after vanilla applied it). Vanilla
     * answers with an accept and a position-and-look packet it sends on the {@code Connection} directly, past the send
     * tap above - so until the next ordinary movement packet this class still traced the server's ray from where he stood
     * BEFORE the warp. A path's next hop a tick after a landing was then judged from the old spot ("meets nothing"), went
     * bare while a chest click was unpaired, and the server - tracing from the landing - took it as the click's second
     * half and dropped it: "no landing after 62 ticks", the rest of the path flown from the wrong place (his Mod Only
     * Test log, 2026-10-15 15:55, Flags #13, #15 and three more). The packet vanilla just sent is his position and look as
     * the client now has them, so that is what the server has heard. Client thread.
     */
    public static void onTeleportAnswered(LocalPlayer player) {
        checkLevel(player);
        if (player == null || player.isPassenger()) {
            return; // a passenger's answer is a vehicle packet, not a position-and-look one
        }
        sentX = player.getX();
        sentY = player.getY();
        sentZ = player.getZ();
        sentYaw = player.getYRot();
        sentPitch = player.getXRot();
        sentKnown = true;
    }

    /** Item uses and item-in-hand block clicks that have left this client, ever (only goes up) - whoever sent them. A
     *  feature counts its own and reads the rest as another feature's (ClearExecutor: another feature's warp landing is
     *  not a server correction of the map's trip). */
    public static int usesSent() {
        return usesSent;
    }

    private static int usesSent;

    /** Bare item-use packets ({@code use_item}) that have left this client, ever (only goes up) - not block clicks. A
     *  sender compares it with its own count from the start of the tick: GrimAC's BadPacketsJ flags a second use in one
     *  tick whose look differs from the first's, since only one look reaches the server per tick. */
    public static int itemUsesSent() {
        return itemUsesSent;
    }

    private static int itemUsesSent;

    /** A right click on a block is leaving. */
    public static void onUseOn(LocalPlayer player, ServerboundUseItemOnPacket p) {
        checkLevel(player);
        if (!player.getItemInHand(p.getHand()).isEmpty()) {
            usesSent++;
        }
        // A click with an empty hand stores a verdict too, but one that cannot deny an item - and it replaces whatever an
        // earlier click stored. Only a click made with an item in hand can leave a use to be eaten (the one real warp a tick
        // after a click with another item, the Dungeon Breaker, landed: Grand Library 2026-10-14 17:29:35).
        unpaired = !player.getItemInHand(p.getHand()).isEmpty();
        clickTick = ActionGate.currentTick();
        clickPos = p.getHitResult().getBlockPos().immutable();
        clickItem = itemId(player, p.getHand());
        lastUseOnSequence = p.getSequence();
        LOGGER.info("[RightClick] block click seq {} at {} face {} with {} (slot {}) | server look {} | shift {} | screen {}",
                p.getSequence(), clickPos.toShortString(), p.getHitResult().getDirection(), clickItem,
                player.getInventory().getSelectedSlot() + 1, sentLook(), player.getLastSentInput().shift(), screen());
    }

    /** A use is leaving: the server traces it now, along the look it last heard. One line per use while a block click is
     *  unpaired or was made in the last {@link #LOG_AFTER_CLICK_TICKS} ticks (and a WARN when the server pairs it). */
    public static void onUse(LocalPlayer player, ServerboundUseItemPacket p) {
        checkLevel(player);
        usesSent++;
        itemUsesSent++;
        BlockHitResult ray = serverRay(player, sentYaw, sentPitch);
        String rayText = ray == null ? "meets nothing in " + SERVER_TRACE + " (a right click in the air)"
                : "meets " + blockName(player, ray.getBlockPos()) + " at " + ray.getBlockPos().toShortString()
                + String.format(Locale.US, " %.2f away", ray.getLocation().distanceTo(sentEye(player, ray)));
        String clickText = !unpaired ? "no unpaired block click"
                : "unpaired block click at " + (clickPos == null ? "?" : clickPos.toShortString()) + " with " + clickItem
                + " " + (ActionGate.currentTick() - clickTick) + " tick(s) ago";
        // Its own block half sent this same tick (vanilla's two-packet right click, or beforeUse's): the halves pair as
        // they should. An older click: the use is eaten.
        boolean sameTick = unpaired && clickTick == ActionGate.currentTick();
        boolean pairs = unpaired && ray != null && !sameTick;
        boolean spent = pairs && spending;
        if (pairs && !spent) {
            barePairedUses++;
        }
        String line = String.format(Locale.US, "[RightClick] use seq %d with %s (slot %d) rot %.2f/%.2f | server look %s"
                        + " | shift %s | screen %s | %s | server ray %s%s", p.getSequence(), itemId(player, p.getHand()),
                player.getInventory().getSelectedSlot() + 1, p.getYRot(), p.getXRot(), sentLook(),
                player.getLastSentInput().shift(), screen(), clickText, rayText,
                spent ? " -> pairs with that click on purpose (a use with no ability): the click is paired now"
                        : pairs ? " -> Hypixel takes this use as that click's second half: no ability, it replays the click's verdict"
                        : sameTick && ray != null ? " -> pairs with its own block half (one right click)" : "");
        if (pairs && !spent) {
            LOGGER.warn(line);
        } else if (unpaired || ActionGate.currentTick() - clickTick <= LOG_AFTER_CLICK_TICKS) {
            LOGGER.info(line);
        } else {
            LOGGER.debug(line); // a use with no block click near it: nothing to pair, not worth a line in his log
        }
        if (ray != null) {
            unpaired = false;
        }
    }

    // ------------------------------------------------------------------------------------------- asked by senders

    /**
     * True when a use sent now at {@code yaw}/{@code pitch} would be taken as the second half of an earlier block click:
     * one may be unpaired, the look is not yet the one the server last heard, and the server's ray along its last-heard
     * look meets a block. The caller then sends NOTHING this tick and turns its body to the look (so this tick's movement
     * packet reports it), and asks again next tick - when this answers false. Logs one line per click and caller
     * ({@code who} null: ask without logging).
     */
    public static boolean mustTurnFirst(LocalPlayer player, float yaw, float pitch, String who) {
        return mustTurnFirst(player, null, yaw, pitch, who);
    }

    /**
     * {@link #mustTurnFirst(LocalPlayer, float, float, String)} for a use the server resolves from {@code serverFeet} - where
     * the server has him while the client has not seen the landing yet (the Interactive Map chains a hop off the last one
     * in flight); null = where the last movement packet put him. Also true while the look is told and its own ray meets a
     * block from there but the client is not standing there yet: the click on that block that pairs the use can only be
     * sent once he has landed, so the hop waits for the landing.
     */
    public static boolean mustTurnFirst(LocalPlayer player, Vec3 serverFeet, float yaw, float pitch, String who) {
        if (!unpaired || player == null) {
            return false;
        }
        checkLevel(player);
        if (!unpaired) {
            return false;
        }
        Vec3 feet = serverFeet != null ? serverFeet : sentFeet(player);
        if (sameLook(player, yaw, pitch)) {
            if (serverFeet == null || player.position().distanceTo(serverFeet) <= 0.5
                    || serverRay(player, feet, sentYaw, sentPitch) == null) {
                return false; // beforeUse pairs it if its ray meets a block; a ray in the air needs nothing
            }
            if (who != null && (loggedTurnClick != clickTick || !who.equals(loggedTurnWho + "/land"))) {
                loggedTurnClick = clickTick;
                loggedTurnWho = who + "/land";
                LOGGER.info("{}: waiting for the landing - the look is told and meets a block within reach, so the use needs "
                        + "the click on that block first, and that goes from where the server has him", who);
            }
            return true;
        }
        BlockHitResult ray = serverRay(player, feet, sentYaw, sentPitch);
        if (ray == null) {
            return false; // the bare use is a right click in the air: it fires, and the click stays unpaired
        }
        if (who != null && (loggedTurnClick != clickTick || !who.equals(loggedTurnWho))) {
            loggedTurnClick = clickTick;
            loggedTurnWho = who;
            LOGGER.info("{}: turning to the use's look first ({} / {}) - the server's last look {} meets {} at {}, and the "
                            + "block click at {} with {} {} tick(s) ago is unpaired: a use now would be taken as its second "
                            + "half (Hypixel drops it). The use goes next tick.", who,
                    String.format(Locale.US, "%.2f", Mth.wrapDegrees(yaw)), String.format(Locale.US, "%.2f", pitch),
                    sentLook(), blockName(player, ray.getBlockPos()), ray.getBlockPos().toShortString(),
                    clickPos == null ? "?" : clickPos.toShortString(), clickItem, ActionGate.currentTick() - clickTick);
        }
        return true;
    }

    /**
     * Right before a use at {@code yaw}/{@code pitch} is sent: when a block click may be unpaired and the server's ray
     * along the (already reported) look meets a block, sends the block half of a vanilla right click first - the click on
     * the block the look meets within reach, with its own prediction sequence - so the use pairs with that click, as a
     * vanilla client's does. Sends nothing in every other case (the use alone is what a vanilla client sends then).
     *
     * @return true when a block half was sent
     */
    public static boolean beforeUse(Minecraft client, LocalPlayer player, float yaw, float pitch, String who) {
        if (!unpaired || player == null || client.level == null || client.gameMode == null) {
            return false;
        }
        checkLevel(player);
        if (!unpaired || !sameLook(player, yaw, pitch)) {
            return false; // not told yet: mustTurnFirst said its ray meets nothing, so the use goes as one in the air
        }
        BlockHitResult srv = serverRay(player, sentYaw, sentPitch);
        if (srv == null) {
            return false;
        }
        return sendBlockHalf(client, player, yaw, pitch, who, srv);
    }

    /**
     * Right before a plain use at the CURRENT look (a leap, a rod, a pearl, a bow shot - nothing turned for it): what a
     * vanilla client's right click sends, when a block click may be unpaired - the click on the block the crosshair meets
     * within reach first, then the use. Vanilla never waits for the server to hear the look, so neither does this.
     *
     * @return true when a block half was sent
     */
    public static boolean beforePlainUse(Minecraft client, LocalPlayer player, String who) {
        if (!unpaired || player == null || client.level == null || client.gameMode == null) {
            return false;
        }
        checkLevel(player);
        if (!unpaired) {
            return false;
        }
        return sendBlockHalf(client, player, player.getYRot(), player.getXRot(), who,
                serverRay(player, sentYaw, sentPitch));
    }

    /** The block half: the client's own pick along the look, as vanilla's crosshair would have it. {@code srv} (may be null)
     *  is what the server's ray meets, for the log when the client's look reaches nothing. */
    private static boolean sendBlockHalf(Minecraft client, LocalPlayer player, float yaw, float pitch, String who,
                                         BlockHitResult srv) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(look(yaw, pitch).scale(player.blockInteractionRange()));
        BlockHitResult hit = client.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            if (srv != null) {
                LOGGER.warn("{}: the server's ray meets {} at {} but this client's look reaches no block - the use goes "
                                + "alone and Hypixel may take it as the block click's second half", who,
                        blockName(player, srv.getBlockPos()), srv.getBlockPos().toShortString());
            }
            return false;
        }
        boolean sneaking = player.getLastSentInput().shift();
        if (!sneaking && opens(client.level.getBlockState(hit.getBlockPos()).getBlock())) {
            LOGGER.warn("{}: the look meets {} at {}, which a right click would open - the use goes alone and Hypixel may "
                            + "take it as the block click's second half", who, blockName(player, hit.getBlockPos()),
                    hit.getBlockPos().toShortString());
            return false;
        }
        if (!(client.gameMode instanceof com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker invoker)) {
            return false;
        }
        invoker.killer560smod$invokeEnsureHasSentCarriedItem();
        BlockHitResult click = hit;
        invoker.killer560smod$invokeStartPrediction(client.level,
                sequence -> new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, click, sequence));
        blockHalvesSent++;
        LOGGER.info("{}: sent the block half of the right click first - {} at {} face {} - so the use pairs with it", who,
                blockName(player, hit.getBlockPos()), hit.getBlockPos().toShortString(), hit.getDirection());
        return true;
    }

    /** The block a vanilla right click along {@code yaw}/{@code pitch} would click from this client's eye, or null. */
    private static BlockHitResult clientPick(Minecraft client, LocalPlayer player, float yaw, float pitch) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(look(yaw, pitch).scale(player.blockInteractionRange()));
        BlockHitResult hit = client.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                player));
        return hit == null || hit.getType() != HitResult.Type.BLOCK ? null : hit;
    }

    /**
     * True when a use at {@code yaw}/{@code pitch} could only go out to be dropped: a block click may be unpaired, the
     * server will trace this look (it has heard it already, or the caller turns to it first because the heard look meets
     * a block), the server's ray along it from {@code serverFeet} (null: the last movement packet's spot) meets a block
     * within {@link #SERVER_TRACE} - so the use needs a block half to pair with - and this client's own look along it
     * reaches no block it may click (nothing within reach, or one a right click would open while not sneaking). The 1.8
     * eye the server traces from is 0.27 above the modern sneaking one, so the two rays can disagree; a click on a block
     * this client cannot see is never sent (killer560, 2026-10-15), so the warp would be taken as the click's second half.
     */
    public static boolean unpairable(Minecraft client, LocalPlayer player, Vec3 serverFeet, float yaw, float pitch) {
        if (!unpaired || player == null || client.level == null) {
            return false;
        }
        checkLevel(player);
        if (!unpaired) {
            return false;
        }
        Vec3 feet = serverFeet != null ? serverFeet : sentFeet(player);
        boolean traced = sameLook(player, yaw, pitch) || serverRay(player, feet, sentYaw, sentPitch) != null;
        if (!traced || serverRay(player, feet, yaw, pitch) == null) {
            return false; // goes as a right click in the air (or with the heard look's verdict): nothing to pair
        }
        BlockHitResult hit = clientPick(client, player, yaw, pitch);
        if (hit == null) {
            return true;
        }
        return !player.getLastSentInput().shift() && opens(client.level.getBlockState(hit.getBlockPos()).getBlock());
    }

    /**
     * When {@link #unpairable} holds for the etherwarp look {@code yaw}/{@code pitch} onto {@code target}: another look
     * from {@code eye} that the etherwarp lands on {@code target} with too (the same check as
     * {@code TeleportUtils.getEtherwarpDirection}: the real ray, {@code dist} blocks) and that is not unpairable - its
     * server ray meets nothing within reach, or this client can click what it meets. Nearest to the old look first.
     * Null when the look is fine as it is, or when no other look works (the caller then sends the old one, as before).
     * The caller turns to the new look; the server hears it on this tick's movement packet and the warp goes next tick -
     * one tick, and only on a detected mismatch.
     */
    public static float[] reaimIfUnpairable(Minecraft client, LocalPlayer player, Vec3 serverFeet, Vec3 eye, BlockPos target,
                                            double dist, float yaw, float pitch, String who) {
        if (target == null || eye == null || !unpairable(client, player, serverFeet, yaw, pitch)) {
            return null;
        }
        float[] best = null;
        double bestCost = Double.MAX_VALUE;
        boolean bestFirm = false;
        for (double[] o : REAIM_POINTS) {
            Vec3 point = new Vec3(target.getX() + o[0], target.getY() + o[1], target.getZ() + o[2]);
            com.killer560.hub.livemap.autoclear.TeleportUtils.Rotation r =
                    com.killer560.hub.livemap.autoclear.TeleportUtils.getDirection(eye, point);
            if (r == null) {
                continue;
            }
            com.killer560.hub.livemap.autoclear.TeleportUtils.RaycastResult land =
                    com.killer560.hub.livemap.autoclear.TeleportUtils.getEtherPos(eye, r.yaw(), r.pitch(), dist);
            if (land == null || !land.succeeded() || !target.equals(land.pos())) {
                continue;
            }
            float ny = yaw + Mth.wrapDegrees(r.yaw() - yaw);
            float np = Mth.clamp(r.pitch(), -90f, 90f);
            if (unpairable(client, player, serverFeet, ny, np)) {
                continue;
            }
            // A look that lands only for its exact float values can go into the neighbouring block once the server reads
            // the running yaw a few bits off (path-wrongblock-1015, his 22:47:07 sim run: re-aimed at -149, 261, -188,
            // the server's cast went into -149, 262, -189). Any look that still lands within the planner's margin wins.
            boolean firm = com.killer560.hub.livemap.autoclear.TeleportUtils.holdsOn(eye, r.yaw(), np, dist, target);
            double cost = Math.abs(ny - yaw) + Math.abs(np - pitch);
            if (firm && !bestFirm || firm == bestFirm && cost < bestCost) {
                bestCost = cost;
                best = new float[]{ny, np};
                bestFirm = firm;
            }
        }
        if (best != null) {
            LOGGER.info("{}: re-aimed {} / {} -> {} / {} at {} - the server's ray along the old look meets a block this "
                            + "client's look cannot reach, so no click could pair the use; the new look's rays agree. The "
                            + "warp goes next tick.", who, String.format(Locale.US, "%.2f", Mth.wrapDegrees(yaw)),
                    String.format(Locale.US, "%.2f", pitch), String.format(Locale.US, "%.2f", Mth.wrapDegrees(best[0])),
                    String.format(Locale.US, "%.2f", best[1]), target.toShortString());
            reaims++;
        } else {
            LOGGER.warn("{}: the old look cannot be paired and no other look lands on {} with rays that agree - sending it "
                    + "as it is", who, target.toShortString());
        }
        return best;
    }

    /** Points inside the target block's faces a re-aimed etherwarp may aim at: a 5x5 grid on the top, 3x3 on each side. */
    private static final double[][] REAIM_POINTS = reaimPoints();

    private static double[][] reaimPoints() {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        double[] g5 = {0.1, 0.3, 0.5, 0.7, 0.9};
        double[] g3 = {0.2, 0.5, 0.8};
        for (double x : g5) {
            for (double z : g5) {
                out.add(new double[]{x, 0.97, z});
            }
        }
        for (double a : g3) {
            for (double y : g3) {
                out.add(new double[]{0.03, y, a});
                out.add(new double[]{0.97, y, a});
                out.add(new double[]{a, y, 0.03});
                out.add(new double[]{a, y, 0.97});
            }
        }
        // Off the tenths lattice: from a stand at a block's centre the lines to the points above often run exactly through a
        // block's edge, which is a fragile aim (see reaimIfUnpairable); these give a firm one a chance.
        double[][] off = {{0.37, 0.61}, {0.63, 0.39}, {0.41, 0.43}, {0.59, 0.57}, {0.23, 0.62}, {0.77, 0.38},
                {0.62, 0.23}, {0.38, 0.77}};
        for (double[] p : off) {
            out.add(new double[]{p[0], 0.97, p[1]});
        }
        return out.toArray(new double[0][]);
    }

    private static int reaims;

    /** Warps re-aimed by {@link #reaimIfUnpairable} - read by the testkit. */
    public static int reaims() {
        return reaims;
    }

    /** Whether the server's last-heard look is {@code yaw}/{@code pitch} (any wrapping, within 0.05 degrees). */
    public static boolean heard(LocalPlayer player, float yaw, float pitch) {
        return sameLook(player, yaw, pitch);
    }

    /**
     * Pairs the stored block click with a harmless use BEFORE the real one goes, for the case {@link #reaimIfUnpairable}
     * cannot cure: the server's ray along the look it has heard meets a block (from the 1.8 eye) while this client's own
     * look reaches none, so the next use would be taken as the stored click's second half and dropped. Sent with the
     * item the caller has just selected - one with no right-click ability (the Dungeon Breaker) - as a bare use at the
     * heard look: a vanilla right click along a look that meets no block in reach is exactly that one packet. The server
     * pairs it with the stored click (verdict replayed: nothing happens) and clears it, so the warp that follows a tick
     * later is an ordinary right click and fires. Should the server not pair it after all, it is a right click in the air
     * with a pickaxe: nothing either - unlike sending the warp itself to be eaten, which costs a round trip to find out
     * and, were the judgement wrong, a second warp from the landing (killer560's Bridges, 2026-10-17).
     *
     * @return true when the use was sent
     */
    public static boolean spendStoredClick(Minecraft client, LocalPlayer player, String who) {
        if (!unpaired || !sentKnown || player == null || client.level == null
                || !(client.gameMode instanceof com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker invoker)) {
            return false;
        }
        checkLevel(player);
        if (!unpaired || player.getMainHandItem().isEmpty()) {
            return false;
        }
        BlockHitResult srv = serverRay(player, sentYaw, sentPitch);
        if (srv == null || clientPick(client, player, sentYaw, sentPitch) != null) {
            return false; // nothing to pair it with, or a vanilla right click here would click a block first
        }
        float yaw = sentYaw;
        float pitch = sentPitch;
        String clickWas = clickPos == null ? "?" : clickPos.toShortString();
        String with = clickItem;
        invoker.killer560smod$invokeEnsureHasSentCarriedItem();
        spending = true;
        try {
            invoker.killer560smod$invokeStartPrediction(client.level,
                    sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, yaw, pitch));
        } finally {
            spending = false;
        }
        storedClicksSpent++;
        LOGGER.info("{}: paired the block click at {} with {} by a use of {} first - the server's ray meets {} at {}, "
                        + "this client's look reaches no block, so the warp would have been that click's second half. "
                        + "The warp goes next tick.", who, clickWas, with, itemId(player, InteractionHand.MAIN_HAND),
                blockName(player, srv.getBlockPos()), srv.getBlockPos().toShortString());
        return true;
    }

    /** {@link #spendStoredClick} is sending: its use pairs on purpose, so it is not counted as an eaten one. */
    private static boolean spending;
    private static int storedClicksSpent;

    /** Stored block clicks {@link #spendStoredClick} paired with a harmless use - read by the testkit. */
    public static int storedClicksSpent() {
        return storedClicksSpent;
    }

    /** The look the server last heard in a movement packet (the running yaw as sent, never wrapped), or null. */
    public static float[] heardLook() {
        return sentKnown ? new float[]{sentYaw, sentPitch} : null;
    }

    /** Whether a block click may still be waiting for its second half on the server. */
    public static boolean unpaired() {
        return unpaired;
    }

    /** Uses this class judged the server would take as an earlier block click's second half (ever; only goes up). A
     *  sender compares it before and after its own use: when it went up, that use is expected to do nothing. */
    public static int barePairedUses() {
        return barePairedUses;
    }

    /**
     * How long a sender waits for the landing of a use {@link #barePairedUses} judged eaten before it sends the use
     * again: the measured round trip ({@link PingTracker#automationMs}) and half again, rounded up to ticks, plus 2 -
     * 3 ticks on a local server. The eaten use cleared the server's stored verdict whatever happened to it (the server
     * pairs a use with the click once), so the second one goes as an ordinary right click in the air and fires. If the
     * judgement was wrong and the first use fired after all (Hypixel traced from an eye this class did not expect), its
     * landing arrives inside the round trip and nothing is sent again. Callers add {@link ServerLag} on top.
     * <p>
     * Why it is needed at all (fullclear-1016, his sim run 2026-10-16 16:59:25 Cathedral #15 and 17:00:06 Crypt): the
     * server's 1.8 ray (1.54 eye) can meet leaves this client's 1.27 look never reaches, {@link #reaimIfUnpairable}
     * found no look that lands on the block with rays that agree, and the warp went out to be dropped. Auto Routes then
     * waited its 61-tick landing timeout off every node's ring and stood until Route Stall stopped the route; the
     * Interactive Map chained four hops from a landing that never happened, all refused.
     */
    public static int eatenRetryTicks() {
        // Before the first pong the tab-list latency stands in, about 1 ms on Hypixel: too short to bet a second warp on.
        int ping = PingTracker.hasMeasurement() ? PingTracker.automationMs() : Math.max(PingTracker.tabListMs(), 300);
        return Math.max(3, (int) Math.ceil(ping * 1.5 / 50.0) + 2);
    }

    public static int blockHalvesSent() {
        return blockHalvesSent;
    }

    public static void reset() {
        unpaired = false;
        clickPos = null;
        clickItem = null;
        sentKnown = false;
    }

    // ------------------------------------------------------------------------------------------- the model

    private static void checkLevel(LocalPlayer player) {
        Level now = player == null ? null : player.level();
        if (now != level) {
            level = now;
            reset(); // a new world is a new server-side player: nothing stored, nothing heard
        }
    }

    /** Whether {@code yaw}/{@code pitch} (any wrapping) is the look the server last heard. */
    private static boolean sameLook(LocalPlayer player, float yaw, float pitch) {
        if (!sentKnown) {
            return false;
        }
        // The look only: where he stands the server traces from its own copy of him (serverRay), whatever the client
        // has done since - a landing still settling 0.05 down must not hold a use that has its look told.
        return Math.abs(Mth.wrapDegrees(yaw - sentYaw)) <= SAME_LOOK_DEGREES
                && Math.abs(Mth.clamp(pitch, -90f, 90f) - sentPitch) <= SAME_LOOK_DEGREES;
    }

    /** The server's 4.5-block trace for a bare use, from the last reported position along {@code yaw}/{@code pitch}:
     *  the first block it meets from any eye height the server may use, or null. */
    private static BlockHitResult serverRay(LocalPlayer player, float yaw, float pitch) {
        return serverRay(player, sentFeet(player), yaw, pitch);
    }

    private static Vec3 sentFeet(LocalPlayer player) {
        return sentKnown ? new Vec3(sentX, sentY, sentZ) : player.position();
    }

    private static BlockHitResult serverRay(LocalPlayer player, Vec3 feet, float yaw, float pitch) {
        Level lvl = player.level();
        if (lvl == null) {
            return null;
        }
        double x = feet.x;
        double y = feet.y;
        double z = feet.z;
        Vec3 dir = look(yaw, pitch).scale(SERVER_TRACE);
        BlockHitResult best = null;
        for (double h : player.getLastSentInput().shift() ? SNEAK_EYES : STAND_EYES) {
            Vec3 eye = new Vec3(x, y + h, z);
            BlockHitResult hit = lvl.clip(new ClipContext(eye, eye.add(dir), ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, player));
            if (hit != null && hit.getType() == HitResult.Type.BLOCK
                    && (best == null || hit.getLocation().distanceToSqr(eye) < best.getLocation().distanceToSqr(eye))) {
                best = hit;
            }
        }
        return best;
    }

    private static Vec3 sentEye(LocalPlayer player, BlockHitResult ray) {
        double x = sentKnown ? sentX : player.getX();
        double y = sentKnown ? sentY : player.getY();
        double z = sentKnown ? sentZ : player.getZ();
        return new Vec3(x, y + (player.getLastSentInput().shift() ? 1.27 : 1.62), z);
    }

    /** A block a right click without sneaking would open or flip instead of using the item. */
    private static boolean opens(Block b) {
        return b instanceof BaseEntityBlock || b instanceof LeverBlock || b instanceof ButtonBlock || b instanceof DoorBlock
                || b instanceof TrapDoorBlock || b instanceof FenceGateBlock;
    }

    /** 1.8's look vector ({@code TeleportUtils.getLook}). */
    private static Vec3 look(float yaw, float pitch) {
        double f2 = -Math.cos(-pitch * 0.017453292f);
        return new Vec3(Math.sin(-yaw * 0.017453292f - 3.1415927f) * f2, Math.sin(-pitch * 0.017453292f),
                Math.cos(-yaw * 0.017453292f - 3.1415927f) * f2);
    }

    private static String sentLook() {
        if (!sentKnown) {
            return "(none yet)";
        }
        return String.format(Locale.US, "%.2f/%.2f from (%.2f, %.2f, %.2f)", Mth.wrapDegrees(sentYaw), sentPitch, sentX,
                sentY, sentZ);
    }

    private static String itemId(LocalPlayer player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (stack.isEmpty()) {
            return "an empty hand";
        }
        String id = com.killer560.hub.cheatutils.CheatUtils.skyblockId(stack);
        return id != null ? id : stack.getItem().toString();
    }

    private static String blockName(LocalPlayer player, BlockPos pos) {
        return player.level().getBlockState(pos).getBlock().getName().getString();
    }

    private static String screen() {
        var s = com.killer560.hub.compat.McCompat.screen(Minecraft.getInstance());
        return s == null ? "none" : s.getClass().getSimpleName();
    }
}
