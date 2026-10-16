package com.killer560.hub.livemap.mixin;

import com.killer560.hub.livemap.MapDrawCache;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A font reload (resource packs, Force Unicode Font, ...) drops the glyph textures that the Dungeon Map's kept text
 * layouts point into, so {@link MapDrawCache} must lay its text out again. Both {@code reload} overloads, 26.1.2 and
 * 26.2 alike (javap).
 */
@Mixin(FontSet.class)
public abstract class MapDrawCacheFontSetMixin {

    @Inject(method = "reload", at = @At("HEAD"))
    private void killer560smod$mapCacheFontReload(CallbackInfo ci) {
        MapDrawCache.onFontReload();
    }
}
