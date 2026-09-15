package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MainMenuTitleLayout;
import com.mojang.realmsclient.gui.screens.RealmsNotificationsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The themed title layout removes the Realms button, but TitleScreen still renders its
 *  RealmsNotificationsScreen, whose news / invite / unseen-notification / trial icons are drawn at the
 *  hard-coded Realms slot (height/4+48+48, right of centre+100 - javap 26.1.2 extractIcons). Cancel that draw
 *  while the layout is applied so the icons don't float next to whatever button now sits there. The
 *  notifications screen itself is left alive (tick / added / removed untouched) so vanilla lifecycle and
 *  data-fetch subscriptions stay balanced; it has no widgets, so it never consumes clicks. */
@Mixin(RealmsNotificationsScreen.class)
public abstract class MainMenuRealmsNotificationsMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideRealmsIcons(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        try {
            if (MainMenuTitleLayout.isAppliedTo(Minecraft.getInstance().screen)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            MainMenuTheme.fail("realms notification icons", t);
        }
    }
}
