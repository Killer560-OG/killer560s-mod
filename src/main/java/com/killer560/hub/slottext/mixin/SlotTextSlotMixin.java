package com.killer560.hub.slottext.mixin;

import com.killer560.hub.slottext.SlotTextFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws Slot Text right after the item's own count and durability bar in
 *  {@code AbstractContainerScreen#extractSlot(GuiGraphicsExtractor, Slot, int, int)}: the method has exactly one call to
 *  {@code GuiGraphicsExtractor#itemDecorations(Font, ItemStack, int, int, String)} (javap, 26.1.2 and 26.2), so the text
 *  sits above the item and its count. Slot-local coordinates (the screen has already translated by leftPos/topPos). A
 *  slot another mixin hides with a HEAD cancel never reaches this. {@code require = 0}: a changed signature disables the
 *  feature instead of crashing. */
@Mixin(AbstractContainerScreen.class)
public abstract class SlotTextSlotMixin {

    @Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"),
            require = 0)
    private void killer560smod$drawSlotText(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        try {
            SlotTextFeature.draw(graphics, (AbstractContainerScreen<?>) (Object) this, slot);
        } catch (Throwable ignored) {
            // Visual only; never let a parsing edge case break inventory rendering.
        }
    }
}
