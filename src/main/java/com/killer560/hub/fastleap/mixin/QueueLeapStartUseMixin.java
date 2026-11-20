package com.killer560.hub.fastleap.mixin;

import com.killer560.hub.fastleap.QueueLeap;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Queue Leap: his own right click (vanilla {@code startUseItem}, called only from the use key) with the leap held. */
@Mixin(Minecraft.class)
public abstract class QueueLeapStartUseMixin {

    @Inject(method = "startUseItem", at = @At("HEAD"))
    private void killer560smod$queueLeapBefore(CallbackInfo ci) {
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            QueueLeap.beforeUse((Minecraft) (Object) this);
        }
    }

    @Inject(method = "startUseItem", at = @At("RETURN"))
    private void killer560smod$queueLeapAfter(CallbackInfo ci) {
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            QueueLeap.afterUse((Minecraft) (Object) this);
        }
    }
}
