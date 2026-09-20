package com.killer560.hub.objecthider.mixin;

import com.killer560.hub.objecthider.ObjectHiderConfig;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** QUOI {@code Tweaks.kt} "Disable item cooldowns" (OFF by default, {@link ObjectHiderConfig}) - the grey
 *  cooldown sweep drawn over an item. QUOI's own hook is a config check inside {@code ItemCooldowns}
 *  ({@code mixins/ItemCooldownsMixin.java:22}); javap-verified {@code ItemCooldowns.getCooldownPercent
 *  (ItemStack, float)} - the value every cooldown-overlay render reads - still exists unchanged in the
 *  26.1.2 merged jar, so forcing it to {@code 0} here has the identical effect. Deliberately does NOT touch
 *  {@code isOnCooldown}, which is what actually gates using the item again - this only ever removes the
 *  visual sweep, never changes real cooldown behaviour or anything sent to the server. */
@Mixin(ItemCooldowns.class)
public abstract class ObjectHiderCooldownMixin {

    @Inject(method = "getCooldownPercent", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$objectHider$cooldownPercent(CallbackInfoReturnable<Float> cir) {
        if (ObjectHiderConfig.getInstance().isDisableItemCooldowns()) {
            cir.setReturnValue(0.0F);
        }
    }
}
