package com.killer560.hub.roomsim.mixin;

import com.killer560.hub.roomsim.SimStaticWater;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.WaterFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds sim water still outside the Water Board - see {@link SimStaticWater}.
 *
 * <p>{@code FlowingFluid.tick(ServerLevel, BlockPos, BlockState, FluidState)} is the scheduled fluid tick that
 * spreads, drains and converts water; {@code WaterFluid} does not override it. Same descriptor in 26.1.2 and 26.2
 * (javap, 2026-10-13), so one copy serves both.
 */
@Mixin(FlowingFluid.class)
public abstract class SimStaticWaterMixin {

    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V",
            at = @At("HEAD"), cancellable = true)
    private void killer560smod$staticSimWater(ServerLevel level, BlockPos pos, BlockState blockState,
                                              FluidState fluidState, CallbackInfo ci) {
        if ((Object) this instanceof WaterFluid && SimStaticWater.shouldFreeze(level, pos)) {
            ci.cancel();
        }
    }
}
