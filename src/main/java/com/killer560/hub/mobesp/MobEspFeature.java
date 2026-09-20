package com.killer560.hub.mobesp;

import com.killer560.hub.cheatutils.WitherEspFeature;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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
 * Dungeon ESP - highlights four kinds of dungeon target, each with its own colour ({@link MobEspConfig}):
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
 * <li><b>Wither Highlight</b> (F7/M7 boss, P1-P4, both builds): the same real withers, but never through walls -
 * Glow Hitbox or Hitbox Fill ({@link MobEspConfig.WitherStyle}).
 * <li><b>Wither ESP</b> (F7/M7 boss, P1-P4): {@link WitherEspFeature#isRealWither} (not Hypixel's invisible /
 * 800-invulnerable-tick display withers). Cheat build only, like the Cheat Utils Wither ESP it replaces; always
 * through walls and never range-limited. Takes precedence over Wither Highlight when both are on.
 * </ul>
 * Styles: Outline Box / Filled Box ({@link EspRenderer}) or Glow - vanilla's entity outline, forced through
 * {@code Minecraft#shouldEntityAppearGlowing} and coloured through {@code Entity#getTeamColor} by the
 * cheatutils {@code WitherGlow*Mixin}s (NoammAddons' two hooks), so no entity data is ever mutated.
 * <p>
 * Legit (the default, and the only option on the legit jar): a target is only highlighted while there is a clear
 * line of sight to it (a real {@code Level#clip} raycast from the camera) and boxes are depth-tested. Through Walls
 * ({@link MobEspConfig#isThroughWalls()}, cheat build only) skips the raycast for starred mobs and bats and draws
 * their boxes with no depth test; Wither ESP is through-walls by definition.
 * <p>
 * Selection for starred mobs + bats is either the Range slider or, with Room Scoped on, the live dungeon room the
 * player is standing in plus a margin - see {@link #currentRoomBox}.
 */
public final class MobEspFeature {

    private static final String STAR = "✯";
    private static final String HEART = "❤";
    private static final Set<String> MINIBOSS_NAMES = Set.of(
            "Shadow Assassin", "Lost Adventurer", "Frozen Adventurer", "Diamond Guy", "King Midas");

    /** Everything a box target needs; glow targets only need the colour. */
    record Box(int argb, boolean filled, boolean throughWalls) {
    }

    /** Entity id -> box spec, rebuilt every client tick; read by the render callback. */
    private static volatile Map<Integer, Box> boxTargets = Map.of();
    /** Entity id -> ARGB for the targets whose style is Glow; read by the glow mixins. */
    private static volatile Map<Integer, Integer> glowTargets = Map.of();
    /** Starred name-tag armor stand id -> resolved real mob id (resolved once, like Noamm's own cache). */
    private static final Map<Integer, Integer> standToMob = new HashMap<>();
    private static Object lastLevel = null;

    /**
     * Line-of-sight cache (2026-09-20 FPS pass). {@code hasLineOfSight} is a full {@code Level#clip} voxel walk and
     * used to run once per candidate entity per tick - with the 22 starred stands of a real F7 clear that was the
     * most expensive per-entity operation in the mod. Each entity's raycast is now refreshed every
     * {@link #LOS_REFRESH_TICKS} ticks, staggered by entity id so the cost is spread over those ticks instead of
     * spiking on one. Value packs {@code tick << 1 | result}.
     */
    private static final Map<Integer, Long> losCache = new HashMap<>();
    private static final int LOS_REFRESH_TICKS = 4;
    private static long tickCounter = 0;

    /** Room Scoped box cache - only rebuilt when the room, the margin or the scan snapshot actually changes. */
    private static DungeonLayout cachedLayout = null;
    private static int cachedRoom = -1;
    private static double cachedRoomMargin = -1.0;
    private static AABB cachedRoomBox = null;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-mobesp");
    private static int lastLoggedGateBits = Integer.MIN_VALUE;
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
        tickCounter++;
        if (client.level != lastLevel) {
            // Entity ids restart on a new server, so a dungeon -> dungeon warp would otherwise keep stale ids.
            lastLevel = client.level;
            standToMob.clear();
            losCache.clear();
            invalidateLayout();
            clearTargets();
        }
        boolean hasWorld = client.level != null && client.player != null;
        // Cheapest gates first: a disabled feature must not reach DungeonState/LiveMap at all (FPS pass).
        boolean anyRaw = (cfg.getStarredMobsRaw() || cfg.getBatsRaw() || cfg.getWithersRaw()
                || cfg.getWitherHighlightRaw()) && com.killer560.hub.util.SkyblockGate.allows();
        if (!hasWorld || !anyRaw) {
            if (!boxTargets.isEmpty() || !glowTargets.isEmpty()) {
                standToMob.clear();
                losCache.clear();
                clearTargets();
            }
            logGates(cfg, false, false, false, false, false, false);
            return;
        }
        boolean inDungeon = DungeonState.isInDungeon();
        boolean inBoss = LiveMapFeature.isInBoss();
        boolean wantStars = cfg.isStarredMobsEnabled() && inDungeon && !inBoss;
        boolean wantBats = cfg.isBatsEnabled() && inDungeon && !inBoss;
        boolean witherActive = (cfg.getWithersRaw() || cfg.getWitherHighlightRaw())
                && WitherEspFeature.isWitherBossActive(client);
        boolean wantWitherEsp = witherActive && cfg.isWithersEnabled();
        // Wither ESP wins when both are on - otherwise the same wither is drawn twice.
        boolean wantWitherHighlight = witherActive && !wantWitherEsp && cfg.isWitherHighlightEnabled();

        logGates(cfg, wantStars, wantBats, wantWitherEsp, wantWitherHighlight, inDungeon, inBoss);

        if (!wantStars) {
            standToMob.clear();
        }
        if (!wantStars && !wantBats && !wantWitherEsp && !wantWitherHighlight) {
            losCache.clear();
            clearTargets();
            return;
        }

        boolean throughWalls = cfg.isThroughWalls();
        boolean filled = cfg.getStyle() == MobEspConfig.Style.FILLED;
        boolean glow = cfg.getStyle() == MobEspConfig.Style.GLOW;
        boolean witherGlow = cfg.getWitherHighlightStyle() == MobEspConfig.WitherStyle.GLOW;
        Vec3 eye = client.player.getEyePosition();
        // Room Scoped replaces Range entirely for starred mobs + bats; if the room isn't known yet (map not
        // scanned, or standing off the grid) fall back to Range rather than showing nothing.
        AABB roomBox = (wantStars || wantBats) && cfg.isRoomScoped()
                ? currentRoomBox(client, cfg.getRoomMargin()) : null;
        double rangeSq = roomBox != null ? Double.MAX_VALUE : cfg.getRange() * cfg.getRange();

        Map<Integer, Box> foundBoxes = new LinkedHashMap<>();
        Map<Integer, Integer> foundGlows = new LinkedHashMap<>();
        Set<Integer> liveStands = new HashSet<>();
        int stars = 0;
        int bats = 0;
        int withers = 0;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player) {
                continue;
            }
            if (witherActive && entity instanceof WitherBoss && WitherEspFeature.isRealWither(entity)) {
                // Both wither modes ignore Range and Room Scoped entirely (killer560: "the wither esp should work
                // no matter the range"). Wither ESP is also unconditionally through-walls.
                boolean espMode = wantWitherEsp;
                if (!espMode && !wantWitherHighlight) {
                    continue;
                }
                int color = espMode ? cfg.getWitherColor() : cfg.getWitherHighlightColor();
                boolean wallsOk = espMode;
                boolean asGlow = espMode ? glow : witherGlow;
                boolean asFilled = espMode ? filled : !witherGlow;
                if (accept(client, eye, entity, Double.MAX_VALUE, null, wallsOk, asGlow, asFilled, wallsOk,
                        foundBoxes, foundGlows, color)) {
                    withers++;
                }
            } else if (wantBats && entity instanceof Bat bat) {
                if (isSecretBat(bat) && accept(client, eye, bat, rangeSq, roomBox, throughWalls, glow, filled,
                        throughWalls, foundBoxes, foundGlows, cfg.getBatColor())) {
                    bats++;
                }
            } else if (wantStars && entity instanceof ArmorStand stand) {
                // hasCustomName() is a flat data-watcher read; getName().getString() builds a String. A dungeon room
                // is full of unnamed decoration stands, so check the cheap one first (FPS pass).
                if (!stand.hasCustomName()) {
                    continue;
                }
                String name = stand.getName().getString();
                if (!name.contains(STAR) || !name.contains(HEART)) {
                    continue;
                }
                liveStands.add(stand.getId());
                Entity mob = resolveMob(client, stand, name);
                if (mob != null && accept(client, eye, mob, rangeSq, roomBox, throughWalls, glow, filled,
                        throughWalls, foundBoxes, foundGlows, cfg.getStarredColor())) {
                    stars++;
                }
            } else if (wantStars && entity instanceof Player p && isMiniboss(client, p)) {
                if (accept(client, eye, p, rangeSq, roomBox, throughWalls, glow, filled, throughWalls,
                        foundBoxes, foundGlows, cfg.getStarredColor())) {
                    stars++;
                }
            }
        }
        standToMob.keySet().retainAll(liveStands);
        // A target that FAILED the raycast must keep its cached "not visible" answer, or the expensive case would
        // never be cached at all - so prune by age instead of by what got accepted this tick.
        if ((tickCounter & 63L) == 0L) {
            losCache.values().removeIf(v -> tickCounter - (v >>> 1) > 100L);
        }

        boxTargets = Map.copyOf(foundBoxes);
        glowTargets = Map.copyOf(foundGlows);

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastCountsLogMs >= 2000) {
            lastCountsLogMs = nowMs;
            String counts = "starredStands=" + liveStands.size() + " stars=" + stars + " bats=" + bats
                    + " withers=" + withers + " room=" + (roomBox == null ? "range" : "scoped");
            if (!counts.equals(lastLoggedCounts)) {
                LOGGER.info("[DungeonEsp] {}", counts);
                lastLoggedCounts = counts;
            }
        }
    }

    /** Was a 15-part concatenated string built every tick purely to compare against the last one (FPS pass) - now a
     *  bitset, with the string only built on an actual change. */
    private static void logGates(MobEspConfig cfg, boolean wantStars, boolean wantBats, boolean wantWitherEsp,
                                 boolean wantWitherHighlight, boolean inDungeon, boolean inBoss) {
        int bits = (wantStars ? 1 : 0) | (wantBats ? 1 << 1 : 0) | (wantWitherEsp ? 1 << 2 : 0)
                | (wantWitherHighlight ? 1 << 3 : 0) | (cfg.getStarredMobsRaw() ? 1 << 4 : 0)
                | (cfg.getBatsRaw() ? 1 << 5 : 0) | (cfg.getWithersRaw() ? 1 << 6 : 0)
                | (cfg.getWitherHighlightRaw() ? 1 << 7 : 0) | (cfg.isThroughWalls() ? 1 << 8 : 0)
                | (inDungeon ? 1 << 9 : 0) | (inBoss ? 1 << 10 : 0) | (cfg.isRoomScoped() ? 1 << 11 : 0)
                | (cfg.getStyle().ordinal() << 12) | (cfg.getWitherHighlightStyle().ordinal() << 14);
        if (bits == lastLoggedGateBits) {
            return;
        }
        lastLoggedGateBits = bits;
        LOGGER.info("[DungeonEsp] Gates changed: stars={} bats={} witherEsp={} witherHighlight={} (cfg stars={}"
                        + " bats={} withers={} witherHighlight={}) style={} witherStyle={} throughWalls={}"
                        + " roomScoped={} inDungeon={} inBoss={}",
                wantStars, wantBats, wantWitherEsp, wantWitherHighlight, cfg.getStarredMobsRaw(), cfg.getBatsRaw(),
                cfg.getWithersRaw(), cfg.getWitherHighlightRaw(), cfg.getStyle(), cfg.getWitherHighlightStyle(),
                cfg.isThroughWalls(), cfg.isRoomScoped(), inDungeon, inBoss);
    }

    /**
     * killer560, 2026-09-20: "instead of being range based it should be every starred mob in the room I am currently
     * in. Then if a mob is slightly outside of the room it gets that as well. Anything within about 5-10 blocks."
     * <p>
     * Uses the live scanned dungeon, not a guess: {@link DungeonLayout#current()} (the shared per-tick snapshot),
     * {@code roomAtWorld} for the player's room and the room's own even tiles for its footprint - each tile is
     * 32x32 centred on {@link DungeonLayout#cellCenter}, which reproduces {@code LiveMapFeature.roomWorldBounds}
     * exactly. Vertically unbounded: dungeon rooms stack over several floors and the margin is a horizontal one.
     *
     * @return the current room's box inflated by {@code margin}, or null when the room isn't known yet (in which
     *         case the caller falls back to Range).
     */
    private static AABB currentRoomBox(Minecraft client, double margin) {
        DungeonLayout layout = DungeonLayout.current();
        if (layout != cachedLayout) {
            cachedLayout = layout;
            cachedRoom = -1;
            cachedRoomBox = null;
        }
        int room = layout.roomAtWorld(client.player.getX(), client.player.getZ());
        if (room < 0 || room >= layout.roomCount()) {
            return null;
        }
        if (room == cachedRoom && margin == cachedRoomMargin && cachedRoomBox != null) {
            return cachedRoomBox;
        }
        int[] tiles = layout.tiles(room);
        if (tiles == null || tiles.length == 0) {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (int tile : tiles) {
            BlockPos centre = DungeonLayout.cellCenter(tile);
            minX = Math.min(minX, centre.getX() - 16.0);
            minZ = Math.min(minZ, centre.getZ() - 16.0);
            maxX = Math.max(maxX, centre.getX() + 16.0);
            maxZ = Math.max(maxZ, centre.getZ() + 16.0);
        }
        cachedRoom = room;
        cachedRoomMargin = margin;
        // y 0..255, the same vertically-unbounded room box SecretWaypointsFeature's mimic check builds.
        cachedRoomBox = new AABB(minX - margin, 0.0, minZ - margin, maxX + margin, 255.0, maxZ + margin);
        return cachedRoomBox;
    }

    private static void invalidateLayout() {
        cachedLayout = null;
        cachedRoom = -1;
        cachedRoomBox = null;
    }

    /**
     * @param rangeSq      squared range limit, or {@code Double.MAX_VALUE} when the target ignores Range
     * @param roomBox      Room Scoped box the target must sit inside, or null for range-based selection
     * @param skipRaycast  true to accept without a line-of-sight check (through-walls modes only)
     */
    private static boolean accept(Minecraft client, Vec3 eye, Entity target, double rangeSq, AABB roomBox,
                                  boolean skipRaycast, boolean asGlow, boolean asFilled, boolean noDepth,
                                  Map<Integer, Box> foundBoxes, Map<Integer, Integer> foundGlows, int color) {
        int id = target.getId();
        if (target.isRemoved() || foundBoxes.containsKey(id) || foundGlows.containsKey(id)) {
            return false;
        }
        // Cheap rejections first, so the raycast below only ever runs for a target that could actually be shown.
        if (roomBox != null) {
            if (!roomBox.contains(target.getX(), target.getY(), target.getZ())) {
                return false;
            }
        } else if (target.distanceToSqr(client.player) > rangeSq) {
            return false;
        }
        if (!skipRaycast && !hasLineOfSight(client, eye, target)) {
            return false;
        }
        if (asGlow) {
            foundGlows.put(id, color);
        } else {
            foundBoxes.put(id, new Box(color, asFilled, noDepth));
        }
        return true;
    }

    private static void render(LevelRenderContext context) {
        Map<Integer, Box> current = boxTargets;
        Minecraft client = Minecraft.getInstance();
        if (current.isEmpty() || client.level == null) {
            return;
        }
        float lineWidth = MobEspConfig.getInstance().getLineWidth();
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (Map.Entry<Integer, Box> target : current.entrySet()) {
            Entity entity = client.level.getEntity(target.getKey());
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            Box box = target.getValue();
            Vec3 lerped = entity.getPosition(partialTick);
            AABB aabb = entity.getBoundingBox().move(lerped.subtract(entity.position()));
            if (box.filled()) {
                EspRenderer.filled(context, aabb, box.argb(), box.throughWalls());
            }
            EspRenderer.outline(context, aabb, box.argb(), lineWidth, box.throughWalls());
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

    /** A real block raycast from the camera to the target's eyes, refreshed at most every
     *  {@link #LOS_REFRESH_TICKS} ticks per entity and staggered by entity id (see {@link #losCache}). */
    private static boolean hasLineOfSight(Minecraft client, Vec3 eye, Entity target) {
        int id = target.getId();
        Long cached = losCache.get(id);
        if (cached != null && (tickCounter + id) % LOS_REFRESH_TICKS != 0) {
            return (cached & 1L) != 0L;
        }
        BlockHitResult result = client.level.clip(new ClipContext(
                eye, target.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
        boolean visible = result.getType() == HitResult.Type.MISS;
        losCache.put(id, (tickCounter << 1) | (visible ? 1L : 0L));
        return visible;
    }

    private static void clearTargets() {
        boxTargets = Map.of();
        glowTargets = Map.of();
    }
}
