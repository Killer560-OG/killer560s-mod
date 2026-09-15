package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuChrome;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ModMenu's mod list (optional dependency, hence @Pseudo + string target). ModMenu 18.0.0's ModListWidget
 * overrides extractListItems and draws the selected row through its own
 * {@code drawSelectionHighlight(G, int x, int y, int width, int height, int borderColor, int fillColor)}
 * (javap on modmenu-18.0.0.jar): fill(x, y-2, x+w, y+h+2, border) then fill(x+1, y-1, x+w-1, y+h+1, fill),
 * border = -1 when the list is focused. Replaced with the same box as MenuListMixin's selection. The list
 * background, separators and scrollbar already come from the vanilla AbstractSelectionList paths.
 */
@Pseudo
@Mixin(targets = "com.terraformersmc.modmenu.gui.widget.ModListWidget")
public abstract class MenuModMenuListMixin {

    @Inject(method = "drawSelectionHighlight(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIII)V",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void killer560smod$themedModSelection(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                                                  int borderColor, int fillColor, CallbackInfo ci) {
        if (!MainMenuTheme.activeOnMenus()) {
            return;
        }
        try {
            boolean focused = ((Object) this instanceof AbstractWidget widget) && widget.isFocused();
            MenuChrome.drawSelection(graphics, x, y - 2, width, height + 4, focused, false);
            ci.cancel();
        } catch (Throwable t) {
            MainMenuTheme.fail("modmenu selection", t);
        }
    }
}
