package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MainMenuThemeConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hide Pause Menu Icons (killer560, 2026-11-01: "the issue with the menu is those little buttons. I dont think they are
 * needed"). javap 26.3 and 26.2: {@code PauseScreen.createPauseMenu} builds the icon row (Report Bugs, Give Feedback, the
 * FriendsButton, Player Reporting) as a {@code LinearLayout.horizontal()} and adds it to the grid with
 * {@code RowHelper.addChild(LayoutElement, int, LayoutSettings)} spanning both columns. The only other call of that
 * descriptor there adds Back to Game, a Button. Not adding the row leaves no hole: the grid's next element starts the next
 * row, so Options (or Hypixel's Server Links, added through {@code addChild(LayoutElement, int)}) follows Statistics with
 * the usual 4 px padding, and the row's buttons never become screen widgets ({@code visitWidgets} walks the grid).
 */
@Mixin(PauseScreen.class)
public abstract class PauseMenuIconRowMixin {

    @WrapOperation(method = "createPauseMenu()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;ILnet/minecraft/client/gui/layouts/LayoutSettings;)Lnet/minecraft/client/gui/layouts/LayoutElement;"),
            require = 0)
    private LayoutElement killer560smod$hideIconRow(GridLayout.RowHelper helper, LayoutElement element, int span,
                                                    LayoutSettings settings, Operation<LayoutElement> original) {
        try {
            if (element instanceof LinearLayout && MainMenuThemeConfig.getInstance().isHidePauseIcons()) {
                return element;
            }
        } catch (Throwable t) {
            MainMenuTheme.fail("pause icons", t);
        }
        return original.call(helper, element, span, settings);
    }
}
