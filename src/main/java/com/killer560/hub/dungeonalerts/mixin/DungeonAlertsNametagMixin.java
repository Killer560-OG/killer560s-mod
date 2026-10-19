package com.killer560.hub.dungeonalerts.mixin;

import com.killer560.hub.dungeonalerts.ClassColors;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Class Colors draws its own class-coloured nametag for a dungeon teammate, so the vanilla tag is dropped for that
 * player (killer560, 2026-10-18: two tags showed). Same hook and descriptor as OwnNameTagF5Mixin:
 * {@code AvatarRenderer.shouldShowName(Avatar, double)} is {@code (Lnet/minecraft/world/entity/Avatar;D)Z} on both
 * 26.1.2 and 26.2. Only ever turns a tag off, and only while {@link ClassColors#replacesVanillaNametag} says so.
 */
@Mixin(AvatarRenderer.class)
public abstract class DungeonAlertsNametagMixin {

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("RETURN"), cancellable = true,
            require = 0)
    private void killer560smod$classColorHideVanillaTag(Avatar entity, double distanceSq,
                                                         CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && entity instanceof Player p && ClassColors.replacesVanillaNametag(p)) {
            cir.setReturnValue(false);
        }
    }
}
