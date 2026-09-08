package com.killer560.hub.clicktranslate.mixin;

import com.killer560.hub.clicktranslate.ClickTranslateFeature;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Recognizes our own click-to-translate {@code ClickEvent} before vanilla tries to interpret it. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenClickMixin {

    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true)
    private void killer560smod$handleTranslateClick(Style style, boolean insertionMode, CallbackInfoReturnable<Boolean> cir) {
        if (ClickTranslateFeature.tryHandleClick(style)) {
            cir.setReturnValue(true);
        }
    }
}
