package com.killer560.hub.perfprobe.mixin;

import com.killer560.hub.perfprobe.PerfProbe;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times the open screen's whole draw in {@link PerfProbe} (probe on only, fps-gui-1015). javap 26.1.2 and 26.2: the
 *  final {@code extractRenderStateWithTooltipAndSubtitles(GuiGraphicsExtractor, int, int, float)} is what GameRenderer
 *  calls for the screen, in both versions. */
@Mixin(Screen.class)
public abstract class ScreenProbeMixin {

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), require = 0)
    private void killer560$perfProbeEnter(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick,
                                          CallbackInfo ci) {
        PerfProbe.enterScreen();
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"), require = 0)
    private void killer560$perfProbeExit(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick,
                                         CallbackInfo ci) {
        PerfProbe.exitScreen(this);
    }
}
