package com.killer560.hub.ap3;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The clicking half of {@code /ap3 edit db}: right-click a block to add it to the breaker node being edited,
 * shift-right-click to remove it. While {@link Ap3Feature#isEditMode()} is on, a right-click on a block goes to
 * {@link Ap3Feature#onEditRightClick} and the real interaction is suppressed so the held item doesn't also fire.
 * <p>
 * Same {@link UseBlockCallback} approach as {@code autoroutes/AutoRoutesEditInput} (no mixin needed - verified
 * against fabric-events-interaction-v0 and the 26.1.2 jar: the client-side hook runs at the top of
 * {@code MultiPlayerGameMode#useItemOn}; returning {@link InteractionResult#FAIL} sends no packet and ends
 * {@code Minecraft#startUseItem} before the item use and the off-hand pass). Every click in edit mode is suppressed,
 * consumed or not: edit mode is a deliberate, temporary state the user typed a command to enter.
 */
public final class Ap3EditInput {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ap3");

    /** Vanilla repeats a held right-click every 4 ticks (200 ms); anything faster than this on the same block with
     *  the same shift state is that auto-repeat, not a second deliberate click. */
    private static final long HOLD_REPEAT_MS = 350;

    private static BlockPos lastPos;
    private static boolean lastShift;
    private static long lastAtMs;

    private Ap3EditInput() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient} (see API.md). */
    public static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            try {
                if (hit == null || level == null || !level.isClientSide()) {
                    return InteractionResult.PASS;
                }
                if (!Ap3Feature.isEditMode() || !Ap3Config.getInstance().isEnabledRaw()) {
                    return InteractionResult.PASS;
                }
                if (hand != InteractionHand.MAIN_HAND) {
                    return InteractionResult.FAIL;
                }
                BlockPos pos = hit.getBlockPos();
                boolean shift = player.isShiftKeyDown();
                long now = System.currentTimeMillis();
                boolean autoRepeat = pos.equals(lastPos) && shift == lastShift && now - lastAtMs < HOLD_REPEAT_MS;
                lastPos = pos;
                lastShift = shift;
                lastAtMs = now;
                if (!autoRepeat) {
                    Ap3Feature.onEditRightClick(pos, shift);
                }
                return InteractionResult.FAIL;
            } catch (Exception e) {
                LOGGER.warn("[AP3] edit click failed", e);
                return InteractionResult.PASS;
            }
        });
    }

    /** Forget the hold-repeat state - call when edit mode is turned off so the next click is always fresh. */
    public static void reset() {
        lastPos = null;
        lastAtMs = 0L;
    }
}
