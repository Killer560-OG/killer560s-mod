package com.killer560.hub.commandprefix.mixin;

import com.killer560.hub.commandprefix.CommandPrefixFeature;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Command Prefixes "Prefix Key Opens Chat": a character typed with no screen open (vanilla drops it) is offered to
 *  {@link CommandPrefixFeature#onChar}. Target {@code charTyped(long, CharacterEvent)}: public on 26.3, private on 26.2 (javap). */
@Mixin(KeyboardHandler.class)
public class PrefixOpenChatMixin {

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void killer560smod$prefixOpenChat(long window, CharacterEvent event, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (window == mc.getWindow().handle() && CommandPrefixFeature.onChar(mc, event.codepoint())) {
            ci.cancel();
        }
    }
}
