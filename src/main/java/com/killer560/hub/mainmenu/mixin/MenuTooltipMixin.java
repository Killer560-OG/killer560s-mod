package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuWidgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Widget tooltips on themed menus (option descriptions, button hints, other mods' config tooltips): the purple-framed
 * {@code tooltip/background} + {@code tooltip/frame} sprites become a warm near-black box with a 1px orange border.
 * <p>
 * 26.1.2 (javap): every tooltip background goes through the static
 * {@code TooltipRenderUtil.extractTooltipBackground(GuiGraphicsExtractor,int x,int y,int w,int h,Identifier style)}
 * (only caller in the client: GuiGraphicsExtractor's tooltip extraction), which blits both sprites at
 * (x-12, y-12, w+24, h+24).
 * <p>
 * Not themed: tooltips with a custom {@code style} (item tooltip_style component), anything while no screen is open
 * (HUD - activeOnMenus() is false), container screens (excluded by activeOnMenus()), and the chat screen (chat hover
 * events show server item/entity tooltips) - see {@link MenuWidgets#tooltipsActive()}.
 */
@Mixin(TooltipRenderUtil.class)
public abstract class MenuTooltipMixin {

    @Inject(method = "extractTooltipBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIILnet/minecraft/resources/Identifier;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void killer560smod$themedTooltip(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                                                    Identifier style, CallbackInfo ci) {
        if (style != null || !MenuWidgets.tooltipsActive()) {
            return;
        }
        try {
            MenuWidgets.drawTooltip(graphics, x, y, width, height);
            ci.cancel();
        } catch (Throwable t) {
            MainMenuTheme.fail("tooltip", t);
        }
    }
}
