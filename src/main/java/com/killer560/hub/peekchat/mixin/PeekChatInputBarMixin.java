package com.killer560.hub.peekchat.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Peek Chat has no input line, so it has no input bar either. {@code ChatScreen.extractRenderState} draws that bar with
 * its one and only {@code GuiGraphicsExtractor.fill(IIIII)V} call (javap, 26.1.2 and 26.2: {@code fill(2, height - 14,
 * width - 2, height - 2, options.getBackgroundColor(...))}) before the chat lines; it is skipped for the Peek Chat screen
 * and drawn as before for every other chat. Matched by class name so the screen class stays outside this mixin package.
 */
@Mixin(ChatScreen.class)
public abstract class PeekChatInputBarMixin {

    @Redirect(method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V", ordinal = 0),
            require = 0)
    private void killer560smod$noPeekInputBar(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
        if (!(((Object) this) instanceof com.killer560.hub.peekchat.PeekChatScreen)) {
            graphics.fill(x0, y0, x1, y1, color);
        }
    }
}
