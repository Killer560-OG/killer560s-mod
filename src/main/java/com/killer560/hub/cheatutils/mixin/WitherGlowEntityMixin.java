package com.killer560.hub.cheatutils.mixin;

import com.killer560.hub.mobesp.MobEspFeature;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Dungeon ESP Glow style: per-target outline colour (name kept from the old Wither ESP so the mixin config is unchanged).
 *  Target javap-verified 26.1.2: {@code public int Entity#getTeamColor()} (not overridden by LivingEntity/Mob/Monster/
 *  WitherBoss) - same injection NoammAddons' MixinEntity uses. */
@Mixin(Entity.class)
public abstract class WitherGlowEntityMixin {

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$cheatutilsWitherColor(CallbackInfoReturnable<Integer> cir) {
        Integer color = MobEspFeature.glowColor((Entity) (Object) this);
        if (color != null) {
            cir.setReturnValue(color & 0xFFFFFF);
        }
    }
}
