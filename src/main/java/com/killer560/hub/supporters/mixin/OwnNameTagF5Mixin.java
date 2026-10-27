package com.killer560.hub.supporters.mixin;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.supporters.SupportersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * "My Name in F5" (Player Cosmetics, killer560 2026-10-11: "add a setting to see your own name in f5 mode"). Vanilla
 * never shows the camera entity's nametag: {@code LivingEntityRenderer.shouldShowName} returns false for
 * {@code entity == getCameraEntity()} (javap, 26.1.2), and {@code AvatarRenderer.shouldShowName(Avatar, double)} only
 * narrows that further. With the setting on, the local player's tag is shown in both third-person views, never in first
 * person and never with F1. The tag's TEXT is the normal path ({@code getNameTag}, then Name Changer's Font-level
 * replace), so a custom name, color or fade shows exactly as everyone else's tag would. Descriptor
 * {@code (Lnet/minecraft/world/entity/Avatar;D)Z} on both 26.1.2 and 26.2 (javap). {@code require = 0}.
 */
@Mixin(AvatarRenderer.class)
public abstract class OwnNameTagF5Mixin {

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("RETURN"), cancellable = true,
            require = 0)
    private void killer560smod$ownNameInF5(Avatar entity, double distanceSq, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (entity == null || entity != mc.player || mc.options.getCameraType().isFirstPerson()
                || McCompat.hudHidden(mc)) {
            return;
        }
        // Song Tag / My Lyrics In F5 (2026-10-26): your own song or lyric line hangs over your name, so the name is shown
        // in third person while one is up, even with My Name in F5 off.
        if (!SupportersConfig.getInstance().isOwnNameInF5() && !com.killer560.hub.spotify.SongTagDraw.hasOwnLine()) {
            return;
        }
        cir.setReturnValue(true);
    }
}
