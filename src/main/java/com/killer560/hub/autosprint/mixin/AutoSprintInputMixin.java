package com.killer560.hub.autosprint.mixin;

import com.killer560.hub.autosprint.AutoSprintFeature;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Auto Sprint: the sprint key reads as held while he walks forward himself (see {@link AutoSprintFeature}). Stands down
 *  on any tick the mod's own movers own the keys, so whichever order the TAIL hooks run in, theirs is what counts.
 *  Target checked with javap on 26.3 and 26.2: {@code KeyboardInput.tick()V}, {@code ClientInput.keyPresses}. */
@Mixin(KeyboardInput.class)
public abstract class AutoSprintInputMixin extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void killer560smod$autoSprint(CallbackInfo ci) {
        Input k = this.keyPresses;
        boolean sprint = AutoSprintFeature.sprintFor(k.forward(), k.shift(), k.sprint());
        if (sprint != k.sprint()) {
            this.keyPresses = new Input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), k.shift(), sprint);
        }
    }
}
