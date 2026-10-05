package com.killer560.hub.petwheel.mixin;

import com.killer560.hub.petwheel.PetSummoner;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pet Wheel: no walking while a summon is in flight (killer560, 2026-10-04: "make it so I cannot walk while it
 * is in progress"). Same technique as {@code pathfinding.mixin.PathWalkInputMixin}: rewrite the {@code Input}
 * record and {@code moveVector} that {@code KeyboardInput#tick} just built (javap, 26.1.2 and 26.2:
 * {@code public void tick()}, {@code ClientInput.keyPresses} public, {@code moveVector} protected). Forward,
 * back, strafe, jump and sprint are dropped; sneak is left as the player has it. The key mappings themselves are
 * never touched, so a key still held when the summon ends just works again the next tick. Extends
 * {@code ClientInput} only so the protected {@code moveVector} is reachable.
 */
@Mixin(KeyboardInput.class)
public abstract class PetWheelInputMixin extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void killer560smod$petWheelHoldStill(CallbackInfo ci) {
        if (!PetSummoner.suppressesMovement()) {
            return;
        }
        Input k = this.keyPresses;
        this.keyPresses = new Input(false, false, false, false, false, k.shift(), false);
        this.moveVector = Vec2.ZERO;
    }
}
