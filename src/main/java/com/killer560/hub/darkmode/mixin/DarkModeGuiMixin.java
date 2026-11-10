package com.killer560.hub.darkmode.mixin;

import com.killer560.hub.darkmode.DarkModeFeature;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Dark Mode's one fill: {@code Gui.extractRenderState} ends with {@code graphics.applyCursor(window)}, after the HUD,
 * the open screen with its tooltips, toasts and the debug overlay (javap 26.2 and 26.3: same call, same owner). Wrapping
 * that call hands us the frame's own graphics without relying on the method's parameters, which differ between versions.
 */
@Mixin(Gui.class)
public abstract class DarkModeGuiMixin {

    @WrapOperation(method = "extractRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;applyCursor(Lcom/mojang/blaze3d/platform/Window;)V"),
            require = 0)
    private void killer560smod$darkFill(GuiGraphicsExtractor graphics, Window window, Operation<Void> original) {
        DarkModeFeature.frameFill(graphics);
        original.call(graphics, window);
    }
}
