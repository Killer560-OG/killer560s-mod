package com.killer560.hub.cheatutils.mixin;

import com.killer560.hub.mobesp.MobEspFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Dungeon ESP Glow style: forces the vanilla glow outline on the current targets (name kept from the old Wither ESP so
 *  the mixin config is unchanged). Which entities qualify - including the legit line-of-sight rule - is decided in
 *  {@link MobEspFeature}. Target javap-verified 26.1.2: {@code public boolean shouldEntityAppearGlowing(Entity)}, called by
 *  EntityRenderer before it reads {@code Entity#getTeamColor()} - the same hook NoammAddons' MixinMinecraft uses. */
@Mixin(Minecraft.class)
public abstract class WitherGlowMinecraftMixin {

    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$cheatutilsWitherGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (MobEspFeature.shouldGlow(entity)) {
            cir.setReturnValue(true);
        }
    }
}
