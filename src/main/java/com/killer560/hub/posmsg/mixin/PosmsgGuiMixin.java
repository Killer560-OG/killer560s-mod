package com.killer560.hub.posmsg.mixin;

import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.posmsg.PosmsgHudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Posmsg Waypoints HUD list at the same point in the HUD render pass every other always-on
 *  overlay in this mod uses (see {@code GifPlayerGuiMixin}/{@code DvdGuiMixin} for the identical
 *  pattern) - looked up by id from {@link HudElementRegistry} rather than holding its own reference,
 *  so the HUD editor's drag-to-move stays the single source of truth for its position. */
@Mixin(Gui.class)
public abstract class PosmsgGuiMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$drawPosmsgWaypoints(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        HudElementRegistry.all().stream()
                .filter(e -> e.id().equals("posmsg_waypoints"))
                .findFirst()
                .ifPresent(element -> {
                    int[] pos = HudElementRegistry.resolvePosition(element);
                    if (((PosmsgHudElement) element).isVisible()) {
                        element.render(graphics, pos[0], pos[1]);
                    }
                });
    }
}
