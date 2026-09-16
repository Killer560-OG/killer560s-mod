package com.killer560.hub.terminals.mixin;

import com.killer560.hub.terminals.TerminalQolFeature;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Terminal QoL's two input hooks (see {@link TerminalQolFeature}):
 *  <ul>
 *  <li><b>Terminal Protection</b> - swallows the first click that lands within the configured threshold of a
 *  terminal opening. Deliberately a separate mixin class from
 *  {@link TerminalAutoClickInputBlockMixin} (which blocks ALL input while Auto Terminals is mid-click, a
 *  cheat-build-only feature) - these two are unrelated features with unrelated gates, and two
 *  {@code @Inject}s at the same HEAD compose fine: whichever runs first and cancels wins, and either
 *  outcome (blocked by the bot, blocked by protection) is "this click does not reach the terminal".
 *  <li><b>Melody Keys</b> - 1-4 click Melody's four row buttons. Cancelling is mandatory, not cosmetic:
 *  {@code AbstractContainerScreen.keyPressed} feeds 1-9 to {@code checkHotbarKeyPressed}, so an uncancelled
 *  keypress would send a real SWAP click trying to move a hotbar item into the terminal.
 *  </ul>
 *  Targets verified with javap against the real 26.1.2 jar:
 *  {@code public boolean mouseClicked(MouseButtonEvent, boolean)} and
 *  {@code public boolean keyPressed(KeyEvent)} on {@code AbstractContainerScreen}, and
 *  {@code protected void slotClicked(Slot, int, int, ContainerInput)} (reached through the existing
 *  {@code SlotClickInvoker}). */
@Mixin(AbstractContainerScreen.class)
public abstract class TerminalQolInputMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void killer560smod$terminalProtection(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (TerminalQolFeature.shouldSwallowClick()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void killer560smod$melodyKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        // Escape is never intercepted, same rule TerminalAutoClickInputBlockMixin already follows - closing the
        // menu must never be trapped behind one of this mod's features.
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            return;
        }
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (TerminalQolFeature.handleMelodyKey(self, event.key())) {
            cir.setReturnValue(true);
        }
    }
}
