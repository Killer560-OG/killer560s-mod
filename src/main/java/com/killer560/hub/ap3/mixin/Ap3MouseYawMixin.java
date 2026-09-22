package com.killer560.hub.ap3.mixin;

import com.killer560.hub.ap3.Ap3Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The mouse half of the Camera Planner's view freeze ({@code Ap3ViewYawMixin}): while the view is frozen, the yaw
 * part of a mouse turn ({@code Entity#turn(double, double)}, which {@code MouseHandler#turnPlayer} calls with the
 * raw deltas; vanilla scales yaw by 0.15) moves the view yaw instead of the real yaw, so looking around never
 * disturbs the planner. Pitch is untouched - the planner never turns it. Local player only.
 */
@Mixin(Entity.class)
public abstract class Ap3MouseYawMixin {

    @ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private double killer560smod$ap3MouseYaw(double yawDelta) {
        if ((Object) this != Minecraft.getInstance().player) {
            return yawDelta;
        }
        return Ap3Executor.onMouseYaw((float) yawDelta * 0.15f) ? 0.0 : yawDelta;
    }
}
