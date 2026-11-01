package com.killer560.hub.helditem.mixin;

import com.killer560.hub.helditem.HeldItemConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Held Item Transform "No Re-equip" - see {@link HeldItemConfig}. 26.3 copy: the equip state moved from
 *  {@code ItemInHandRenderer} to {@code net.minecraft.client.player.FirstPersonHandsAndItems}, with the same four
 *  hand-height floats and {@code shouldInstantlyReplaceVisibleItem(ItemStack, ItemStack, LocalPlayer)} /
 *  {@code tick(LocalPlayer)} (javap, 26.3). The handlers take only the callback info, which Mixin accepts for any
 *  descriptor, so they are word for word the 26.2 copy's. */
@Mixin(FirstPersonHandsAndItems.class)
public abstract class HeldItemEquipMixin {

    @Shadow private float mainHandHeight;
    @Shadow private float oMainHandHeight;
    @Shadow private float offHandHeight;
    @Shadow private float oOffHandHeight;

    @Inject(method = "shouldInstantlyReplaceVisibleItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$heldItemInstantReplace(CallbackInfoReturnable<Boolean> cir) {
        if (!HeldItemConfig.isActive()) return;
        if (HeldItemConfig.getInstance().isNoEquip()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void killer560smod$heldItemNoEquip(CallbackInfo ci) {
        if (!HeldItemConfig.isActive()) return;
        if (!HeldItemConfig.getInstance().isNoEquip()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        // Vanilla drops the hands out of view while riding etc. (isHandsBusy) - keep that behavior.
        if (player == null || player.isHandsBusy()) return;
        mainHandHeight = 1f;
        oMainHandHeight = 1f;
        offHandHeight = 1f;
        oOffHandHeight = 1f;
    }
}
