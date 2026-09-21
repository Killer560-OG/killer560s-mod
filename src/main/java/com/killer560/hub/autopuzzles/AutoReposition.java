package com.killer560.hub.autopuzzles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java port of QUOI {@code puzzlesolvers/Repositionable.kt}: etherwarp onto a standing spot. Sequence per QUOI:
 * swap to Aspect of the Void/End, hold sneak (+2 tick delay if it wasn't held), optionally wait until standing still,
 * use the item aimed at the spot's visible face ({@link AutoPuzzleUtil#etherwarpDirection}), wait until
 * {@code player.at(spot)}, then either swap to the shortbow (bow puzzles - sneak stays held, like QUOI) or release
 * sneak and wait 2 ticks. Additions for safety: gated on the "Etherwarp Reposition" toggle by callers, a 40-tick
 * arrival timeout, and the held item is re-checked to be the AOTV right before use.
 */
final class AutoReposition {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autopuzzles");
    private static final int ARRIVE_TIMEOUT_TICKS = 40;

    /** True while sneak is being held down by an auto (released by {@link #releaseSneak}). */
    private static boolean sneakHeldByUs = false;

    private enum Stage { IDLE, SNEAK_DELAY, AWAIT_STAND, USE, AWAIT_ARRIVE, AFTER_BOW, RELEASE_DELAY }

    private final String tag;
    private Stage stage = Stage.IDLE;
    private BlockPos spot;
    private boolean bow;
    private boolean awaitStand;
    private int wait;
    private boolean swapOk;

    AutoReposition(String tag) {
        this.tag = tag;
    }

    boolean isActive() {
        return stage != Stage.IDLE;
    }

    /** QUOI {@code reposition(spot, bow, stand, awaitStand)}. No-op if already running / no direction. */
    void start(Minecraft client, BlockPos target, boolean bowAfter, boolean stand, boolean awaitStandStill) {
        LocalPlayer player = client.player;
        if (isActive() || player == null || client.level == null) {
            return;
        }
        if (stand && AutoPuzzleUtil.isMoving(player)) {
            return;
        }
        if (AutoPuzzleUtil.etherwarpDirection(client.level, player, target) == null) {
            return;
        }
        spot = target;
        bow = bowAfter;
        awaitStand = awaitStandStill;
        swapOk = AutoPuzzleUtil.swapTo(client, player, AutoPuzzleUtil::isAotv);
        LOGGER.info("[AutoPuzzles] {}: etherwarp reposition to {} (bow={} swapOk={})", tag, target, bowAfter, swapOk);
        if (!client.options.keyShift.isDown()) {
            client.options.keyShift.setDown(true);
            sneakHeldByUs = true;
            wait = 2;
            stage = Stage.SNEAK_DELAY;
        } else {
            stage = awaitStand ? Stage.AWAIT_STAND : Stage.USE;
        }
        tick(client);
    }

    /** Runs the sequence; call every tick while {@link #isActive()}. */
    void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            cancel(client);
            return;
        }
        switch (stage) {
            case SNEAK_DELAY -> {
                client.options.keyShift.setDown(true);
                if (--wait <= 0) {
                    stage = awaitStand ? Stage.AWAIT_STAND : Stage.USE;
                }
            }
            case AWAIT_STAND -> {
                if (!AutoPuzzleUtil.isMoving(player)) {
                    stage = Stage.USE;
                    tick(client);
                }
            }
            case USE -> {
                if (!swapOk) {
                    swapOk = AutoPuzzleUtil.swapTo(client, player, AutoPuzzleUtil::isAotv);
                }
                float[] dir = AutoPuzzleUtil.etherwarpDirection(client.level, player, spot);
                if (!swapOk || dir == null || !AutoPuzzleUtil.isAotv(player.getMainHandItem())) {
                    LOGGER.info("[AutoPuzzles] {}: reposition cancelled (swapOk={} dir={} held={})", tag, swapOk,
                            dir != null, AutoPuzzleUtil.skyblockId(player.getMainHandItem()));
                    cancel(client);
                    return;
                }
                if (!AutoPuzzleUtil.useItemRotated(client, player, dir[0], dir[1])) {
                    return; // gate held this tick back - stay in USE and warp on a later tick
                }
                // Our own warp - waive the gate's teleport stand-down so the arrival step isn't held off too.
                com.killer560.hub.util.ActionGate.expectSelfTeleport(com.killer560.hub.util.ActionGate.Actor.PUZZLE_WORLD);
                wait = ARRIVE_TIMEOUT_TICKS;
                stage = Stage.AWAIT_ARRIVE;
            }
            case AWAIT_ARRIVE -> {
                if (AutoPuzzleUtil.at(player, spot)) {
                    stage = bow ? Stage.AFTER_BOW : Stage.RELEASE_DELAY;
                    wait = 2;
                    if (!bow) {
                        releaseSneak(client);
                    }
                    tick(client);
                } else if (--wait <= 0) {
                    LOGGER.info("[AutoPuzzles] {}: reposition to {} timed out", tag, spot);
                    cancel(client);
                }
            }
            case AFTER_BOW -> {
                if (AutoPuzzleUtil.swapTo(client, player, AutoPuzzleUtil::isShortbow) || --wait <= 0) {
                    stage = Stage.IDLE;
                }
            }
            case RELEASE_DELAY -> {
                if (--wait <= 0) {
                    stage = Stage.IDLE;
                }
            }
            default -> {
            }
        }
    }

    void cancel(Minecraft client) {
        if (stage != Stage.IDLE && sneakHeldByUs) {
            releaseSneak(client);
        }
        stage = Stage.IDLE;
    }

    /** Releases sneak only if an auto pressed it (QUOI {@code mc.options.keyShift.isDown = false} on finish). */
    static void releaseSneak(Minecraft client) {
        if (sneakHeldByUs) {
            sneakHeldByUs = false;
            client.options.keyShift.setDown(false);
        }
    }
}
