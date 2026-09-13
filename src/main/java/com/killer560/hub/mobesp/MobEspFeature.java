package com.killer560.hub.mobesp;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Star Mob Hitbox ESP: highlights (vanilla's own real "Glowing" outline - {@code Entity#setGlowingTag},
 * the same mechanism Spectator mode and real potion Glowing already use, not a custom shader) any
 * living entity whose display name contains the configured filter (default the "✯" star glyph Hypixel
 * uses on star-tier dungeon mobs).
 * <p>
 * Legit mode only glows a mob while there's an actual clear line of sight to it right now (a real
 * {@link net.minecraft.world.level.Level#clip} raycast from the camera) - it never shows information
 * that isn't already visible on screen, the same category as a "glowing visible mob" QoL highlight.
 * Cheat mode skips that check entirely (glows through walls too - a real positional-awareness ESP),
 * gated on {@link MobEspConfig#isCheatMode()} exactly like every other real rule-violating feature in
 * this mod. Only ever un-glows an entity THIS class itself glowed (tracked in {@link #glowingIds}), so
 * it can't clobber some other, unrelated reason an entity might already be glowing.
 */
public final class MobEspFeature {

    private static final Set<Integer> glowingIds = new HashSet<>();

    private MobEspFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
        MobEspConfig cfg = MobEspConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || client.level == null || client.player == null || cfg.getNameFilter().isBlank()) {
            clearAllGlowing();
            return;
        }

        Set<Integer> shouldGlow = new HashSet<>();
        Vec3 eye = client.player.getEyePosition();
        double rangeSq = cfg.getRange() * cfg.getRange();

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living == client.player) {
                continue;
            }
            String name = living.getName().getString();
            if (!name.contains(cfg.getNameFilter())) {
                continue;
            }
            if (living.distanceToSqr(client.player) > rangeSq) {
                continue;
            }
            boolean visible = cfg.isCheatMode() || hasLineOfSight(client, eye, living);
            if (visible) {
                shouldGlow.add(living.getId());
                living.setGlowingTag(true);
            }
        }

        for (int id : glowingIds) {
            if (!shouldGlow.contains(id)) {
                Entity e = client.level.getEntity(id);
                if (e instanceof LivingEntity living) {
                    living.setGlowingTag(false);
                }
            }
        }
        glowingIds.clear();
        glowingIds.addAll(shouldGlow);
    }

    private static boolean hasLineOfSight(Minecraft client, Vec3 eye, LivingEntity target) {
        BlockHitResult result = client.level.clip(new ClipContext(
                eye, target.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
        return result.getType() == HitResult.Type.MISS;
    }

    private static void clearAllGlowing() {
        if (glowingIds.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            for (int id : glowingIds) {
                Entity e = client.level.getEntity(id);
                if (e instanceof LivingEntity living) {
                    living.setGlowingTag(false);
                }
            }
        }
        glowingIds.clear();
    }
}
