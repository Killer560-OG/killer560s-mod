package com.killer560.hub.ap3.mixin;

import com.killer560.hub.ap3.Ap3Executor;
import com.killer560.hub.util.PlaceRotationGuard;
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
 * It also sends AP3's armed USE / BLOCK / BOOM click at its head ({@link Ap3Executor#beforeSendPosition}).
 * Rotation 360 rule: every value written here is a running value - the player's own previous one, or a server-side
 * yaw that was seeded from the player's own live yaw and only ever moved by bounded wrapped deltas - never wrapped,
 * never normalized, and the live values are restored unchanged.
 */
@Mixin(LocalPlayer.class)
public abstract class Ap3RotationSendMixin {

    @Shadow private float yRotLast;
    @Shadow private float xRotLast;

    @Unique private boolean killer560smod$swapped;
    @Unique private float killer560smod$liveYaw;
    @Unique private float killer560smod$livePitch;

    /** The yaw {@link PlaceRotationGuard} moved for this send, to put back at RETURN; NaN when none. */
    @Unique private float killer560smod$unNudgedYaw = Float.NaN;

    @Inject(method = "sendPosition", at = @At("HEAD"), require = 0)
    private void killer560smod$ap3HoldRotation(CallbackInfo ci) {
        // A dig aimed by a look turned this tick goes here, right ahead of the packet that carries the look
        // (util/BeforeMove, GrimAC RotationBreak's 15 ms lend) - as vanilla's dig precedes its sendPosition.
        com.killer560.hub.util.BeforeMove.beforeSendPosition();
        killer560smod$ap3HoldRotationInner();
        // Last, on whatever yaw is about to go out (AP3's swaps included): never repeat the turn before the last block
        // click (GrimAC DuplicateRotPlace, util/PlaceRotationGuard).
        killer560smod$unNudgedYaw = PlaceRotationGuard.beforeSend((LocalPlayer) (Object) this, this.yRotLast);
    }

    @Unique
    private void killer560smod$ap3HoldRotationInner() {
        Ap3Executor.onRotationMixinApplied();
        LocalPlayer self = (LocalPlayer) (Object) this;
        // A USE / BLOCK / BOOM armed at this tick's START goes out here, right ahead of the movement packet that carries
        // its aim (Ap3Executor.actPending: GrimAC reads that packet's rotation for a place only within 15 ms of it).
        // Before the swaps below, so a use packet carries the live aim.
        Ap3Executor.beforeSendPosition(self);
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
        Ap3Executor.afterSendPosition((LocalPlayer) (Object) this);
        com.killer560.hub.util.BeforeMove.afterSendPosition((LocalPlayer) (Object) this);
        if (!Float.isNaN(killer560smod$unNudgedYaw)) {
            if (!killer560smod$swapped) {
                ((LocalPlayer) (Object) this).setYRot(killer560smod$unNudgedYaw);
            }
            killer560smod$unNudgedYaw = Float.NaN;
        }
        if (!killer560smod$swapped) {
            return;
        }
        killer560smod$swapped = false;
        LocalPlayer self = (LocalPlayer) (Object) this;
        self.setYRot(killer560smod$liveYaw);
        self.setXRot(killer560smod$livePitch);
    }
}
