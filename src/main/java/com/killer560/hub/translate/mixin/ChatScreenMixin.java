package com.killer560.hub.translate.mixin;

import com.killer560.hub.translate.TranslateFeature;
import net.minecraft.client.gui.screens.ChatScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts outgoing chat before vanilla sends it. {@code handleChatInput} normalizes the typed
 * text, adds it to chat history (if {@code addToHistory}), then either sends it as a command
 * (leading "/") or a plain chat message - see the real (decompiled) method body this mirrors.
 * When Chat Translate is on and the line isn't a command, {@link TranslateFeature#tryIntercept}
 * takes over: it still adds the original (untranslated) text to history so it matches what was
 * typed, but sends the translated text instead.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-translate");

    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void killer560smod$translateOutgoing(String message, boolean addToHistory, CallbackInfo ci) {
        LOGGER.info("ChatScreenMixin fired: raw message=\"{}\"", message);
        String normalized = ((ChatScreen) (Object) this).normalizeChatMessage(message);
        if (TranslateFeature.tryIntercept(normalized, addToHistory)) {
            ci.cancel();
        }
    }
}
