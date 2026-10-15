package com.killer560.hub.pricetooltip.mixin;

import com.killer560.hub.pricetooltip.PriceTooltipFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Price Tooltip lines (see {@link PriceTooltipFeature}) on every tooltip built through
 * {@code Screen#getTooltipFromItem(Minecraft, ItemStack)} - the player inventory and every plain screen. Container slot
 * hovers do not all arrive here, so {@link PriceTooltipContainerMixin} hooks those; the feature never adds its lines
 * twice. Moved here from the Item Browser's old "Item Value" line (2026-10-15), whose two hooks these were.
 */
@Mixin(Screen.class)
public abstract class PriceTooltipScreenMixin {

    @Inject(
            method = "getTooltipFromItem(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private static void killer560smod$appendPrices(Minecraft client, ItemStack stack,
                                                     CallbackInfoReturnable<List<Component>> cir) {
        try {
            List<Component> withPrices = PriceTooltipFeature.append(stack, cir.getReturnValue());
            if (withPrices != null) {
                cir.setReturnValue(withPrices);
            }
        } catch (Throwable ignored) {
            // Visual-only; the unmodified tooltip is already the return value.
        }
    }
}
