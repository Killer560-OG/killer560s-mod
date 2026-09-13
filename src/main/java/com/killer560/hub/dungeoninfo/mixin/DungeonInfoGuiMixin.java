package com.killer560.hub.dungeoninfo.mixin;

import com.killer560.hub.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Dungeon Info (secrets/time) HUD at the same point in the render pass every other
 *  always-on overlay in this mod uses. */
@Mixin(Gui.class)
public abstract class DungeonInfoGuiMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$drawDungeonInfo(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        HudElementRegistry.all().stream()
                .filter(e -> e.id().equals("dungeon_info"))
                .findFirst()
                .ifPresent(element -> {
                    int[] pos = HudElementRegistry.resolvePosition(element);
                    element.render(graphics, pos[0], pos[1]);
                });
    }
}
