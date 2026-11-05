package com.killer560.hub.peekchat.mixin;

import com.killer560.hub.peekchat.PeekChatFeature;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** While Peek Chat is shown the chat counts as open: vanilla then uses the open chat's height and lines per page
 *  ({@code getHeight}, {@code getLinesPerPage}) and keeps the scroll place as lines arrive. {@code public boolean
 *  isChatFocused()} (javap, 26.3 and 26.2). No screen is opened, so the game keeps its input. */
@Mixin(ChatComponent.class)
public abstract class PeekChatFocusMixin {

    @ModifyReturnValue(method = "isChatFocused", at = @At("RETURN"), require = 0)
    private boolean killer560smod$peekFocused(boolean focused) {
        return focused || PeekChatFeature.isPeeking();
    }
}
