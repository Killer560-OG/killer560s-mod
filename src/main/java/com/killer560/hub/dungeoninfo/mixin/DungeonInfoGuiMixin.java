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

/** Draws the Secrets HUD and Time HUD ({@code DungeonInfoFeature.SecretsHudElement}/{@code TimeHudElement})
 *  at the same point in the render pass every other always-on overlay in this mod uses. Split into two
 *  elements 2026-09-21 (previously one combined "Dungeon Info" element) so each is separately movable and
 *  toggleable, per killer560's secrets/score/time HUD split - both still draw from this one mixin. */
@Mixin(Gui.class)
public abstract class DungeonInfoGuiMixin {

    private static final String[] ELEMENT_IDS = {"dungeon_info", "dungeon_time_hud"};

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
