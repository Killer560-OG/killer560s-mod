package com.killer560.hub.scoreboard.mixin;

import com.killer560.hub.scoreboard.ScoreboardBlur;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Custom Scoreboard background blur: {@code GuiRenderer#draw(GpuBufferSlice)V} (26.1.2, verified with javap) opens the
 * GUI render passes on the main target, so its head is the last point where the main target only holds the world and
 * no render pass is active - {@link ScoreboardBlur#copyIfNeeded} copies it there (only on frames that queued a blur).
 * {@code require = 0}: if this stops applying, {@link ScoreboardBlur} notices the copy never ran and disables itself.
 */
@Mixin(GuiRenderer.class)
public abstract class CustomScoreboardBlurMixin {

    @Inject(method = "draw(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", at = @At("HEAD"), require = 0)
    private void killer560smod$copyForScoreboardBlur(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        ScoreboardBlur.copyIfNeeded();
    }
}
