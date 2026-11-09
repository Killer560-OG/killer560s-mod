package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MainMenuThemeConfig;
import com.killer560.hub.compat.McCompat;
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
import net.minecraft.core.Holder;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.Dialogs;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

    @Unique
    private Button killer560smod$pendingMods;

    @Shadow
    abstract Optional<? extends Holder<Dialog>> getCustomAdditions();

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
        killer560smod$pendingMods = null;
        LayoutElement out = hide ? element : original.call(helper, element, span, settings);
        try {
            if (!FabricLoader.getInstance().isModLoaded("modmenu") || hide && holdsModMenu(element)) {
                Screen self = (Screen) (Object) this;
                Optional<? extends Holder<Dialog>> extra = getCustomAdditions();
                boolean links = extra.isPresent() && extra.get().is(Dialogs.SERVER_LINKS);
                Button mods = Button.builder(Component.literal("Mods"),
                        b -> McCompat.setScreen(Minecraft.getInstance(), new ModsScreen(self))).width(links ? 98 : 204).build();
                if (links) {
                    killer560smod$pendingMods = mods;
                } else {
                    helper.addChild(mods, 2);
                }
            }
        } catch (Throwable t) {
            MainMenuTheme.fail("pause mods button", t);
        }
        return out;
    }

    /** Vanilla adds Server Links in addCustomDialogButtons, one row below: Mods (half width) goes beside it. */
    @WrapOperation(method = "addCustomDialogButtons", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;I)Lnet/minecraft/client/gui/layouts/LayoutElement;"),
            require = 0)
    private LayoutElement killer560smod$modsBesideLinks(GridLayout.RowHelper helper, LayoutElement element, int span,
                                                        Operation<LayoutElement> original) {
        Button mods = killer560smod$pendingMods;
        killer560smod$pendingMods = null;
        if (mods == null) {
            return original.call(helper, element, span);
        }
        if (element instanceof Button links) {
            links.setWidth(98);
            original.call(helper, mods, 1);
            return original.call(helper, links, 1);
        }
        mods.setWidth(204);
        original.call(helper, mods, 2);
        return original.call(helper, element, span);
    }

    private static boolean holdsModMenu(LayoutElement row) {
        boolean[] found = {false};
        row.visitWidgets(w -> found[0] |= w.getClass().getName().startsWith("com.terraformersmc.modmenu."));
        return found[0];
    }
}
