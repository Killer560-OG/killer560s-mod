package com.killer560.hub.pathfinding.mixin;

import com.killer560.hub.pathfinding.AutoWalker;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Auto Walk Path's movement, same technique the Interactive Map already uses for forced sneak
 * ({@code livemap/mixin/LiveMapKeyboardInputMixin}): rewrite the {@code Input} record {@code KeyboardInput#tick} just
 * built. Both the record AND {@code moveVector} are replaced, because {@code tick} computes the move vector from the
 * keys before this injection runs (javap-verified on the real 26.1.2 jar: {@code tick()} builds {@code keyPresses}
 * from the key mappings, then {@code moveVector = new Vec2(left/right impulse, forward/backward impulse).normalized()}).
 * <p>
 * The physical keys are never touched. His own presses stop the run through {@code PathStopKeyMixin} (a fresh press,
 * since 2026-10-17); a key he was holding when it started is simply overridden here. The class extends
 * {@code ClientInput} purely so the protected {@code moveVector} field is accessible.
 */
@Mixin(KeyboardInput.class)
public abstract class PathWalkInputMixin extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void killer560smod$pathWalkInput(CallbackInfo ci) {
        AutoWalker.onMixinApplied();
        if (!AutoWalker.isSessionActive()) {
            return;
        }
        // His own presses stop it through PathStopKeyMixin (a fresh press only); a key held from before the start is
        // simply overridden here, as Auto Routes does.
        Input keys = this.keyPresses;
        if (!AutoWalker.isDriving()) {
            return;
        }
        boolean f = AutoWalker.wantForward();
        boolean b = AutoWalker.wantBack();
        boolean l = AutoWalker.wantLeft();
        boolean r = AutoWalker.wantRight();
        this.keyPresses = new Input(f, b, l, r, AutoWalker.wantJump(), keys.shift(), AutoWalker.wantSprint());
        // Exactly what vanilla's tick derives from those keys: each axis -1/0/1, then normalized.
        this.moveVector = new Vec2((l ? 1f : 0f) - (r ? 1f : 0f), (f ? 1f : 0f) - (b ? 1f : 0f)).normalized();
    }
}
