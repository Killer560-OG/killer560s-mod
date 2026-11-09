package com.killer560.hub.itemhighlight.mixin;

import com.killer560.hub.itemhighlight.DungeonItemHighlighter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Same injection point as {@code InventorySearchSlotMixin}: the fill goes under the item, over the slot. */
@Mixin(AbstractContainerScreen.class)
public abstract class DungeonItemHighlightSlotMixin {

    @Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;isFake()Z"), require = 0)
    private void killer560smod$drawDungeonItemMark(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                                                   CallbackInfo ci) {
        DungeonItemHighlighter.drawSlotHighlight(graphics, (AbstractContainerScreen<?>) (Object) this, slot);
    }
}
