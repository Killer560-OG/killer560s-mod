package com.killer560.hub.inventorytheme.mixin;

import com.killer560.hub.inventorytheme.InventoryThemeFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Real bug found and fixed (2026-09-27) - see {@link InventoryThemeFeature}'s class doc for the full
 *  javap-verified chain: {@code RecipeBookComponent#init} sets its {@code visible} field from whatever
 *  the player's recipe book was left open/closed as last time, completely independent of this feature,
 *  and {@code AbstractRecipeBookScreen#extractRenderState} skips drawing the real inventory slots
 *  entirely while it's visible on a narrow window. Forcing {@code isVisible()} false here (rather than
 *  just cancelling {@code RecipeBookComponent#extractRenderState}'s own draw) fixes both problems at
 *  once: the panel never draws, AND the caller always takes the normal background+slots+labels path
 *  this feature's other mixins already handle correctly - so the real inventory always shows. Doubles as
 *  killer560's separate "remove ... the recipe book" ask; companion to
 *  {@link InventoryThemeFeature#register()}'s button hide, which alone would leave a dead button if the
 *  panel was already open from a past session. {@code require = 0} so a future MC update that
 *  restructures this just leaves the recipe book alone instead of crashing. */
@Mixin(RecipeBookComponent.class)
public abstract class InventoryThemeRecipeBookVisibilityMixin {

    @Inject(method = "isVisible", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$forceHidden(CallbackInfoReturnable<Boolean> cir) {
        Screen screen = Minecraft.getInstance().screen;
        if (InventoryThemeFeature.shouldTheme(screen)) {
            cir.setReturnValue(false);
        }
    }
}
