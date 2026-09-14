package com.killer560.hub.etherwarp.mixin;

import com.killer560.hub.etherwarp.EtherwarpHudElement;
import com.killer560.hub.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Real bug found and fixed (2026-09-14, pre-testing bug-review pass): this mixin never existed, so
 *  {@link EtherwarpHudElement} was registered ({@code "etherwarp_waypoints"}) but nothing ever called its
 *  {@code render}, except the HUD editor's generic per-element placeholder box - meaning the whole feature
 *  silently never drew anything during real gameplay, only ever looking correct while the HUD editor
 *  itself was open. Same real pattern every other always-on 2D overlay in this mod already uses (see
 *  {@code PosmsgGuiMixin} for the identical structure this was copied from). */
@Mixin(Gui.class)
public abstract class EtherwarpGuiMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$drawEtherwarpWaypoints(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        HudElementRegistry.all().stream()
                .filter(e -> e.id().equals("etherwarp_waypoints"))
                .findFirst()
                .ifPresent(element -> {
                    int[] pos = HudElementRegistry.resolvePosition(element);
                    if (((EtherwarpHudElement) element).isVisible()) {
                        element.render(graphics, pos[0], pos[1]);
                    }
                });
    }
}
