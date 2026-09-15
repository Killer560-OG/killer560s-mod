package com.killer560.hub.dungeonalerts.mixin;

import com.killer560.hub.dungeonalerts.ClassColors;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Class Colors tab-list recolor. javap (26.1.2 merged jar): {@code public Component
 *  getNameForDisplay(PlayerInfo)}, called from {@code PlayerTabOverlay#extractRenderState}. {@code require = 0}. */
@Mixin(PlayerTabOverlay.class)
public abstract class DungeonAlertsTabOverlayMixin {

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$dungeonAlerts$classColor(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        Component original = cir.getReturnValue();
        Component recolored = ClassColors.recolorTabName(info, original);
        if (recolored != original) {
            cir.setReturnValue(recolored);
        }
    }
}
