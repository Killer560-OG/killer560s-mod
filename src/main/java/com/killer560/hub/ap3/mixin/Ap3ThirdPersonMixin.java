package com.killer560.hub.ap3.mixin;

import com.killer560.hub.ap3.Ap3Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The F5 half of the server-side yaw lock - killer560 (2026-09-21): "If I go into f5 it still looks like my head is
 * facing the way my actual crosshair is pointed when it should be at whatever angle is most optimal." While a held
 * walk has the yaw the server receives locked to the walk ({@link Ap3Executor#strafeServerYaw()}), the LOCAL
 * player's third-person model is drawn with body and head at that yaw instead of the camera yaw, so his own F5 view
 * shows what the server and everyone else are told. Afterwards the model glides back onto vanilla's body yaw
 * ({@link Ap3Executor#thirdPersonModelYaw}), never pops.
 * <p>
 * Least invasive hook there is: {@code AvatarRenderer} is 26.1.2's player-model renderer ({@code SupportersScaleMixin}
 * already hangs off the same method), and {@code extractRenderState(Avatar, AvatarRenderState, float)} is where the
 * per-frame render-state object is filled from the live entity - javap on the real 26.1.2 jar: its
 * {@code LivingEntityRenderer} super call writes {@code LivingEntityRenderState.bodyRot} (from
 * {@code yBodyRotO/yBodyRot} via {@code solveBodyRot}) and {@code yRot = Mth.wrapDegrees(headRot - bodyRot)} (the
 * head's yaw RELATIVE to the body, which is what {@code HumanoidModel.setupAnim} turns the head by); nothing after the
 * super call ({@code extractHumanoidRenderState}, the arm poses, cape, flight data) writes either field again, so a
 * TAIL injection is the last word. The descriptor is spelled out because the class also carries two erased bridge
 * overloads of the same name.
 * <p>
 * Render-state only: the entity's own {@code yRot / yBodyRot / yHeadRot} are never touched, so the camera, the
 * first-person view, block picking and every packet are unaffected - the state object is rebuilt from the entity
 * every frame. Only the local player is ever changed (identity check against {@code Minecraft.player}); other
 * players' models are vanilla. {@code require = 0}: a wrong target here only leaves F5 showing the camera yaw,
 * never a crash. Registered in {@code killer560smod-ap3.mixins.json}.
 */
@Mixin(AvatarRenderer.class)
public abstract class Ap3ThirdPersonMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;"
            + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"), require = 0)
    private void killer560smod$ap3ThirdPerson(Avatar entity, AvatarRenderState state, float partialTick,
                                              CallbackInfo ci) {
        if (entity == null || entity != Minecraft.getInstance().player) {
            return;
        }
        // Vanilla's absolute head yaw for this frame, before the body is moved out from under it.
        float headAbs = state.bodyRot + state.yRot;
        float yaw = Ap3Executor.thirdPersonModelYaw(state.bodyRot, partialTick);
        if (Float.isNaN(yaw)) {
            return;
        }
        state.bodyRot = yaw;
        // Locked: head AND body at the server-side yaw (that is the whole point). Gliding back afterwards: the head
        // is already within half a degree of the camera, so it follows vanilla while the body catches up.
        state.yRot = Float.isNaN(Ap3Executor.strafeServerYaw()) ? Mth.wrapDegrees(headAbs - yaw) : 0f;
    }
}
