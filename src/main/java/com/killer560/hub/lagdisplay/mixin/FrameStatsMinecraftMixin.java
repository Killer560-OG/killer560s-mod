package com.killer560.hub.lagdisplay.mixin;

import com.killer560.hub.lagdisplay.FrameStats;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame boundaries and the frame's tick, packet/task and FPS-limiter phases for {@link FrameStats} (stutter-1026).
 * Targets checked with javap on 26.1.2 and 26.2 (identical): {@code private void runTick(boolean)} calls
 * {@code PacketProcessor.processQueuedPackets()V}, {@code Minecraft.runAllTasks()V} and {@code Minecraft.tick()V};
 * {@code renderFrame(boolean)} calls the static {@code FramerateLimiter.limitDisplayFPS(I)V}. {@code require = 0}: a
 * clash with another mod must never stop the game starting; testkit 1026-stutter-hooks proves every phase fires.
 */
@Mixin(Minecraft.class)
public abstract class FrameStatsMinecraftMixin {

    @Inject(method = "runTick", at = @At("HEAD"), require = 0)
    private void killer560smod$frameStart(CallbackInfo ci) {
        FrameStats.frameStart();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V"), require = 0)
    private void killer560smod$tickBegin(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.begin(FrameStats.TICK);
        }
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V",
            shift = At.Shift.AFTER), require = 0)
    private void killer560smod$tickEnd(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.end(FrameStats.TICK);
        }
    }

    @Inject(method = "runTick", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V"),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V")}, require = 0)
    private void killer560smod$tasksBegin(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.begin(FrameStats.TASKS);
        }
    }

    @Inject(method = "runTick", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V",
                    shift = At.Shift.AFTER),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V", shift = At.Shift.AFTER)},
            require = 0)
    private void killer560smod$tasksEnd(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.end(FrameStats.TASKS);
        }
    }

    /** The buffer swap / present, where the GPU driver and vsync wait: 26.1.2 {@code RenderSystem.flipFrame(TracyFrameCapture)},
     *  26.2 {@code GpuSurface.present()} (javap, both called from {@code renderFrame}). */
    @Inject(method = "renderFrame", at = {
            @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V"),
            @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V")}, require = 0)
    private void killer560smod$swapBegin(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.begin(FrameStats.SWAP);
        }
    }

    @Inject(method = "renderFrame", at = {
            @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V",
                    shift = At.Shift.AFTER),
            @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V", shift = At.Shift.AFTER)},
            require = 0)
    private void killer560smod$swapEnd(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.end(FrameStats.SWAP);
        }
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"), require = 0)
    private void killer560smod$limiterBegin(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.begin(FrameStats.LIMITER);
        }
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V", shift = At.Shift.AFTER), require = 0)
    private void killer560smod$limiterEnd(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.end(FrameStats.LIMITER);
        }
    }
}
