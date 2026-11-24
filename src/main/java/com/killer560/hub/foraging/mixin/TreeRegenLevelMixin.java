package com.killer560.hub.foraging.mixin;

import com.killer560.hub.foraging.TreeRegen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Regenerating Trees: the server sets a block through {@code ClientLevel.setServerVerifiedBlockState(BlockPos, BlockState,
 * int)} (public void, as DioriteGlassLevelMixin uses), so at HEAD the level still holds the old state. A throw on the
 * packet path disconnects him, so the hook is caught.
 */
@Mixin(ClientLevel.class)
public abstract class TreeRegenLevelMixin {

    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"), require = 0)
    private void killer560smod$treeRegen(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        try {
            TreeRegen.onChange(this, pos, ((ClientLevel) (Object) this).getBlockState(pos), state);
        } catch (RuntimeException e) {
            // never break the packet path
        }
    }
}
