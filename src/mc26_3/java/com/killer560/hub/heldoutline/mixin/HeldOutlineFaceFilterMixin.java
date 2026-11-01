package com.killer560.hub.heldoutline.mixin;

import com.killer560.hub.heldoutline.HeldOutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While Held Item Outline draws a face mask, item submits keep only the near or the far face
 *  ({@link HeldOutlineRenderer#filterSubmit}). 26.3 copy: {@code submitItem(PoseStack, ItemDisplayContext, int, int, int,
 *  int[], ItemQuads, FoilType)} (javap), where 26.2 took a {@code List<BakedQuad>}. {@code ItemQuads} is a record of
 *  {@code all}, {@code solid} and {@code translucent} lists; the filter reads {@code all()} and resubmits through
 *  {@code ItemQuads.split}, vanilla's own constructor for it. One static int read when the outline is not drawing. */
@Mixin(SubmitNodeStorage.class)
public abstract class HeldOutlineFaceFilterMixin {

    @Inject(method = "submitItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$heldOutlineFaces(PoseStack pose, ItemDisplayContext context, int light, int overlay,
                                                int outline, int[] tints, ItemQuads quads,
                                                ItemStackRenderState.FoilType foil, CallbackInfo ci) {
        if (!HeldOutlineRenderer.isFiltering() || quads == null) {
            return; // every item submit in the game comes here: no allocation unless a face mask is being drawn
        }
        SubmitNodeStorage self = (SubmitNodeStorage) (Object) this;
        if (HeldOutlineRenderer.filterSubmit(pose, quads.all(),
                only -> self.submitItem(pose, context, light, overlay, outline, tints, ItemQuads.split(only), foil))) {
            ci.cancel();
        }
    }
}
