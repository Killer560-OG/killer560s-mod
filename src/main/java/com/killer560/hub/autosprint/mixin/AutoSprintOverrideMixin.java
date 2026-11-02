package com.killer560.hub.autosprint.mixin;

import com.killer560.hub.autosprint.AutoSprintFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Other mods' auto sprint while the mod's own movers drive (see {@link AutoSprintFeature#blocksSprintStart}): a sprint
 *  START on the local player is refused while a mover drives with the sprint key up. {@code LivingEntity.setSprinting(Z)V}
 *  is the method both vanilla's {@code LocalPlayer.aiStep} and a mod's direct call end in (LocalPlayer, AbstractClientPlayer
 *  and Player do not override it; javap 26.3 and 26.2). */
@Mixin(LivingEntity.class)
public abstract class AutoSprintOverrideMixin {

    @Inject(method = "setSprinting", at = @At("HEAD"), cancellable = true)
    private void killer560smod$noForeignSprint(boolean sprinting, CallbackInfo ci) {
        if (!sprinting) {
            return;
        }
        Object self = this;
        if (!(self instanceof LocalPlayer player) || player != Minecraft.getInstance().player || player.isSprinting()) {
            return;
        }
        boolean key = player.input != null && player.input.keyPresses.sprint();
        if (AutoSprintFeature.blocksSprintStart(key)) {
            ci.cancel();
        }
    }
}
