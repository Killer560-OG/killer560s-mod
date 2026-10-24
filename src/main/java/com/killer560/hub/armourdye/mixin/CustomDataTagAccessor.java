package com.killer560.hub.armourdye.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code CustomData}'s only public reader is {@code copyTag()}, a full NBT deep copy. Armor Recolor reads
 * {@code ExtraAttributes.id} for every rendered armor piece every frame, so this read-only accessor mixin exposes
 * the existing backing tag instead (injects no new behavior at all - the lowest-risk real category of mixin this
 * mod uses, same as {@code AbstractContainerScreenAccessor}). Skyblocker does the identical thing with its own
 * {@code CustomDataAccessor}.
 * <p>
 * Treat the returned tag as read-only: it IS the stack's live NBT, not a copy.
 */
@Mixin(CustomData.class)
public interface CustomDataTagAccessor {

    @Accessor("tag")
    CompoundTag killer560smod$getTag();
}
