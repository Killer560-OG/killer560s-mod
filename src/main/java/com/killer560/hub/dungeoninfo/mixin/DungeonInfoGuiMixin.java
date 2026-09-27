package com.killer560.hub.dungeoninfo.mixin;

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

/** Draws the Secrets HUD ({@code DungeonInfoFeature.SecretsHudElement}) at the same point in the render
 *  pass every other always-on overlay in this mod uses. Used to also draw a Time HUD element here (split
 *  out 2026-09-21 from one combined "Dungeon Info" element); the Time HUD was removed 2026-09-27
 *  (killer560: "remove the time hud those are things that should be in the splits section"). */
@Mixin(Gui.class)
public abstract class DungeonInfoGuiMixin {

    private static final String[] ELEMENT_IDS = {"dungeon_info"};

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$drawDungeonInfo(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        // Chat stays see-through (killer560: "dont make it hide the gui if i open chat"); the HUD editor draws
        // the element itself, and the element's own render() no longer hides for either.
        if (HudVisibility.menuOpen()) {
            return;
        }
        // Indexed lookup, not a Stream: this runs every frame (2026-09-20, FPS pass).
        for (String id : ELEMENT_IDS) {
            HudElement element = HudElementRegistry.byId(id);
            if (element == null) {
                continue;
            }
            int[] pos = HudElementRegistry.resolvePosition(element);
            element.render(graphics, pos[0], pos[1]);
        }
    }
}
