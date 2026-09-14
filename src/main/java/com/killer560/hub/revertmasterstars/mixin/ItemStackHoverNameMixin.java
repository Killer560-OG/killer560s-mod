package com.killer560.hub.revertmasterstars.mixin;

import com.killer560.hub.revertmasterstars.RevertMasterStarsFeature;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Read-modify-return only - injects at the tail of real vanilla {@code ItemStack#getHoverName()} and
 *  optionally swaps its return value, same low-risk shape as QUOI's own confirmed, shipped mixin this
 *  is ported from. Never touches item data, NBT, or anything server-visible - purely a client-side
 *  display-text substitution. */
@Mixin(ItemStack.class)
public abstract class ItemStackHoverNameMixin {

    @Inject(method = "getHoverName", at = @At("RETURN"), cancellable = true)
    private void killer560smod$revertMasterStars(CallbackInfoReturnable<Component> cir) {
        Component original = cir.getReturnValue();
        Component modified = RevertMasterStarsFeature.modifyHoverName(original);
        if (modified != original) {
            cir.setReturnValue(modified);
        }
    }
}
