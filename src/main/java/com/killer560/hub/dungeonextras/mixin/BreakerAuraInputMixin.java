package com.killer560.hub.dungeonextras.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Breaker Aura's walk-kept yaw (balate-1109, dungeonextras/BreakerAim.walkYaws): when the body is turned k x 45 degrees
 * off his view for a dig, his own eight-way keys are turned k steps back here, so the push is exactly his own. Discrete
 * keys, vanilla's normalized vector. Priority 1900: after the automation input mixins, before Inventory Walk's still tick.
 */
@Mixin(value = KeyboardInput.class, priority = 1900)
public abstract class BreakerAuraInputMixin extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void killer560smod$breakerAuraKeys(CallbackInfo ci) {
        Input keys = com.killer560.hub.dungeonextras.BreakerAim.rotateInput(Minecraft.getInstance(), this.keyPresses);
        if (keys != null) {
            this.keyPresses = keys;
            float x = (keys.left() ? 1f : 0f) - (keys.right() ? 1f : 0f);
            float y = (keys.forward() ? 1f : 0f) - (keys.backward() ? 1f : 0f);
            this.moveVector = new Vec2(x, y).normalized();
        }
    }
}
