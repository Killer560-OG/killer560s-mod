package com.killer560.hub.ap3.mixin;

import com.killer560.hub.ap3.Ap3Executor;
import com.killer560.hub.ap3.Ap3FreezeState;
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
        if (Ap3FreezeState.isFrozen()) {
            Ap3FreezeState.turnView((float) yawDelta * 0.15f, 0f);
            return 0.0;
        }
        return Ap3Executor.onMouseYaw((float) yawDelta * 0.15f) ? 0.0 : yawDelta;
    }

    /** Freeze State: the pitch part of a mouse turn moves the free camera, not the frozen character. */
    @ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private double killer560smod$ap3MousePitch(double pitchDelta) {
        if ((Object) this != Minecraft.getInstance().player) {
            return pitchDelta;
        }
        if (Ap3FreezeState.isFrozen()) {
            Ap3FreezeState.turnView(0f, (float) pitchDelta * 0.15f);
            return 0.0;
        }
        return Ap3Executor.onMousePitch((float) pitchDelta * 0.15f) ? 0.0 : pitchDelta;
    }
}
