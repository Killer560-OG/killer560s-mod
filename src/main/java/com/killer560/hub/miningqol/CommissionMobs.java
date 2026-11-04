package com.killer560.hub.miningqol;

import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Commission Mobs (killer560, 2026-11-03: "also it should highlight commission mobs as an option as well"): an outline box
 * on every mob that counts for one of his OPEN commissions, read from the tab list's Commissions widget
 * ({@link MiningState}). "Goblin Slayer: 40%" boxes the goblins, "Glacite Walker Slayer" the Glacite Walkers, "Star Sentry
 * Puncher" the Star Sentries; a DONE commission boxes nothing. Which mobs count comes from hypixelskyblock.minecraft.wiki
 * (Commissions); a "X Slayer" / "X Puncher" not in the table boxes mobs named X.
 *
 * <p>The mob is found by its name tag, which Hypixel puts either on the mob or on an armor stand riding just above it
 * ("[Lv25] Goblin 1,500/1,500❤"); a stand is mapped to its mob the way Dungeon ESP maps a starred stand (the entity
 * with the id before it, else the nearest living entity under it). A name tag is server data on a non-player entity, so
 * another player's chat cannot forge one; real players (v4 UUIDs) are never boxed.
 *
 * <p>No range (killer560, 2026-11-03: "it feels like it has a very small range"): every loaded mob is looked at, and a mob
 * once identified is remembered by entity id for as long as it is loaded and alive, so it stays highlighted after the
 * server stops sending its name-tag stand at range (Hypixel tracks the stands nearer than the mobs; Starred Mob Hitboxes
 * keeps a starred mob the same way). A remembered mob is dropped when its commission no longer wants it, it dies or
 * unloads, or the world changes. No mob is matched by entity type alone: Goblins and Treasure Hoarders are player-model
 * NPCs like every other NPC, and SkyHanni's own mob detection ({@code MobFilter}) also names these mobs only through their
 * stands, so no type rule here is unambiguous.
 *
 * <p>Style (Commission Mob Style): Outline, Fill, Fill + Outline (the shared {@code WorldRenderUtils} box passes), or Glow
 * (vanilla's glow outline, through the cheatutils WitherGlow mixins Dungeon ESP's Glow style uses).
 *
 * <p>Legit (both builds): only a mob in plain sight (a block raycast from the eyes) is highlighted, in every style, and
 * boxes are depth-tested, like Starred Mob Hitboxes. Through Walls (cheat build only) skips the raycast and draws boxes with
 * no depth test. Draw-only: nothing is clicked or sent.
 */
public final class CommissionMobs {

    /** Commission name -> the mob names that count for it (hypixelskyblock.minecraft.wiki, Commissions; Goblin page). */
    private static final Map<String, List<String>> MOBS = Map.ofEntries(
            Map.entry("Goblin Slayer", List.of("Goblin", "Weakling", "Knifethrower", "Fireslinger")),
            Map.entry("Goblin Raid Slayer", List.of("Goblin", "Weakling", "Knifethrower", "Fireslinger")),
            Map.entry("Golden Goblin Slayer", List.of("Golden Goblin", "Diamond Goblin")),
            Map.entry("Elusive Goblin Slayer", List.of("Golden Goblin", "Diamond Goblin")),
            Map.entry("Glacite Walker Slayer", List.of("Glacite Walker", "Ice Walker")),
            Map.entry("Ice Walker Slayer", List.of("Ice Walker", "Glacite Walker")),
            Map.entry("Mines Slayer", List.of("Goblin", "Weakling", "Knifethrower", "Fireslinger", "Glacite Walker",
                    "Ice Walker")),
            Map.entry("Treasure Hoarder Puncher", List.of("Treasure Hoarder")),
            Map.entry("Star Sentry Puncher", List.of("Star Sentry")),
            Map.entry("Automaton Slayer", List.of("Automaton")),
            Map.entry("Sludge Slayer", List.of("Sludge")),
            Map.entry("Team Treasurite Member Slayer", List.of("Team Treasurite")),
            Map.entry("Yog Slayer", List.of("Yog")),
            Map.entry("Thyst Slayer", List.of("Thyst")),
            Map.entry("Boss Corleone Slayer", List.of("Boss Corleone")),
            Map.entry("Corleone Slayer", List.of("Boss Corleone")),
            Map.entry("Maniac Slayer", List.of("Maniac")));

    private static final int REFRESH_TICKS = 4;
    /** How many identified mobs are remembered at most (far more than a mining island ever loads). */
    private static final int MAX_KNOWN = 512;

    private static int ticks = 0;
    private static String patternKey = "";
    private static Pattern pattern = null;
    private static volatile List<String> wanted = List.of();
    private static volatile int[] targets = new int[0];
    private static volatile boolean throughWalls = false;
    private static volatile MiningQolConfig.CommissionMobStyle style = MiningQolConfig.CommissionMobStyle.OUTLINE;
    /** Glow hooks: the glowing ids, sorted, and their ARGB color. */
    private static volatile int[] glowIds = new int[0];
    private static volatile int glowArgb = 0;
    /** Identified mobs: entity id -> the wanted mob name its tag matched. */
    private static final java.util.LinkedHashMap<Integer, String> KNOWN = new java.util.LinkedHashMap<>();
    private static Object knownLevel = null;
    private static int frames = 0;

    private CommissionMobs() {
    }

    /** The mob names that count for {@code coms}' open commissions, in order, no repeats (also the testkit's hook). */
    public static List<String> mobsFor(List<MiningState.Commission> coms) {
        Set<String> out = new LinkedHashSet<>();
        for (MiningState.Commission c : coms) {
            if (c.done()) {
                continue;
            }
            List<String> known = MOBS.get(c.name());
            if (known != null) {
                out.addAll(known);
            } else if (c.name().endsWith(" Slayer") || c.name().endsWith(" Puncher")) {
                String mob = c.name().substring(0, c.name().lastIndexOf(' ')).trim();
                if (!mob.isEmpty()) {
                    out.add(mob);
                }
            }
        }
        return List.copyOf(out);
    }

    private static Pattern compile(List<String> mobs) {
        StringBuilder sb = new StringBuilder("(?:^|[\\s\\]])(?:");
        for (int i = 0; i < mobs.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(Pattern.quote(mobs.get(i)));
        }
        return Pattern.compile(sb.append(")(?=$|[\\s\\d])").toString());
    }

    static void tick(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        if (!cfg.isCommissionMobs() || !cfg.isCommissionHud() || !MiningState.commissionsKnown() || client.level == null
                || client.player == null) {
            clear();
            return;
        }
        if (ticks++ % REFRESH_TICKS != 0) {
            return;
        }
        List<String> mobs = mobsFor(MiningState.commissions());
        wanted = mobs;
        if (mobs.isEmpty()) {
            // Nothing wanted: forget the remembered mobs and stop any glow too.
            targets = new int[0];
            glowIds = new int[0];
            KNOWN.clear();
            return;
        }
        String key = String.join("|", mobs);
        if (!key.equals(patternKey)) {
            patternKey = key;
            pattern = compile(mobs);
        }
        boolean walls = cfg.isCommissionMobsEsp();
        if (knownLevel != client.level) {
            KNOWN.clear();
            knownLevel = client.level;
        }
        // Identify: every loaded name tag (no range), remembered by the mob's id.
        for (Entity e : client.level.entitiesForRendering()) {
            if (e == client.player || !e.hasCustomName()) {
                continue;
            }
            String plain = com.killer560.hub.util.ChatObserver.stripCodes(e.getCustomName().getString());
            java.util.regex.Matcher m = pattern.matcher(plain);
            if (!m.find()) {
                continue;
            }
            LivingEntity mob = e instanceof ArmorStand stand ? mobUnder(client, stand) : e instanceof LivingEntity l ? l : null;
            if (mob == null || !mob.isAlive() || mob.isRemoved() || mob == client.player || isRealPlayer(mob)) {
                continue;
            }
            String name = matchedName(plain, mobs);
            if (KNOWN.size() < MAX_KNOWN || KNOWN.containsKey(mob.getId())) {
                KNOWN.put(mob.getId(), name);
            }
        }
        // Keep: every remembered mob still loaded, alive and wanted, in sight unless Through Walls.
        Vec3 eye = client.player.getEyePosition();
        Set<String> want = new java.util.HashSet<>(mobs);
        List<Integer> found = new ArrayList<>();
        java.util.Iterator<Map.Entry<Integer, String>> it = KNOWN.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, String> en = it.next();
            Entity e = client.level.getEntity(en.getKey());
            if (!(e instanceof LivingEntity mob) || !mob.isAlive() || mob.isRemoved() || !want.contains(en.getValue())) {
                it.remove();
                continue;
            }
            if (walls || visible(client, eye, mob)) {
                found.add(mob.getId());
            }
        }
        int[] ids = new int[found.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = found.get(i);
        }
        MiningQolConfig.CommissionMobStyle st = cfg.getCommissionMobStyle();
        throughWalls = walls;
        style = st;
        targets = ids;
        if (st.glow) {
            int[] sorted = ids.clone();
            java.util.Arrays.sort(sorted);
            glowArgb = cfg.getCommissionMobColor();
            glowIds = sorted;
        } else if (glowIds.length != 0) {
            glowIds = new int[0];
        }
    }

    /** Which of {@code mobs} the tag names (the longest that matches, so "Golden Goblin" beats "Goblin"). */
    private static String matchedName(String plain, List<String> mobs) {
        String best = null;
        for (String mob : mobs) {
            if ((best == null || mob.length() > best.length()) && compile(List.of(mob)).matcher(plain).find()) {
                best = mob;
            }
        }
        return best == null ? mobs.get(0) : best;
    }

    /** Glow mixin hook (cheatutils WitherGlowMinecraftMixin): whether this entity gets the vanilla glow outline. */
    public static boolean shouldGlow(Entity entity) {
        int[] ids = glowIds;
        return ids.length != 0 && java.util.Arrays.binarySearch(ids, entity.getId()) >= 0;
    }

    /** Glow mixin hook (cheatutils WitherGlowEntityMixin): the ARGB outline color, or null when not glowing. */
    public static Integer glowColor(Entity entity) {
        return shouldGlow(entity) ? glowArgb : null;
    }

    private static boolean isRealPlayer(LivingEntity e) {
        return e instanceof Player p && p.getUUID().version() != 2;
    }

    /** The mob a name-tag stand rides above: the entity with the id before it, else the nearest living one under it. */
    private static LivingEntity mobUnder(Minecraft client, ArmorStand stand) {
        Entity prev = client.level.getEntity(stand.getId() - 1);
        if (prev instanceof LivingEntity l && !(prev instanceof ArmorStand) && prev != client.player
                && horizontalSq(prev, stand) < 2.25 && stand.getY() - prev.getY() > -1.0 && stand.getY() - prev.getY() < 4.0) {
            return l;
        }
        AABB below = stand.getBoundingBox().inflate(0.75, 0, 0.75).expandTowards(0, -3.5, 0);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : client.level.getEntities(stand, below)) {
            if (e instanceof LivingEntity l && !(e instanceof ArmorStand) && e != client.player) {
                double d = e.distanceToSqr(stand);
                if (d < bestD) {
                    bestD = d;
                    best = l;
                }
            }
        }
        return best;
    }

    private static double horizontalSq(Entity a, Entity b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean visible(Minecraft client, Vec3 eye, LivingEntity mob) {
        for (Vec3 to : new Vec3[]{mob.getEyePosition(), mob.getBoundingBox().getCenter()}) {
            if (client.level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                    client.player)).getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    private static void clear() {
        if (targets.length != 0) {
            targets = new int[0];
        }
        if (glowIds.length != 0) {
            glowIds = new int[0];
        }
        KNOWN.clear();
        if (!wanted.isEmpty()) {
            wanted = List.of();
        }
        ticks = 0;
    }

    static void render(LevelRenderContext context) {
        int[] ids = targets;
        Minecraft client = Minecraft.getInstance();
        MiningQolConfig.CommissionMobStyle st = style;
        if (ids.length == 0 || client.level == null || st.glow) {
            return; // Glow draws through the glow mixins, not here
        }
        float pt = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float[] k = WorldRenderUtils.argbToFloats(MiningQolConfig.getInstance().getCommissionMobColor());
        // Fresh arrays every frame: on 26.2 the draw runs later in the frame (WorldRenderUtils.renderOutlineBoxes).
        AABB[] boxes = new AABB[ids.length];
        float[] rgba = new float[ids.length * 4];
        int n = 0;
        for (int id : ids) {
            Entity e = client.level.getEntity(id);
            if (e == null || e.isRemoved()) {
                continue;
            }
            double dx = Mth.lerp(pt, e.xo, e.getX()) - e.getX();
            double dy = Mth.lerp(pt, e.yo, e.getY()) - e.getY();
            double dz = Mth.lerp(pt, e.zo, e.getZ()) - e.getZ();
            boxes[n] = e.getBoundingBox().move(dx, dy, dz);
            rgba[n * 4] = k[0];
            rgba[n * 4 + 1] = k[1];
            rgba[n * 4 + 2] = k[2];
            rgba[n * 4 + 3] = k[3] <= 0f ? 1f : k[3];
            n++;
        }
        if (n == 0) {
            return;
        }
        if (st.fill) {
            // A see-through fill: the outline-only alpha would hide the mob.
            WorldRenderUtils.renderFilledBoxes(context, boxes, rgba, n, st.outline ? 0.3f : 0.45f, throughWalls);
        }
        if (st.outline) {
            WorldRenderUtils.renderOutlineBoxes(context, boxes, rgba, n, 2f, throughWalls);
        }
        frames++;
    }


    /** Entity ids boxed now. */
    public static List<Integer> targetIds() {
        List<Integer> out = new ArrayList<>();
        for (int id : targets) {
            out.add(id);
        }
        return out;
    }

    public static int frames() {
        return frames;
    }

    public static boolean throughWallsNow() {
        return throughWalls;
    }

    /** The style drawn now (testkit). */
    public static String styleNow() {
        return style.name();
    }

    /** How many identified mobs are remembered now (testkit). */
    public static int knownCount() {
        return KNOWN.size();
    }

    /** Glowing ids now (testkit). */
    public static List<Integer> glowIdsNow() {
        List<Integer> out = new ArrayList<>();
        for (int id : glowIds) {
            out.add(id);
        }
        return out;
    }
}
