package com.killer560.hub.hud.mixin;

import com.killer560.hub.hud.AutoScale;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The mouse half of Auto Scale for the mod's screens (see {@link AutoScaleScreenMixin}). These two static methods are
 * the single funnel every GUI mouse coordinate goes through on 26.1.2 and 26.2 (javap, 2026-10-05): the press,
 * release, scroll and move positions in {@code MouseHandler}, the drag position AND the drag delta, and the mouseX/Y
 * {@code GameRenderer} hands to the open screen's render. Dividing here by the open screen's layout factor puts all
 * of them in that screen's scaled coordinates at once; for any screen that is not scaled the factor is 1 and nothing
 * changes.
 */
@Mixin(MouseHandler.class)
public abstract class AutoScaleMouseMixin {

    @Inject(method = "getScaledXPos(Lcom/mojang/blaze3d/platform/Window;D)D", at = @At("RETURN"), cancellable = true)
    private static void killer560smod$autoScaleX(Window window, double x, CallbackInfoReturnable<Double> cir) {
        float f = AutoScale.openScreenFactor();
        if (f != 1.0f) {
            cir.setReturnValue(cir.getReturnValueD() / f);
        }
    }

    @Inject(method = "getScaledYPos(Lcom/mojang/blaze3d/platform/Window;D)D", at = @At("RETURN"), cancellable = true)
    private static void killer560smod$autoScaleY(Window window, double y, CallbackInfoReturnable<Double> cir) {
        float f = AutoScale.openScreenFactor();
        if (f != 1.0f) {
            cir.setReturnValue(cir.getReturnValueD() / f);
        }
    }
}
