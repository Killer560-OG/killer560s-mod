package com.killer560.hub.helditem.mixin;

import com.killer560.hub.helditem.HeldItemConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Held Item Transform "Swing Speed" - scales the local player's arm-swing duration (vanilla: 6 ticks,
 *  shortened by Haste / lengthened by Mining Fatigue, computed in {@code LivingEntity.getCurrentSwingDuration()}).
 *  Same hook NoammAddons' 26.1.2 {@code MixinLivingEntity} uses. Only drives the client-side
 *  {@code swingTime}/{@code getAttackAnim} animation for our own player - the swing packet is sent by
 *  {@code swing()} regardless, so this is visual only. Applied on top of vanilla's value, so Haste still counts. */
@Mixin(LivingEntity.class)
public abstract class HeldItemSwingDurationMixin {

    @Inject(method = "getCurrentSwingDuration", at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$heldItemSwingSpeed(CallbackInfoReturnable<Integer> cir) {
        if (!HeldItemConfig.isActive()) return;
        float speed = HeldItemConfig.getInstance().getSwingSpeed();
        if (speed == 1.0f) return;
        if ((Object) this != Minecraft.getInstance().player) return;
        cir.setReturnValue(Math.max(1, Math.round(cir.getReturnValueI() / speed)));
    }
}
