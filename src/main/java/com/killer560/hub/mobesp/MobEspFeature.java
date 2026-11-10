package com.killer560.hub.mobesp;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.cheatutils.WitherEspFeature;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
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
 * Dungeon ESP - highlights four kinds of dungeon target, each with its own color ({@link MobEspConfig}):
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
 * through walls and never range-limited. Takes precedence over Wither Highlight when both are on. Its optional
 * Tracer draws a line from your eyes to each highlighted wither's hitbox center, in the wither color; Wither Highlight
 * has the same Tracer, drawn only to a wither that passed the line-of-sight test (2026-10-15).
 * </ul>
 * Styles: Outline Box / Filled Box ({@link EspRenderer}) or Glow - vanilla's entity outline, forced through
 * {@code Minecraft#shouldEntityAppearGlowing} and colored through {@code Entity#getTeamColor} by the
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

    /** Everything a box target needs; glow targets only need the color. */
    record Box(int argb, boolean filled, boolean throughWalls) {
    }

    /** Entity id -> box spec, rebuilt every client tick; read by the render callback. */
    private static volatile Map<Integer, Box> boxTargets = Map.of();
    /** Entity id -> ARGB for the targets whose style is Glow; read by the glow mixins. */
    private static volatile Map<Integer, Integer> glowTargets = Map.of();
    /** Entity id -> ARGB for Wither ESP targets that also get a tracer line. Kept separate from
     *  {@link #boxTargets} because a tracer has to be drawn for the Glow style too, and because nothing in the box
     *  map records which of its entries is a wither. */
    private static volatile Map<Integer, Integer> tracerTargets = Map.of();
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

    private MobEspFeature() {
    }

    public static void register() {
        EspRenderer.init();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("MobEspFeature", client -> tick()));
        GhostMobs.register();
        LevelRenderEvents.COLLECT_SUBMITS.register(MobEspFeature::render);
    }

    /** Testkit: entity id -> ARGB of every box target drawn this tick (glow targets included). */
    public static Map<Integer, Integer> drawnColors() {
        Map<Integer, Integer> out = new HashMap<>();
        boxTargets.forEach((id, b) -> out.put(id, b.argb()));
        out.putAll(glowTargets);
        return out;
    }

    /** Glow mixin hook: whether this entity should get the vanilla glow outline. */
    public static boolean shouldGlow(Entity entity) {
        GlowSet set = glowSet; // per entity per frame: a binary search over ints, no boxed id (fps pass 2026-10-19)
        return entity != null && set.ids.length != 0 && java.util.Arrays.binarySearch(set.ids, entity.getId()) >= 0;
    }

    /** Glow mixin hook: the ARGB outline color for this entity, or null if Dungeon ESP isn't glowing it. */
    public static Integer glowColor(Entity entity) {
        GlowSet set = glowSet;
        if (entity == null || set.ids.length == 0) return null;
        int i = java.util.Arrays.binarySearch(set.ids, entity.getId());
        return i >= 0 ? set.colors[i] : null;
    }

    /** {@link #glowTargets} as sorted entity ids and their colors (the map's own Integer objects), published with it.
     *  The two glow hooks run for every entity every frame while anything glows, and each did a boxed-key map lookup
     *  (entity ids are far above Integer's small cache, so a new Integer each time). Same answers as the map. */
    private record GlowSet(int[] ids, Integer[] colors) {
    }

    private static final GlowSet NO_GLOW = new GlowSet(new int[0], new Integer[0]);
    private static volatile GlowSet glowSet = NO_GLOW;

    private static GlowSet glowSetOf(Map<Integer, Integer> targets) {
        if (targets.isEmpty()) return NO_GLOW;
        int[] ids = new int[targets.size()];
        int n = 0;
        for (Integer id : targets.keySet()) {
            ids[n++] = id;
        }
        java.util.Arrays.sort(ids);
        Integer[] colors = new Integer[ids.length];
        for (int i = 0; i < ids.length; i++) {
            colors[i] = targets.get(ids[i]);
        }
        return new GlowSet(ids, colors);
    }

    private static void tick() {
        MobEspConfig cfg = MobEspConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        tickCounter++;
        if (client.level != lastLevel) {
            // Entity ids restart on a new server, so a dungeon -> dungeon warp would otherwise keep stale ids.
            lastLevel = client.level;
            standToMob.clear();
            KNOWN_STARRED.clear();
            PREDICTED_FELS.clear();
            STARRED_ROOMS.clear();
            MIMIC_CHESTS_SPENT.clear();
            NAMES.clear();
            chestTargets = List.of();
            losCache.clear();
            invalidateLayout();
            clearTargets();
        }
        if ((tickCounter & 63L) == 0L && !NAMES.isEmpty()) {
            NAMES.keySet().removeIf(Entity::isRemoved);
        }
        boolean hasWorld = client.level != null && client.player != null;
        // Cheapest gates first: a disabled feature must not reach DungeonState/LiveMap at all (FPS pass).
        boolean anyRaw = (cfg.getStarredMobsRaw() || cfg.getBatsRaw() || cfg.getBloodMobsRaw() || cfg.getWithersRaw()
                || cfg.getWitherHighlightRaw() || cfg.getFelSkullsRaw() || cfg.getMimicRaw())
                && com.killer560.hub.util.SkyblockGate.allows();
        if (!hasWorld || !anyRaw) {
            if (!boxTargets.isEmpty() || !glowTargets.isEmpty() || !tracerTargets.isEmpty()) {
                standToMob.clear();
                losCache.clear();
                clearTargets();
            }
            return;
        }
        boolean inDungeon = DungeonState.isInDungeon();
        boolean inBoss = LiveMapFeature.isInBoss();
        boolean wantStars = cfg.isStarredMobsEnabled() && inDungeon && !inBoss;
        boolean wantBats = cfg.isBatsEnabled() && inDungeon && !inBoss;
        boolean wantFels = cfg.isFelSkullsEnabled() && inDungeon && !inBoss;
        boolean wantMimic = cfg.isMimicEnabled() && inDungeon && !inBoss;
        // Fel Skulls are predicted from where the starred mobs are, so the starred stands are read for either.
        boolean readStars = wantStars || wantFels;
        // Blood Mobs (killer560, 2026-10-15): the Watcher's mobs, by identity (BloodCampFeature tracks them from the
        // moment they land), drawn like starred mobs - same style, same line-of-sight rule, through walls with ESP on -
        // but wherever they are: no Range or Room Scoped limit ("there's a chance they walk out of the room").
        Set<Integer> bloodIds = cfg.isBloodMobsEnabled() && inDungeon && !inBoss
                ? com.killer560.hub.bloodcamp.BloodCampFeature.bloodMobIds() : Set.of();
        boolean wantBlood = !bloodIds.isEmpty();
        boolean witherActive = (cfg.getWithersRaw() || cfg.getWitherHighlightRaw())
                && WitherEspFeature.isWitherBossActive(client);
        boolean wantWitherEsp = witherActive && cfg.isWithersEnabled();
        // Wither ESP wins when both are on - otherwise the same wither is drawn twice.
        boolean wantWitherHighlight = witherActive && !wantWitherEsp && cfg.isWitherHighlightEnabled();

        if (!readStars) {
            standToMob.clear();
            KNOWN_STARRED.clear();
        }
        if (!wantMimic && !chestTargets.isEmpty()) {
            chestTargets = List.of();
        }
        if (!readStars && !wantBats && !wantBlood && !wantWitherEsp && !wantWitherHighlight && !wantMimic) {
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

        // One Tracer setting for both wither modes (killer560, 2026-10-15): the ESP draws it to every wither it
        // highlights, through walls; the legit highlight only to one that passed the line-of-sight test in accept().
        boolean witherTracer = (wantWitherEsp || wantWitherHighlight) && cfg.isWitherTracerEnabled();

        // Reused every tick, and published below only when they differ from what is drawn (FPS pass, 2026-10-15: four
        // fresh collections and three copies per tick before, ~3 KB, whether or not anything had changed).
        Map<Integer, Box> foundBoxes = FOUND_BOXES;
        Map<Integer, Integer> foundGlows = FOUND_GLOWS;
        Map<Integer, Integer> foundTracers = FOUND_TRACERS;
        foundBoxes.clear();
        foundGlows.clear();
        foundTracers.clear();
        int stars = 0;
        int bats = 0;
        List<ArmorStand> felSkullsSeen = FEL_SKULLS_SEEN;
        List<Entity> mimicsSeen = MIMICS_SEEN;
        felSkullsSeen.clear();
        mimicsSeen.clear();
        int withers = 0;
        int bloodDrawn = 0;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || GhostMobs.hidden(entity)) continue;
            if (wantBlood && bloodIds.contains(entity.getId())) {
                // A Floor VII giant is 12 blocks tall: its eye sits in or above the Blood Room's ceiling while its body
                // stands in plain view (killer560, 2026-10-22), so without ESP a blood mob also counts as seen when the
                // middle of its box is.
                boolean seen = throughWalls || hasLineOfSight(client, eye, entity)
                        || hasLineOfSightTo(client, eye, entity, entity.getBoundingBox().getCenter(), BODY_LOS_KEY);
                if (seen && accept(client, eye, entity, Double.MAX_VALUE, null, true, glow, filled, throughWalls,
                        foundBoxes, foundGlows, cfg.getBloodColor())) {
                    bloodDrawn++;
                }
                continue;
            }
            if (witherActive && entity instanceof WitherBoss && WitherEspFeature.isRealWither(entity)) {
                // Both wither modes ignore Range and Room Scoped entirely (killer560: "the wither esp should work
                // no matter the range"). Wither ESP is also unconditionally through-walls.
                boolean espMode = wantWitherEsp;
                if (!espMode && !wantWitherHighlight) continue;
                // The same color in both modes (killer560, 2026-10-15: no separate wither boss color for ESP).
                int color = cfg.getWitherHighlightColor();
                boolean wallsOk = espMode;
                boolean asGlow = espMode ? glow : witherGlow;
                boolean asFilled = espMode ? filled : !witherGlow;
                if (accept(client, eye, entity, Double.MAX_VALUE, null, wallsOk, asGlow, asFilled, wallsOk,
                        foundBoxes, foundGlows, color)) {
                    withers++;
                    if (witherTracer) {
                        foundTracers.put(entity.getId(), color);
                    }
                }
            } else if (wantBats && entity instanceof Bat bat) {
                if (isSecretBat(bat) && accept(client, eye, bat, rangeSq, roomBox, throughWalls, glow, filled,
                        throughWalls, foundBoxes, foundGlows, cfg.getBatColor())) {
                    bats++;
                }
            } else if (readStars && entity instanceof ArmorStand stand) {
                // A dungeon room is full of unnamed decoration stands; only those can be Fel skulls.
                if (!stand.hasCustomName()) {
                    if (wantFels && isFelSkull(client, stand)) {
                        felSkullsSeen.add(stand);
                    }
                    continue;
                }
                String name = starredName(stand);
                if (name == null) continue;
                Entity mob = resolveMob(client, stand, name);
                if (mob != null && GhostMobs.hidden(mob)) continue;
                if (mob != null) {
                    rememberStarred(mob);
                }
                // Starred mobs ignore Range and Room Scoped, as QUOI's starred ESP does: every starred mob in the dungeon is
                // drawn until it dies, wherever it is (2026-10-22). Line of sight still applies.
                if (wantStars && mob != null && accept(client, eye, mob, Double.MAX_VALUE, null, throughWalls, glow, filled,
                        throughWalls, foundBoxes, foundGlows, cfg.getStarredColor())) {
                    stars++;
                }
            } else if (readStars && entity instanceof Player p && isMiniboss(client, p)) {
                rememberStarred(p);
                if (wantStars && accept(client, eye, p, Double.MAX_VALUE, null, throughWalls, glow, filled, throughWalls,
                        foundBoxes, foundGlows, cfg.getStarredColor())) {
                    stars++;
                }
            } else if (wantMimic && entity instanceof net.minecraft.world.entity.monster.zombie.Zombie z && z.isBaby()
                    && z.isAlive() && !z.isRemoved()) {
                mimicsSeen.add(z);
            }
        }
        // Keep only stands still on the client under a starred name (a set of this tick's stands cost ~1.4 KB a tick).
        if (!standToMob.isEmpty()) {
            standToMob.keySet().removeIf(MobEspFeature::notStarredStand);
        }
        if (readStars) {
            // Starred mobs whose name-tag stand the client no longer has (killer560, 2026-11-01: "it seems to be missing
            // mobs that are slightly far away in rooms. Like 2 ish room tiles away"). The "✯ ... ❤" name lives on an armor
            // stand, and a server keeps misc entities (stands) loaded for a shorter range than mobs (Spigot's defaults: 32
            // and 48 blocks), so a starred mob 1.5-2 room tiles away is still on the client while its star is not, and was
            // not drawn. A mob once seen starred stays starred for as long as it lives.
            stars += rememberedStarred(client, eye, wantStars, throughWalls, glow, filled, foundBoxes, foundGlows,
                    cfg.getStarredColor());
        }
        if (wantFels) {
            fels(client, eye, felSkullsSeen, throughWalls, glow, filled, foundBoxes, foundGlows, cfg.getFelColor());
        } else if (!PREDICTED_FELS.isEmpty()) {
            PREDICTED_FELS.clear();
        }
        if (wantMimic) {
            mimics(client, eye, mimicsSeen, throughWalls, glow, filled, foundBoxes, foundGlows, cfg.getMimicColor());
        }
        // A target that FAILED the raycast must keep its cached "not visible" answer, or the expensive case would
        // never be cached at all - so prune by age instead of by what got accepted this tick.
        if ((tickCounter & 63L) == 0L) {
            losCache.values().removeIf(v -> tickCounter - (v >>> 1) > 100L);
        }

        if (!foundBoxes.equals(boxTargets)) {
            boxTargets = Map.copyOf(foundBoxes);
        }
        if (!foundGlows.equals(glowTargets)) {
            Map<Integer, Integer> glows = Map.copyOf(foundGlows);
            glowSet = glowSetOf(glows);
            glowTargets = glows;
        }
        if (!foundTracers.equals(tracerTargets)) {
            tracerTargets = Map.copyOf(foundTracers);
        }
    }

    /** Client-thread scratch for {@link #tick}. */
    private static final List<ArmorStand> FEL_SKULLS_SEEN = new java.util.ArrayList<>();
    private static final List<Entity> MIMICS_SEEN = new java.util.ArrayList<>();
    private static final Map<Integer, Box> FOUND_BOXES = new LinkedHashMap<>();
    private static final Map<Integer, Integer> FOUND_GLOWS = new LinkedHashMap<>();
    private static final Map<Integer, Integer> FOUND_TRACERS = new LinkedHashMap<>();

    /**
     * killer560, 2026-09-20: "instead of being range based it should be every starred mob in the room I am currently
     * in. Then if a mob is slightly outside of the room it gets that as well. Anything within about 5-10 blocks."
     * <p>
     * Uses the live scanned dungeon, not a guess: {@link DungeonLayout#current()} (the shared per-tick snapshot),
     * {@code roomAtWorld} for the player's room and the room's own even tiles for its footprint - each tile is
     * 32x32 centered on {@link DungeonLayout#cellCenter}, which reproduces {@code LiveMapFeature.roomWorldBounds}
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
        if (room < 0 || room >= layout.roomCount()) return null;
        if (room == cachedRoom && margin == cachedRoomMargin && cachedRoomBox != null) return cachedRoomBox;
        int[] tiles = layout.tiles(room);
        if (tiles == null || tiles.length == 0) return null;
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
        Integer id = target.getId(); // boxed once: three autoboxes of an id over 127 were three allocations
        if (target.isRemoved() || foundBoxes.containsKey(id) || foundGlows.containsKey(id)) return false;
        // Cheap rejections first, so the raycast below only ever runs for a target that could actually be shown.
        if (roomBox != null) {
            if (!roomBox.contains(target.getX(), target.getY(), target.getZ())) return false;
        } else if (target.distanceToSqr(client.player) > rangeSq) {
            return false;
        }
        if (!skipRaycast && !hasLineOfSight(client, eye, target)) return false;
        if (asGlow) {
            foundGlows.put(id, color);
        } else {
            Box box = lastBox;
            if (box == null || box.argb() != color || box.filled() != asFilled || box.throughWalls() != noDepth) {
                box = lastBox = new Box(color, asFilled, noDepth);
            }
            foundBoxes.put(id, box);
        }
        return true;
    }

    /** The last box spec handed out: every target of one kind shares one, so a tick allocates none. Client thread. */
    private static Box lastBox;

    private static void render(LevelRenderContext context) {
        Map<Integer, Box> current = boxTargets;
        Map<Integer, Integer> tracers = tracerTargets;
        List<ChestBox> chests = chestTargets;
        Minecraft client = Minecraft.getInstance();
        if ((current.isEmpty() && tracers.isEmpty() && chests.isEmpty()) || client.level == null || client.player == null) return;
        MobEspConfig renderCfg = MobEspConfig.getInstance();
        float lineWidth = renderCfg.getLineWidth();
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (!tracers.isEmpty()) {
            // killer560, 2026-09-27: "the wwither tracer is bugged and kinda works. It looks like it is being
            // drawn to my players head instead of their crosshair." It started at
            // client.player.getEyePosition(partialTick) - still the PLAYER, which lags a tick behind the
            // camera the same way even with partialTick interpolation applied to it. WorldRenderUtils.tracerOrigin(context)
            // reads the camera directly instead, same fix as doorkeys.DoorKeysFeature's tracer.
            Vec3 eyes = WorldRenderUtils.tracerOrigin(context);
            float thickness = renderCfg.getWitherTracerThickness();
            for (Map.Entry<Integer, Integer> target : tracers.entrySet()) {
                Entity entity = client.level.getEntity(target.getKey());
                if (entity == null || entity.isRemoved()) continue;
                // Aim at the hitbox center rather than the feet: a wither's box is 3.5 blocks tall and a line to
                // its origin reads as pointing at the floor underneath it.
                Vec3 lerped = entity.getPosition(partialTick);
                Vec3 centre = entity.getBoundingBox().move(lerped.subtract(entity.position())).getCenter();
                EspRenderer.tracer(context, eyes, centre, target.getValue(), thickness, true);
            }
        }
        // Every box into four reusable lists (fill / outline x depth-tested / through walls), each drawn with ONE call:
        // per box this used to be a fill call and an outline call, each with its own color array and lambda, plus an
        // AABB and two Vec3 for the interpolated box (FPS pass, 2026-10-15). Same boxes, same colors, same types.
        FILL_DEPTH.clear();
        FILL_WALLS.clear();
        LINE_DEPTH.clear();
        LINE_WALLS.clear();
        for (Map.Entry<Integer, Box> target : current.entrySet()) {
            Entity entity = client.level.getEntity(target.getKey());
            if (entity == null || entity.isRemoved()) continue;
            Box box = target.getValue();
            // entity.getBoundingBox().move(entity.getPosition(partialTick).subtract(entity.position())), without the
            // three allocations.
            double dx = net.minecraft.util.Mth.lerp(partialTick, entity.xo, entity.getX()) - entity.getX();
            double dy = net.minecraft.util.Mth.lerp(partialTick, entity.yo, entity.getY()) - entity.getY();
            double dz = net.minecraft.util.Mth.lerp(partialTick, entity.zo, entity.getZ()) - entity.getZ();
            AABB bb = entity.getBoundingBox();
            int argb = box.argb();
            float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
            float a = EspRenderer.alpha(argb);
            if (box.filled()) {
                (box.throughWalls() ? FILL_WALLS : FILL_DEPTH).add(bb.minX + dx, bb.minY + dy, bb.minZ + dz,
                        bb.maxX + dx, bb.maxY + dy, bb.maxZ + dz, r, g, b, a * 0.35f);
            }
            (box.throughWalls() ? LINE_WALLS : LINE_DEPTH).add(bb.minX + dx, bb.minY + dy, bb.minZ + dz,
                    bb.maxX + dx, bb.maxY + dy, bb.maxZ + dz, r, g, b, a);
        }
        for (ChestBox c : chests) {
            AABB bb = c.box();
            int argb = c.spec().argb();
            float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
            float a = EspRenderer.alpha(argb);
            if (c.spec().filled()) {
                (c.spec().throughWalls() ? FILL_WALLS : FILL_DEPTH).add(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ,
                        r, g, b, a * 0.35f);
            }
            (c.spec().throughWalls() ? LINE_WALLS : LINE_DEPTH).add(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ,
                    r, g, b, a);
        }
        FILL_DEPTH.drawFilled(context, EspRenderer.fillType(false));
        FILL_WALLS.drawFilled(context, EspRenderer.fillType(true));
        LINE_DEPTH.drawOutlines(context, EspRenderer.lineType(false), lineWidth);
        LINE_WALLS.drawOutlines(context, EspRenderer.lineType(true), lineWidth);
    }

    /** Render-thread scratch for {@link #render}, reused every frame. */
    private static final com.killer560.hub.util.BoxBatch FILL_DEPTH = new com.killer560.hub.util.BoxBatch();
    private static final com.killer560.hub.util.BoxBatch FILL_WALLS = new com.killer560.hub.util.BoxBatch();
    private static final com.killer560.hub.util.BoxBatch LINE_DEPTH = new com.killer560.hub.util.BoxBatch();
    private static final com.killer560.hub.util.BoxBatch LINE_WALLS = new com.killer560.hub.util.BoxBatch();

    /**
     * Every starred mob the client can see right now, resolved the way the ESP resolves them (the "star ... heart" stand
     * down to its mob, plus the named minibosses), whatever the ESP's own settings, range or room scope say. Auto Clear's
     * target list (killer560, 2026-10-06: "kills starred mobs only ... reuse" the ESP's detection). Client thread.
     */
    public static List<Entity> starredMobs(Minecraft client) { return starredMobs(client, null); }

    /**
     * {@link #starredMobs(Minecraft)}, also filling {@code tags} (when not null) with each mob's name tag as the client
     * reads it - the "✯ Name 337.5k❤" stand's text, whose health Auto Clear's one-tap estimate reads (killer560,
     * 2026-10-20). A miniboss found by its player name and no stand gets no entry.
     */
    public static List<Entity> starredMobs(Minecraft client, Map<Integer, String> tags) {
        List<Entity> out = new java.util.ArrayList<>();
        if (client.level == null || client.player == null) return out;
        Set<Integer> seen = new HashSet<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player) continue;
            Entity mob = null;
            String tag = null;
            if (entity instanceof ArmorStand stand && stand.hasCustomName()) {
                String name = starredName(stand);
                if (name != null) {
                    mob = resolveMob(client, stand, name);
                    tag = name;
                }
            } else if (entity instanceof Player p && isMiniboss(client, p)) {
                mob = p;
            }
            if (mob != null && mob.isAlive() && !mob.isRemoved() && !GhostMobs.hidden(mob) && seen.add(mob.getId())) {
                out.add(mob);
            }
            if (mob != null && tag != null && tags != null) {
                tags.put(mob.getId(), tag);
            }
        }
        return out;
    }

    /**
     * Each named stand's text, read again only when its custom-name Component is a different object: the client keeps the
     * Component a data packet set until the next one replaces it, so a name change is seen the tick it lands. getString()
     * built a String for every named stand on every tick (and in every {@link #starredMobs} call), about 22 KB a call in a
     * dungeon room (mobespscan-1105). Pruned of removed stands every 64 ticks and cleared on a world change. Client thread.
     */
    private static final Map<ArmorStand, NameMemo> NAMES = new java.util.IdentityHashMap<>();
    private record NameMemo(net.minecraft.network.chat.Component source, String starred) {
    }
    private static int nameReads;

    /** The stand's "✯ ... ❤" name, or null when its name is not a starred tag (or it has none). */
    private static String starredName(ArmorStand stand) {
        net.minecraft.network.chat.Component c = stand.getCustomName();
        if (c == null) return null;
        NameMemo m = NAMES.get(stand);
        if (m == null || m.source() != c) {
            String name = c.getString();
            nameReads++;
            m = new NameMemo(c, name.contains(STAR) && name.contains(HEART) ? name : null);
            NAMES.put(stand, m);
        }
        return m.starred();
    }

    private static boolean notStarredStand(Integer standId) {
        return !(Minecraft.getInstance().level.getEntity(standId) instanceof ArmorStand s) || s.isRemoved()
                || starredName(s) == null;
    }

    /** Testkit: how many stand names were read (built into a String) since the jar loaded. */
    public static int nameReadsForTest() { return nameReads; }

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
            if (mob != null && !mob.isRemoved()) return mob;
            standToMob.remove(stand.getId());
        }

        int offset = name.toUpperCase(Locale.ROOT).contains("WITHERMANCER") ? 3 : 1;
        Entity byId = client.level.getEntity(stand.getId() - offset);
        Entity mob = null;
        if (byId != null && !(byId instanceof ArmorStand) && isValidMob(client, byId)) {
            mob = byId;
        } else {
            List<Entity> below = client.level.getEntities(stand, stand.getBoundingBox().move(0.0, -1.0, 0.0),
                    e -> !(e instanceof ArmorStand) && !(e instanceof ExperienceOrb));
            for (Iterator<Entity> it = below.iterator(); it.hasNext() && mob == null; ) {
                Entity e = it.next();
                if (isValidMob(client, e) && !standToMob.containsValue(e.getId())) {
                    mob = e;
                }
            }
        }
        if (mob != null) {
            standToMob.put(stand.getId(), mob.getId());
        }
        return mob;
    }

    /** Only real living, non-bat, non-wither entities (never a dropped item, projectile or you) count as a star mob. */
    private static boolean isValidMob(Minecraft client, Entity e) {
        if (!(e instanceof LivingEntity) || e instanceof Bat) return false;
        if (e == client.player || e instanceof WitherBoss || e instanceof AbstractArrow) return false;
        if (e instanceof Player p) return !p.isInvisible() && p.getUUID().version() == 2;
        return true;
    }

    /** A real block raycast from the camera to the target's eyes, refreshed at most every
     *  {@link #LOS_REFRESH_TICKS} ticks per entity and staggered by entity id (see {@link #losCache}). */
    private static boolean hasLineOfSight(Minecraft client, Vec3 eye, Entity target) {
        return hasLineOfSightTo(client, eye, target, null, 0);
    }

    /** Cache key salt for the ray to a blood mob's box center: entity ids are positive, so the salted key never meets one. */
    private static final int BODY_LOS_KEY = Integer.MIN_VALUE;

    /** {@link #hasLineOfSight} to {@code point} (null: the target's eyes, read only when the cache misses), cached under
     *  {@code target.getId() ^ keySalt}. */
    private static boolean hasLineOfSightTo(Minecraft client, Vec3 eye, Entity target, Vec3 point, int keySalt) {
        Integer id = target.getId() ^ keySalt;
        Long cached = losCache.get(id);
        if (cached != null && (tickCounter + target.getId()) % LOS_REFRESH_TICKS != 0) return (cached & 1L) != 0L;
        if (point == null) point = target.getEyePosition();
        BlockHitResult result = client.level.clip(new ClipContext(
                eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
        boolean visible = result.getType() == HitResult.Type.MISS;
        losCache.put(id, (tickCounter << 1) | (visible ? 1L : 0L));
        return visible;
    }

    // ---- Starred memory, Fel Skulls, Mimic (doc-blood-1101) ---------------------------------------------------------

    /** Mob ids seen with a starred name tag (or a named miniboss), kept while they live; cleared with the level. */
    private static final Set<Integer> KNOWN_STARRED = new HashSet<>();
    /** Fel skull stand ids predicted starred: seen within {@link #FEL_PACK_RADIUS} of a starred mob. */
    private static final Set<Integer> PREDICTED_FELS = new HashSet<>();
    /** Rooms (see {@link #roomKey}) a starred mob or a predicted Fel was ever seen in. */
    private static final Set<Long> STARRED_ROOMS = new HashSet<>();
    /** Trapped chests whose mimic has come out: no longer highlighted. */
    private static final Set<Long> MIMIC_CHESTS_SPENT = new HashSet<>();
    /** Testkit hook: rooms off the scanned map (no layout) counted as cleared. */
    private static final Set<Long> TEST_CLEARED_ROOMS = new HashSet<>();
    /** How close to a starred mob a Fel skull is part of its pack: "starred mobs will spawn by each other". */
    static final double FEL_PACK_RADIUS = 6.0;

    private record ChestBox(AABB box, Box spec) {
    }

    private static volatile List<ChestBox> chestTargets = List.of();
    private static long lastChestScanTick = Long.MIN_VALUE / 2;
    /** Testkit: what the Fel and Mimic passes did last tick. */
    private static final Set<Integer> LAST_FELS_DRAWN = new HashSet<>();
    private static int lastOtherFelsDrawn;

    private static void rememberStarred(Entity mob) {
        if (KNOWN_STARRED.add(mob.getId())) {
            Vec3 p = mob.position();
            STARRED_ROOMS.add(roomKey(p.x, p.z));
        }
    }

    /** Draws the remembered starred mobs this tick's stands did not already draw; forgets the dead. */
    private static int rememberedStarred(Minecraft client, Vec3 eye, boolean draw, boolean throughWalls, boolean glow,
                                         boolean filled, Map<Integer, Box> foundBoxes, Map<Integer, Integer> foundGlows,
                                         int color) {
        int drawn = 0;
        Iterator<Integer> it = KNOWN_STARRED.iterator();
        while (it.hasNext()) {
            Entity mob = client.level.getEntity(it.next());
            if (mob == null || mob.isRemoved() || !mob.isAlive()) {
                it.remove();
                continue;
            }
            if (draw && !GhostMobs.hidden(mob) && accept(client, eye, mob, Double.MAX_VALUE, null, throughWalls, glow, filled, throughWalls,
                    foundBoxes, foundGlows, color)) {
                drawn++;
                rememberedDrawn++;
            }
        }
        return drawn;
    }

    private static int rememberedDrawn;

    /**
     * A dormant Fel: an armor stand with no name wearing a skull, the "upside-down Enderman head placed on the ground"
     * (hypixelskyblock.minecraft.wiki, Fels) that wakes into the Fel when a player comes near - the same thing the mod's
     * own dungeon sim places (roomsim/SimMobs#spawnFel: an invisible stand wearing a skull). The Blood Room's flying
     * skulls are not Fels.
     */
    static boolean isFelSkull(Minecraft client, ArmorStand stand) {
        if (stand.isRemoved() || stand.isPassenger()) return false;
        net.minecraft.world.item.ItemStack head = stand.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
        // A wither skeleton skull too (simmobs2-1101): his Doc has Fels as "black fel skulls", and that is what the sim places;
        // with only the player head and the plain skull, Fel Skulls drew none of the sim's sleeping Fels.
        if (head.isEmpty() || !(head.is(net.minecraft.world.item.Items.PLAYER_HEAD)
                || head.is(net.minecraft.world.item.Items.SKELETON_SKULL)
                || head.is(net.minecraft.world.item.Items.WITHER_SKELETON_SKULL))) {
            return false;
        }
        if (!stand.isInvisible()) return false;
        return !com.killer560.hub.bloodcamp.BloodCampFeature.inBloodRoomTile(stand.position());
    }

    /**
     * killer560, 2026-11-01: "Make that a toggle to highlight fels first of all, then only have it highlight fel skulls that
     * were by other starred mobs not random ones all about. Because starred mobs will spawn by each other ... Also if you
     * kill all starred mobs in a room and all the predicted fel skulls have it highlight other skulls if that toggle for
     * highlight fel skulls is on. Then once the room is clear it should no longer highlight them anymore."
     * <ol>
     *   <li>A Fel skull within {@link #FEL_PACK_RADIUS} of a living starred mob is predicted starred, and stays so.</li>
     *   <li>In a room where starred mobs (or predicted Fels) were seen and none is left alive, every other Fel skull is
     *   drawn too: the room is not cleared yet, so the missing star is in one of them.</li>
     *   <li>Once the map shows the room cleared, none of its skulls is drawn.</li>
     * </ol>
     */
    private static void fels(Minecraft client, Vec3 eye, List<ArmorStand> skulls, boolean throughWalls, boolean glow,
                             boolean filled, Map<Integer, Box> foundBoxes, Map<Integer, Integer> foundGlows, int color) {
        LAST_FELS_DRAWN.clear();
        lastOtherFelsDrawn = 0;
        List<Entity> starred = FEL_STARRED;
        starred.clear();
        for (Integer id : KNOWN_STARRED) {
            Entity e = client.level.getEntity(id);
            if (e != null && e.isAlive() && !e.isRemoved()) {
                starred.add(e);
            }
        }
        Set<Integer> liveSkulls = FEL_LIVE;
        liveSkulls.clear();
        for (ArmorStand s : skulls) {
            liveSkulls.add(s.getId());
            if (!PREDICTED_FELS.contains(s.getId())) {
                for (Entity m : starred) {
                    if (m.distanceToSqr(s) <= FEL_PACK_RADIUS * FEL_PACK_RADIUS) {
                        PREDICTED_FELS.add(s.getId());
                        STARRED_ROOMS.add(roomKey(s.getX(), s.getZ()));
                        LOGGER.info("[DungeonESP] Fel skull #{} predicted starred: {} blocks from starred {} #{}", s.getId(),
                                String.format(Locale.ROOT, "%.1f", Math.sqrt(m.distanceToSqr(s))), m.getType().toShortString(),
                                m.getId());
                        break;
                    }
                }
            }
        }
        PREDICTED_FELS.retainAll(liveSkulls);
        // Rooms still holding a living starred mob or a predicted Fel.
        Set<Long> busy = FEL_BUSY;
        busy.clear();
        for (Entity m : starred) {
            busy.add(roomKey(m.getX(), m.getZ()));
        }
        for (ArmorStand s : skulls) {
            if (PREDICTED_FELS.contains(s.getId())) {
                busy.add(roomKey(s.getX(), s.getZ()));
            }
        }
        for (ArmorStand s : skulls) {
            long room = roomKey(s.getX(), s.getZ());
            boolean predicted = PREDICTED_FELS.contains(s.getId());
            boolean others = !predicted && STARRED_ROOMS.contains(room) && !busy.contains(room) && !roomCleared(room);
            if (predicted && roomCleared(room)) continue;
            if ((predicted || others) && accept(client, eye, s, Double.MAX_VALUE, null, throughWalls, glow, filled,
                    throughWalls, foundBoxes, foundGlows, color)) {
                LAST_FELS_DRAWN.add(s.getId());
                if (others) {
                    lastOtherFelsDrawn++;
                }
            }
        }
    }

    /** Client-thread scratch for {@link #fels}. */
    private static final List<Entity> FEL_STARRED = new java.util.ArrayList<>();
    private static final Set<Integer> FEL_LIVE = new HashSet<>();
    private static final Set<Long> FEL_BUSY = new HashSet<>();

    /** A room id from the scanned map, or (off the map) a key for the 32x32 dungeon cell. */
    static long roomKey(double x, double z) {
        int room = DungeonLayout.current().roomAtWorld(x, z);
        if (room >= 0) return room;
        long tx = Math.round((x - LiveMapFeature.START_X) / 32.0);
        long tz = Math.round((z - LiveMapFeature.START_Z) / 32.0);
        return -1_000_000L - ((tx + 500L) * 2000L + (tz + 500L));
    }

    private static boolean roomCleared(long key) {
        if (TEST_CLEARED_ROOMS.contains(key) || key < 0) return TEST_CLEARED_ROOMS.contains(key);
        DungeonLayout layout = DungeonLayout.current();
        int[] tiles = key < layout.roomCount() ? layout.tiles((int) key) : null;
        if (tiles == null) return false;
        for (int tile : tiles) {
            if (LiveMapFeature.isRoomCleared(tile)) return true;
        }
        return false;
    }

    /**
     * Mimic (killer560, 2026-11-01: "Add an option for mimic highlight as well"): the Mimic is a baby zombie (the test
     * Auto Routes' Kill Mimic already uses, autoroutes/AwaitEvents), and before it comes out, the trapped chest it hides in
     * (dungeon secret chests are plain chests; the mimic's is the trapped one). A chest stops being drawn once a mimic has
     * been seen beside it.
     */
    private static void mimics(Minecraft client, Vec3 eye, List<Entity> mimics, boolean throughWalls, boolean glow,
                               boolean filled, Map<Integer, Box> foundBoxes, Map<Integer, Integer> foundGlows, int color) {
        lastMimicsDrawn = 0;
        for (Entity m : mimics) {
            if (accept(client, eye, m, Double.MAX_VALUE, null, throughWalls, glow, filled, throughWalls, foundBoxes,
                    foundGlows, color)) {
                lastMimicsDrawn++;
            }
        }
        long now = client.level.getGameTime();
        if (now - lastChestScanTick < 10 && now >= lastChestScanTick) return;
        lastChestScanTick = now;
        List<ChestBox> out = new java.util.ArrayList<>();
        int r = 48;
        BlockPos at = client.player.blockPosition();
        for (int cx = (at.getX() - r) >> 4; cx <= (at.getX() + r) >> 4; cx++) {
            for (int cz = (at.getZ() - r) >> 4; cz <= (at.getZ() + r) >> 4; cz++) {
                net.minecraft.world.level.chunk.ChunkAccess chunk;
                try {
                    chunk = client.level.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                } catch (RuntimeException e) {
                    continue;
                }
                if (!(chunk instanceof net.minecraft.world.level.chunk.LevelChunk lc)) continue;
                for (BlockPos p : lc.getBlockEntities().keySet()) {
                    if (!client.level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST)) continue;
                    if (MIMIC_CHESTS_SPENT.contains(p.asLong())) continue;
                    Vec3 c = Vec3.atCenterOf(p);
                    boolean spent = false;
                    for (Entity m : mimics) {
                        if (m.position().distanceToSqr(c) <= 64.0) {
                            spent = true;
                        }
                    }
                    if (spent) {
                        MIMIC_CHESTS_SPENT.add(p.asLong());
                        LOGGER.info("[DungeonESP] mimic out of the trapped chest at {} {} {}: chest no longer drawn",
                                p.getX(), p.getY(), p.getZ());
                        continue;
                    }
                    boolean seen = throughWalls || clearTo(client, eye, c);
                    if (seen) {
                        out.add(new ChestBox(new AABB(p.getX() + 0.0625, p.getY(), p.getZ() + 0.0625, p.getX() + 0.9375,
                                p.getY() + 0.875, p.getZ() + 0.9375), new Box(color, filled || glow, throughWalls)));
                    }
                }
            }
        }
        chestTargets = List.copyOf(out);
    }

    private static int lastMimicsDrawn;

    private static boolean clearTo(Minecraft client, Vec3 from, Vec3 to) {
        BlockHitResult hit = client.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                client.player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(BlockPos.containing(to));
    }


    public static int lastOtherFelsDrawn() { return lastOtherFelsDrawn; }

    public static int rememberedDrawn() { return rememberedDrawn; }

    public static int lastMimicsDrawn() { return lastMimicsDrawn; }

    public static int chestTargetCount() { return chestTargets.size(); }

    public static void setTestRoomCleared(double x, double z, boolean cleared) {
        long key = roomKey(x, z);
        if (cleared) {
            TEST_CLEARED_ROOMS.add(key);
        } else {
            TEST_CLEARED_ROOMS.remove(key);
        }
    }

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-mobesp");

    private static void clearTargets() {
        chestTargets = List.of();
        boxTargets = Map.of();
        glowSet = NO_GLOW;
        glowTargets = Map.of();
        tracerTargets = Map.of();
    }
}
