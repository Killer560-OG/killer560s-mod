package com.killer560.hub.peekchat.mixin;

import com.killer560.hub.peekchat.PeekChatFeature;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Peek Chat's overlay. {@code Hud.extractChat} skips the chat while it reads as focused (an open ChatScreen draws it);
 *  while peeking no screen is open, so the HUD draws it here in the open chat's FOREGROUND mode (every line, full
 *  alpha), with the mouse off the chat so no line reads as hovered. {@code private void extractChat(GuiGraphicsExtractor,
 *  DeltaTracker)}, fields {@code chat} and {@code tickCount} (javap, 26.3 and 26.2). */
@Mixin(Hud.class)
public abstract class PeekChatHudMixin {

    @Shadow @Final private ChatComponent chat;
    @Shadow private int tickCount;

    @Inject(method = "extractChat", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$peekChat(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        if (PeekChatFeature.isPeeking()) {
            ci.cancel();
            graphics.nextStratum();
            chat.extractRenderState(graphics, Minecraft.getInstance().font, tickCount, -10000, -10000,
                    ChatComponent.DisplayMode.FOREGROUND, false);
        }
    }
}
