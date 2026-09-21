package com.killer560.hub.ap3.mixin;

import com.killer560.hub.ap3.Ap3Executor;
import net.minecraft.client.player.ClientInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Server Strafe Angle's sprint half. While a held walk drives with the server-side yaw locked to the 45-degree
 * strafe angle ({@link Ap3Executor#isStrafeDriving()}), the analog input the executor writes is CAMERA-relative
 * (that is what makes the movement itself go the recorded way), so when the camera points away from the walk the
 * forward component is zero or negative and vanilla would refuse to start - or would stop - sprinting. javap on the
 * 26.1.2 jar: {@code LocalPlayer.canStartSprinting} and {@code shouldStopRunSprinting} both key on
 * {@code ClientInput.hasForwardImpulse()} ({@code moveVector.y > 1e-5}; there is no 0.8 threshold in this
 * version), and {@code aiStep} then starts the sprint off {@code keyPresses.sprint()}, which the executor already
 * supplies. This reports a forward impulse while - and only while - the lock drives, so the client sprints in the
 * recorded direction whatever the camera does. The server, which sees a W+A / W+D key record against the strafe
 * yaw plus the sprint command, sees exactly the sprint a real 45-degree strafer sends.
 * <p>
 * Target verified with javap on the 26.1.2 jar: {@code public boolean hasForwardImpulse()} on
 * {@code net.minecraft.client.player.ClientInput} ({@code KeyboardInput} inherits it, not overrides). Registered in
 * {@code killer560smod-ap3.mixins.json}. Nothing here is written to the player; it is a read-side answer only.
 */
@Mixin(ClientInput.class)
public abstract class Ap3StrafeImpulseMixin {

    @Inject(method = "hasForwardImpulse", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$ap3StrafeImpulse(CallbackInfoReturnable<Boolean> cir) {
        Ap3Executor.onSprintMixinApplied();
        if (Ap3Executor.isStrafeDriving()) {
            cir.setReturnValue(true);
        }
    }
}
