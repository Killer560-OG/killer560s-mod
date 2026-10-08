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
            ChatTidy.Match match = ChatTidy.findStack(allMessages, built, visibleMessageFilter);
            if (match == null) {
                return;
            }
            GuiMessage earlier = match.earlier();
            GuiMessage replacement = new GuiMessage(built.addedTime(), match.content(), null, built.source(), built.tag());
            allMessages.remove(match.index());
            // Its wrapped lines: trimmedMessages is newest first and index i is i rows above the bottom, so a removed
            // line below the scrolled view (i < chatScrollbarPos) shifts the view by one; one inside or above it does not.
            int belowView = 0;
            int i = 0;
            for (Iterator<GuiMessage.Line> it = trimmedMessages.iterator(); it.hasNext(); i++) {
                if (it.next().parent() == earlier) {
                    it.remove();
                    if (i < chatScrollbarPos) {
                        belowView++;
                    }
                }
            }
            if (belowView > 0) {
                chatScrollbarPos = Math.max(0, chatScrollbarPos - belowView);
            }
            logChatMessage(built);
            addMessageToDisplayQueue(replacement);
            addMessageToQueue(replacement);
            ChatTidy.stacked(match, replacement);
            ci.cancel();
        } catch (RuntimeException e) {
            ChatTidy.fail(e);
        }
    }
}
