package com.killer560.hub.thorn;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Thorn ESP - highlights, only during the F4/M4 boss fight:
 * <ul>
 * <li><b>Spirit Bear</b>: a Player entity named "Spirit Bear..." (NoammAddons 26.1.2 {@code F4Features}
 * {@code gameProfile.name.lowercase().startsWith("spirit bear")}).
 * <li><b>Spirit mobs</b>: the wiki's six spirit animals (hypixelskyblock.minecraft.wiki/w/Thorn: Spirit Bull, Wolf, Bat,
 * Rabbit, Chicken, Sheep) matched by vanilla entity class - Wolf / cow (AbstractCow, covers a mooshroom too) / Bat /
 * Rabbit / Chicken / Sheep. The entity types Hypixel uses for them are not verified in a live run yet.
 * <li><b>Spirit Bow</b>: the dropped bow is an armor stand - NoFrills {@code SpiritBowHighlight} matches the stand's name
 * "Spirit Bow", NoammAddons matches a stand whose main-hand item is named "Bow"; either counts here. The stand is tiny,
 * so the box is NoFrills' player-sized 0.5 x 1.975 box on the ground under it.
 * </ul>
 * Styles and rules are Dungeon ESP's ({@code mobesp.MobEspFeature}): Outline / Filled (depth-tested unless Through
 * Walls) or Glow (vanilla outline via {@code ThornGlow*Mixin}). Legit: only targets with a clear line of sight right now.
 * Through Walls is cheat build only ({@link ThornConfig#isThroughWalls()}).
 */
public final class ThornEspFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-thorn");

    /** Entity id -> ARGB, rebuilt every client tick. */
    private static volatile Map<Integer, Integer> targets = Map.of();
    private static volatile Map<Integer, Integer> glowTargets = Map.of();
    /** Ids in {@link #targets} that are Spirit Bow stands (drawn with the ground box). */
    private static volatile Set<Integer> bowIds = Set.of();
    private static String lastLoggedCounts = null;

    private ThornEspFeature() {
    }

    public static boolean shouldGlow(Entity entity) {
        return entity != null && glowTargets.containsKey(entity.getId());
    }

    public static Integer glowColor(Entity entity) {
        return entity == null ? null : glowTargets.get(entity.getId());
    }

    static void reset() {
        targets = Map.of();
        glowTargets = Map.of();
        bowIds = Set.of();
    }

    /** Called every client tick while the Thorn fight is active. */
    static void tick(Minecraft client) {
        ThornConfig cfg = ThornConfig.getInstance();
        if (client.level == null || client.player == null || !cfg.isAnyEspEnabled()) {
            if (!targets.isEmpty()) {
                reset();
            }
            return;
        }
        boolean wantBear = cfg.isBearEspEnabled();
        boolean wantMobs = cfg.isMobEspEnabled();
        boolean wantBow = cfg.isBowEspEnabled();
        boolean throughWalls = cfg.isThroughWalls();
        Vec3 eye = client.player.getEyePosition();
        Map<Integer, Integer> found = new LinkedHashMap<>();
        java.util.HashSet<Integer> bows = new java.util.HashSet<>();
        int bears = 0;
        int mobs = 0;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || entity.isRemoved()) {
                continue;
            }
            if (entity instanceof Player) {
                if (wantBear && SpiritBearTracker.isSpiritBear(entity) && entity.isAlive() && visible(client, eye, entity, throughWalls)) {
                    found.put(entity.getId(), cfg.getBearColor());
                    bears++;
                }
            } else if (entity instanceof ArmorStand stand) {
                if (wantBow && isSpiritBow(stand) && visible(client, eye, stand, throughWalls)) {
                    found.put(stand.getId(), cfg.getBowColor());
                    bows.add(stand.getId());
                }
            } else if (wantMobs && isKnownSpiritAnimal(entity) && entity.isAlive() && !entity.isInvisible()
                    && visible(client, eye, entity, throughWalls)) {
                found.put(entity.getId(), cfg.getMobColor());
                mobs++;
            }
        }

        Map<Integer, Integer> snapshot = Map.copyOf(found);
        targets = snapshot;
        bowIds = Set.copyOf(bows);
        glowTargets = cfg.getStyle() == ThornConfig.Style.GLOW ? snapshot : Map.of();

        String counts = "bears=" + bears + " mobs=" + mobs + " bows=" + bows.size();
        if (!counts.equals(lastLoggedCounts) && (bears > 0 || bows.size() > 0 || lastLoggedCounts == null)) {
            LOGGER.info("[ThornEsp] {}", counts);
            lastLoggedCounts = counts;
        }
    }

    /** The wiki's six spirit animals by vanilla class (see class doc). */
    static boolean isKnownSpiritAnimal(Entity entity) {
        return entity instanceof Wolf || entity instanceof AbstractCow || entity instanceof Bat
                || entity instanceof Rabbit || entity instanceof Chicken || entity instanceof Sheep;
    }

    private static boolean isSpiritBow(ArmorStand stand) {
        String name = ChatFormatting.stripFormatting(stand.getName().getString());
        if ("Spirit Bow".equals(name)) {
            return true;
        }
        var held = stand.getMainHandItem();
        if (held.isEmpty()) {
            return false;
        }
        String heldName = ChatFormatting.stripFormatting(held.getHoverName().getString());
        return "Bow".equals(heldName);
    }

    private static boolean visible(Minecraft client, Vec3 eye, Entity target, boolean throughWalls) {
        if (throughWalls) {
            return true;
        }
        BlockHitResult result = client.level.clip(new ClipContext(
                eye, target.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
        return result.getType() == HitResult.Type.MISS;
    }

    static void render(LevelRenderContext context) {
        Map<Integer, Integer> current = targets;
        ThornConfig cfg = ThornConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (current.isEmpty() || client.level == null || cfg.getStyle() == ThornConfig.Style.GLOW
                || !ThornFeature.inThornBoss()) {
            return;
        }
        boolean throughWalls = cfg.isThroughWalls();
        boolean filled = cfg.getStyle() == ThornConfig.Style.FILLED;
        float lineWidth = cfg.getLineWidth();
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Set<Integer> bows = bowIds;
        for (Map.Entry<Integer, Integer> target : current.entrySet()) {
            Entity entity = client.level.getEntity(target.getKey());
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            AABB box;
            if (bows.contains(entity.getId())) {
                box = bowBox(client, entity);
            } else {
                Vec3 lerped = entity.getPosition(partialTick);
                box = entity.getBoundingBox().move(lerped.subtract(entity.position()));
            }
            if (filled) {
                ThornEspRenderer.filled(context, box, target.getValue(), throughWalls);
            }
            ThornEspRenderer.outline(context, box, target.getValue(), lineWidth, throughWalls);
        }
    }

    /** NoFrills {@code SpiritBowHighlight}: ground under the stand (up to 4 blocks down), then a 0.5 x 1.975 x 0.5 box
     *  standing on it. */
    private static AABB bowBox(Minecraft client, Entity stand) {
        BlockPos pos = stand.blockPosition();
        BlockPos ground = pos;
        for (int i = 0; i <= 4; i++) {
            BlockPos below = pos.below(i);
            if (!client.level.getBlockState(below).isAir()) {
                ground = below;
                break;
            }
        }
        return AABB.ofSize(Vec3.atCenterOf(ground).add(0.0, 1.4875, 0.0), 0.5, 1.975, 0.5);
    }
}
