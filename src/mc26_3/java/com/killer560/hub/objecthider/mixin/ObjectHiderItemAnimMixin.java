package com.killer560.hub.objecthider.mixin;

import com.killer560.hub.objecthider.ObjectHiderConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * QUOI {@code ItemAnimations.kt} "No eat animation" and "No shortbow swing" (both OFF by default,
 * {@link ObjectHiderConfig}). 26.3 copy; the 26.2 copy (src/mc26_2) explains the two hooks.
 *
 * <p>26.3 removed {@code ItemInHandRenderer}; both hooks are on {@code FirstPersonHandsAndItemsRenderer} (javap, 26.3):
 * {@code applyEatTransform(PoseStack, float, HumanoidArm, float, int)} (the stack and player became render-state
 * numbers, so the handler takes only the callback info, which Mixin accepts for any descriptor), and
 * {@code submitArmWithItem}, whose swing-progress {@code attack} is still the third float parameter
 * ({@code ordinal = 2}; it is what the method passes to {@code renderPlayerArm} as the swing).
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class ObjectHiderItemAnimMixin {

    @Inject(method = "applyEatTransform", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$objectHider$noEatAnimation(CallbackInfo ci) {
        if (ObjectHiderConfig.getInstance().isNoEatAnimation()) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "submitArmWithItem", at = @At("HEAD"), ordinal = 2, argsOnly = true, require = 0)
    private float killer560smod$objectHider$noShortbowSwing(float attack) {
        if (!ObjectHiderConfig.getInstance().isNoShortbowSwing()) {
            return attack;
        }
        Player player = Minecraft.getInstance().player;
        if (player != null && (com.killer560.hub.trajectories.Shortbows.isShortbowId(player.getMainHandItem())
                || com.killer560.hub.trajectories.Shortbows.isShortbowId(player.getOffhandItem()))) {
            return 0f;
        }
        return attack;
    }
}
