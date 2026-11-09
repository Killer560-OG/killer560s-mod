package com.killer560.hub.dungeonqol.mixin;

import com.killer560.hub.dungeonqol.ItemQualityFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Item Quality line on the same tooltip funnel Enchant Colors uses (see its mixin for why this method). */
@Mixin(Screen.class)
public abstract class ItemQualityTooltipMixin {

    @Inject(
            method = "getTooltipFromItem(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private static void killer560smod$itemQuality(Minecraft client, ItemStack stack,
                                                  CallbackInfoReturnable<List<Component>> cir) {
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
