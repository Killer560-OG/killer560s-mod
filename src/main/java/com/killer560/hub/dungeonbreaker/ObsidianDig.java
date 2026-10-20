package com.killer560.hub.dungeonbreaker;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One Dungeon Breaker dig of an OBSIDIAN block, held the way a hand holds left click on it.
 * <p>
 * killer560 (2026-10-19): obsidian does not break like the other blocks - it takes time, it is not one fast burst. Every
 * other block is broken by the Dungeon Breaker on the server's answer to a single START_DESTROY_BLOCK, which is why
 * Breaker Aura and Auto Routes send one START per block, zero-ping it, and go on. Obsidian treated that way left a ghost
 * hole (zero ping set it to air on the client while the server still had it), was re-STARTed every few ticks (each new
 * START restarts the server's dig), and the route's 40-tick confirm gave up on it before it could break.
 * <p>
 * So obsidian is dug as a vanilla client digs a block that is not instant, packet for packet: one START (through the
 * prediction sequence, nothing changed locally), a main-hand swing every tick while held, and a STOP_DESTROY_BLOCK on
 * the tick the client's own destroy progress ({@code BlockState.getDestroyProgress} with the held item, summed per tick
 * as {@code MultiPlayerGameMode.continueDestroyBlock} does) reaches one. Nothing is predicted: the block counts as
 * broken only when the SERVER's block update makes it air on the client. A STOP the server acks while the block still
 * stands is a refusal, and the dig starts over the tick after, as a hand still holding the button would. Leaving reach,
 * the tool leaving the hand, or the owner cancelling sends the ABORT_DESTROY_BLOCK that letting go of the button sends.
 * No fixed wait anywhere: the dig ends on the server's answer. {@link #GIVE_UP_TICKS} only ends a dig the server never
 * answers at all, and says so.
 * <p>
 * Every step is logged at INFO under the owner's logger (block, start tick, tool, progress, the server's answers, the
 * finish or why it was abandoned) - grep "Obsidian dig" in latest.log.
 */
public final class ObsidianDig {

    /** A dig the server has not answered in this long (30 s) is given up and reported; never a wait the caller makes. */
    public static final int GIVE_UP_TICKS = 600;
    /** Refusals in a row before the dig is given up rather than started again. */
    private static final int MAX_RESTARTS = 3;
    /** How often a dig still under way logs its progress, in ticks. */
    private static final int PROGRESS_LOG_TICKS = 20;

    public enum Result { RUNNING, BROKEN, ABANDONED }

    private final Logger log;
    private final String tag;

    private BlockPos pos;
    private Direction face;
    private long startGameTime;
    private int ticks;
    private float progress;
    private float perTick;
    private boolean stopSent;
    private int startSeq = -1;
    private int stopSeq = -1;
    private int stopTick;
    private boolean startAckLogged;
    private int refusedAtTick = -1;
    private int restarts;
    private String tool;
    private int lastStageLogged = -2;
    /** Dig tick of the last "held by the gate" line, so a held STOP/restart logs at most once per 20 ticks. */
    private int lastGateHoldLogTick = -100;

    // The server's own break-progress packets for the block being dug (ClientboundBlockDestructionPacket), written by
    // dungeonbreaker.mixin.ObsidianDigPacketMixin on the render thread. One dig is watched at a time.
    private static volatile BlockPos watched;
    private static volatile int serverStage = -1;
    private static volatile int stagePackets;

    public ObsidianDig(Logger log, String tag) {
        this.log = log;
        this.tag = tag;
    }

    /** True for the blocks the Dungeon Breaker digs over time rather than on one START. */
    public static boolean isObsidian(BlockState state) {
        return state != null && state.is(Blocks.OBSIDIAN);
    }

    /** From the destruction-packet hook. Never throws. */
    public static void onServerStage(BlockPos at, int stage) {
        BlockPos w = watched;
        if (w != null && w.equals(at)) {
            serverStage = stage;
            stagePackets++;
        }
    }

    public boolean active() {
        return pos != null;
    }

    public BlockPos pos() {
        return pos;
    }

    /**
     * Sends the dig's START for {@code at} (which must be obsidian, loaded and in reach; the caller has already chosen
     * it, spent its charge and put the breaker in the hand). {@code aim}, when not null, turns the body to the block
     * before each dig packet (Auto Routes' obvious mode, GrimAC RotationBreak).
     */
    public void start(Minecraft client, LocalPlayer player, BlockPos at, Direction digFace, Consumer<BlockPos> aim) {
        ClientLevel level = client.level;
        pos = at.immutable();
        face = digFace;
        startGameTime = level.getGameTime();
        ticks = 0;
        progress = 0f;
        restarts = 0;
        refusedAtTick = -1;
        stopSent = false;
        startAckLogged = false;
        stopSeq = -1;
        lastStageLogged = -2;
        lastGateHoldLogTick = -100;
        watched = pos;
        serverStage = -1;
        stagePackets = 0;
        ItemStack held = player.getMainHandItem();
        String id = ItemIdentity.skyblockId(held);
        tool = (id != null ? id : held.getItem().toString()) + " in slot " + player.getInventory().getSelectedSlot();
        perTick = level.getBlockState(pos).getDestroyProgress(player, level, pos);
        sendStart(client, player, aim);
        log.info("{} Obsidian dig START {} face {} at game tick {} (session tick {}, seq {}, box distance {}) | tool {}"
                        + " | client destroy progress {} a tick ({})",
                tag, pos.toShortString(), face, startGameTime, com.killer560.hub.util.SessionRecorderSink.tick(),
                startSeq, fmt((float) Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(),
                        pos))), tool, fmt(perTick),
                perTick >= 1f ? "instant by the client's count: no STOP, waiting for the server"
                        : perTick <= 0f ? "never by the client's count: holding until the server breaks it"
                        : "STOP due after ~" + (int) Math.ceil(1f / perTick) + " tick(s)");
        // The caller swings once for its tick, as it does for any dig it sends.
    }

    /**
     * One tick of the held dig. Call every client tick while {@link #active()} and send no other dig meanwhile - a START
     * for another block would end this one on the server.
     *
     * @param rangeSq squared reach to the block's box; leaving it lets go of the dig
     * @param mayAct  asked before a STOP or a restarted START goes out; false holds it to a later tick
     */
    public Result tick(Minecraft client, LocalPlayer player, double rangeSq, BooleanSupplier mayAct,
                       Consumer<BlockPos> aim) {
        if (pos == null) {
            return Result.ABANDONED;
        }
        ClientLevel level = client.level;
        if (level == null || player == null) {
            finish(null, "world changed", false);
            return Result.ABANDONED;
        }
        ticks++;
        BlockState state = level.isLoaded(pos) ? level.getBlockState(pos) : null;
        logStage();
        if (state == null) {
            finish(client, "the block's chunk unloaded", false);
            return Result.ABANDONED;
        }
        if (state.isAir()) {
            // Nothing was predicted, so air here is the server's block update.
            log.info("{} Obsidian dig BROKEN {} {} tick(s) after the START (game tick {} -> {}) | {} | client progress"
                            + " {} | server stage {} ({} stage packet(s)) | {} restart(s)", tag, pos.toShortString(),
                    ticks, startGameTime, level.getGameTime(), stopSent ? "STOP sent at tick " + stopTick
                            : "no STOP sent: the server broke it on its own", fmt(progress), serverStage, stagePackets,
                    restarts);
            clear();
            return Result.BROKEN;
        }
        if (!isObsidian(state)) {
            finish(client, "the block became " + state.getBlock().getName().getString(), false);
            return Result.ABANDONED;
        }
        if (com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), pos) > rangeSq) {
            finish(client, "out of reach (" + fmt((float) Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(
                    player.getEyePosition(), pos))) + " blocks)", true);
            return Result.ABANDONED;
        }
        if (!ItemIdentity.isDungeonBreaker(player.getMainHandItem())) {
            String id = ItemIdentity.skyblockId(player.getMainHandItem());
            finish(client, "the Dungeon Breaker left the hand (now " + (id != null ? id
                    : player.getMainHandItem().getItem().toString()) + ")", true);
            return Result.ABANDONED;
        }
        if (ticks > GIVE_UP_TICKS) {
            finish(client, "no break from the server after " + ticks + " tick(s)", true);
            return Result.ABANDONED;
        }
        if (!startAckLogged && com.killer560.hub.util.ChestWindows.acked(startSeq)) {
            startAckLogged = true;
            log.info("{} Obsidian dig {}: server acked the START (seq {}) by tick {}, block still obsidian", tag,
                    pos.toShortString(), startSeq, ticks);
        }
        // A hand holding the button on a block swings every tick.
        player.swing(InteractionHand.MAIN_HAND);
        if (stopSent) {
            if (refusedAtTick < 0 && com.killer560.hub.util.ChestWindows.acked(stopSeq)) {
                // The ack and the block update can land in either order; the refusal is read a tick later.
                refusedAtTick = ticks;
            } else if (refusedAtTick >= 0 && ticks > refusedAtTick) {
                if (restarts >= MAX_RESTARTS) {
                    finish(client, "the server refused the STOP " + (restarts + 1) + " time(s)", true);
                    return Result.ABANDONED;
                }
                if (!mayAct.getAsBoolean()) {
                    logGateHold("restart START");
                    return Result.RUNNING;
                }
                restarts++;
                log.info("{} Obsidian dig {}: server acked the STOP (seq {}) and the block is still obsidian - refused,"
                        + " starting over (restart {}) at tick {}", tag, pos.toShortString(), stopSeq, restarts, ticks);
                progress = 0f;
                stopSent = false;
                refusedAtTick = -1;
                startAckLogged = false;
                sendStart(client, player, aim);
                log.info("{} [ObsidianDig] restart START_DESTROY_BLOCK sent: pos={} seq={} digTick={} tick={}", tag,
                        pos.toShortString(), startSeq, ticks, com.killer560.hub.util.SessionRecorderSink.tick());
            }
            return Result.RUNNING;
        }
        if (perTick < 1f) {
            perTick = state.getDestroyProgress(player, level, pos);
            progress += perTick;
        }
        if (progress >= 1f && perTick < 1f) {
            if (!mayAct.getAsBoolean()) {
                logGateHold("STOP_DESTROY_BLOCK");
                return Result.RUNNING;
            }
            if (aim != null) {
                aim.accept(pos);
            }
            sendAction(client, player, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, true);
            stopSent = true;
            stopTick = ticks;
            log.info("{} Obsidian dig {}: client progress {} at tick {} - STOP_DESTROY_BLOCK sent (seq {}), waiting for"
                    + " the server's block update", tag, pos.toShortString(), fmt(progress), ticks, stopSeq);
            return Result.RUNNING;
        }
        if (ticks % PROGRESS_LOG_TICKS == 0) {
            log.info("{} Obsidian dig {}: still obsidian at tick {} | client progress {} ({} a tick) | server stage {}"
                            + " ({} stage packet(s)) | START {}", tag, pos.toShortString(), ticks, fmt(progress),
                    fmt(perTick), serverStage, stagePackets, startAckLogged ? "acked" : "not acked yet");
        }
        return Result.RUNNING;
    }

    /** The owner cancelled the dig (route stopped, another node fired, the aura switched off...). Lets go of it. */
    public void abandon(Minecraft client, String reason) {
        if (pos == null) {
            return;
        }
        finish(client, reason, true);
    }

    private void finish(Minecraft client, String reason, boolean letGo) {
        boolean sentAbort = false;
        if (letGo && client != null && client.player != null && client.getConnection() != null && client.level != null
                && client.level.isLoaded(pos) && isObsidian(client.level.getBlockState(pos))) {
            // What vanilla's stopDestroyBlock sends when the button comes up mid-dig.
            client.player.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, Direction.DOWN));
            sentAbort = true;
        }
        log.info("{} Obsidian dig ABANDONED {} after {} tick(s) (started game tick {}): {} | client progress {} | {} |"
                        + " server stage {} ({} stage packet(s)) | {} restart(s) | tool {}{}", tag, pos.toShortString(),
                ticks, startGameTime, reason, fmt(progress), stopSent ? "STOP sent at tick " + stopTick : "no STOP sent",
                serverStage, stagePackets, restarts, tool, sentAbort ? " | ABORT_DESTROY_BLOCK sent" : "");
        clear();
    }

    private void clear() {
        if (watched != null && watched.equals(pos)) {
            watched = null;
        }
        pos = null;
        face = null;
        stopSent = false;
        startSeq = -1;
        stopSeq = -1;
        startAckLogged = false;
        refusedAtTick = -1;
    }

    private void sendStart(Minecraft client, LocalPlayer player, Consumer<BlockPos> aim) {
        if (aim != null) {
            aim.accept(pos);
        }
        sendAction(client, player, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, false);
    }

    /** A sequenced dig packet through vanilla's prediction, with NO local block change - the server decides. */
    private void sendAction(Minecraft client, LocalPlayer player, ServerboundPlayerActionPacket.Action action,
                            boolean stop) {
        final BlockPos at = pos;
        final Direction f = face;
        if (client.gameMode instanceof MultiPlayerGameModeInvoker invoker) {
            invoker.killer560smod$invokeStartPrediction(client.level, sequence -> {
                if (stop) {
                    stopSeq = sequence;
                } else {
                    startSeq = sequence;
                }
                return new ServerboundPlayerActionPacket(action, at, f, sequence);
            });
        } else {
            player.connection.send(new ServerboundPlayerActionPacket(action, at, f));
        }
    }

    /** The owner's gate (ActionGate) refused this tick, so the STOP or restart waits a tick. Rate-limited. */
    private void logGateHold(String what) {
        if (ticks - lastGateHoldLogTick < 20) {
            return;
        }
        lastGateHoldLogTick = ticks;
        log.info("{} [ObsidianDig] {} held: pos={} digTick={} progress={} restarts={} tick={} - the action gate gave"
                        + " this tick to something else, trying next tick", tag, what,
                pos == null ? "null" : pos.toShortString(), ticks, fmt(progress), restarts,
                com.killer560.hub.util.SessionRecorderSink.tick());
    }

    private void logStage() {
        int s = serverStage;
        if (s != lastStageLogged && stagePackets > 0) {
            lastStageLogged = s;
            log.info("{} Obsidian dig {}: server break stage {} at tick {} (client progress {})", tag,
                    pos.toShortString(), s, ticks, fmt(progress));
        }
    }

    private static String fmt(float f) {
        return String.format(Locale.US, "%.3f", f);
    }
}
