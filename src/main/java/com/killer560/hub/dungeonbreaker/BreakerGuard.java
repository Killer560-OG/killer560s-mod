package com.killer560.hub.dungeonbreaker;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DummyLevers;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.secrets.SecretsFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a Dungeon Breaker (Zero Ping, Breaker Aura, an Auto Routes breaker node) must never break
 * (killer560, 2026-10-18: "make an option for no breaking secrets and add in the fairy room stuff but not for
 * puzzles"). Two rules, one answer for every caller:
 * <ul>
 * <li>Anything inside a Fairy room - always, no setting. The room is the one over the BLOCK, by the map's own room
 * type ({@link LiveMapFeature#roomTypeAt}).</li>
 * <li>A dungeon secret block, when {@link DungeonBreakerConfig#isNoBreakSecrets} is on. The kinds are the Secret
 * Triggerbot's (QUOI {@code Dungeon.isSecret}): chests, trapped chests, levers that are not dummies, and the Wither
 * Essence / Redstone Key skulls.</li>
 * </ul>
 * Puzzle rooms are deliberately not touched by either rule.
 */
public final class BreakerGuard {

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-dungeonbreaker");
    /** Positions currently reported as protected, with the reason - logged on change only, since Breaker Aura asks about
     *  every pick every tick. Bounded; cleared when it grows past the cap (the next verdicts simply log again). */
    private static final java.util.Map<BlockPos, String> PROTECTED_LOGGED =
            new java.util.concurrent.ConcurrentHashMap<>(); // AP3's planner may ask from another thread
    private static final int PROTECTED_LOG_CAP = 4096;

    private BreakerGuard() {
    }

    /** True when the breaker must leave this block alone. */
    public static boolean protects(BlockGetter level, BlockPos pos) {
        if (inFairyRoom(pos)) {
            logVerdict(pos, "inside a Fairy room");
            return true;
        }
        boolean secret = DungeonBreakerConfig.getInstance().isNoBreakSecrets() && isSecretBlock(level, pos);
        if (secret) {
            logVerdict(pos, "secret block (" + level.getBlockState(pos).getBlock().getName().getString()
                    + ") with Don't break secrets on");
        } else if (!PROTECTED_LOGGED.isEmpty() && pos != null) {
            String was = PROTECTED_LOGGED.remove(pos);
            if (was != null) {
                LOGGER.info("[BreakerGuard] no longer protected: pos={} was={} tick={}", pos.toShortString(), was,
                        com.killer560.hub.util.SessionRecorderSink.tick());
            }
        }
        return secret;
    }

    /** Logs a refusal the first time a position is refused for a reason; side-effect free apart from the log map. */
    private static void logVerdict(BlockPos pos, String reason) {
        try {
            if (pos == null || reason.equals(PROTECTED_LOGGED.get(pos))) {
                return;
            }
            if (PROTECTED_LOGGED.size() >= PROTECTED_LOG_CAP) {
                PROTECTED_LOGGED.clear();
            }
            PROTECTED_LOGGED.put(pos.immutable(), reason);
            LOGGER.info("[BreakerGuard] refused: pos={} reason={} noBreakSecrets={} tick={}", pos.toShortString(),
                    reason, DungeonBreakerConfig.getInstance().isNoBreakSecrets(),
                    com.killer560.hub.util.SessionRecorderSink.tick());
        } catch (RuntimeException ignored) {
            // logging only - never let it change the guard's answer
        }
    }

    public static boolean inFairyRoom(BlockPos pos) {
        if (!DungeonState.isInDungeon()) {
            return false;
        }
        return "FAIRY".equals(LiveMapFeature.roomTypeAt(pos.getX() + 0.5, pos.getZ() + 0.5));
    }

    public static boolean isSecretBlock(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            return true;
        }
        if (block == Blocks.LEVER) {
            return !DummyLevers.isDummy(pos);
        }
        if (block instanceof AbstractSkullBlock) {
            return SecretsFeature.isWitherEssence(level, pos) || SecretsFeature.isRedstoneKey(level, pos);
        }
        return false;
    }
}
