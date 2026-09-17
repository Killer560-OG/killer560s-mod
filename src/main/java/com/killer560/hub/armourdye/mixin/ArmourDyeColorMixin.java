package com.killer560.hub.armourdye.mixin;

import com.killer560.hub.armourdye.ArmourDye;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The dye half of Armour Recolour, and the single highest-value hook in the whole feature - this is the same place
 * Skyblocker hooks ({@code mixins/DyedItemColorMixin}, credited in {@link ArmourDye}).
 * <p>
 * {@code DyedItemColor.getOrDefault(stack, fallback)} is what BOTH render paths ask for the leather tint:
 * {@code EquipmentLayerRenderer#renderLayers} calls it for the armour painted on the body, and
 * {@code net.minecraft.client.color.item.Dye} calls it for the item model in inventories, the hotbar, your hand and
 * dropped item entities. One override therefore covers every surface at once. Because it replaces the value
 * {@code getOrDefault} was going to return, it also works on a piece that has no {@code minecraft:dyed_color}
 * component at all - the mod never has to add one, so nothing is ever written to the stack.
 * <p>
 * Plain {@code @Inject} rather than MixinExtras' {@code @ModifyReturnValue}: the stack is parameter 0 here, so
 * there is nothing to capture, and this repo doesn't pull MixinExtras in anywhere else.
 */
@Mixin(DyedItemColor.class)
public class ArmourDyeColorMixin {

    @Inject(method = "getOrDefault(Lnet/minecraft/world/item/ItemStack;I)I", at = @At("RETURN"), cancellable = true)
    private static void killer560smod$armourDyeColor(ItemStack stack, int fallback, CallbackInfoReturnable<Integer> cir) {
        // Cheapest possible bail-out: one volatile boolean when the feature is off, which is the default.
        if (!ArmourDye.active()) {
            return;
        }
        int original = cir.getReturnValueI();
        // ArmourDye swallows its own exceptions and latches the feature off after repeated failures, so a bad
        // entry can never throw out of a render call.
        int replaced = ArmourDye.dyeColor(stack, original);
        if (replaced != original) {
            cir.setReturnValue(replaced);
        }
    }
}
