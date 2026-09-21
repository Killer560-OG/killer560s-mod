package com.killer560.hub.inventorytheme.mixin;

import com.killer560.hub.inventorytheme.InventoryThemeFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces the vanilla chest-style background texture with the mod's own Amber-bordered panel -
 *  killer560's item 5.8 ("Custom inventory overlay in the mod's theme"). Same target/shape as the
 *  already-shipped {@code StorageOverlayBackgroundMixin} and {@code TerminalSolverBackgroundMixin}
 *  (both cancel this exact method on this exact class already) - {@code ContainerScreen.extractBackground}
 *  is the one place vanilla draws the stone chest texture for every generic chest-style menu, which is
 *  what every Hypixel Skyblock GUI (dungeon menus, NPC shops, etc.) actually is under the hood. Stands
 *  down on any screen Storage Overlay already owns - see {@link InventoryThemeFeature#isOwnedByStorageOverlay}. */
@Mixin(ContainerScreen.class)
public abstract class InventoryThemeContainerBackgroundMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themeContainerBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                          float partialTick, CallbackInfo ci) {
        ContainerScreen self = (ContainerScreen) (Object) this;
        if (!InventoryThemeFeature.shouldTheme(self) || InventoryThemeFeature.isOwnedByStorageOverlay(self.getTitle().getString())) {
            return;
        }
        InventoryThemeFeature.drawBackground(graphics, self);
        ci.cancel();
    }
}
