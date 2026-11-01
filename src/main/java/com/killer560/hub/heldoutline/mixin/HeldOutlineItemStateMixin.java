package com.killer560.hub.heldoutline.mixin;

import com.killer560.hub.heldoutline.HeldOutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Held Item Outline, 26.3: records a first-person held item at {@code ItemStackRenderState.submit(PoseStack,
 * SubmitNodeCollector, int, int, int)} HEAD (same descriptor in 26.2 and 26.3, javap) while
 * {@code HeldOutlineItemCaptureMixin} has marked a {@code submitArmWithItem} as running, with a redraw that submits
 * this same render state again (vanilla reuses it, but it is not re-extracted before the outline draws later in the
 * same frame). On 26.2 the mark is never set, so this is one static int read and does nothing; 26.2 records at
 * {@code ItemInHandRenderer.renderItem} instead. Read-only: the vanilla draw is untouched.
 */
@Mixin(ItemStackRenderState.class)
public abstract class HeldOutlineItemStateMixin {

    @Inject(method = "submit", at = @At("HEAD"), require = 0)
    private void killer560smod$heldOutlineCaptureState(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                                       int overlay, int outline, CallbackInfo ci) {
        int hand = HeldOutlineRenderer.armHand();
        if (hand == 0 || !HeldOutlineRenderer.wantsCapture()) {
            return;
        }
        ItemStackRenderState self = (ItemStackRenderState) (Object) this;
        HeldOutlineRenderer.capture(hand == 1, poseStack, (ps, col) -> self.submit(ps, col, light, overlay, outline));
    }
}
