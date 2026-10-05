package com.killer560.hub.petwheel.mixin;

import com.killer560.hub.petwheel.PetSummoner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pet Wheel's "Hide Pets Menu": never show the /pets screen a wheel summon opened (see
 *  {@link PetSummoner#interceptPetsScreen}). Cancelled at HEAD, before the mouse is released or the key mappings
 *  are dropped, so nothing flickers. Target: {@code public void setScreen(Screen)} on {@code Gui} (javap, 26.2: the screen stack moved off {@code Minecraft}), called by
 *  {@code MenuScreens$ScreenConstructor.fromPacket} right after it assigns {@code player.containerMenu}.
 *  <p>
 *  <b>This mixin has one copy per Minecraft version</b>, in {@code src/mc26_1/java} and {@code src/mc26_2/java},
 *  exactly like {@code fastleap.mixin.FastLeapHideMenuMixin}, because the target class differs. A change to one
 *  belongs in the other.
 */
@Mixin(Gui.class)
public abstract class PetWheelHideMenuMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hidePetsMenu(Screen screen, CallbackInfo ci) {
        if (screen == null) {
            return;
        }
        try {
            if (PetSummoner.interceptPetsScreen(Minecraft.getInstance(), screen)) {
                ci.cancel();
            }
        } catch (RuntimeException ignored) {
            // never break screen changes - on any error the menu is simply shown as before
        }
    }
}
