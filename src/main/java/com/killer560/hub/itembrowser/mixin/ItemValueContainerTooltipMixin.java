package com.killer560.hub.itembrowser.mixin;

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
 * The container-slot-hover half of the "Item Value" tooltip (see {@link ItemValueTooltipMixin}'s class
 * doc for why both hooks exist) - same injection point/reasoning as
 * {@code RngMeterOverlay}'s own {@code AbstractContainerScreenMixin#killer560smod$appendProfitLine}
 * (the confirmed real funnel for a container/chest slot hover in 26.1.2).
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ItemValueContainerTooltipMixin extends Screen {

    protected ItemValueContainerTooltipMixin(Component title) {
        super(title);
    }

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$appendItemValue(ItemStack stack, CallbackInfoReturnable<List<Component>> cir) {
        try {
            List<Component> withValue = ItemValueTooltipHelper.appendValue(stack, cir.getReturnValue());
            if (withValue != null) {
                cir.setReturnValue(withValue);
            }
        } catch (Throwable ignored) {
            // Visual-only; the unmodified vanilla tooltip is already the return value.
        }
    }
}
