package com.killer560.hub.util;

import com.killer560.hub.armourdye.mixin.CustomDataTagAccessor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Read access to a stack's {@code CUSTOM_DATA} tag (Hypixel's ExtraAttributes) without the deep copy.
 *
 * <p>{@code CustomData#copyTag()} is the only public reader and copies the whole tree - enchantments, gems, runes and
 * all - and the Skyblock id reads that use it run for the held item every tick, for every slot of a menu every frame
 * (Item Protect) and for every arm render (no-shortbow-swing). They only ever read one or two keys, so they read the
 * live tag through Armour Recolour's accessor instead (FPS sweep, 2026-10-07).
 *
 * <p><b>The returned tag is the stack's own NBT. Never write to it.</b> A caller that needs to modify or keep the tag
 * must still use {@code copyTag()}.
 */
public final class ItemNbt {

    private ItemNbt() {
    }

    /** The live CUSTOM_DATA tag of {@code stack} (read only), or null for an empty stack or one without custom data. */
    public static CompoundTag view(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : ((CustomDataTagAccessor) (Object) data).killer560smod$getTag();
    }
}
