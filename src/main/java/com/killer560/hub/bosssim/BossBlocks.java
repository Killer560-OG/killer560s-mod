package com.killer560.hub.bosssim;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block states by their vanilla text ({@code minecraft:lever[face=floor,powered=false]}), parsed once and cached. Text
 * rather than {@code Blocks.X.defaultBlockState().setValue(...)} so one source compiles on 26.3 and 26.2 (the colored
 * block constants and some property holders moved between them; the text never did).
 */
final class BossBlocks {

    private static final Map<String, BlockState> CACHE = new ConcurrentHashMap<>();

    /** Clients told, no shape or neighbor updates: nothing pops off and nothing flows when it is written. */
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private BossBlocks() {
    }

    static BlockState of(String text) {
        return CACHE.computeIfAbsent(text, t -> {
            try {
                return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, t, false).blockState();
            } catch (Exception e) {
                throw new IllegalArgumentException("bad block state '" + t + "': " + e.getMessage(), e);
            }
        });
    }

    /** Writes one block if it differs (clients told, nothing updated around it). */
    static void set(ServerLevel level, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos) != state) {
            level.setBlock(pos, state, FLAGS);
        }
    }

    static void set(ServerLevel level, int x, int y, int z, String state) {
        set(level, new BlockPos(x, y, z), of(state));
    }

    /** Fills an inclusive box (any corner order). */
    static void fill(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    set(level, new BlockPos(x, y, z), state);
                }
            }
        }
    }
}
