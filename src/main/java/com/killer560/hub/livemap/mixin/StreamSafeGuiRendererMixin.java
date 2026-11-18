package com.killer560.hub.livemap.mixin;

import com.killer560.hub.livemap.StreamSafeMap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stream Safe Funny Map: {@code GuiRenderer.draw} draws into {@code GameRenderer.mainRenderTarget()} (its only use there,
 * 26.3 and 26.2, javap); while the overlay's own renderer draws, it gets the overlay's offscreen target instead. Every
 * other draw keeps the game's target.
 */
@Mixin(GuiRenderer.class)
public abstract class StreamSafeGuiRendererMixin {

    @WrapOperation(method = "draw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget killer560smod$streamSafeTarget(GameRenderer gameRenderer, Operation<RenderTarget> original) {
        return StreamSafeMap.drawTarget(original.call(gameRenderer));
    }
}
