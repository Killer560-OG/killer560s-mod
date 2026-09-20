package com.killer560.hub.objecthider.mixin;

import com.killer560.hub.objecthider.ObjectHiderConfig;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * QUOI {@code NameTags.kt} "Custom nametag" (OFF by default, {@link ObjectHiderConfig#isCancelVanillaNametags()})
 * - QUOI cancels the vanilla nametag so it can draw its own instead (mixins/LivingEntityRendererMixin.java:16-23,
 * {@code shouldShowName} HEAD cancellable). This port has no custom nametag renderer to replace it with, so
 * turning this on simply removes every living entity's floating nametag - same "hide" scope as everything
 * else in this pack. javap-verified {@code LivingEntityRenderer} declares two {@code shouldShowName}
 * overloads (the generic {@code (T, double)} and a bridge {@code (Entity, double)}); the descriptor below
 * pins the real one so a future overload change degrades to "hider off" ({@code require = 0}) instead of
 * silently hooking the bridge. This runs once per living entity per frame like
 * {@link ObjectHiderRenderMixin}, so the check is a single gated boolean read - no string work, no regex.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class ObjectHiderNametagMixin {

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("HEAD"),
            cancellable = true, require = 0)
    private void killer560smod$objectHider$shouldShowName(LivingEntity entity, double distance,
                                                           CallbackInfoReturnable<Boolean> cir) {
        if (ObjectHiderConfig.getInstance().isCancelVanillaNametags()) {
            cir.setReturnValue(false);
        }
    }
}
