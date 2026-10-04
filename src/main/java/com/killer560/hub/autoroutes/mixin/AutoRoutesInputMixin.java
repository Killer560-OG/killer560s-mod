package com.killer560.hub.autoroutes.mixin;

import com.killer560.hub.autoroutes.RouteExecutor;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Auto Routes playback movement - the exact technique of {@code pathfinding/mixin/PathWalkInputMixin} and the
 * Interactive Map's forced sneak: rewrite the {@code Input} record {@code KeyboardInput#tick} just built, plus the
 * {@code moveVector} it derived from the keys before this injection runs (javap-verified on the real 26.1.2 jar:
 * {@code moveVector = new Vec2(calculateImpulse(left, right), calculateImpulse(forward, backward)).normalized()}).
 * <p>
 * The physical keys are never touched, so the {@code keyPresses} read at the top are the player's own - that is how
 * "the player pressed WASD/space" is detected ({@code RouteExecutor#onInputTick} decides what that means). Without this mixin (config not registered) the
 * executor falls back to holding the key mappings, exactly like {@code AutoWalker}.
 * <p>
 * Registration: {@code killer560smod-autoroutes.mixins.json} must be listed in {@code fabric.mod.json} - an
 * unregistered mixin config silently does nothing.
 */
@Mixin(KeyboardInput.class)
public abstract class AutoRoutesInputMixin extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void killer560smod$autoRoutesInput(CallbackInfo ci) {
        RouteExecutor.onMixinApplied();
        if (!RouteExecutor.isSessionActive()) {
            return;
        }
        Input keys = this.keyPresses;
        boolean userKeys = keys.forward() || keys.backward() || keys.left() || keys.right() || keys.jump();
        // The executor decides: keys held from before it took over are overridden, a fresh press is a takeover
        // (see RouteExecutor#onInputTick). An override with nothing driven installs an all-off record (plus the
        // etherwarp's sneak), so held keys move nothing while a node's action runs.
        if (!RouteExecutor.onInputTick(userKeys)) {
            return;
        }
        this.keyPresses = RouteExecutor.drivenInput();
        float x = RouteExecutor.moveVectorX();
        float y = RouteExecutor.moveVectorY();
        Vec2 move = new Vec2(x, y);
        this.moveVector = (x != 0f || y != 0f) ? move.normalized() : move;
    }
}
