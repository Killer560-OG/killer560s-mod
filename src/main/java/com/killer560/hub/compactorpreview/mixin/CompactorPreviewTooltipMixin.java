package com.killer560.hub.compactorpreview.mixin;

import com.killer560.hub.compactorpreview.CompactorPreviewFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/** Adds the Compactor Preview grid to the component list handed to {@code GuiGraphicsExtractor#tooltip(Font, List, int, int,
 *  ClientTooltipPositioner, Identifier)}, the one place every tooltip is drawn (javap 26.1.2 and 26.2; Scrollable Tooltips
 *  wraps the same method). The list is replaced by a copy, never edited, since vanilla's may be immutable.
 *  {@code require = 0}: a changed signature disables the feature instead of crashing; any failure leaves the tooltip as is. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class CompactorPreviewTooltipMixin {

    @ModifyVariable(
            method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private List<ClientTooltipComponent> killer560smod$addCompactorGrid(List<ClientTooltipComponent> components) {
        try {
            return CompactorPreviewFeature.augment(components);
        } catch (Throwable ignored) {
            return components;
        }
    }
}
