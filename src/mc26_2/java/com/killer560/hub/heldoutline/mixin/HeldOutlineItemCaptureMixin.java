package com.killer560.hub.heldoutline.mixin;

import com.killer560.hub.heldoutline.HeldOutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records each first-person item the hand renderer draws ({@code ItemInHandRenderer.renderItem}, javap 26.2), with a
 *  redraw through the same {@code renderItem}. Read-only: the vanilla draw is untouched. 26.2 copy; 26.3 has no
 *  ItemInHandRenderer (see that copy and {@link HeldOutlineItemStateMixin}). */
@Mixin(ItemInHandRenderer.class)
public abstract class HeldOutlineItemCaptureMixin {

    @Inject(method = "renderItem", at = @At("HEAD"), require = 0)
    private void killer560smod$heldOutlineCapture(LivingEntity entity, ItemStack stack, ItemDisplayContext context,
                                                  PoseStack poseStack, SubmitNodeCollector collector, int light,
                                                  CallbackInfo ci) {
        if (entity == null || stack == null || stack.isEmpty() || context == null || !context.firstPerson()
                || !HeldOutlineRenderer.wantsCapture()) {
            return;
        }
        boolean mainHand = !context.leftHand() == (entity.getMainArm() == HumanoidArm.RIGHT);
        ItemInHandRenderer self = (ItemInHandRenderer) (Object) this;
        HeldOutlineRenderer.capture(mainHand, poseStack,
                (ps, col) -> self.renderItem(entity, stack, context, ps, col, light));
    }
}
