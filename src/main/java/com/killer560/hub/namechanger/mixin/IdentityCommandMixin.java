package com.killer560.hub.namechanger.mixin;

import com.killer560.hub.namechanger.IdentityCommandFeature;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts outgoing chat before vanilla sends it - identical shape to {@code
 * com.killer560.hub.translate.mixin.ChatScreenMixin} (same method, same {@code normalizeChatMessage} call,
 * same cancel-if-handled contract). {@link IdentityCommandFeature#tryIntercept} takes over only for a typed
 * {@code /party}/{@code /p}/{@code /friend}/{@code /f} command whose target is a known nickname - see that
 * class's own doc for exactly what it does and why it's safe to run alongside Translate/Auto Correct's own
 * hooks on this same method.
 */
@Mixin(ChatScreen.class)
public abstract class IdentityCommandMixin {

    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$identityRewrite(String message, boolean addToHistory, CallbackInfo ci) {
        String normalized = ((ChatScreen) (Object) this).normalizeChatMessage(message);
        if (IdentityCommandFeature.tryIntercept(normalized, addToHistory)) {
            ci.cancel();
        }
    }
}
