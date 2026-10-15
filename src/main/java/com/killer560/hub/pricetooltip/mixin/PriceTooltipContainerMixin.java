package com.killer560.hub.pricetooltip.mixin;

import com.killer560.hub.pricetooltip.PriceTooltipFeature;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * The container-slot half of the Price Tooltip (see {@link PriceTooltipScreenMixin}): {@code
 * AbstractContainerScreen#getTooltipFromContainerItem}, the funnel every chest-style menu's slot hover goes through.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class PriceTooltipContainerMixin extends Screen {

    protected PriceTooltipContainerMixin(Component title) {
        super(title);
    }

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$appendPrices(ItemStack stack, CallbackInfoReturnable<List<Component>> cir) {
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
