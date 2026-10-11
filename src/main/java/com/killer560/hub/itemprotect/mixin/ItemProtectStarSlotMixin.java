package com.killer560.hub.itemprotect.mixin;

import com.killer560.hub.itemprotect.ItemProtectFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the protected-item star as part of the slot itself, at the TAIL of
 * {@code AbstractContainerScreen#extractSlot(GuiGraphicsExtractor, Slot, int, int)} - after the item and its count
 * (javap 26.1.2 and 26.2: the last return follows {@code itemDecorations}). killer560, 2026-10-11: "if i hover
 * something with a lock icon then it disappears". The star used to be drawn from {@code ScreenEvents.afterExtract},
 * which runs after the deferred tooltip's stratum, so the hovered slot had to be skipped to keep the star off the
 * tooltip. Drawn here it sits in the slots' own stratum: every protected slot keeps its star, hovered or not, and
 * the tooltip still covers it. A slot another mixin hides with a HEAD cancel (Storage Overlay, reskins) never
 * reaches the TAIL, so no star is left where no item draws. Coordinates are slot-local: {@code extractContents}
 * has already translated by leftPos/topPos (docs/LESSONS-GUI.md).
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ItemProtectStarSlotMixin {

    @Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At("TAIL"), require = 0)
    private void killer560smod$drawProtectStar(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                                               CallbackInfo ci) {
        ItemProtectFeature.drawSlotStar(graphics, slot);
    }
}
