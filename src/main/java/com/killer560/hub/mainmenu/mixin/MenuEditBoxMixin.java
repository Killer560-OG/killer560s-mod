package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuWidgets;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Bordered text fields (server name/address, world name/seed, search boxes, other mods' config fields, and this
 * mod's own settings fields) on themed menus: the grey {@code widget/text_field(_highlighted)} 9-slice becomes a
 * dark field with a dim orange border, full orange while focused. Text, hint, suggestion, selection highlight and
 * cursor are left exactly as vanilla draws them (light text / grey hint on a dark field stays readable).
 * <p>
 * 26.1.2 (javap): {@code EditBox.extractWidgetRenderState(GuiGraphicsExtractor,int,int,float)} contains exactly one
 * {@code GuiGraphicsExtractor.blitSprite(RenderPipeline,Identifier,int,int,int,int)} call - the border sprite, only
 * reached when {@code isBordered()}. Unbordered boxes (chat input, social search) never hit it. No vanilla EditBox
 * subclass overrides extractWidgetRenderState.
 */
@Mixin(EditBox.class)
public abstract class MenuEditBoxMixin {

    @Redirect(method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"),
            require = 0)
    private void killer560smod$themedTextField(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                               int x, int y, int width, int height) {
        if (MenuWidgets.active()) {
            try {
                EditBox self = (EditBox) (Object) this;
                MenuWidgets.drawTextField(graphics, x, y, width, height, self.isActive(), self.isFocused());
                return;
            } catch (Throwable t) {
                MainMenuTheme.fail("text field", t);
            }
        }
        graphics.blitSprite(pipeline, sprite, x, y, width, height);
    }
}
