package com.killer560.hub.chathistory.mixin;

import com.killer560.hub.chathistory.ChatHistoryConfig;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Unlimited Chat History. {@code ChatComponent.addMessageToQueue} trims {@code allMessages} to
 * {@code MAX_CHAT_HISTORY} (100) and {@code addMessageToDisplayQueue} trims the wrapped {@code trimmedMessages} to the
 * same number; the constant is inlined by javac, so each method holds a literal {@code bipush 100} (javap 26.1.2). Both
 * are replaced by {@link ChatHistoryConfig#limit()}. The constructor's {@code 100} is the sent-message history's
 * capacity and {@code addRecentChat}'s is that history's cap; neither is touched.
 */
@Mixin(ChatComponent.class)
public abstract class ChatHistoryMixin {

    @ModifyConstant(method = {"addMessageToQueue", "addMessageToDisplayQueue"}, constant = @Constant(intValue = 100))
    private int killer560smod$historyLimit(int vanilla) {
        return ChatHistoryConfig.limit();
    }
}
