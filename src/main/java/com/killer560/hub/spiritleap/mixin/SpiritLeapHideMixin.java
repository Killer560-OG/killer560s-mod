package com.killer560.hub.spiritleap.mixin;

import com.killer560.hub.spiritleap.SpiritLeapOverlayFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the real Spirit Leap chest's slots, hover highlight, tooltip and labels while the custom leap menu is
 *  drawn over it (2026-09-15, killer560: "the original menu and my inventory needs to be hidden just like we did
 *  with terminals"). Targets verified with javap on the 26.1.2 jar. */
@Mixin(AbstractContainerScreen.class)
public abstract class SpiritLeapHideMixin {

    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideLeapSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (SpiritLeapOverlayFeature.isHiding(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractSlotHighlightBack", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideLeapHighlightBack(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (SpiritLeapOverlayFeature.isHiding(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractSlotHighlightFront", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideLeapHighlightFront(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (SpiritLeapOverlayFeature.isHiding(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideLeapTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (SpiritLeapOverlayFeature.isHiding(this)) {
            ci.cancel();
        }
    }

    // killer560 (2026-10-11): "dont have it show the player head after I click on the name I want to leap to. right
    // now it shows this little player head almost fully faded out on my cursor". A leap click is a real PICKUP on the
    // head's slot, so until Hypixel answers the client holds that head on its cursor and draws it there (and, on
    // 26.1.2, animates it snapping back). Nothing of the real menu shows while the custom one is up, so neither does
    // the item it carries. extractSnapbackItem is 26.1.2 only; extractFloatingItem is what both draw through (javap,
    // both versions).
    @Inject(method = "extractCarriedItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideLeapCarried(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (SpiritLeapOverlayFeature.isHiding(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractSnapbackItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideLeapSnapback(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (SpiritLeapOverlayFeature.isHiding(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractFloatingItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideLeapFloating(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y, String text,
                                               CallbackInfo ci) {
        if (SpiritLeapOverlayFeature.isHiding(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractLabels", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideLeapLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (SpiritLeapOverlayFeature.isHiding(this)) {
            ci.cancel();
        }
    }
}
