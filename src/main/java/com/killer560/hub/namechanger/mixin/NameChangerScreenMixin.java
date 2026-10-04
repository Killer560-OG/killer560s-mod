package com.killer560.hub.namechanger.mixin;

import com.killer560.hub.namechanger.NameReplacer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves this mod's own menus untouched by Name Changer (see {@link NameReplacer#beginScreen}). Hooked on
 * {@code Screen.extractRenderStateWithTooltipAndSubtitles(GuiGraphicsExtractor,int,int,float)}, the final
 * method every screen is drawn through - javap-confirmed identical in the 26.1.2 and 26.2 merged jars.
 */
@Mixin(Screen.class)
public abstract class NameChangerScreenMixin {

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("HEAD"), require = 0)
    private void killer560smod$nameChangerScreenStart(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                      float partialTick, CallbackInfo ci) {
        NameReplacer.beginScreen(this);
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("RETURN"), require = 0)
    private void killer560smod$nameChangerScreenEnd(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                    float partialTick, CallbackInfo ci) {
        NameReplacer.endScreen();
    }
}
