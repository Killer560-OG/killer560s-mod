package com.killer560.hub.packdisabler.mixin;

import com.killer560.hub.packdisabler.PackDisabler;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Pack Disabler's one hook into item drawing. {@code appendItemLayers} starts with
 * {@code Identifier model = stack.get(DataComponents.ITEM_MODEL)} and draws whatever that names (javap, 26.1.2 and 26.2
 * identical: the only Identifier local, stored at offset 10). Swapping the stored value swaps the model for every place
 * an item is drawn - slots, hand, dropped, item frames - without copying the stack, and a ModifyVariable chains with
 * any other mod's hook on the same call instead of fighting it the way a Redirect would.
 */
@Mixin(ItemModelResolver.class)
public class PackDisablerItemModelMixin {

    @ModifyVariable(method = "appendItemLayers", at = @At("STORE"), ordinal = 0)
    private Identifier killer560smod$packDisablerModel(Identifier model, ItemStackRenderState state, ItemStack stack,
            ItemDisplayContext context, Level level, ItemOwner owner, int seed) {
        return PackDisabler.resolveModel(stack, model);
    }
}
