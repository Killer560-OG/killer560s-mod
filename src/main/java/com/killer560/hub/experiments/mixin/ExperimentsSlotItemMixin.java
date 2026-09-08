package com.killer560.hub.experiments.mixin;

import com.killer560.hub.experiments.ExperimentsFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Substitutes the real Superpairs item icon in place of a covering glass pane at the SOURCE - the very
 * first {@code Slot.getItem()} read inside {@code AbstractContainerScreen.extractSlot} (verified via
 * javap against the real decompiled jar: it's the first ItemStack local stored in that method, right
 * after reading the slot's x/y). Real bug found and fixed (2026-09-07): the previous approach drew a
 * second ghost item on top of the vanilla-rendered pane from {@code ExperimentsFeature.highlightSlot},
 * relying on draw order/compositing to hide the pane underneath - killer560 confirmed via real testing that
 * this did NOT reliably work even after a {@code graphics.nextStratum()} fix attempt (Minecraft's item
 * icons render with real depth for their isometric look, so a second item at the same position can still
 * lose a depth test to the first one regardless of submission order). Substituting at the source instead
 * means only ONE item is ever extracted/rendered for that slot in the first place, so there's no z-order
 * or depth-buffer question to get wrong.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ExperimentsSlotItemMixin {

    @ModifyVariable(method = "extractSlot", at = @At("STORE"), ordinal = 0)
    private ItemStack killer560smod$substituteGhostIcon(ItemStack original, GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY) {
        ItemStack ghost = ExperimentsFeature.superpairsGhostIcon(slot.index);
        return ghost != null && !ghost.isEmpty() ? ghost : original;
    }
}
