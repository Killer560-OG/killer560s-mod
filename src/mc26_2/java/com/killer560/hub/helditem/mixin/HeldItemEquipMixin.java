package com.killer560.hub.helditem.mixin;

import com.killer560.hub.helditem.HeldItemConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Held Item Transform "No Re-equip" - see {@link HeldItemConfig}. 26.2 copy: the equip state lives on
 *  {@code ItemInHandRenderer} ({@code shouldInstantlyReplaceVisibleItem(ItemStack, ItemStack)}, {@code tick()} and the
 *  four hand-height floats, javap). 26.3 moved all of it to {@code FirstPersonHandsAndItems}; see that copy. Split out of
 *  {@link HeldItemInHandRendererMixin} on 2026-11-01 so each version's copy targets one class. */
@Mixin(ItemInHandRenderer.class)
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
