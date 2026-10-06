package com.killer560.hub.autopuzzles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

/**
 * Java port of QUOI {@code puzzlesolvers/Repositionable.kt}: etherwarp onto a standing spot. Sequence per QUOI:
 * swap to Aspect of the Void/End, hold sneak (+2 tick delay if it wasn't held), optionally wait until standing still,
 * use the item aimed at the spot's visible face ({@link AutoPuzzleUtil#etherwarpDirection}), wait until
 * {@code player.at(spot)}, then either swap to the shortbow (bow puzzles - sneak stays held, like QUOI) or release
 * sneak and wait 2 ticks. Additions for safety: gated on the "Etherwarp Reposition" toggle by callers, a 40-tick
 * arrival timeout, and the held item is re-checked to be the AOTV right before use.
 */
final class AutoReposition {

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");
    private static final int ARRIVE_TIMEOUT_TICKS = 40;

    /** True while sneak is being held down by an auto (released by {@link #releaseSneak}). */
    private static boolean sneakHeldByUs = false;

    private enum Stage { IDLE, SNEAK_DELAY, AWAIT_STAND, USE, AWAIT_ARRIVE, AFTER_BOW, RELEASE_DELAY }

    private final String tag;
    /** The owning auto's free camera, taken before the warp's aim turns him (see {@link FreeCam}). */
    private final FreeCam camera;
    private Stage stage = Stage.IDLE;
    private BlockPos spot;
    private boolean bow;
    private boolean awaitStand;
    private int wait;
    private boolean swapOk;
    /** The last "not started" reason logged, so a per-tick refusal is one line, not twenty a second. */
    private String lastRefusal;

    AutoReposition(String tag, FreeCam camera) {
        this.tag = tag;
        this.camera = camera;
    }

    boolean isActive() {
        return stage != Stage.IDLE;
    }

    /** QUOI {@code reposition(spot, bow, stand, awaitStand)}. No-op if already running / no direction.
     *  @return true if a reposition is now running (started now or already was) */
    boolean start(Minecraft client, BlockPos target, boolean bowAfter, boolean stand, boolean awaitStandStill) {
        LocalPlayer player = client.player;
        if (isActive()) {
            return true;
        }
        if (player == null || client.level == null) {
            return false;
        }
        if (stand && AutoPuzzleUtil.isMoving(player)) {
            return false;
        }
        if (AutoPuzzleUtil.etherwarpAim(client.level, player, target) == null) {
            // Was silent: an auto whose reposition target cannot be seen just sat there with nothing in the log.
            String why = "no etherwarp aim onto " + AutoPuzzleUtil.fmt(target) + " from where you stand";
            if (!why.equals(lastRefusal)) {
                lastRefusal = why;
                LOGGER.info("[AutoPuzzles] {}: reposition not started - {}", tag, why);
            }
            return false;
        }
        lastRefusal = null;
        spot = target;
        bow = bowAfter;
        awaitStand = awaitStandStill;
        swapOk = AutoPuzzleUtil.swapTo(client, player, AutoPuzzleUtil::isAotv);
        if (!client.options.keyShift.isDown()) {
            client.options.keyShift.setDown(true);
            sneakHeldByUs = true;
            wait = 2;
            stage = Stage.SNEAK_DELAY;
        } else {
            stage = awaitStand ? Stage.AWAIT_STAND : Stage.USE;
        }
        LOGGER.info("[AutoPuzzles] {}: repositioning onto {} (swap to AOTV {})", tag, AutoPuzzleUtil.fmt(target),
                swapOk ? "ok" : "pending");
        tick(client);
        return true;
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
                float[] dir = AutoPuzzleUtil.etherwarpAim(client.level, player, spot);
                if (!swapOk || dir == null || !AutoPuzzleUtil.isAotv(player.getMainHandItem())) {
                    LOGGER.info("[AutoPuzzles] {}: reposition cancelled (swapOk={} dir={} held={})", tag, swapOk,
                            dir != null, AutoPuzzleUtil.skyblockId(player.getMainHandItem()));
                    cancel(client);
                    return;
                }
                camera.engage(player); // before the aim turns him, so the held view is where he was looking
                if (!AutoPuzzleUtil.useItemRotated(client, player, dir[0], dir[1])) {
                    return; // gate held this tick back - stay in USE and warp on a later tick
                }
                LOGGER.info("[AutoPuzzles] {}: warp sent onto {} (yaw {} pitch {}, holding {}, sneaking {})", tag,
                        AutoPuzzleUtil.fmt(spot), String.format(java.util.Locale.ROOT, "%.1f", dir[0]),
                        String.format(java.util.Locale.ROOT, "%.1f", dir[1]),
                        AutoPuzzleUtil.skyblockId(player.getMainHandItem()), player.isShiftKeyDown());
                // Our own warp - waive the gate's teleport stand-down so the arrival step isn't held off too.
                com.killer560.hub.util.ActionGate.expectSelfTeleport(com.killer560.hub.util.ActionGate.Actor.PUZZLE_WORLD);
                wait = ARRIVE_TIMEOUT_TICKS;
                stage = Stage.AWAIT_ARRIVE;
            }
            case AWAIT_ARRIVE -> {
                if (AutoPuzzleUtil.at(player, spot)) {
                    LOGGER.info("[AutoPuzzles] {}: arrived on {}", tag, AutoPuzzleUtil.fmt(spot));
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
