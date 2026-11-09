package com.killer560.hub.dungeonqol.mixin;

import com.killer560.hub.dungeonqol.ItemQualityFeature;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** The container-slot half of Item Quality (see {@link ItemQualityTooltipMixin}): a chest-style menu's slot hover (the
 *  Auction House, the Museum, every Hypixel menu) builds its tooltip in {@code getTooltipFromContainerItem}, which does
 *  not pass through {@code Screen.getTooltipFromItem}. The feature never adds its line twice. */
@Mixin(AbstractContainerScreen.class)
public abstract class ItemQualityContainerMixin extends Screen {

    protected ItemQualityContainerMixin(Component title) { super(title); }

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$itemQuality(ItemStack stack, CallbackInfoReturnable<List<Component>> cir) {
        try {
            List<Component> lines = cir.getReturnValue();
            List<Component> out = ItemQualityFeature.addLine(stack, lines);
            if (out != lines) {
                cir.setReturnValue(out);
            }
        } catch (Throwable ignored) {
            // Visual only; the vanilla tooltip is already the return value.
        }
    }
}
