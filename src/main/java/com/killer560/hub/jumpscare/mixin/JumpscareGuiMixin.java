package com.killer560.hub.jumpscare.mixin;

import com.killer560.hub.jumpscare.JumpscareFeature;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the full-screen jumpscare image at the same point in the HUD render pass the other
 *  overlays use, so it always renders on top of everything else already drawn that frame. */
@Mixin(Gui.class)
public abstract class JumpscareGuiMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$drawJumpscare(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        JumpscareFeature.renderOverlay(graphics);
    }
}
