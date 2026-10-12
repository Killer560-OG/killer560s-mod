package com.killer560.hub.croesus.mixin;

import com.killer560.hub.croesus.ChestProfitFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Croesus menu's run heads (killer560, 2026-10-12). Highlight Runs is drawn at the one {@code Slot.isFake()} call
 * in {@code extractSlot(GuiGraphicsExtractor, Slot, int, int)}, right before the item - the point Inventory Search and
 * the Inventory Theme use - so the item, the hover highlight and the tooltip all sit on top of it (it used to be
 * painted after the whole screen, over the tooltip). Hide Opened Runs cancels a claimed run's slot, its hover highlight
 * and its tooltip, with the same targets the Bazaar reskin hides (javap 26.1.2 and 26.2, BazaarReskinHideMixin).
 */
@Mixin(AbstractContainerScreen.class)
public abstract class CroesusSlotMixin {

    @Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideOpenedRun(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                                            CallbackInfo ci) {
        if (ChestProfitFeature.hidesSlot((AbstractContainerScreen<?>) (Object) this, slot)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;isFake()Z"), require = 0)
    private void killer560smod$drawRunHighlight(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                                               CallbackInfo ci) {
        ChestProfitFeature.drawRunSlot(graphics, (AbstractContainerScreen<?>) (Object) this, slot);
    }

    @Inject(method = "extractSlotHighlightBack", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideOpenedRunHoverBack(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (ChestProfitFeature.hidesHovered((AbstractContainerScreen<?>) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractSlotHighlightFront", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideOpenedRunHoverFront(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (ChestProfitFeature.hidesHovered((AbstractContainerScreen<?>) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideOpenedRunTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                   CallbackInfo ci) {
        if (ChestProfitFeature.hidesHovered((AbstractContainerScreen<?>) (Object) this)) {
            ci.cancel();
        }
    }
}
