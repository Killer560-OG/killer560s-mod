package com.killer560.hub.peekchat.mixin;

import com.killer560.hub.peekchat.PeekChatFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a peek is shown the wheel scrolls the chat and not the hotbar ({@link PeekChatFeature#onScroll}).
 *  {@code private void onScroll(long, double, double)} (javap, 26.3 and 26.2). */
@Mixin(MouseHandler.class)
public abstract class PeekChatScrollMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$peekScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (window == Minecraft.getInstance().getWindow().handle() && PeekChatFeature.onScroll(yOffset)) {
            ci.cancel();
        }
    }
}
