package com.killer560.hub.itemrarity.mixin;

import com.killer560.hub.itemrarity.ItemRarityFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the rarity background inside {@code AbstractContainerScreen#extractSlot}, right before vanilla's
 *  {@code slot.isFake()} check that picks between {@code graphics.fakeItem(...)} / {@code graphics.item(...)}
 *  - i.e. after the quick-craft highlight fill and the empty-slot icon, and immediately before the item
 *  itself, so the item always renders on top. Slot coordinates here are {@code slot.x}/{@code slot.y} in the
 *  screen's already-translated space (the same locals vanilla passes to {@code item(...)}).
 *  <p>
 *  Injecting there rather than at HEAD also means the existing HEAD cancels (Storage Overlay /
 *  Terminal Solver hiding a slot, and vanilla's own early return for the stack being split-dragged) skip
 *  this too - no orphan colored square where no item is drawn.
 *  <p>
 *  Verified with javap against minecraft-merged-043a8b3edf-26.1.2.jar: {@code protected void
 *  extractSlot(GuiGraphicsExtractor, Slot, int, int)} contains exactly one
 *  {@code invokevirtual net/minecraft/world/inventory/Slot.isFake:()Z} (offset 390). {@code require = 0} so
 *  a mismatch after an MC update just disables the feature instead of crashing. */
@Mixin(AbstractContainerScreen.class)
public abstract class ItemRaritySlotMixin {

    @Inject(
            method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;isFake()Z"),
            require = 0
    )
    private void killer560smod$drawRarityBackground(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        try {
            ItemRarityFeature.drawBackground(graphics, slot.getItem(), slot.x, slot.y, false);
        } catch (Throwable ignored) {
            // Visual-only; never let a parsing edge case break inventory rendering.
        }
    }
}
