package com.killer560.hub.ap3.mixin;

import com.killer560.hub.ap3.Ap3FreezeState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freeze State ({@link Ap3FreezeState}): while frozen the local player's {@code aiStep} - input, sprint, gravity and
 * {@code travel} - is skipped outright, so the character stays exactly where it is with no velocity. Everything else
 * in the tick (and {@code sendPosition}, which then reports the same spot) runs as normal. Local player only (the
 * class is {@code LocalPlayer}). Registered in {@code killer560smod-ap3.mixins.json}.
 */
@Mixin(LocalPlayer.class)
public abstract class Ap3FreezeMixin {

    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$ap3Freeze(CallbackInfo ci) {
        if (Ap3FreezeState.isFrozen()) {
            LocalPlayer self = (LocalPlayer) (Object) this;
            if (Ap3FreezeState.consumeForwardStep(self)) {
                return; // a predicted forward step: this one tick runs for real
            }
            self.setDeltaMovement(Vec3.ZERO);
            ci.cancel();
        }
    }
}
