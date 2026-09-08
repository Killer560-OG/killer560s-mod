package com.killer560.hub.clicktranslate.mixin;

import com.killer560.hub.clicktranslate.ClickTranslateFeature;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Wraps every incoming chat message so clicking it triggers a translate-to-English lookup. Hooks
 * all three of {@code ChatComponent}'s add-message entry points, not just {@code addPlayerMessage} -
 * many servers (Hypixel included, apparently) send what looks like normal player chat through the
 * system-message path instead of vanilla's signed player-chat packets, so relying on
 * {@code addPlayerMessage} alone silently missed real chat messages.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @ModifyVariable(method = "addPlayerMessage", at = @At("HEAD"), argsOnly = true)
    private Component killer560smod$wrapPlayerMessage(Component message) {
        return ClickTranslateFeature.wrap(message);
    }

    @ModifyVariable(method = "addClientSystemMessage", at = @At("HEAD"), argsOnly = true)
    private Component killer560smod$wrapClientSystemMessage(Component message) {
        return ClickTranslateFeature.wrap(message);
    }

    @ModifyVariable(method = "addServerSystemMessage", at = @At("HEAD"), argsOnly = true)
    private Component killer560smod$wrapServerSystemMessage(Component message) {
        return ClickTranslateFeature.wrap(message);
    }
}
