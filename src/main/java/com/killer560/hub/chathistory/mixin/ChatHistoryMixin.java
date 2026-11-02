package com.killer560.hub.chathistory.mixin;

import com.killer560.hub.chathistory.ChatHistoryConfig;
import net.minecraft.client.gui.components.ChatComponent;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Unlimited Chat History. {@code ChatComponent.addMessageToQueue} trims {@code allMessages} to
 * {@code MAX_CHAT_HISTORY} (100) and {@code addMessageToDisplayQueue} trims the wrapped {@code trimmedMessages} to the
 * same number; the constant is inlined by javac, so each method holds a literal {@code bipush 100} (javap 26.1.2). Both
 * are lifted to no cap while Unlimited is on (off, the value passes through unchanged). The constructor's {@code 100} is the sent-message history's
 * capacity and {@code addRecentChat}'s is that history's cap; neither is touched.
 *
 * <p>A MixinExtras {@code @ModifyExpressionValue} on the constant, never a {@code @ModifyConstant}: More Chat History
 * hooks the same two constants with a require-1 {@code @ModifyExpressionValue} and Skysoft with a require-1
 * {@code @ModifyConstant}. A {@code @ModifyConstant} takes the constant for itself, so whichever mod came second either
 * lost silently or crashed the game at boot (the owner's "chat history crash", 2026-11-01). Modify-value handlers chain.
 */
@Mixin(ChatComponent.class)
public abstract class ChatHistoryMixin {

    @ModifyExpressionValue(method = {"addMessageToQueue", "addMessageToDisplayQueue"},
            at = @At(value = "CONSTANT", args = "intValue=100"))
    private int killer560smod$historyLimit(int limit) {
        // Off: pass on whatever the cap is by now (100, or another chat-history mod's larger one), never force 100.
        // Limited raises it to the chosen limit; Unlimited lifts it.
        return ChatHistoryConfig.apply(limit);
    }
}
