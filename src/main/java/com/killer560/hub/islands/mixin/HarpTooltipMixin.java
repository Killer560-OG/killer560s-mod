package com.killer560.hub.islands.mixin;

import com.killer560.hub.islands.HarpHelperFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Melody's Harp Helper's Hide Tooltips: an empty tooltip for every item while the harp menu is open. Same funnel as the
 *  other tooltip mixins ({@code Screen.getTooltipFromItem}); the check is one field read outside the harp. */
@Mixin(Screen.class)
public abstract class HarpTooltipMixin {

    @Inject(
            method = "getTooltipFromItem(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private static void killer560smod$hideHarpTooltip(Minecraft client, ItemStack stack,
                                                      CallbackInfoReturnable<List<Component>> cir) {
        if (HarpHelperFeature.hidesTooltips()) {
            cir.setReturnValue(List.of());
        }
    }
}
