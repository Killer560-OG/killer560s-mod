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
 *  {@code ChatScreen.handleComponentClicked} (what {@code ChatScreenClickMixin} hooks for the
 *  whole-message copy behavior) never receives - by the time vanilla calls that method, the click has
 *  already been resolved down to just a Style, with no coordinate left. Gated on the RIGHT mouse button
 *  specifically (button 1) - per killer560's round-11 "change both to be shift instead of control"
 *  request, both copy gestures now share the Shift modifier and are told apart by button instead:
 *  Shift+Left-Click copies the whole message (unaffected by this class, routed through
 *  {@code ChatScreenClickMixin}/{@code ClickTranslateFeature} same as always), Shift+Right-Click copies
 *  just this one line. When it isn't a right-click, shift isn't held, or no valid line is under the
 *  cursor, {@code tryHandleLineClick} returns false immediately and this falls through to vanilla's own
 *  handling exactly as before. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenLineClickMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void killer560smod$handleLineClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() == 1 && CopyChatFeature.tryHandleLineClick(event.x(), event.y())) {
            cir.setReturnValue(true);
        }
    }
}
