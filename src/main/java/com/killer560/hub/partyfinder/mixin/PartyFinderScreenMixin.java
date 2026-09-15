package com.killer560.hub.partyfinder.mixin;

import com.killer560.hub.partyfinder.PartyFinderOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Party Finder Overlay hooks (Devonian uses RenderSlotEvent / PostRenderSlotsEvent for these):
 *  <ul>
 *  <li>highlight fill - in {@code extractSlot} right before the item is drawn (same {@code Slot.isFake} point as
 *  {@code ItemRaritySlotMixin}), so the head renders on top;
 *  <li>member count - at the end of {@code extractSlot}, above the item;
 *  <li>tooltip rewrite - on the list returned by {@code getTooltipFromContainerItem} (Devonian rewrites the item's
 *  lore instead; editing the returned tooltip leaves the real item untouched).
 *  </ul>
 *  All {@code require = 0}: a mismatch after an MC update only disables the overlay. */
@Mixin(AbstractContainerScreen.class)
public abstract class PartyFinderScreenMixin {

    @Inject(
            method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;isFake()Z"),
            require = 0
    )
    private void killer560smod$partyFinderHighlight(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        try {
            PartyFinderOverlay.drawSlotBackground((AbstractContainerScreen<?>) (Object) this, graphics, slot);
        } catch (Throwable ignored) {
        }
    }

    @Inject(
            method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At("TAIL"),
            require = 0
    )
    private void killer560smod$partyFinderCount(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        try {
            PartyFinderOverlay.drawSlotForeground((AbstractContainerScreen<?>) (Object) this, graphics, slot);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$partyFinderTooltip(ItemStack stack, CallbackInfoReturnable<List<Component>> cir) {
        try {
            List<Component> rewritten = PartyFinderOverlay.rewriteTooltip((AbstractContainerScreen<?>) (Object) this,
                    stack, cir.getReturnValue());
            if (rewritten != null) {
                cir.setReturnValue(rewritten);
            }
        } catch (Throwable ignored) {
        }
    }
}
