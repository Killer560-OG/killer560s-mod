package com.killer560.hub.fastleap.mixin;

import com.killer560.hub.fastleap.LeapRotation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link LeapRotation}: a click in the leap menu, whoever made it (his own, Fast Leap, Auto Leap). Head of
 *  {@code handleContainerInput(int, int, int, ContainerInput, Player)} (javap 26.3); the CallbackInfo-only handler takes
 *  any argument types. */
@Mixin(MultiPlayerGameMode.class)
public abstract class LeapRotationClickMixin {

    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void killer560smod$leapRotClick(CallbackInfo ci) {
        LeapRotation.onContainerClick(com.killer560.hub.compat.McCompat.screen(Minecraft.getInstance()));
    }
}
