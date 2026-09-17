package com.killer560.hub.enchantcolors.mixin;

import com.killer560.hub.enchantcolors.EnchantColorsFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Recolours enchantment names in item lore - see {@link EnchantColorsFeature}.
 *  <p>
 *  Verified with javap against minecraft-merged-043a8b3edf-26.1.2.jar: {@code public static
 *  List&lt;Component&gt; getTooltipFromItem(Minecraft, ItemStack)} on {@code Screen}, whose body is a single
 *  call to {@code ItemStack#getTooltipLines}. It is the one funnel worth hooking:
 *  {@code AbstractContainerScreen#getTooltipFromContainerItem(ItemStack)} is literally
 *  {@code return getTooltipFromItem(this.minecraft, stack);} (confirmed in the same disassembly), so every
 *  container menu, the player inventory, and every other screen that shows an item tooltip all arrive here.
 *  <p>
 *  Injecting on the RETURN of the client-side tooltip BUILDER rather than on {@code ItemStack#getTooltipLines}
 *  keeps this off the item's real data - nothing the server or any logic path sees is modified, only what is
 *  drawn. {@code require = 0} so a signature change after a Minecraft update disables the feature instead of
 *  crashing, and the body swallows throwables: a lore parsing edge case must never take down tooltip
 *  rendering. */
@Mixin(Screen.class)
public abstract class EnchantColorsTooltipMixin {

    @Inject(
            method = "getTooltipFromItem(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private static void killer560smod$colorEnchants(Minecraft client, ItemStack stack,
                                                    CallbackInfoReturnable<java.util.List<Component>> cir) {
        try {
            java.util.List<Component> lines = cir.getReturnValue();
            java.util.List<Component> recolored = EnchantColorsFeature.recolor(stack, lines);
            if (recolored != lines) {
                cir.setReturnValue(recolored);
            }
        } catch (Throwable ignored) {
            // Visual-only; the unmodified vanilla tooltip is already the return value.
        }
    }
}
