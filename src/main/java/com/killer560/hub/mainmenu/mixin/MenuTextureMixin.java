package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuChrome;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Header/footer separators and the Create World / Statistics tab-header band, hooked at the one draw call
 * they all share instead of at each screen: {@code GuiGraphicsExtractor.blit(RenderPipeline, Identifier,
 * int x, int y, float u, float v, int w, int h, int texW, int texH)}. Every 26.1.2 use of
 * Screen.HEADER_SEPARATOR / FOOTER_SEPARATOR / INWORLD_* (AbstractSelectionList.extractListSeparators,
 * TabNavigationBar.extractRenderState, CreateWorldScreen / StatsScreen.extractRenderState) and of
 * CreateWorldScreen.TAB_HEADER_BACKGROUND (CreateWorldScreen / StatsScreen.extractMenuBackground) goes
 * through this exact overload (javap), and mods referencing Screen's public constants hit it too.
 * <p>
 * Per-call cost for unrelated blits: four reference compares and one string-length compare.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class MenuTextureMixin {

    @Inject(method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themedSeparators(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v,
                                                int width, int height, int textureWidth, int textureHeight, CallbackInfo ci) {
        int kind = MenuChrome.classifyTexture(texture);
        if (kind == MenuChrome.KIND_NONE || !MainMenuTheme.activeOnMenus()) {
            return;
        }
        try {
            boolean inWorld = Minecraft.getInstance().level != null;
            MenuChrome.drawTextureReplacement((GuiGraphicsExtractor) (Object) this, kind, x, y, width, height, inWorld);
            ci.cancel();
        } catch (Throwable t) {
            MainMenuTheme.fail("menu separator", t);
        }
    }
}
