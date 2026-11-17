package com.killer560.hub.chatchannel.mixin;

import com.killer560.hub.chatchannel.ChatChannelFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Chat Channel Indicator at the START of the chat screen's render, so the command suggestions vanilla draws
 *  after it land on top (same approach as Chat Tabs). A chaining inject at HEAD; nothing is redirected. */
@Mixin(ChatScreen.class)
public abstract class ChatChannelScreenMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), require = 0)
    private void killer560smod$channelLine(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ChatChannelFeature.draw((ChatScreen) (Object) this, graphics);
    }
}
