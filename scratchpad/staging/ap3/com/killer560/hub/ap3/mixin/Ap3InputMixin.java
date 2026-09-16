package com.killer560.hub.ap3.mixin;

import com.killer560.hub.ap3.Ap3Executor;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AP3 movement - the analog {@code moveVector} drive. Injected at the TAIL of {@code KeyboardInput#tick}, after
 * vanilla has built {@code keyPresses} from the real keys and derived
 * {@code moveVector = new Vec2(calculateImpulse(left, right), calculateImpulse(forward, backward)).normalized()}
 * (javap-verified on the 26.1.2 jar). AP3 replaces both: the {@code Input} record with the nearest 8-way keys for
 * the direction it moves in, and the vector with its own analog one - NOT re-normalised, because its length is the
 * speed ({@code Ap3Executor#writeMove}: 1/0.98 for the real W+A speed, 1/d for the plain-W speed).
 * <p>
 * The physical keys are never touched, so the {@code keyPresses} read at the top are the player's own - that is how
 * "the player pressed WASD/space, stop immediately" is detected. Without this mixin (config not registered) the
 * executor falls back to holding the key mappings, 8-way only.
 * <p>
 * <b>Why a second mixin on the same method is safe:</b> {@code autoroutes/mixin/AutoRoutesInputMixin} injects here
 * too, but Auto Routes only ever runs in dungeon CLEAR (never boss) and AP3 only ever runs in the F7/M7 BOSS
 * (Phase 3). They are mutually exclusive by game state, so at most one of them rewrites the input in any tick and
 * neither can undo the other. Do not relax either gate without revisiting this.
 * <p>
 * Registration: {@code killer560smod-ap3.mixins.json} must be listed in {@code fabric.mod.json} - an unregistered
 * mixin config silently does nothing.
 */
@Mixin(KeyboardInput.class)
public abstract class Ap3InputMixin extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void killer560smod$ap3Input(CallbackInfo ci) {
        Ap3Executor.onMixinApplied();
        if (!Ap3Executor.isSessionActive()) {
            return;
        }
        Input keys = this.keyPresses;
        if (keys.forward() || keys.backward() || keys.left() || keys.right() || keys.jump()) {
            Ap3Executor.onUserMovementInput();
            return;
        }
        if (!Ap3Executor.isDriving()) {
            return;
        }
        this.keyPresses = Ap3Executor.drivenInput();
        this.moveVector = new Vec2(Ap3Executor.moveVectorX(), Ap3Executor.moveVectorY());
    }
}
