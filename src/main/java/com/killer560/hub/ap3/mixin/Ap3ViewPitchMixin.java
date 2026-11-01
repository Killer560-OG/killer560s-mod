package com.killer560.hub.ap3.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The pitch half of {@link Ap3ViewYawMixin} where 26.3 put it: LocalPlayer no longer declares {@code getViewXRot(float)},
 * only Entity does (javap 26.3), and Mixin cannot inject into an inherited method. On 26.2 LocalPlayer still overrides it
 * and Ap3ViewYawMixin's own injection serves; this one is then never reached for the local player.
 */
@Mixin(Entity.class)
public abstract class Ap3ViewPitchMixin {

    @Inject(method = "getViewXRot", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$ap3FrozenPitchEntity(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof LocalPlayer player)) {
            return;
        }
        float pitch = com.killer560.hub.ap3.Ap3FreezeState.viewPitchOverride(player);
        if (!Float.isNaN(pitch)) {
            cir.setReturnValue(pitch);
        }
    }
}
