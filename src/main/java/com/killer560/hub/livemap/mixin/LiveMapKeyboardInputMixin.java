package com.killer560.hub.livemap.mixin;

import com.killer560.hub.livemap.autoclear.ClearExecutor;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** QUOI ClearExecutor {@code KeyEvent.Input}: holds sneak while an etherwarp hop is current or next. */
@Mixin(KeyboardInput.class)
public abstract class LiveMapKeyboardInputMixin {

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void killer560smod$liveMapForceSneak(CallbackInfo ci) {
        if (!ClearExecutor.shouldForceSneak()) {
            return;
        }
        ClientInput self = (ClientInput) (Object) this;
        Input k = self.keyPresses;
        if (!k.shift()) {
            self.keyPresses = new Input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), true, k.sprint());
        }
    }
}
