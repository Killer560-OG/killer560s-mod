package com.killer560.hub.terminals.mixin;

import com.killer560.hub.terminals.TerminalQolFeature;
import com.killer560.hub.util.ChatObserver;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide Completion, chat half. Hooks the same private funnel {@code ChatComponentMixin} already uses -
 *  {@code addMessage(Component, MessageSignature, GuiMessageSource, GuiMessageTag)}, javap-confirmed on the real
 *  26.1.2 jar as the single method all three public add paths call - so a completion line another mod cancelled
 *  via Fabric's {@code ALLOW_GAME} and re-added itself is caught here too.
 *  <p>
 *  {@link ChatObserver#onChatComponentAdd} is called explicitly before cancelling, and that is the load-bearing
 *  part of this class: the mod's Device Times, Terminal Timers and Tick Timers all read these exact lines
 *  through {@code ChatObserver}, which normally sees them via {@code ChatComponentMixin}'s own
 *  {@code @ModifyVariable} on this same method. Two HEAD injections on one method have no guaranteed order, so
 *  this class cannot assume that already ran. Calling it here makes both orders correct:
 *  <ul>
 *  <li>this injection first - observers are dispatched once here, then the cancel stops the ModifyVariable ever
 *  running;
 *  <li>this injection second - the ModifyVariable already dispatched, and this second call is dropped by
 *  {@code ChatObserver}'s own 250 ms identical-text de-dup.
 *  </ul>
 *  Either way subscribers see the line exactly once. {@code ChatObserver}'s rewriters can run twice in the
 *  second case; the only registered one ({@code TerminalTimersFeature#rewrite}) has its own 250 ms
 *  same-line guard, so its bookkeeping cannot be double-counted, and the returned component is discarded
 *  anyway because the line is being hidden. */
@Mixin(ChatComponent.class)
public abstract class TerminalQolChatMixin {

    @Inject(method = "addMessage", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideTerminalCompletionChat(Component message, MessageSignature signature,
                                                          GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
        if (!TerminalQolFeature.shouldHideCompletionChat(message)) {
            return;
        }
        ChatObserver.onChatComponentAdd(message);
        ci.cancel();
    }
}
