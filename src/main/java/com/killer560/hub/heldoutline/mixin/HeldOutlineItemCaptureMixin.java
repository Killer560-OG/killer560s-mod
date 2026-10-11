package com.killer560.hub.heldoutline.mixin;

import com.killer560.hub.heldoutline.HeldOutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records each first-person item the hand renderer draws ({@code renderItem}, identical in 26.1.2 and 26.2, javap).
 *  Read-only: the vanilla draw is untouched. */
@Mixin(ItemInHandRenderer.class)
public abstract class HeldOutlineItemCaptureMixin {

    @Inject(method = "renderItem", at = @At("HEAD"), require = 0)
    private void killer560smod$heldOutlineCapture(LivingEntity entity, ItemStack stack, ItemDisplayContext context,
                                                  PoseStack poseStack, SubmitNodeCollector collector, int light,
                                                  CallbackInfo ci) {
        HeldOutlineRenderer.capture(entity, stack, context, poseStack, light);
    }
}
