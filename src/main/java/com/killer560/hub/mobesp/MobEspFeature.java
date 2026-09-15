package com.killer560.hub.mobesp;

import com.killer560.hub.cheatutils.WitherEspFeature;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dungeon ESP - highlights exactly three kinds of dungeon target, each with its own colour
 * ({@link MobEspConfig}):
 * <ul>
 * <li><b>Starred mobs</b> (dungeon clear, not boss). Hypixel puts the "✯ ... ❤" name on a separate invisible
 * armor stand, never on the mob, so each starred stand is mapped to its real mob the way NoammAddons 26.1.2
 * {@code StarMobESP.checkStarMob} and QUOI {@code DungeonESP.handleStand} both do: the mob is the entity with id
 * {@code standId - 1} (Withermancers {@code - 3}: the ids between are their wither skulls); if that isn't a real
 * mob, the first valid entity inside the stand's bounding box shifted 1 block down. Player-model minibosses
 * (Shadow Assassin, Lost/Frozen Adventurer, Diamond Guy, King Midas) are matched by name like Noamm/QUOI/Devonian
 * do, v2-UUID NPCs only.
 * <li><b>Bats</b> (dungeon clear, not boss): Hypixel's secret bats have Skyblock health (QUOI: 100/200/400/800,
 * Devonian: anything but vanilla's 6) - so a visible, alive, non-passenger {@link Bat} whose max health isn't 6.
 * <li><b>Wither bosses</b> (F7/M7 boss, P1-P4): {@link WitherEspFeature#isRealWither} (not Hypixel's invisible /
 * 800-invulnerable-tick display withers). Cheat build only, like the Cheat Utils Wither ESP it replaces.
 * </ul>
 * Styles: Outline Box / Filled Box ({@link EspRenderer}) or Glow - vanilla's entity outline, forced through
 * {@code Minecraft#shouldEntityAppearGlowing} and coloured through {@code Entity#getTeamColor} by the
 * cheatutils {@code WitherGlow*Mixin}s (NoammAddons' two hooks), so no entity data is ever mutated.
 * <p>
 * Legit (the default, and the only option on the legit jar): a target is only highlighted while there is a clear
 * line of sight to it right now (a real {@code Level#clip} raycast from the camera) and boxes are depth-tested.
 * Through Walls ({@link MobEspConfig#isThroughWalls()}, cheat build only) skips the raycast and draws boxes with
 * no depth test.
 */
public final class MobEspFeature {

    private static final String STAR = "✯";
    private static final String HEART = "❤";
    private static final Set<String> MINIBOSS_NAMES = Set.of(
            "Shadow Assassin", "Lost Adventurer", "Frozen Adventurer", "Diamond Guy", "King Midas");

    /** Entity id -> ARGB, rebuilt every client tick; read by the render callback and the glow mixins. */
    private static volatile Map<Integer, Integer> targets = Map.of();
    /** Same as {@link #targets} while the style is Glow, empty otherwise. */
    private static volatile Map<Integer, Integer> glowTargets = Map.of();
    /** Starred name-tag armor stand id -> resolved real mob id (resolved once, like Noamm's own cache). */
    private static final Map<Integer, Integer> standToMob = new HashMap<>();
    private static Object lastLevel = null;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-mobesp");
    private static String lastLoggedGates = null;
    private static String lastLoggedCounts = null;
    private static long lastCountsLogMs = 0;

    private MobEspFeature() {
    }

    public static void register() {
        EspRenderer.init();
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(MobEspFeature::render);
    }

    /** Glow mixin hook: whether this entity should get the vanilla glow outline. */
    public static boolean shouldGlow(Entity entity) {
        return entity != null && glowTargets.containsKey(entity.getId());
    }

    /** Glow mixin hook: the ARGB outline colour for this entity, or null if Dungeon ESP isn't glowing it. */
    public static Integer glowColor(Entity entity) {
        return entity == null ? null : glowTargets.get(entity.getId());
    }

    private static void tick() {
        MobEspConfig cfg = MobEspConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (client.level != lastLevel) {
            // Entity ids restart on a new server, so a dungeon -> dungeon warp would otherwise keep stale ids.
            lastLevel = client.level;
            standToMob.clear();
            clearTargets();
        }
        boolean inDungeon = DungeonState.isInDungeon();
        boolean inBoss = LiveMapFeature.isInBoss();
        boolean hasWorld = client.level != null && client.player != null;
        boolean wantStars = hasWorld && cfg.isStarredMobsEnabled() && inDungeon && !inBoss;
        boolean wantBats = hasWorld && cfg.isBatsEnabled() && inDungeon && !inBoss;
        boolean wantWithers = hasWorld && cfg.isWithersEnabled() && WitherEspFeature.isWitherBossActive(client);

        String gates = "stars=" + wantStars + " bats=" + wantBats + " withers=" + wantWithers
                + " (cfg stars=" + cfg.getStarredMobsRaw() + " bats=" + cfg.getBatsRaw() + " withers=" + cfg.getWithersRaw()
                + ") style=" + cfg.getStyle() + " throughWalls=" + cfg.isThroughWalls()
                + " inDungeon=" + inDungeon + " inBoss=" + inBoss;
        if (!gates.equals(lastLoggedGates)) {
            LOGGER.info("[DungeonEsp] Gates changed: {}", gates);
            lastLoggedGates = gates;
        }
        if (!wantStars) {
            standToMob.clear();
        }
        if (!wantStars && !wantBats && !wantWithers) {
            clearTargets();
            return;
        }

        boolean throughWalls = cfg.isThroughWalls();
        Vec3 eye = client.player.getEyePosition();
        double rangeSq = cfg.getRange() * cfg.getRange();
        Map<Integer, Integer> found = new LinkedHashMap<>();
        Set<Integer> liveStands = new HashSet<>();
        int stars = 0;
        int bats = 0;
        int withers = 0;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player) {
                continue;
            }
            if (wantWithers && WitherEspFeature.isRealWither(entity)) {
                if (accept(client, eye, entity, Double.MAX_VALUE, throughWalls, found, cfg.getWitherColor())) {
                    withers++;
                }
            } else if (wantBats && entity instanceof Bat bat) {
                if (isSecretBat(bat) && accept(client, eye, bat, rangeSq, throughWalls, found, cfg.getBatColor())) {
                    bats++;
                }
            } else if (wantStars && entity instanceof ArmorStand stand) {
                String name = stand.getName().getString();
                if (!name.contains(STAR) || !name.contains(HEART)) {
                    continue;
                }
                liveStands.add(stand.getId());
                Entity mob = resolveMob(client, stand, name);
                if (mob != null && accept(client, eye, mob, rangeSq, throughWalls, found, cfg.getStarredColor())) {
                    stars++;
                }
            } else if (wantStars && entity instanceof Player p && isMiniboss(client, p)) {
                if (accept(client, eye, p, rangeSq, throughWalls, found, cfg.getStarredColor())) {
                    stars++;
                }
            }
        }
        standToMob.keySet().retainAll(liveStands);

        Map<Integer, Integer> snapshot = Map.copyOf(found);
        targets = snapshot;
        glowTargets = cfg.getStyle() == MobEspConfig.Style.GLOW ? snapshot : Map.of();

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastCountsLogMs >= 2000) {
            lastCountsLogMs = nowMs;
            String counts = "starredStands=" + liveStands.size() + " stars=" + stars + " bats=" + bats + " withers=" + withers;
            if (!counts.equals(lastLoggedCounts)) {
                LOGGER.info("[DungeonEsp] {}", counts);
                lastLoggedCounts = counts;
            }
        }
    }

    private static boolean accept(Minecraft client, Vec3 eye, Entity target, double rangeSq, boolean throughWalls,
                                  Map<Integer, Integer> found, int color) {
        if (target.isRemoved() || found.containsKey(target.getId()) || target.distanceToSqr(client.player) > rangeSq) {
            return false;
        }
        if (!throughWalls && !hasLineOfSight(client, eye, target)) {
            return false;
        }
        found.put(target.getId(), color);
        return true;
    }

    private static void render(LevelRenderContext context) {
        Map<Integer, Integer> current = targets;
        MobEspConfig cfg = MobEspConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (current.isEmpty() || client.level == null || cfg.getStyle() == MobEspConfig.Style.GLOW) {
            return;
        }
        boolean throughWalls = cfg.isThroughWalls();
        boolean filled = cfg.getStyle() == MobEspConfig.Style.FILLED;
        float lineWidth = cfg.getLineWidth();
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (Map.Entry<Integer, Integer> target : current.entrySet()) {
            Entity entity = client.level.getEntity(target.getKey());
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            Vec3 lerped = entity.getPosition(partialTick);
            AABB box = entity.getBoundingBox().move(lerped.subtract(entity.position()));
            if (filled) {
                EspRenderer.filled(context, box, target.getValue(), throughWalls);
            }
            EspRenderer.outline(context, box, target.getValue(), lineWidth, throughWalls);
        }
    }

    private static boolean isSecretBat(Bat bat) {
        return bat.isAlive() && !bat.isPassenger() && !bat.isInvisible() && bat.getMaxHealth() != 6.0f;
    }

    private static boolean isMiniboss(Minecraft client, Player p) {
        return p != client.player && p.getUUID().version() == 2 && MINIBOSS_NAMES.contains(p.getName().getString());
    }

    /** Maps a starred name-tag stand to its real mob (see this class's doc), caching a successful match. */
    private static Entity resolveMob(Minecraft client, ArmorStand stand, String name) {
        Integer cached = standToMob.get(stand.getId());
        if (cached != null) {
            Entity mob = client.level.getEntity(cached);
            if (mob != null && !mob.isRemoved()) {
                return mob;
            }
            standToMob.remove(stand.getId());
        }

        int offset = name.toUpperCase(Locale.ROOT).contains("WITHERMANCER") ? 3 : 1;
        Entity byId = client.level.getEntity(stand.getId() - offset);
        Entity mob = null;
        String how = null;
        if (byId != null && !(byId instanceof ArmorStand) && isValidMob(client, byId)) {
            mob = byId;
            how = "id-" + offset;
        } else {
            List<Entity> below = client.level.getEntities(stand, stand.getBoundingBox().move(0.0, -1.0, 0.0),
                    e -> !(e instanceof ArmorStand) && !(e instanceof ExperienceOrb));
            for (Iterator<Entity> it = below.iterator(); it.hasNext() && mob == null; ) {
                Entity e = it.next();
                if (isValidMob(client, e) && !standToMob.containsValue(e.getId())) {
                    mob = e;
                    how = "bbox-below";
                }
            }
        }
        if (mob != null) {
            standToMob.put(stand.getId(), mob.getId());
            LOGGER.info("[DungeonEsp] Resolved starred stand {} \"{}\" -> {} id={} via {}",
                    stand.getId(), name, mob.getType().toShortString(), mob.getId(), how);
        }
        return mob;
    }

    /** Only real living, non-bat, non-wither entities (never a dropped item, projectile or you) count as a star mob. */
    private static boolean isValidMob(Minecraft client, Entity e) {
        if (!(e instanceof LivingEntity) || e instanceof Bat) {
            return false;
        }
        if (e == client.player || e instanceof WitherBoss || e instanceof AbstractArrow) {
            return false;
        }
        if (e instanceof Player p) {
            return !p.isInvisible() && p.getUUID().version() == 2;
        }
        return true;
    }

    private static boolean hasLineOfSight(Minecraft client, Vec3 eye, Entity target) {
        BlockHitResult result = client.level.clip(new ClipContext(
                eye, target.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
        return result.getType() == HitResult.Type.MISS;
    }

    private static void clearTargets() {
        targets = Map.of();
        glowTargets = Map.of();
    }
}
