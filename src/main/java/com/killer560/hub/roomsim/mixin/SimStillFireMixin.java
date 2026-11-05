package com.killer560.hub.roomsim.mixin;

import com.killer560.hub.roomsim.SimStaticWater;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sim fire stands still, as Hypixel's does: no aging, spreading or burning out (bossfloor-1104, the F7 boss's captured
 * fires burned its wool and coal). {@code FireBlock.tick} has the same descriptor in 26.3 and 26.2 (javap, 2026-11-04).
 */
@Mixin(FireBlock.class)
public abstract class SimStillFireMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void killer560smod$stillSimFire(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                           CallbackInfo ci) {
        if (SimStaticWater.holdsStill(level)) {
            ci.cancel();
        }
    }
}
