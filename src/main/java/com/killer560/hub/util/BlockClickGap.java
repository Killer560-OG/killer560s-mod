package com.killer560.hub.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * Hypixel drops an item use that arrives right after a right click on a BLOCK made with the same item in hand.
 * <p>
 * killer560's Mod Only Test log of a real Red Blue / Raccoon run (2026-10-14 17:22-17:29): six Auto Routes etherwarps sent
 * one tick after a chest or lever click had met the node's await - Secret Aura's or his own, the AOTV already in hand -
 * got "no landing after 61 ticks", with no chat line at all (a refused warp says "There are blocks in the way!"), and
 * every retry 3 s later landed. The one sent a tick after a click made with ANOTHER item (a hotbar swap in the same
 * tick) landed, and so did every one sent a tick after a left-click skip. So the server ignores a use of the item he
 * just clicked a block with. How long that lasts was not measured (only the 1-tick gap is in the log); the gap kept here
 * is vanilla's own right-click cadence, {@code rightClickDelay} 4, and at least 200 ms of real time so a client
 * catching up on ticks cannot squeeze it.
 * <p>
 * Fed by {@code AwaitEventsGameModeMixin} at RETURN of {@code MultiPlayerGameMode.useItemOn} (every block click the
 * client sends - vanilla's and every feature's). Client thread only.
 */
public final class BlockClickGap {

    /** Client ticks a use of the clicking item waits after the click (vanilla rightClickDelay). */
    public static final int MIN_TICKS = 4;
    /** And real time, for a client catching up on ticks. */
    public static final long MIN_NANOS = 200_000_000L;

    private static long clickTick = Long.MIN_VALUE / 2;
    private static long clickNanos;
    private static ItemStack clickItem = ItemStack.EMPTY;
    private static BlockPos clickPos;

    private BlockClickGap() {
    }

    /** A block click went out with {@code held} in the hand. */
    public static void onBlockClick(ItemStack held, BlockPos pos) {
        clickTick = ActionGate.currentTick();
        clickNanos = System.nanoTime();
        clickItem = held == null ? ItemStack.EMPTY : held.copy();
        clickPos = pos == null ? null : pos.immutable();
    }

    /** Ticks a use of {@code held} must still wait, 0 when it may go now (no recent click, or one made with another item). */
    public static int ticksLeft(ItemStack held) {
        if (held == null || held.isEmpty() || clickItem.isEmpty() || !ItemStack.isSameItemSameComponents(held, clickItem)) {
            return 0;
        }
        long ticks = ActionGate.currentTick() - clickTick;
        if (ticks < 0 || ticks >= 40) {
            return 0;
        }
        int byTicks = (int) Math.max(0, MIN_TICKS - ticks);
        long nanosLeft = MIN_NANOS - (System.nanoTime() - clickNanos);
        int byTime = nanosLeft <= 0 ? 0 : (int) Math.ceil(nanosLeft / 50_000_000.0);
        return Math.max(byTicks, byTime);
    }

    /** Ticks since the last block click (any item), or -1 when there was none in the last 40. */
    public static long ticksSinceClick() {
        long ticks = ActionGate.currentTick() - clickTick;
        return ticks < 0 || ticks >= 40 ? -1 : ticks;
    }

    public static BlockPos lastClickPos() {
        return clickPos;
    }

    public static void reset() {
        clickTick = Long.MIN_VALUE / 2;
        clickItem = ItemStack.EMPTY;
        clickPos = null;
    }
}
