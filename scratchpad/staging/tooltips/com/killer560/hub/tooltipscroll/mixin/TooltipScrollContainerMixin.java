package com.killer560.hub.tooltipscroll.mixin;

import com.killer560.hub.tooltipscroll.TooltipScrollFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hover tracking + the mouse wheel for Scrollable Tooltips.
 *  <p>
 *  Verified with javap against minecraft-merged-043a8b3edf-26.1.2.jar:
 *  {@code protected void extractTooltip(GuiGraphicsExtractor, int, int)} (whose own body reads
 *  {@code this.hoveredSlot} and returns early when it is null or empty - injecting at HEAD therefore still
 *  sees the "hovering nothing" case, which is what resets the scroll) and
 *  {@code public boolean mouseScrolled(double, double, double, double)}, which vanilla implements directly
 *  (it offers bundle slot actions and otherwise returns false) rather than inheriting.
 *  <p>
 *  Every Hypixel Skyblock menu is an {@link AbstractContainerScreen}, so this covers all real item lore.
 *  {@code require = 0} on both so a signature change disables the feature instead of crashing, and both
 *  bodies swallow throwables - a scroll handler must never take down the screen. */
@Mixin(AbstractContainerScreen.class)
public abstract class TooltipScrollContainerMixin {

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "extractTooltip(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V", at = @At("HEAD"), require = 0)
    private void killer560smod$trackHoveredItem(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        try {
            Slot slot = this.hoveredSlot;
            TooltipScrollFeature.setHovered(slot == null || !slot.hasItem() ? null : slot.getItem());
        } catch (Throwable ignored) {
            // Tracking only; a failure here just means the scroll offset isn't reset this frame.
        }
    }

    @Inject(method = "mouseScrolled(DDDD)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$scrollTooltip(double mouseX, double mouseY, double scrollX, double scrollY,
                                             CallbackInfoReturnable<Boolean> cir) {
        try {
            if (TooltipScrollFeature.onMouseScrolled(scrollY)) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {
            // Falls through to vanilla scroll handling.
        }
    }
}
