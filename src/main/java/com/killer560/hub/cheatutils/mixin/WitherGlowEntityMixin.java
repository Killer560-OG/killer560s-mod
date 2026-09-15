package com.killer560.hub.cheatutils.mixin;

import com.killer560.hub.cheatutils.WitherEspFeature;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Per-Wither outline color. Target javap-verified 26.1.2: {@code public int Entity#getTeamColor()} (not
 *  overridden by LivingEntity/Mob/Monster/WitherBoss) - same injection NoammAddons' MixinEntity uses. */
@Mixin(Entity.class)
public abstract class WitherGlowEntityMixin {

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$cheatutilsWitherColor(CallbackInfoReturnable<Integer> cir) {
        if (WitherEspFeature.shouldGlow((Entity) (Object) this)) {
            cir.setReturnValue(WitherEspFeature.glowColor() & 0xFFFFFF);
        }
    }
}
