package com.killer560.hub.gui.mixin;

import com.killer560.hub.gui.BelowTooltip;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs {@link BelowTooltip} callbacks at the end of a container screen's own draw, before the deferred tooltip pass. */
@Mixin(AbstractContainerScreen.class)
public abstract class BelowTooltipContainerMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
    private void killer560smod$belowTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
                                            CallbackInfo ci) {
        BelowTooltip.fire((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY, partialTick);
    }
}
