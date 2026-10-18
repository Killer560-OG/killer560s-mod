package com.killer560.hub.freelook.mixin;

import com.killer560.hub.freelook.FreelookFeature;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Freelook's pan, mouse half: while the pan key is held, the mouse's turn goes to the pan camera and the player is not
 * turned at all. {@code private void turnPlayer(double)} on {@code MouseHandler} ends in one
 * {@code LocalPlayer.turn(DD)V} call on both 26.1.2 and 26.2 (javap, 2026-10-18), with the raw deltas that
 * {@code Entity.turn} scales by 0.15, so the camera turns exactly as fast as his look would. Wrapping the CALL here sits
 * upstream of every {@code Entity.turn} mixin (AP3's free camera, ViewFreeze), so a pan never leaks into them.
 */
@Mixin(MouseHandler.class)
public abstract class FreelookMouseMixin {

    @WrapOperation(method = "turnPlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void killer560smod$freelookTurn(LocalPlayer player, double yaw, double pitch, Operation<Void> original) {
        if (FreelookFeature.isPanning()) {
            FreelookFeature.turn((float) yaw * 0.15f, (float) pitch * 0.15f);
            return;
        }
        original.call(player, yaw, pitch);
    }
}
