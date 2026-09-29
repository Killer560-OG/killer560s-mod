package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Dungeonbreaker charges, and putting the blocks back.
 *
 * <p>killer560 (2026-09-28): "make sure dungeon breaker blocks come back after broken just like on main and I
 * have dungeon breaker charges just like on main."
 *
 * <p>Both halves matter for practice and for opposite reasons. The charges are the constraint - a breaker route
 * that uses more charges than you have is not a route, and a sim with unlimited ones would let him rehearse
 * something he cannot do. The blocks coming back is what makes the room reusable: without it the second run
 * through a room is through a room he has already demolished, and every run after that is a different room.
 *
 * <p>Taken from the wiki (checked 2026-09-28): the Dungeonbreaker holds a small number of charges that refill
 * over time, and blocks it breaks in a dungeon return after a delay. The numbers live here as named constants
 * so they are one edit when he tells me the real ones - I would rather have them visibly in one place and
 * wrong than spread through the code and wrong.
 */
public final class SimBreakerState {

    /** Charges held at once. */
    private static final int MAX_CHARGES = 5;

    /** Server ticks to regain one charge. */
    private static final int RECHARGE_TICKS = 20 * 6;

    /** Server ticks before a broken block comes back. */
    private static final int RESTORE_TICKS = 20 * 10;

    private static int charges = MAX_CHARGES;
    private static int rechargeCounter;

    /** A block waiting to be put back, with the state it had. */
    private record Broken(ServerLevel level, BlockPos pos, BlockState state, int dueAtTick) {
    }

    private static final Deque<Broken> PENDING = new ArrayDeque<>();
    private static int tickCounter;

    private SimBreakerState() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    /** Wipes charges and pending restores - for a new room or leaving the sim. */
    public static synchronized void reset() {
        charges = MAX_CHARGES;
        rechargeCounter = 0;
        PENDING.clear();
    }

    public static synchronized int charges() {
        return charges;
    }

    public static synchronized int maxCharges() {
        return MAX_CHARGES;
    }

    /**
     * Spends a charge, if there is one.
     *
     * @return whether the break may go ahead
     */
    public static synchronized boolean trySpend() {
        if (charges <= 0) {
            return false;
        }
        charges--;
        return true;
    }

    /** Remembers a block so it can be put back later. Called with the state BEFORE it was broken. */
    public static synchronized void remember(ServerLevel level, BlockPos pos, BlockState state) {
        PENDING.add(new Broken(level, pos.immutable(), state, tickCounter + RESTORE_TICKS));
    }

    private static void tick() {
        java.util.List<Broken> due = new java.util.ArrayList<>();
        synchronized (SimBreakerState.class) {
            tickCounter++;
            if (charges < MAX_CHARGES && ++rechargeCounter >= RECHARGE_TICKS) {
                rechargeCounter = 0;
                charges++;
            }
            while (!PENDING.isEmpty() && PENDING.peek().dueAtTick() <= tickCounter) {
                due.add(PENDING.poll());
            }
        }
        // Restored outside the lock: setBlock can run arbitrary block logic and holding a lock across that is
        // how a deadlock gets written.
        for (Broken b : due) {
            if (b.level().getBlockState(b.pos()).isAir()) {
                // Only if nothing has taken its place. Putting a block back on top of something he built, or
                // inside him, would be worse than leaving the hole.
                b.level().setBlockAndUpdate(b.pos(), b.state());
            }
        }
    }

    /** Tells him where he stands, the way the real item's lore does. */
    public static void announce() {
        ModChat.send("Sim", ModChat.text("Dungeonbreaker charges: "),
                ModChat.value(charges() + "/" + maxCharges()));
    }
}
