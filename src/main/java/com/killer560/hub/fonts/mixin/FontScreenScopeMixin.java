package com.killer560.hub.fonts.mixin;

import com.killer560.hub.fonts.CustomFonts;
import com.killer560.hub.fonts.FontScope;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mod Menu Only: the menu font applies while one of the mod's screens lays itself out ({@code init(int,int)},
 * {@code resize}) or draws ({@code extractRenderStateWithTooltipAndSubtitles}, the final method every screen is drawn
 * through, tooltips included). The HUD and chat behind it are drawn outside these calls and keep Minecraft's font.
 * Names and descriptors identical in 26.1.2 and 26.2 (javap).
 */
@Mixin(Screen.class)
public abstract class FontScreenScopeMixin {

    @Unique
    private int killer560smod$fontDepth;

    @Unique
    private void killer560smod$enter() {
        if (CustomFonts.active() && CustomFonts.isModScreen((Screen) (Object) this)) {
            killer560smod$fontDepth++;
            FontScope.enterMenu();
        }
    }

    @Unique
    private void killer560smod$exit() {
        if (killer560smod$fontDepth > 0) {
            killer560smod$fontDepth--;
            FontScope.exitMenu();
        }
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("HEAD"), require = 0)
    private void killer560smod$drawStart(CallbackInfo ci) {
        killer560smod$enter();
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("RETURN"), require = 0)
    private void killer560smod$drawEnd(CallbackInfo ci) {
        killer560smod$exit();
    }

    @Inject(method = "init(II)V", at = @At("HEAD"), require = 0)
    private void killer560smod$initStart(CallbackInfo ci) {
        killer560smod$enter();
    }

    @Inject(method = "init(II)V", at = @At("RETURN"), require = 0)
    private void killer560smod$initEnd(CallbackInfo ci) {
        killer560smod$exit();
    }

    @Inject(method = "resize(II)V", at = @At("HEAD"), require = 0)
    private void killer560smod$resizeStart(CallbackInfo ci) {
        killer560smod$enter();
    }

    @Inject(method = "resize(II)V", at = @At("RETURN"), require = 0)
    private void killer560smod$resizeEnd(CallbackInfo ci) {
        killer560smod$exit();
    }
}
