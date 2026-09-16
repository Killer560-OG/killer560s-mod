package com.killer560.hub.ap3.mixin;

import com.killer560.hub.ap3.Ap3Executor;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LOOK nodes are "a client-side rotation change only. Turns the camera without sending rotation to the server."
 * {@code LocalPlayer#sendPosition} (javap-verified: private, compares {@code getYRot()/getXRot()} against the private
 * {@code yRotLast/xRotLast} and only then sends a {@code Rot}/{@code PosRot} packet) is the one place rotation goes
 * out. While {@link Ap3Executor#isRotationSendSuppressed()} holds, this swaps the live rotation for the last SENT
 * one around that call so the comparison sees no change and no rotation packet is built; the live values are put
 * back on return so the camera, the movement frame and everything else keep the client-side look.
 * <p>
 * Rotation 360 rule: both values written here are the player's own previous running values (never wrapped, never
 * normalised) and the live ones are restored unchanged.
 */
@Mixin(LocalPlayer.class)
public abstract class Ap3RotationSendMixin {

    @Shadow private float yRotLast;
    @Shadow private float xRotLast;

    @Unique private boolean killer560smod$swapped;
    @Unique private float killer560smod$liveYaw;
    @Unique private float killer560smod$livePitch;

    @Inject(method = "sendPosition", at = @At("HEAD"), require = 0)
    private void killer560smod$ap3HoldRotation(CallbackInfo ci) {
        if (!Ap3Executor.isRotationSendSuppressed()) {
            return;
        }
        LocalPlayer self = (LocalPlayer) (Object) this;
        killer560smod$liveYaw = self.getYRot();
        killer560smod$livePitch = self.getXRot();
        self.setYRot(this.yRotLast);
        self.setXRot(this.xRotLast);
        killer560smod$swapped = true;
    }

    @Inject(method = "sendPosition", at = @At("RETURN"), require = 0)
    private void killer560smod$ap3RestoreRotation(CallbackInfo ci) {
        if (!killer560smod$swapped) {
            return;
        }
        killer560smod$swapped = false;
        LocalPlayer self = (LocalPlayer) (Object) this;
        self.setYRot(killer560smod$liveYaw);
        self.setXRot(killer560smod$livePitch);
    }
}
