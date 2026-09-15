package com.killer560.hub.clicktranslate.mixin;

import com.killer560.hub.clicktranslate.ClickTranslateFeature;
import com.killer560.hub.util.ChatObserver;
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

    // Real bug found and fixed (2026-09-14, real Hypixel F7 log): a device-completion line another mod
    // (Odin's Terminal Splits) cancelled via Fabric's ALLOW_GAME and re-added straight to ChatComponent never
    // reached any Fabric chat listener. ChatObserver sees every real chat add here instead. Hooked on the
    // private addMessage(Component, MessageSignature, GuiMessageSource, GuiMessageTag) funnel - all three
    // public methods above call it (javap-confirmed on the 26.1.2 jar), so it also catches a mod invoking it
    // through an accessor. Runs after ClickTranslate's wraps above; getString() is unchanged by the wrap.
    @ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true)
    private Component killer560smod$observeAddedMessage(Component message) {
        return ChatObserver.onChatComponentAdd(message);
    }
}
