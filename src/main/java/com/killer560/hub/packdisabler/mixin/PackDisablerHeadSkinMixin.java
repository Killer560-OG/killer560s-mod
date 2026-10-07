package com.killer560.hub.packdisabler.mixin;

import com.killer560.hub.packdisabler.PackDisabler;
import net.minecraft.client.renderer.special.PlayerHeadSpecialRenderer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * When Pack Disabler draws an item with the vanilla player-head model (its pre-pack look was a head), the head renderer
 * reads the skin from the stack's PROFILE, which a pack-era item no longer carries. {@code extractArgument} stores that
 * profile in its only ResolvableProfile local (javap, 26.1.2 and 26.2 identical); this supplies the old skin there, so
 * the normal player-head path (skin cache, the player's packs) draws it.
 */
@Mixin(PlayerHeadSpecialRenderer.class)
public class PackDisablerHeadSkinMixin {

    @ModifyVariable(
            method = "extractArgument(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/client/renderer/PlayerSkinRenderCache$RenderInfo;",
            at = @At("STORE"), ordinal = 0)
    private ResolvableProfile killer560smod$packDisablerSkin(ResolvableProfile profile, ItemStack stack) {
        return PackDisabler.resolveProfile(stack, profile);
    }
}
