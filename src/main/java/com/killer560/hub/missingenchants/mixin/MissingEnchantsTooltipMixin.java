package com.killer560.hub.missingenchants.mixin;

import com.killer560.hub.missingenchants.MissingEnchantsFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Adds the Missing Enchants section to every client-built item tooltip (the same funnel Enchant Colors uses; the higher priority makes this run after its recolor). Draw-only: the item's data is untouched. */
@Mixin(value = Screen.class, priority = 2000)
public abstract class MissingEnchantsTooltipMixin {

    @Inject(
            method = "getTooltipFromItem(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private static void killer560smod$missingEnchants(Minecraft client, ItemStack stack, CallbackInfoReturnable<List<Component>> cir) {
        try {
            List<Component> lines = cir.getReturnValue();
            List<Component> out = MissingEnchantsFeature.append(stack, lines);
            if (out != lines) {
                cir.setReturnValue(out);
            }
        } catch (Throwable ignored) {
            // Visual-only; the unmodified tooltip is already the return value.
        }
    }
}
