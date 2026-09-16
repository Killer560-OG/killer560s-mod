package com.killer560.hub.autoroutes;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The clicking half of {@code /ar edit db}: "lets me right-click blocks to add them to that breaker and
 * shift-right-click to remove them" (killer560, 2026-09-16). While {@link AutoRoutesFeature#isEditMode()} is on,
 * a right-click on a block goes to {@link AutoRoutesFeature#onEditRightClick} and the real interaction is
 * suppressed so the held item (AOTV, sceptre, superboom...) doesn't also fire.
 * <p>
 * No mixin needed - this is exactly what Fabric's {@link UseBlockCallback} is for, and this repo already uses
 * it (KingRelicsFeature). Verified against fabric-events-interaction-v0 5.2.6 + the 26.1.2 jar: the client-side
 * hook runs at the top of {@code MultiPlayerGameMode#useItemOn}; a non-PASS result that doesn't
 * {@code consumesAction()} (i.e. {@link InteractionResult#FAIL}) sends NO {@code ServerboundUseItemOnPacket}, and
 * {@code Minecraft#startUseItem} returns as soon as {@code useItemOn} yields a {@code Fail} - so neither the block
 * use nor the follow-up item use (nor the off-hand pass) happens. QUOI does the same job by watching outgoing
 * packets; hooking before the packet exists is the cleaner way to get "don't also use your item".
 * <p>
 * Every click in edit mode is suppressed, consumed by the feature or not: edit mode is a deliberate, temporary
 * state the user typed a command to enter, and a mis-aimed click firing a teleport mid-edit is worse than a chest
 * not opening until {@code /ar edit db} is typed again.
 */
public final class AutoRoutesEditInput {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod/autoroutes");

    /** Vanilla repeats a held right-click every 4 ticks (200 ms); anything faster than this on the same block with
     *  the same shift state is that auto-repeat, not a second deliberate click. */
    private static final long HOLD_REPEAT_MS = 350;

    private static BlockPos lastPos;
    private static boolean lastShift;
    private static long lastAtMs;

    private AutoRoutesEditInput() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient} (see INTEGRATION.md). */
    public static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            try {
                if (hit == null || level == null || !level.isClientSide()) {
                    return InteractionResult.PASS;
                }
                if (!AutoRoutesFeature.isEditMode() || !AutoRoutesConfig.getInstance().isEnabledRaw()) {
                    return InteractionResult.PASS;
                }
                // The main-hand FAIL already ends startUseItem before the off-hand pass, but if anything else
                // short-circuits that order, the off-hand must not slip through and use its item either.
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
                    AutoRoutesFeature.onEditRightClick(pos, shift);
                }
                return InteractionResult.FAIL;
            } catch (Exception e) {
                // Never let an edit-mode bug turn every right-click into a crash; log once per click and let the
                // click through as if edit mode were off.
                LOGGER.warn("[AutoRoutes] edit click failed", e);
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
