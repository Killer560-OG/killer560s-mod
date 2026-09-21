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
 * The one place rotation leaves the client. {@code LocalPlayer#sendPosition} (javap-verified on the 26.1.2 jar:
 * private, no args; compares {@code getYRot()/getXRot()} against the private {@code yRotLast/xRotLast}, builds the
 * {@code PosRot} / {@code Rot} packet from {@code getYRot()/getXRot()}, and stores them back into
 * {@code yRotLast/xRotLast} on the way out) is wrapped for two AP3 features, both by swapping the live rotation for
 * the duration of that one call and putting it back on return, so the camera, the movement frame and third-person
 * rendering never see anything but the live values:
 * <ul>
 * <li><b>The held-walk yaw lock</b> ({@link Ap3Executor#strafeServerYaw()} not NaN): the yaw sent is AP3's running
 *     server-side yaw - the walk direction, or its 45-degree strafe angle with "45 Degree Strafe" on - instead of
 *     the camera yaw. Pitch goes out as the camera's. Checked first: a walk's explicit yaw supersedes a LOOK's
 *     hold.</li>
 * <li><b>LOOK</b> ({@link Ap3Executor#isRotationSendSuppressed()}): "a client-side rotation change only. Turns the
 *     camera without sending rotation to the server." The live rotation is swapped for the last SENT one so the
 *     comparison sees no change and no rotation packet is built.</li>
 * </ul>
 * Rotation 360 rule: every value written here is a running value - the player's own previous one, or a server-side
 * yaw that was seeded from the player's own live yaw and only ever moved by bounded wrapped deltas - never wrapped,
 * never normalised, and the live values are restored unchanged.
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
        Ap3Executor.onRotationMixinApplied();
        LocalPlayer self = (LocalPlayer) (Object) this;
        float strafeYaw = Ap3Executor.strafeServerYaw();
        if (!Float.isNaN(strafeYaw)) {
            killer560smod$liveYaw = self.getYRot();
            killer560smod$livePitch = self.getXRot();
            self.setYRot(strafeYaw);
            killer560smod$swapped = true;
            return;
        }
        if (!Ap3Executor.isRotationSendSuppressed()) {
            return;
        }
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
