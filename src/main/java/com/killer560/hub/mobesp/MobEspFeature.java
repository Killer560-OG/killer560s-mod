package com.killer560.hub.mobesp;

import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Star Mob Hitbox ESP: highlights (vanilla's own real "Glowing" outline - {@code Entity#setGlowingTag},
 * the same mechanism Spectator mode and real potion Glowing already use, not a custom shader) the real
 * mob behind every name tag containing the configured filter (default the "✯" star glyph Hypixel
 * uses on star-tier dungeon mobs).
 * <p>
 * Legit mode only glows a mob while there's an actual clear line of sight to it right now (a real
 * {@link net.minecraft.world.level.Level#clip} raycast from the camera) - it never shows information
 * that isn't already visible on screen, the same category as a "glowing visible mob" QoL highlight.
 * Cheat mode skips that check entirely (glows through walls too - a real positional-awareness ESP),
 * gated on {@link MobEspConfig#isCheatMode()} exactly like every other real rule-violating feature in
 * this mod. Only ever un-glows an entity THIS class itself glowed (tracked in {@link #glowingIds}), so
 * it can't clobber some other, unrelated reason an entity might already be glowing.
 * <p>
 * Real bug found and fixed (2026-09-14): {@code ArmorStand} is a {@code LivingEntity}, and on Hypixel the
 * star is on a separate invisible name-tag armor stand, never on the mob itself - so this glowed the
 * (invisible) name-tag stands instead of the mobs, and ran everywhere, not just in dungeons. Now gated on
 * {@link DungeonState#isInDungeon()}, armor stands are never glowed, and each starred name-tag stand is
 * mapped to its real mob the same way NoammAddons' 26.1.2 {@code StarMobESP.checkStarMob} does: the mob
 * is normally the entity with id {@code standId - 1} (Withermancers {@code - 3}: the ids in between are
 * their wither skulls); if that isn't a real non-armor-stand entity, fall back to the first entity inside
 * the stand's bounding box shifted 1 block down (never an armor stand/XP orb/arrow/Wither; a Player only
 * if visible, a v2-UUID NPC, and not you).
 */
public final class MobEspFeature {

    private static final Set<Integer> glowingIds = new HashSet<>();
    /** Starred name-tag armor stand id -> resolved real mob id (resolved once, like Noamm's own cache). */
    private static final Map<Integer, Integer> standToMob = new HashMap<>();
    private static Object lastLevel = null;
    /** Hypixel's star-mob glyph (the same "✯" as MobEspConfig's default filter) and name-tag heart. */
    private static final String STAR = "✯";
    private static final String HEART = "❤";

    // [MobEsp] diagnostics - logging only.
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-mobesp");
    private static String lastLoggedGates = null;
    private static String lastLoggedCounts = null;
    private static long lastCountsLogMs = 0;

    private MobEspFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
        MobEspConfig cfg = MobEspConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        boolean inDungeon = DungeonState.isInDungeon();
        String gates = "enabled=" + cfg.isEnabled() + " filter='" + cfg.getNameFilter() + "' range=" + cfg.getRange()
                + " cheatMode=" + cfg.isCheatMode() + " hasLevel=" + (client.level != null)
                + " inDungeon=" + inDungeon;
        if (!gates.equals(lastLoggedGates)) {
            LOGGER.info("[MobEsp] Gates changed: {}", gates);
            lastLoggedGates = gates;
        }
        if (client.level != lastLevel) {
            // Real bug found and fixed (2026-09-14 review pass): entity ids restart on a new server, so a
            // dungeon -> dungeon warp (inDungeon never false) kept stale stand->mob ids and un-glowed/mapped
            // unrelated new entities. Old entities are gone with the old level - just forget them.
            lastLevel = client.level;
            glowingIds.clear();
            standToMob.clear();
        }
        if (!cfg.isEnabled() || client.level == null || client.player == null || cfg.getNameFilter().isBlank()
                || !inDungeon) {
            clearAllGlowing();
            standToMob.clear();
            return;
        }

        String filter = cfg.getNameFilter();
        Set<Integer> shouldGlow = new HashSet<>();
        Vec3 eye = client.player.getEyePosition();
        double rangeSq = cfg.getRange() * cfg.getRange();
        int starredStands = 0;
        int resolvedStands = 0;
        int directNameMatches = 0;
        int inRange = 0;
        String sampleName = null;
        Set<Integer> liveStands = new HashSet<>();
        Set<Entity> candidates = new HashSet<>();

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player) {
                continue;
            }
            String name = entity.getName().getString();
            if (!name.contains(filter)) {
                continue;
            }
            if (sampleName == null) {
                sampleName = name;
            }
            if (entity instanceof ArmorStand stand) {
                // Real bug found and fixed (2026-09-14 review pass): any stand containing the filter counted
                // (e.g. star-bearing hologram/NPC text). NoammAddons' StarMobESP requires the mob-tag heart
                // too (name ends in "§c❤"), so require both the star and the heart here.
                if (!name.contains(STAR) || !name.contains(HEART)) {
                    continue;
                }
                // Hypixel's star lives on a separate name-tag stand - glow the real mob it belongs to instead.
                starredStands++;
                liveStands.add(stand.getId());
                Entity mob = resolveMob(client, stand, name);
                if (mob != null) {
                    resolvedStands++;
                    candidates.add(mob);
                }
            } else if (entity instanceof LivingEntity) {
                directNameMatches++;
                candidates.add(entity);
            }
        }
        standToMob.keySet().retainAll(liveStands);

        for (Entity mob : candidates) {
            if (mob.isRemoved() || mob.distanceToSqr(client.player) > rangeSq) {
                continue;
            }
            inRange++;
            boolean visible = cfg.isCheatMode() || hasLineOfSight(client, eye, mob);
            if (visible) {
                shouldGlow.add(mob.getId());
                mob.setGlowingTag(true);
            }
        }

        for (int id : glowingIds) {
            if (!shouldGlow.contains(id)) {
                Entity e = client.level.getEntity(id);
                if (e != null) {
                    e.setGlowingTag(false);
                }
            }
        }
        glowingIds.clear();
        glowingIds.addAll(shouldGlow);

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastCountsLogMs >= 2000) {
            lastCountsLogMs = nowMs;
            String counts = "starredStands=" + starredStands + " resolvedToMob=" + resolvedStands
                    + " directNameMatches=" + directNameMatches + " inRange=" + inRange + " glowing=" + shouldGlow.size();
            if (!counts.equals(lastLoggedCounts)) {
                LOGGER.info("[MobEsp] {} sample=\"{}\"", counts, sampleName);
                lastLoggedCounts = counts;
            }
        }
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
            // State-change only: logged once per newly resolved stand.
            LOGGER.info("[MobEsp] Resolved starred stand {} \"{}\" -> {} id={} via {}",
                    stand.getId(), name, mob.getType().toShortString(), mob.getId(), how);
        }
        return mob;
    }

    /** Real bug found and fixed (2026-09-14 review pass): the id-offset/bbox fallback could resolve a stand to
     *  a dropped item, projectile or ambient bat - now only real living, non-bat entities count. */
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

    private static void clearAllGlowing() {
        if (glowingIds.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            for (int id : glowingIds) {
                Entity e = client.level.getEntity(id);
                if (e != null) {
                    e.setGlowingTag(false);
                }
            }
        }
        glowingIds.clear();
    }
}
