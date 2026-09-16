package com.killer560.hub.thorn.mixin;

import com.killer560.hub.thorn.ThornEspFeature;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Thorn ESP Glow style: per-target outline colour. Same hook as Dungeon ESP's {@code WitherGlowEntityMixin} (target
 *  javap-verified on the 26.1.2 jar: {@code public int Entity#getTeamColor()}). Only returns a colour for entities
 *  {@link ThornEspFeature} is glowing, so it never changes anything Dungeon ESP colours. */
@Mixin(Entity.class)
public abstract class ThornGlowEntityMixin {

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$thornGlowColor(CallbackInfoReturnable<Integer> cir) {
        Integer color = ThornEspFeature.glowColor((Entity) (Object) this);
        if (color != null) {
            cir.setReturnValue(color & 0xFFFFFF);
        }
    }
}
