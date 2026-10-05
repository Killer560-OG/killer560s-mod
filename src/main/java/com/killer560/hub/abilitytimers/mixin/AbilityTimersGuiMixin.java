package com.killer560.hub.abilitytimers.mixin;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.hud.HudVisibility;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Ability Timers HUD list at the same point in the render pass every other always-on
 *  overlay in this mod uses (see {@code GifPlayerGuiMixin}/{@code PosmsgGuiMixin}). */
@Mixin(Gui.class)
public abstract class AbilityTimersGuiMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$drawAbilityTimers(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        // Chat stays see-through (killer560: "dont make it hide the gui if i open chat"); the HUD editor draws
        // the element itself, and the element's own render() no longer hides for either.
        if (HudVisibility.menuOpen()) {
            return;
        }
        // Indexed lookup, not a Stream: this runs every frame (2026-09-20, FPS pass).
        HudElement element = HudElementRegistry.byId("ability_timers");
        if (element == null) {
            return;
        }
        int[] pos = HudElementRegistry.resolvePosition(element);
        // Drawn at the element's scale (own x global HUD scale) like every other HUD element; this used to ignore
        // scale entirely, so scroll-resizing it in the HUD editor did nothing in game.
        float scale = HudElementRegistry.resolveScale(element);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            element.render(graphics, 0, 0);
        } finally {
            graphics.pose().popMatrix();
        }
    }
}
