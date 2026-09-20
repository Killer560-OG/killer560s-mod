package com.killer560.hub.objecthider.mixin;

import com.killer560.hub.objecthider.ObjectHiderConfig;
import com.killer560.hub.pathfinding.IslandDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * QUOI {@code RenderOptimiser.kt} "Disable fog" (OFF by default) - QUOI's own hook is a
 * {@code @ModifyVariable} on {@code FogRenderer.getBuffer}, forcing {@code FogMode} to {@code NONE}
 * (mixins/FogRendererMixin.java:14-22); javap-verified {@code FogRenderer.getBuffer(FogMode)} still exists
 * unchanged in the 26.1.2 merged jar with the same two-constant enum ({@code NONE}, {@code WORLD}), so the
 * same technique applies directly. Also folds in the 1.1.1 {@code Tweaks.kt} "Fix Crimson Isle fog": Night
 * Vision on Crimson Isle renders a much thicker fog than anywhere else on Hypixel, so this is scoped
 * separately to "on Crimson Isle AND currently has Night Vision" rather than reusing the blanket
 * "Disable fog" toggle. Purely a GPU fog-buffer selection - never touches world state or anything sent to
 * the server.
 */
@Mixin(FogRenderer.class)
public abstract class ObjectHiderFogMixin {

    @ModifyVariable(method = "getBuffer", at = @At("HEAD"), argsOnly = true, require = 0)
    private FogRenderer.FogMode killer560smod$objectHider$fogMode(FogRenderer.FogMode mode) {
        ObjectHiderConfig cfg = ObjectHiderConfig.getInstance();
        if (cfg.isDisableFog()) {
            return FogRenderer.FogMode.NONE;
        }
        if (cfg.isFixCrimsonIsleFog() && "CRIMSON_ISLE".equals(IslandDetector.graphIsland())) {
            Player player = Minecraft.getInstance().player;
            if (player != null && player.hasEffect(MobEffects.NIGHT_VISION)) {
                return FogRenderer.FogMode.NONE;
            }
        }
        return mode;
    }
}
