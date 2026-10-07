package com.killer560.hub.chattidy.mixin;

import com.killer560.hub.chattidy.ChatTidy;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Chat Tidy's display-time hook (see {@link ChatTidy}). {@code ChatComponent.addMessage(Component, MessageSignature,
 * GuiMessageSource, GuiMessageTag)} has the same descriptor and body shape on 26.1.2 and 26.2 (javap 2026-10-07; only
 * the gui-ticks getter it calls first moved, Gui -> Hud), so one copy serves both.
 * <p>
 * It injects at the method's one {@code Predicate.test} call (the visible-message filter), which is AFTER
 * {@code clicktranslate/mixin/ChatComponentMixin}'s HEAD hook has handed the line to {@code ChatObserver} and after the
 * {@code GuiMessage} is built. The {@code message} argument here is read from its local slot at this point, so it is
 * the line after every rewriter. Hiding or stacking still writes the ORIGINAL line to the log through
 * {@code logChatMessage}, exactly as vanilla would have.
 */
@Mixin(ChatComponent.class)
public abstract class ChatTidyMixin {

    @Shadow
    @Final
    private List<GuiMessage> allMessages;

    @Shadow
    @Final
    private List<GuiMessage.Line> trimmedMessages;

    @Shadow
    private int chatScrollbarPos;

    @Shadow
    private Predicate<GuiMessage> visibleMessageFilter;

    @Shadow
    protected abstract void logChatMessage(GuiMessage message);

    @Shadow
    protected abstract void addMessageToDisplayQueue(GuiMessage message);

    @Shadow
    protected abstract void addMessageToQueue(GuiMessage message);

    @Inject(method = "addMessage",
            at = @At(value = "INVOKE", target = "Ljava/util/function/Predicate;test(Ljava/lang/Object;)Z"),
            cancellable = true)
    private void killer560smod$chatTidy(Component message, MessageSignature signature, GuiMessageSource source,
                                        GuiMessageTag tag, CallbackInfo ci, @Local GuiMessage built) {
        try {
            if (ChatTidy.shouldHide(message, source)) {
                logChatMessage(built);
                ci.cancel();
                return;
            }
            if (!ChatTidy.stackingOn() || allMessages.isEmpty() || !visibleMessageFilter.test(built)) {
                return;
            }
            GuiMessage top = allMessages.get(0);
            Component stackedContent = ChatTidy.stack(top, built);
            if (stackedContent == null) {
                return;
            }
            GuiMessage replacement = new GuiMessage(built.addedTime(), stackedContent, null, built.source(), built.tag());
            allMessages.remove(0);
            int removed = 0;
            for (Iterator<GuiMessage.Line> it = trimmedMessages.iterator(); it.hasNext(); ) {
                if (it.next().parent() == top) {
                    it.remove();
                    removed++;
                }
            }
            if (removed > 0 && chatScrollbarPos > 0) {
                // addMessageToDisplayQueue scrolls up one per line it adds while the chat is scrolled; these were taken away.
                chatScrollbarPos = Math.max(0, chatScrollbarPos - removed);
            }
            logChatMessage(built);
            addMessageToDisplayQueue(replacement);
            addMessageToQueue(replacement);
            ChatTidy.stacked(replacement);
            ci.cancel();
        } catch (RuntimeException e) {
            ChatTidy.fail(e);
        }
    }
}
