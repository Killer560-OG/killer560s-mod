package com.killer560.hub.terminals.mixin;

import com.killer560.hub.terminals.TerminalQolFeature;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide Completion, title half (Devonian {@code dungeons/f7/TerminalHideCompletion.kt}). Devonian cancels the
 *  {@code ClientboundSetTitleTextPacket}/{@code ClientboundSetSubtitleTextPacket} on its own packet event;
 *  hooking {@code Gui.setTitle}/{@code Gui.setSubtitle} instead reaches the same thing one step later, on the
 *  client thread, without this mod needing a packet-level mixin at all - {@code ClientPacketListener}'s title
 *  handlers call exactly these two methods and nothing else does.
 *  <p>
 *  Targets verified with javap against the real 26.1.2 jar: {@code public void setTitle(Component)} and
 *  {@code public void setSubtitle(Component)} both really exist on {@code net.minecraft.client.gui.Gui}.
 *  Cancelling is safe on both - neither returns anything and neither is the thing that drives the title FADE
 *  timer ({@code setTimes}/{@code resetTitleTimes} are separate methods), so a hidden title simply never gets
 *  any text to draw. */
@Mixin(Gui.class)
public abstract class TerminalQolTitleMixin {

    @Inject(method = "setTitle", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideTerminalCompletionTitle(Component title, CallbackInfo ci) {
        if (TerminalQolFeature.shouldHideCompletionTitle(title)) {
            ci.cancel();
        }
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideTerminalCompletionSubtitle(Component subtitle, CallbackInfo ci) {
        if (TerminalQolFeature.shouldHideCompletionTitle(subtitle)) {
            ci.cancel();
        }
    }
}
