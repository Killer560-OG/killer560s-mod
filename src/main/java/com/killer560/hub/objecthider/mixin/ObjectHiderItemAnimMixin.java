package com.killer560.hub.objecthider.mixin;

import com.killer560.hub.objecthider.ObjectHiderConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * QUOI {@code ItemAnimations.kt} "No eat animation" and "No shortbow swing" (both OFF by default,
 * {@link ObjectHiderConfig}). This mod already ports "No re-equip reset" / "No swing animation" /
 * "No hand sway" onto this exact class in {@code helditem.mixin.HeldItemInHandRendererMixin}; these two are
 * the remaining {@code ItemAnimations.kt} entries that file doesn't cover, added here instead of touching
 * that (differently-owned) file - Mixin allows multiple {@code @Mixin(ItemInHandRenderer.class)} classes to
 * coexist as long as they don't inject the same instruction twice, which these don't.
 * <p>
 * "No eat animation" cancels {@code applyEatTransform} outright (QUOI: {@code ItemInHandRendererMixin.java:164}).
 * "No shortbow swing" is QUOI's swing suppression scoped specifically to the shortbow
 * ({@code ItemAnimations.kt:35}); reached here the same way {@code HeldItemInHandRendererMixin.killer560smod$
 * heldItemNoSwing} already reaches the same argument - a single-parameter {@code @ModifyVariable} on
 * {@code renderArmWithItem}'s {@code attack} (swing-progress) float, {@code ordinal = 2} among that method's
 * float parameters (frameInterp, xRot, attack). That form has no access to which hand/stack is being drawn,
 * so the check reads the local player's current hands directly instead - same {@code CUSTOM_DATA} "id" read
 * {@code ObjectHiderFeature.skyblockId} already uses for dungeon items - and forces {@code 0} whenever either
 * hand holds a Skyblock shortbow (renderArmWithItem runs once per hand per frame, so this over-suppresses the
 * off-hand's swing on the rare dual-wield case, an accepted approximation given no per-hand context is
 * available at this injection shape). "No term swing" ({@code ItemAnimations.kt:34}) is NOT implemented - this
 * codebase has no existing signal for "a Skyblock terminal screen is open" to scope it to, and guessing one
 * risks suppressing swing animation everywhere a container is open instead of just terminals; see staging
 * notes.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ObjectHiderItemAnimMixin {

    @Inject(method = "applyEatTransform", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$objectHider$noEatAnimation(PoseStack poseStack, float partialTick, HumanoidArm arm,
                                                           ItemStack stack, Player player, CallbackInfo ci) {
        if (ObjectHiderConfig.getInstance().isNoEatAnimation()) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "renderArmWithItem", at = @At("HEAD"), ordinal = 2, argsOnly = true, require = 0)
    private float killer560smod$objectHider$noShortbowSwing(float attack) {
        if (!ObjectHiderConfig.getInstance().isNoShortbowSwing()) {
            return attack;
        }
        Player player = Minecraft.getInstance().player;
        if (player != null && (isShortbow(player.getMainHandItem()) || isShortbow(player.getOffhandItem()))) {
            return 0f;
        }
        return attack;
    }

    /** Same technique as {@code ObjectHiderFeature.skyblockId} - the item's Skyblock id from CUSTOM_DATA. */
    private static boolean isShortbow(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        CompoundTag tag = data.copyTag();
        String id = tag.contains("id") ? tag.getStringOr("id", null) : null;
        return id != null && id.contains("SHORTBOW");
    }
}
