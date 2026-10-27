package com.killer560.hub.invwalk.mixin;

import com.killer560.hub.invwalk.InventoryWalkFeature;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory Walk's still tick (Stop To Click): while a click or a menu close is held back, the input this tick is all
 * keys up - the same record vanilla builds with nothing pressed, so nothing fractional - whatever built it before (his
 * keys, or an automation's input mixin: this one runs last, priority 2000). The server then gets an input packet with no
 * movement and a stop-sprint before the held packets go. {@code KeyboardInput.tick} on 26.1.2 and 26.2 (javap), the target
 * {@code AutoRoutesInputMixin} uses.
 */
@Mixin(value = KeyboardInput.class, priority = 2000)
public abstract class InventoryWalkInputMixin extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void killer560smod$invWalkStill(CallbackInfo ci) {
        if (InventoryWalkFeature.wantsStillTick()) {
            this.keyPresses = Input.EMPTY;
            this.moveVector = Vec2.ZERO;
        }
    }
}
