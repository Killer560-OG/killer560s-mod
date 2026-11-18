package com.killer560.hub.packdisabler.mixin;

import com.killer560.hub.packdisabler.PackDisabler;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Pack Disabler's enchanted looks (texredo3-1117): {@code ItemStack.hasFoil} (javap 26.2 and 26.3) decides the glint
 * wherever a stack is drawn. Only a stack that would draw without it is asked about, and the first test is a set lookup
 * of its pack model, so a normal stack costs one component read.
 */
@Mixin(ItemStack.class)
public class PackDisablerGlintMixin {

    @ModifyReturnValue(method = "hasFoil", at = @At("RETURN"))
    private boolean killer560smod$packDisablerGlint(boolean foil) {
        return foil || PackDisabler.forcedGlint((ItemStack) (Object) this);
    }
}
