package com.killer560.hub.copychat.mixin;

import com.killer560.hub.copychat.CopyChatFeature;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Intercepts a raw chat click BEFORE vanilla resolves it down to a clicked Style - needed because
 *  {@link CopyChatFeature#tryHandleLineClick} (killer560's "shift click would only get the line my
 *  cursor is on" request, 2026-09-09) needs the actual click Y position, which
 *  {@code ChatScreen.handleComponentClicked} (what {@code ChatScreenClickMixin} hooks for the existing
 *  Ctrl+Click-whole-message behavior) never receives - by the time vanilla calls that method, the click
 *  has already been resolved down to just a Style, with no coordinate left. When shift isn't held (or
 *  no valid line is under the cursor), {@code tryHandleLineClick} returns false immediately and this
 *  falls through to vanilla's own handling exactly as before - Ctrl+Click and Click Translate are
 *  unaffected. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenLineClickMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void killer560smod$handleLineClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() == 0 && CopyChatFeature.tryHandleLineClick(event.x(), event.y())) {
            cir.setReturnValue(true);
        }
    }
}
