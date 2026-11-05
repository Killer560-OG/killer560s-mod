package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MainMenuThemeConfig;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.modlist.ModListConfig;
import com.killer560.hub.modlist.ModsScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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
 * <p>
 * The Mods row (killer560, 2026-11-04: "it should also be in your normal esc button press ingame to see that menu. there
 * should be a mods button") goes in at the same call, after the icon row or in its place: a full-width
 * {@code addChild(LayoutElement, int)} row like Mod Menu's own. With Mod Menu installed its own Mods button is kept and
 * ours is added only when the hidden icon row took Mod Menu's icon with it, so there is never a second one.
 */
@Mixin(PauseScreen.class)
public abstract class PauseMenuIconRowMixin {

    @WrapOperation(method = "createPauseMenu()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;ILnet/minecraft/client/gui/layouts/LayoutSettings;)Lnet/minecraft/client/gui/layouts/LayoutElement;"),
            require = 0)
    private LayoutElement killer560smod$hideIconRow(GridLayout.RowHelper helper, LayoutElement element, int span,
                                                    LayoutSettings settings, Operation<LayoutElement> original) {
        if (!(element instanceof LinearLayout)) {
            return original.call(helper, element, span, settings);
        }
        boolean hide = false;
        try {
            hide = MainMenuThemeConfig.getInstance().isHidePauseIcons();
        } catch (Throwable t) {
            MainMenuTheme.fail("pause icons", t);
        }
        LayoutElement out = hide ? element : original.call(helper, element, span, settings);
        try {
            if (ModListConfig.getInstance().isPauseButton() && (!FabricLoader.getInstance().isModLoaded("modmenu")
                    || hide && holdsModMenu(element))) {
                Screen self = (Screen) (Object) this;
                helper.addChild(Button.builder(Component.literal("Mods"),
                        b -> McCompat.setScreen(Minecraft.getInstance(), new ModsScreen(self))).width(204).build(), 2);
            }
        } catch (Throwable t) {
            MainMenuTheme.fail("pause mods button", t);
        }
        return out;
    }

    private static boolean holdsModMenu(LayoutElement row) {
        boolean[] found = {false};
        row.visitWidgets(w -> found[0] |= w.getClass().getName().startsWith("com.terraformersmc.modmenu."));
        return found[0];
    }
}
