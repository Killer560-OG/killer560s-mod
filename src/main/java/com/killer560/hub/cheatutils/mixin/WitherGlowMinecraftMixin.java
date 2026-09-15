package com.killer560.hub.cheatutils.mixin;

import com.killer560.hub.cheatutils.WitherEspFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forces the tracked F7 Wither to render with the vanilla glow outline (through walls). Target javap-verified
 *  26.1.2: {@code public boolean shouldEntityAppearGlowing(Entity)}, called by EntityRenderer before it reads
 *  {@code Entity#getTeamColor()} - the same hook NoammAddons' MixinMinecraft uses for its glow ESPs. */
@Mixin(Minecraft.class)
public abstract class WitherGlowMinecraftMixin {

    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$cheatutilsWitherGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (WitherEspFeature.shouldGlow(entity)) {
            cir.setReturnValue(true);
        }
    }
}
