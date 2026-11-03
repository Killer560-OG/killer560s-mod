package com.killer560.hub.bazaar.mixin;

import com.killer560.hub.bazaar.BazaarOrderHelper;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Bazaar Order Helper ({@link BazaarOrderHelper}) in Hypixel's Manage Orders chest: the slot color at the one
 * {@code Slot.isFake()} call in {@code extractSlot} (behind the item and under the tooltip, the point the Croesus
 * highlight and Inventory Search use), and its tooltip lines on the RETURN of {@code getTooltipFromContainerItem} (the
 * funnel the Price Tooltip uses).
 */
@Mixin(AbstractContainerScreen.class)
public abstract class BazaarOrderHelperMixin {

    @Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;isFake()Z"), require = 0)
    private void killer560smod$orderHelperSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                                               CallbackInfo ci) {
        BazaarOrderHelper.drawSlot(graphics, (AbstractContainerScreen<?>) (Object) this, slot);
    }

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$orderHelperTooltip(ItemStack stack, CallbackInfoReturnable<List<Component>> cir) {
        try {
            List<Component> out = BazaarOrderHelper.appendTooltip((AbstractContainerScreen<?>) (Object) this, stack,
                    cir.getReturnValue());
            if (out != null) {
                cir.setReturnValue(out);
            }
        } catch (Throwable ignored) {
            // visual only; the unmodified tooltip is already the return value
        }
    }
}
