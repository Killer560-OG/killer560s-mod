package com.killer560.hub.itemrarity.mixin;

import com.killer560.hub.itemrarity.ItemRarityFeature;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hotbar (and offhand) rarity backgrounds: HEAD of {@code Gui#extractSlot}, which runs after the hotbar
 *  sprite and before the item (including its pickup "pop" scale) is drawn - same hook NoammAddons'
 *  {@code MixinGui#onRenderHotbarSlot} uses on 26.1.2.
 *  <p>
 *  Verified with javap against minecraft-merged-043a8b3edf-26.1.2.jar: {@code private void
 *  extractSlot(GuiGraphicsExtractor, int, int, DeltaTracker, Player, ItemStack, int)}. The empty-stack early
 *  return is AFTER HEAD, so {@link ItemRarityFeature#drawBackground} checks {@code isEmpty()} itself.
 *  {@code require = 0} so a mismatch just disables it instead of crashing. */
@Mixin(Gui.class)
public abstract class ItemRarityHotbarMixin {

    @Inject(
            method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/client/DeltaTracker;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;I)V",
            at = @At("HEAD"),
            require = 0
    )
    private void killer560smod$drawHotbarRarityBackground(GuiGraphicsExtractor graphics, int x, int y, DeltaTracker deltaTracker,
                                                          Player player, ItemStack stack, int seed, CallbackInfo ci) {
        try {
            ItemRarityFeature.drawBackground(graphics, stack, x, y, true);
        } catch (Throwable ignored) {
            // Visual-only; never let a parsing edge case break HUD rendering.
        }
    }
}
