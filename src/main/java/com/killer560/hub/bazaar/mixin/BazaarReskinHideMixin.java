package com.killer560.hub.bazaar.mixin;

import com.killer560.hub.bazaar.BazaarReskin;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the real Bazaar chest's slots, hover highlight, tooltip and labels while the reskin is drawn over it. Same
 *  five targets as {@code SpiritLeapHideMixin}, each checked with javap on the 26.1.2 and 26.2 jars (2026-10-07):
 *  {@code extractSlot(GuiGraphicsExtractor, Slot, int, int)}, {@code extractSlotHighlightBack/Front(GuiGraphicsExtractor)},
 *  {@code extractTooltip(GuiGraphicsExtractor, int, int)}, {@code extractLabels(GuiGraphicsExtractor, int, int)}. */
@Mixin(AbstractContainerScreen.class)
public abstract class BazaarReskinHideMixin {

    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideBazaarSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (BazaarReskin.isHiding(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractSlotHighlightBack", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideBazaarHighlightBack(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (BazaarReskin.isHiding(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractSlotHighlightFront", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideBazaarHighlightFront(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (BazaarReskin.isHiding(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideBazaarTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (BazaarReskin.isHiding(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractLabels", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideBazaarLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (BazaarReskin.isHiding(this)) {
            ci.cancel();
        }
    }
}
