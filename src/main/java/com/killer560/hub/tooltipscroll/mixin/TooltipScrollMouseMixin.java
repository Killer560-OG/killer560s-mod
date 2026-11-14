package com.killer560.hub.tooltipscroll.mixin;

import com.killer560.hub.tooltipscroll.TooltipScrollFeature;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The wheel for Scrollable Tooltips, taken BEFORE the open screen sees it (tipscroll-1113). The old hook sat in
 *  {@code AbstractContainerScreen.mouseScrolled}, which the creative inventory, the mod's own overlays and every other
 *  screen that overrides it never reach, and which only ran for container hovers. Verified with javap on 26.3:
 *  {@code MouseHandler.onScroll(JDD)V} calls {@code Screen.mouseScrolled(DDDD)Z} once. */
@Mixin(MouseHandler.class)
public abstract class TooltipScrollMouseMixin {

    @WrapOperation(method = "onScroll(JDD)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseScrolled(DDDD)Z"), require = 0)
    private boolean killer560smod$tooltipWheel(Screen screen, double x, double y, double sx, double sy, Operation<Boolean> original) {
        try {
            if (TooltipScrollFeature.onWheelEarly(sy)) {
                return true;
            }
        } catch (Throwable ignored) {
            // Falls through to the screen's own scroll handling.
        }
        return original.call(screen, x, y, sx, sy);
    }
}
