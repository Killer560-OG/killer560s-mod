package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuWidgets;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The two header-separator strips TabNavigationBar draws left of the first tab and right of the last tab become a
 * single border-coloured line that lines up with the themed unselected tabs' bottom edge (MenuTabButtonMixin), so the
 * tab row reads as one continuous themed strip with the selected tab "open" into the content.
 * <p>
 * 26.1.2 (javap): {@code TabNavigationBar.extractRenderState(GuiGraphicsExtractor,int,int,float)} has exactly two
 * {@code GuiGraphicsExtractor.blit(RenderPipeline,Identifier,int,int,float,float,int,int,int,int)} calls, both with
 * Screen.HEADER_SEPARATOR at y = layoutBottom - 2, height 2 (texture 32x2); the 24px-tall TabButtons (same height as
 * the layout) are drawn after them. Kept in its own class so it can be dropped independently if separator theming
 * lands elsewhere.
 */
@Mixin(TabNavigationBar.class)
public abstract class MenuTabNavigationBarMixin {

    @Redirect(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"),
            require = 0)
    private void killer560smod$themedTabSeparator(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier texture,
                                                  int x, int y, float u, float v, int width, int height,
                                                  int textureWidth, int textureHeight) {
        if (MenuWidgets.active()) {
            try {
                MenuWidgets.drawTabSeparator(graphics, x, y, width, height);
                return;
            } catch (Throwable t) {
                MainMenuTheme.fail("tab separator", t);
            }
        }
        graphics.blit(pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }
}
