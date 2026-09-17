package com.killer560.hub.armourdye.mixin;

import com.killer560.hub.armourdye.ArmourDye;
import net.minecraft.core.component.DataComponentHolder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The skin and trim half of Armour Recolour. Same hook Skyblocker uses
 * ({@code mixins/DataComponentHolderMixin}, credited in {@link ArmourDye}): a client-side, read-only override of
 * three render-only data components on the client's own copy of a stack.
 * <ul>
 *   <li>{@code minecraft:equippable} - its {@code assetId} is the armour texture set {@code HumanoidArmorLayer}
 *       hands to {@code EquipmentLayerRenderer}, so rewriting it is the armour skin.</li>
 *   <li>{@code minecraft:trim} - read straight off the stack by {@code EquipmentLayerRenderer}, and by the item
 *       model in inventories, so one override covers both.</li>
 *   <li>{@code minecraft:item_model} - the inventory/hand icon, so a skinned piece doesn't look like two different
 *       items in the world and in the menu.</li>
 * </ul>
 *
 * <h2>Why this is safe to put on such a hot method</h2>
 * {@code DataComponentHolder#get} is called for every component of every stack, many times a frame. The guard order
 * here is deliberate: a reference compare against three component types first ({@link ArmourDye#handles}), then one
 * volatile boolean, then the {@code instanceof}, and only then anything that touches NBT. Anything that isn't one
 * of those three components leaves after three compares. {@code ItemStack} does not override {@code get}, so the
 * default method this injects into is the one it actually runs.
 * <p>
 * {@link ArmourDye} reads components back through {@code stack.getComponents().get(...)}, NOT through
 * {@code stack.get(...)} - a {@code DataComponentMap} is a {@code DataComponentGetter} and not a holder, so that
 * path skips this mixin. Reading {@code EQUIPPABLE} the ordinary way from inside the {@code EQUIPPABLE} branch
 * would recurse until the stack overflowed.
 */
@Mixin(DataComponentHolder.class)
public interface ArmourDyeComponentMixin {

    @Inject(method = "get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;",
            at = @At("RETURN"), cancellable = true)
    private void killer560smod$armourComponent(DataComponentType<?> type, CallbackInfoReturnable<Object> cir) {
        if (!ArmourDye.handles(type) || !ArmourDye.active()) {
            return;
        }
        if (!((Object) this instanceof ItemStack stack)) {
            return;
        }
        Object original = cir.getReturnValue();
        // ArmourDye swallows its own exceptions and latches the feature off after repeated failures.
        Object replaced = ArmourDye.component(stack, type, original);
        if (replaced != original) {
            cir.setReturnValue(replaced);
        }
    }
}
