package com.killer560.hub.miningqol.mixin;

import com.killer560.hub.miningqol.CommissionMenuHighlight;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Completed Commissions' green slots, behind the item: the one {@code Slot.isFake()} call in
 * {@code extractSlot(GuiGraphicsExtractor, Slot, int, int)}, the point Croesus' Highlight Runs uses (same target, javap
 * 26.3 and 26.2), so the item, the hover square and the tooltip all sit on top.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class CommissionMenuSlotMixin {

    @Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;isFake()Z"), require = 0)
    private void killer560smod$commissionSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                                              CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        CommissionMenuHighlight.drawSlot(graphics, screen, slot);
        com.killer560.hub.loadoutkeybinds.LoadoutHighlight.drawSlot(graphics, screen, slot);
    }
}
