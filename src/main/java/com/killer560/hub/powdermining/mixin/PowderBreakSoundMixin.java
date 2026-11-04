package com.killer560.hub.powdermining.mixin;

import com.killer560.hub.powdermining.PowderBreakSound;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Powder Break Sounds: the local player's own block-break level event (2001) while powder mining. */
@Mixin(ClientLevel.class)
public abstract class PowderBreakSoundMixin {

    @Inject(method = "levelEvent(Lnet/minecraft/world/entity/Entity;ILnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"),
            cancellable = true)
    private void killer560smod$powderBreakSound(Entity who, int type, BlockPos pos, int data, CallbackInfo ci) {
        try {
            if (PowderBreakSound.handle((ClientLevel) (Object) this, who, type, pos, data)) {
                ci.cancel();
            }
        } catch (RuntimeException e) {
            // never break the world's level events
        }
    }
}
