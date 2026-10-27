package com.killer560.hub.lagdisplay.mixin;

import com.killer560.hub.lagdisplay.FrameStats;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World vs GUI/HUD time in both halves of a 26.x frame, for {@link FrameStats} (stutter-1026). javap, both versions:
 * {@code extract(DeltaTracker, boolean)} calls the level extraction (26.1.2 {@code LevelRenderer.extractLevel}, 26.2
 * {@code LevelExtractor.extract}, both {@code (DeltaTracker, Camera, float)}) and then the GUI's (26.1.2 this class's
 * {@code extractGui}, 26.2 {@code Gui.extractRenderState}, both {@code (DeltaTracker, boolean, boolean)});
 * {@code render(DeltaTracker, boolean)} calls {@code renderLevel(DeltaTracker)} and {@code GuiRenderer.render}
 * ({@code (GpuBufferSlice)} on 26.1.2, {@code ()} on 26.2). Each pair lists both versions' targets; one matches.
 */
@Mixin(GameRenderer.class)
public abstract class FrameStatsGameRendererMixin {

    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), require = 0)
    private void killer560smod$extractBegin(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.begin(FrameStats.EXTRACT);
        }
    }

    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"), require = 0)
    private void killer560smod$extractEnd(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.end(FrameStats.EXTRACT);
        }
    }

    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;extractLevel(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V"),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V")},
            require = 0)
    private void killer560smod$extractLevelBegin(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.begin(FrameStats.EXTRACT_LEVEL);
        }
    }

    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;extractLevel(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V",
                    shift = At.Shift.AFTER),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V",
                    shift = At.Shift.AFTER)},
            require = 0)
    private void killer560smod$extractLevelEnd(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.end(FrameStats.EXTRACT_LEVEL);
        }
    }

    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extractGui(Lnet/minecraft/client/DeltaTracker;ZZ)V"),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V")},
            require = 0)
    private void killer560smod$extractGuiBegin(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.begin(FrameStats.EXTRACT_GUI);
        }
    }

    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extractGui(Lnet/minecraft/client/DeltaTracker;ZZ)V",
                    shift = At.Shift.AFTER),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V",
                    shift = At.Shift.AFTER)},
            require = 0)
    private void killer560smod$extractGuiEnd(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.end(FrameStats.EXTRACT_GUI);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), require = 0)
    private void killer560smod$renderBegin(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.begin(FrameStats.RENDER);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"), require = 0)
    private void killer560smod$renderEnd(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.end(FrameStats.RENDER);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"),
            require = 0)
    private void killer560smod$renderLevelBegin(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.begin(FrameStats.RENDER_LEVEL);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            shift = At.Shift.AFTER), require = 0)
    private void killer560smod$renderLevelEnd(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.end(FrameStats.RENDER_LEVEL);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V")},
            require = 0)
    private void killer560smod$renderGuiBegin(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.begin(FrameStats.RENDER_GUI);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
                    shift = At.Shift.AFTER),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V", shift = At.Shift.AFTER)},
            require = 0)
    private void killer560smod$renderGuiEnd(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.end(FrameStats.RENDER_GUI);
        }
    }
}
