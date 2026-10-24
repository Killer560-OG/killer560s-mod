package com.killer560.hub.gui.mixin;

import com.killer560.hub.hud.HudTextCache;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every one of the mod's own screens (the Dungeon Sim menus, the editors, the color picker...) draws inside
 * {@link HudTextCache}'s scope, so its String lines keep their visual order between frames instead of an ICU Bidi per
 * string per frame (fps-gui-1015; the mod menu opens the scope itself too). The sequence is exactly what vanilla
 * computes. Vanilla's and other mods' screens are untouched. {@code extractRenderStateWithTooltipAndSubtitles} is the
 * final method every screen is drawn through, same descriptor in 26.1.2 and 26.2 (javap).
 */
@Mixin(Screen.class)
public abstract class ModScreenTextCacheMixin {

    @Unique
    private boolean killer560smod$textScope;

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("HEAD"), require = 0)
    private void killer560smod$textScopeBegin(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick,
                                              CallbackInfo ci) {
        killer560smod$textScope = getClass().getName().startsWith("com.killer560.hub.");
        if (killer560smod$textScope) {
            HudTextCache.begin();
        }
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("RETURN"), require = 0)
    private void killer560smod$textScopeEnd(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick,
                                            CallbackInfo ci) {
        if (killer560smod$textScope) {
            killer560smod$textScope = false;
            HudTextCache.end();
        }
    }
}
