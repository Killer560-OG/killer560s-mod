package com.killer560.hub.chattabs.mixin;

import com.killer560.hub.chattabs.ChatTabsFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Chat Tabs row at the START of the chat screen's render, so the command suggestions and the usage hint
 *  vanilla draws after it land on top of the tabs (killer560, 2026-11-09: "the chat tabs go over stuff like tab filled
 *  commands"). A chaining inject at HEAD; nothing is redirected. */
@Mixin(ChatScreen.class)
public abstract class ChatTabsScreenMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), require = 0)
    private void killer560smod$tabsUnderSuggestions(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ChatTabsFeature.drawTabs((ChatScreen) (Object) this, graphics, mouseX, mouseY);
    }
}
