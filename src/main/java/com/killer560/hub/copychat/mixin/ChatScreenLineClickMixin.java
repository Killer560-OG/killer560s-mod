package com.killer560.hub.copychat.mixin;

import com.killer560.hub.copychat.CopyChatFeature;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Copy Chat's one click hook (see {@link CopyChatFeature}): Shift or Ctrl + left click copies the whole message, + right
 *  click the line, before vanilla resolves the click to a style (and before its Shift-click insertion). Anything else -
 *  a plain click, no line under the cursor, the feature off - falls through to vanilla unchanged. Same descriptor on
 *  26.1.2 and 26.2 (javap 2026-10-08). */
@Mixin(ChatScreen.class)
public abstract class ChatScreenLineClickMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void killer560smod$copyChatClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (CopyChatFeature.onChatClick(event.x(), event.y(), event.button(),
                event.hasShiftDown() || event.hasControlDown())) {
            cir.setReturnValue(true);
        }
    }
}
