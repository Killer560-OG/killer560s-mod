package com.killer560.hub.puzzlesolvers.mixin;

import com.killer560.hub.puzzlesolvers.IceFillSolverFeature;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ice Fill Etherwarp Protection (killer560, 2026-11-01). Target: {@code private void startUseItem()} on {@code Minecraft},
 * vanilla's own right click (javap, 26.3 and 26.2: the same). Cancelling it at HEAD sends nothing - neither the click on
 * the block nor the use - so a refused warp is never half sent. Auto Ice Fill calls {@code MultiPlayerGameMode.useItem}
 * itself and never comes through here. {@link IceFillSolverFeature#shouldBlockRightClick} decides.
 */
@Mixin(Minecraft.class)
public abstract class IceFillEtherwarpMixin {

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void killer560smod$iceFillEtherwarpProtection(CallbackInfo ci) {
        if (IceFillSolverFeature.shouldBlockRightClick((Minecraft) (Object) this)) {
            ci.cancel();
        }
    }
}
