package com.killer560.hub.roomsim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * What the Dungeon Breaker cannot break in the sim (killer560, 2026-11-12): smooth (polished) granite, pistons, dispensers, a block with fire on it, and any block a
 * button, torch, lever, sign, ladder or the like hangs on. Asked by the sim's server-side break and, through
 * {@code BreakerGuard.protects}, by Zero Ping, Breaker Aura and Auto Routes while the sim runs. Hypixel is not touched.
 */
public final class SimBreakRules {
    private static final Direction[] SIDES = Direction.values();

    private SimBreakRules() {
    }

    public static boolean refuses(BlockGetter level, BlockPos pos) {
        Block b = level.getBlockState(pos).getBlock();
        if (b == Blocks.POLISHED_GRANITE || b == Blocks.DISPENSER || b instanceof PistonBaseBlock || b instanceof PistonHeadBlock
                || b instanceof MovingPistonBlock) {
            return true;
        }
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : SIDES) {
            n.setWithOffset(pos, d);
            if (level.getBlockState(n).getBlock() instanceof BaseFireBlock) {
                return true; // fire or soul fire burning on it
            }
            Direction hang = support(level.getBlockState(n));
            if (hang != null && n.relative(hang).equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /** The direction from {@code s} (at {@code at}) to the block it hangs on, or null when it hangs on nothing. */
    private static Direction support(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof FaceAttachedHorizontalDirectionalBlock) {
            AttachFace f = s.getValue(BlockStateProperties.ATTACH_FACE);
            return f == AttachFace.FLOOR ? Direction.DOWN : f == AttachFace.CEILING ? Direction.UP
                    : s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        }
        if (b instanceof BaseTorchBlock && s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        }
        if (b instanceof LadderBlock || b instanceof WallSignBlock || b instanceof TripWireHookBlock) {
            return s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        }
        if (b instanceof BaseTorchBlock || b instanceof RedstoneWireBlock || b instanceof BasePressurePlateBlock) {
            return Direction.DOWN;
        }
        return null;
    }
}
