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
 * painted after the whole screen, over the tooltip). Hide Opened Runs cancels only a finished run's slot drawing (its item
 * and highlight): killer560, 2026-10-12, "I should still be able to click where there is no run to open it, just the
 * texture is missing" - the hover square, the tooltip and every click still work on it.
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

    /** The corner mark (key, "?") over the item, under the tooltip: after the slot's item is drawn. */
    @Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At("TAIL"), require = 0)
    private void killer560smod$drawRunGlyph(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                                            CallbackInfo ci) {
        ChestProfitFeature.drawRunGlyph(graphics, (AbstractContainerScreen<?>) (Object) this, slot);
        com.killer560.hub.croesus.CroesusExtras.drawKismetCount(graphics, (AbstractContainerScreen<?>) (Object) this, slot);
    }

    @Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;isFake()Z"), require = 0)
    private void killer560smod$drawRunHighlight(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                                               CallbackInfo ci) {
        ChestProfitFeature.drawRunSlot(graphics, (AbstractContainerScreen<?>) (Object) this, slot);
    }
}
