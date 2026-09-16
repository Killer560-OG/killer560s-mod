package com.killer560.hub.thorn.mixin;

import com.killer560.hub.thorn.ThornEspFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Thorn ESP Glow style: forces the vanilla glow outline on the current Thorn targets. Same hook as Dungeon ESP's
 *  {@code WitherGlowMinecraftMixin} (javap-verified 26.1.2: {@code public boolean
 *  Minecraft#shouldEntityAppearGlowing(Entity)}). Which entities qualify - including the legit line-of-sight rule - is
 *  decided in {@link ThornEspFeature}. */
@Mixin(Minecraft.class)
public abstract class ThornGlowMinecraftMixin {

    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$thornGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (ThornEspFeature.shouldGlow(entity)) {
            cir.setReturnValue(true);
        }
    }
}
