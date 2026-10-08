package com.killer560.hub.auction.ah.mixin;

import com.killer560.hub.auction.ah.AhReskin;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the real AH chest's slots, hover highlight, tooltip and labels under the reskin. The Bazaar reskin's five
 *  targets on {@code AbstractContainerScreen} (javap, 26.1.2 and 26.2): {@code extractSlot(GuiGraphicsExtractor, Slot,
 *  int, int)}, {@code extractSlotHighlightBack/Front(GuiGraphicsExtractor)}, {@code extractTooltip(GuiGraphicsExtractor,
 *  int, int)}, {@code extractLabels(GuiGraphicsExtractor, int, int)}. */
@Mixin(AbstractContainerScreen.class)
public abstract class AhReskinHideMixin {

    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideAhSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (AhReskin.isHiding(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractSlotHighlightBack", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideAhHighlightBack(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (AhReskin.isHiding(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractSlotHighlightFront", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideAhHighlightFront(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (AhReskin.isHiding(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideAhTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (AhReskin.isHiding(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractLabels", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideAhLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (AhReskin.isHiding(this)) {
            ci.cancel();
        }
    }
}
