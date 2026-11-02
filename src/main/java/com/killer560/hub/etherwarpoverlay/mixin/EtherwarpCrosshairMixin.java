package com.killer560.hub.etherwarpoverlay.mixin;

import com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves the crosshair onto the spot the server's etherwarp ray meets while the Etherwarp Overlay shows (killer560,
 * 2026-11-01, p3sim: "adjust my crosshair to the spot that the server recognizes"). The target is
 * {@code Hud.extractCrosshair(GuiGraphicsExtractor, DeltaTracker)}, private, the same on 26.3 and 26.2 (javap 2026-11-01):
 * the whole crosshair (and its attack indicator) is drawn between HEAD and RETURN, so a translate pushed at HEAD and popped
 * at every RETURN moves exactly that and nothing else. Nothing here touches the player's look or any packet.
 */
@Mixin(Hud.class)
public abstract class EtherwarpCrosshairMixin {

    @Unique
    private boolean killer560smod$crosshairMoved;

    @Inject(method = "extractCrosshair(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("HEAD"))
    private void killer560smod$moveCrosshair(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        killer560smod$crosshairMoved = false;
        float[] off;
        try {
            off = EtherwarpOverlayFeature.crosshairOffset(graphics.guiWidth(), graphics.guiHeight());
        } catch (RuntimeException e) {
            off = null;
        }
        if (off == null) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(off[0], off[1]);
        killer560smod$crosshairMoved = true;
    }

    @Inject(method = "extractCrosshair(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("RETURN"))
    private void killer560smod$restoreCrosshair(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        if (killer560smod$crosshairMoved) {
            killer560smod$crosshairMoved = false;
            graphics.pose().popMatrix();
        }
    }
}
