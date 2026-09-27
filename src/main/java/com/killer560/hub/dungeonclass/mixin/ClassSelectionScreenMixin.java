package com.killer560.hub.dungeonclass.mixin;

import com.killer560.hub.dungeonclass.ClassSelectionOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link ClassSelectionOverlay}'s two hooks into any open container screen:
 *  <ul>
 *  <li>render - at the tail of {@code extractContents}, so the party bar and the five class boxes' faint
 *  labels draw on top of everything the screen has already drawn this frame (same reasoning as
 *  {@code partyfinder.mixin.PartyFinderScreenMixin}'s member-count hook: nothing after this point in the
 *  method would draw over it).
 *  <li>input - at the head of {@code mouseClicked}, cancellable. This has to run before vanilla's own click
 *  handling: the party bar is drawn above the container panel's own texture, in space vanilla would otherwise
 *  treat as "clicked outside the menu" and close the screen on. {@link ClassSelectionOverlay#mouseClicked}
 *  only ever returns true for a click on our own top-bar chips or (while a name is picked up) one of the real
 *  class-item slots - anything else is left completely alone, so a real click on a real slot behaves exactly
 *  as if this overlay were not running whenever nothing is picked up.
 *  </ul>
 *  Both {@code require = 0}: a mismatch after an MC update only disables the overlay, same as Party Finder's. */
@Mixin(AbstractContainerScreen.class)
public abstract class ClassSelectionScreenMixin {

    @Inject(
            method = "extractContents(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("TAIL"),
            require = 0
    )
    private void killer560smod$classSelectionRender(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                     float partialTick, CallbackInfo ci) {
        try {
            ClassSelectionOverlay.render((AbstractContainerScreen<?>) (Object) this, graphics);
        } catch (Throwable ignored) {
        }
    }

    @Inject(
            method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void killer560smod$classSelectionClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (ClassSelectionOverlay.mouseClicked((AbstractContainerScreen<?>) (Object) this,
                    event.x(), event.y(), event.button())) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {
        }
    }
}
