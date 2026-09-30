package com.killer560.hub.roomsim.mixin;

import com.killer560.hub.roomsim.SimState;

import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops the sim's speed setting pulling the field of view out.
 *
 * <p>killer560 (2026-09-30): "Make it so speed in sim doesnt zoom my fov out."
 *
 * <p>{@code /speed} writes the player's {@code MOVEMENT_SPEED} attribute, and vanilla's
 * {@code getFieldOfViewModifier} widens the view in proportion to how far that attribute sits above its
 * base. At the speeds he practises at that is a permanent fisheye, and it is not something Hypixel does to
 * him - his real speed comes from gear, which vanilla's FOV code never sees.
 *
 * <p>Done here rather than by writing {@code fovEffectScale}: that is one of HIS vanilla options, saved to
 * {@code options.txt} at shutdown, so turning it off for the sim would quietly turn it off for every world
 * he plays afterwards. This touches nothing outside the sim.
 *
 * <p>It removes the movement FOV change entirely while the sim is active, sprint included, rather than
 * subtracting only the part {@code /speed} contributed. A fixed view is the point - a practice run where the
 * view breathes with every speed change is a different view every session.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class SimFovMixin {

    @Inject(method = "getFieldOfViewModifier", at = @At("HEAD"), cancellable = true)
    private void killer560smod$flatFovInTheSim(boolean useFovSetting, float partialTick,
                                               CallbackInfoReturnable<Float> cir) {
        if (SimState.isActive()) {
            cir.setReturnValue(1.0f);
        }
    }
}
