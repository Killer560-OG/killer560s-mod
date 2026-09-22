package com.killer560.hub.ap3.mixin;

import com.killer560.hub.ap3.Ap3Executor;
import com.killer560.hub.ap3.Ap3FreezeState;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The screen half of the Camera Planner's view freeze - killer560 (2026-09-21): "for that camera change one, do the
 * same freecam style we used for walk nodes so client side I stay looking the same way." While an align turns his
 * REAL yaw (what the physics and every packet use - the part Hypixel accepted 8/8), the camera is drawn from the
 * view yaw he had instead ({@link Ap3Executor#frozenViewYaw()}), which his mouse keeps steering
 * ({@code Ap3MouseYawMixin}).
 * <p>
 * javap on the 26.1.2 jar: {@code LocalPlayer#getViewYRot(float)} returns {@code getYRot()} (the vehicle's
 * interpolated value when riding), and {@code Camera#alignWithEntity} reads it for both first and third person, as
 * does the crosshair pick ({@code getViewVector}). Movement ({@code getYRot()} in {@code travel}), {@code sendPosition}
 * and the player model never call it, so nothing the server sees changes. Registered in
 * {@code killer560smod-ap3.mixins.json}.
 */
@Mixin(LocalPlayer.class)
public abstract class Ap3ViewYawMixin {

    @Inject(method = "getViewYRot", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$ap3FrozenView(float partialTick, CallbackInfoReturnable<Float> cir) {
        Ap3Executor.onViewMixinApplied();
        // Freeze State's free camera first, then the align/walk view freeze.
        float yaw = Ap3FreezeState.isFrozen() ? Ap3FreezeState.viewYaw() : Ap3Executor.frozenViewYaw();
        if (!Float.isNaN(yaw) && !((LocalPlayer) (Object) this).isPassenger()) {
            cir.setReturnValue(yaw);
        }
    }

    /** Freeze State only: the free camera's pitch (the align/walk freeze never touches pitch). */
    @Inject(method = "getViewXRot", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$ap3FrozenPitch(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (((LocalPlayer) (Object) this).isPassenger()) {
            return;
        }
        if (Ap3FreezeState.isFrozen()) {
            cir.setReturnValue(Ap3FreezeState.viewPitch());
            return;
        }
        float pitch = Ap3Executor.frozenViewPitch(); // a Block node aiming the real pitch
        if (!Float.isNaN(pitch)) {
            cir.setReturnValue(pitch);
        }
    }
}
