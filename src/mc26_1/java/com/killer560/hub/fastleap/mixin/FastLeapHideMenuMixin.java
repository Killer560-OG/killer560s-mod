package com.killer560.hub.fastleap.mixin;

import com.killer560.hub.fastleap.LeapManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fast/Auto Leap: never show the Spirit Leap menu the leap itself opened (see
 *  {@link LeapManager#interceptLeapScreen}). Cancelled at HEAD, before {@code Screen.removed}/{@code releaseMouse}/
 *  {@code KeyMapping.releaseAll}/{@code Screen.init}, so there is no cursor flicker and the HUD stays up. Target verified
 *  with javap on the 26.1.2 jar: {@code public void setScreen(net.minecraft.client.gui.screens.Screen)}, called by
 *  {@code MenuScreens.ScreenConstructor.fromPacket} right after it assigns {@code player.containerMenu}.
 *  <p>
 *  <p><b>This mixin has one copy per Minecraft version</b>, in {@code src/mc26_1/java} and
 *  {@code src/mc26_2/java}, because the class it targets is not the same class on both - and a
 *  {@code @Mixin} target is an annotation constant, so it cannot come from the {@code compat} facade the
 *  rest of the port uses. Only one is ever compiled. <b>A change to one belongs in the other</b>, exactly as
 *  for {@code compat/McCompat}: the mixin configs use {@code defaultRequire: 0}, so a copy left behind fails
 *  SILENTLY and the feature simply stops running with nothing in the log.
 */
@Mixin(Minecraft.class)
public abstract class FastLeapHideMenuMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideFastLeapMenu(Screen screen, CallbackInfo ci) {
        if (screen == null) {
            return;
        }
        try {
            if (LeapManager.interceptLeapScreen((Minecraft) (Object) this, screen)) {
                ci.cancel();
            }
        } catch (RuntimeException ignored) {
            // never break screen changes - on any error the menu is simply shown as before
        }
    }
}
