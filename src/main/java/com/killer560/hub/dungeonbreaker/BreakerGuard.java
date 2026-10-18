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

    private BreakerGuard() {
    }

    /** True when the breaker must leave this block alone. */
    public static boolean protects(BlockGetter level, BlockPos pos) {
        if (inFairyRoom(pos)) {
            return true;
        }
        return DungeonBreakerConfig.getInstance().isNoBreakSecrets() && isSecretBlock(level, pos);
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
