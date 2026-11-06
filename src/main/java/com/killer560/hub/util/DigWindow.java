package com.killer560.hub.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.slf4j.Logger;

/**
 * The one dig a server tick that GrimAC allows (multibreak-1102), mirrored from the packets as they leave this client.
 *
 * <p>GrimAC 2.3.74's experimental MultiBreak ({@code checks/impl/breaking/MultiBreak.java}, tag v2.3.74) keeps the
 * position and face of the last dig and flags any START_DESTROY_BLOCK or STOP_DESTROY_BLOCK on another block or face
 * before its {@code hasBroken} is cleared (lines 41-58; an ABORT is ignored, line 37). Only a TICK packet clears it (line
 * 63): a movement packet, or {@code client_tick_end} when no movement packet went before it in that tick
 * ({@code GrimProcessor.isTickPacket}, lines 76-96; {@code CheckManagerListener} 599-609 and 781). A vanilla client sends
 * exactly one of those per client tick, so at most ONE block (and face) can be dug per client tick, whoever digs it, and
 * nothing faster is clean: an extra movement packet is Timer, and a dig sent after the tick's movement packet (from
 * END_CLIENT_TICK or a frame) is GrimAC Post once a transaction answer follows it ({@code Post.java} 77-102).
 *
 * <p>So every automated dig asks {@link #mayDig} first: Breaker Aura, Auto Routes' breaker node, its linger and re-dig,
 * and the boom tap. Before 2026-11-02 Breaker Aura's Multi Break sent every block in reach on one tick (31 MultiBreak*
 * in 62-argrim with experimental checks on); now Multi Break sends them back to back, one a tick, and this window also
 * keeps two features (the linger and a node that fires, Breaker Aura and a route) from sharing one. A dig queued for the
 * head of the movement packet ({@link BeforeMove}) {@link #reserve reserves} the window until it goes.
 *
 * <p>Render thread only. A teleport's echo goes out past the send tap (on the Connection), so it never clears the window
 * here: the conservative side of GrimAC, which may treat it as a tick packet.
 */
public final class DigWindow {

    private static final Logger LOGGER = ModLog.get("killer560smod-digwindow");

    private static boolean dug;
    private static BlockPos lastPos;
    private static Direction lastFace;
    private static boolean movedBeforeTickEnd;
    /** A dig queued for the head of this tick's movement packet, not sent yet. */
    private static BlockPos pending;

    private static long digs;
    private static long conflicts;
    private static long holds;

    private DigWindow() {
    }

    /** From the send tap ({@code RightClickSendMixin}), every packet through the listener, client thread. */
    public static void onPacketSent(Packet<?> p) {
        if (p instanceof ServerboundMovePlayerPacket) {
            dug = false;
            movedBeforeTickEnd = true;
        } else if (p instanceof ServerboundClientTickEndPacket) {
            if (!movedBeforeTickEnd) {
                dug = false;
            }
            movedBeforeTickEnd = false;
        } else if (p instanceof ServerboundPlayerActionPacket a
                && (a.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                || a.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)) {
            BlockPos pos = a.getPos();
            Direction face = a.getDirection();
            if (dug && (!pos.equals(lastPos) || face != lastFace)) {
                conflicts++;
                LOGGER.warn("[DigWindow] two digs in one server tick: {} {} after {} {} (GrimAC MultiBreak would flag it)",
                        pos.toShortString(), face, lastPos.toShortString(), lastFace);
            }
            dug = true;
            lastPos = pos.immutable();
            lastFace = face;
            digs++;
            if (pending != null && pending.equals(pos)) {
                pending = null;
            }
        }
    }

    /** True when a dig on {@code pos} sent now (or at the head of this tick's movement packet) is the only block dug in
     *  this server tick. */
    public static boolean mayDig(BlockPos pos) {
        return (!dug || pos.equals(lastPos)) && (pending == null || pending.equals(pos));
    }

    /** True when nothing has been dug in this server tick yet, nor queued to be. */
    public static boolean open() { return !dug && pending == null; }

    /** {@link #mayDig}, counting a refusal (for the testkit's "held" number). */
    public static boolean mayDigOrHold(BlockPos pos) {
        if (mayDig(pos)) return true;
        holds++;
        return false;
    }

    /**
     * True when HIS held left click will dig this tick (grimgapB-1105). Vanilla's {@code continueAttack} runs in
     * {@code handleKeybinds}, after every START_CLIENT_TICK actor: with the attack key held on a block it sends a
     * START_DESTROY_BLOCK on a new target, or the STOP that finishes the current one ({@code continueDestroyBlock}, javap
     * 26.3). The send tap sees it only after an automated START dig went, so the two are GrimAC MultiBreak on two blocks.
     * Asked at START; his queued click ({@code startAttack}) is ActionGate's rule. Free while the key is up.
     */
    public static boolean handDigsThisTick(net.minecraft.client.Minecraft mc) {
        if (!mc.options.keyAttack.isDown() || mc.player == null || mc.level == null
                || com.killer560.hub.compat.McCompat.screen(mc) != null || mc.missTime > 0 || mc.player.isUsingItem()
                || !mc.mouseHandler.isMouseGrabbed()
                || !(mc.gameMode instanceof com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker gm)
                || gm.killer560smod$getDestroyDelay() > 0) {
            return false;
        }
        // Picked fresh: vanilla picks again after START, on the look the START actors left.
        if (!(mc.player.pick(mc.player.blockInteractionRange(), 1f, false) instanceof net.minecraft.world.phys.BlockHitResult hit)
                || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos pos = hit.getBlockPos();
        var state = mc.level.getBlockState(pos);
        if (state.isAir()) return false;
        boolean same = pos.equals(gm.killer560smod$getDestroyBlockPos()) && net.minecraft.world.item.ItemStack
                .isSameItemSameComponents(gm.killer560smod$getDestroyingItem(), mc.player.getMainHandItem());
        return !same || mc.player.getAbilities().instabuild
                || gm.killer560smod$getDestroyProgress() + state.getDestroyProgress(mc.player, mc.level, pos) >= 1f;
    }

    /** A dig on {@code pos} is queued for the head of this tick's movement packet ({@link BeforeMove}). */
    public static void reserve(BlockPos pos) { pending = pos.immutable(); }

    /** From {@link BeforeMove} once its queue has run (a queued dig that was refused never went) or was dropped. */
    public static void clearPending() { pending = null; }

    /** Digs (START/STOP) sent, digs that shared a server tick with another block (GrimAC MultiBreak), and automated digs
     *  held a tick for the window - only go up; read by the testkit. */
    public static long digs() { return digs; }

    public static long conflicts() { return conflicts; }

    public static long holds() { return holds; }
}
