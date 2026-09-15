package com.killer560.hub.namechanger.mixin;

import com.killer560.hub.namechanger.NameReplacer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps text inside edit boxes (chat input, command line, this mod's own settings fields) exactly as typed, so the
 * cursor lines up and what you see is what gets sent. Everything the box submits while this is active is marked via
 * {@code NameChangerGuiGraphicsMixin}. Verified: {@code EditBox.extractWidgetRenderState(GuiGraphicsExtractor,int,int,float)}.
 */
@Mixin(EditBox.class)
public abstract class NameChangerEditBoxMixin {

    @Inject(method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("HEAD"), require = 0)
    private void killer560smod$nameChangerSuppressStart(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                        float partialTick, CallbackInfo ci) {
        NameReplacer.pushSuppress();
    }

    @Inject(method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("RETURN"), require = 0)
    private void killer560smod$nameChangerSuppressEnd(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                      float partialTick, CallbackInfo ci) {
        NameReplacer.popSuppress();
    }
}
