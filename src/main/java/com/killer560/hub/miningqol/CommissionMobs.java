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
 * <p>Legit (both builds): only a mob in plain sight (a block raycast from the eyes) is boxed, and the box is depth-tested,
 * like Starred Mob Hitboxes. Through Walls (cheat build only) skips the raycast and draws with no depth test. Draw-only:
 * nothing is clicked or sent.
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
    private static final double RANGE = 64.0;

    private static int ticks = 0;
    private static String patternKey = "";
    private static Pattern pattern = null;
    private static volatile List<String> wanted = List.of();
    private static volatile int[] targets = new int[0];
    private static volatile boolean throughWalls = false;
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

    /** Whether a plain (codes stripped) name tag names one of {@code mobs}: the name as whole words, after "[LvN]". */
    public static boolean tagMatches(String plain, List<String> mobs) {
        return compile(mobs).matcher(plain).find();
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
            targets = new int[0];
            return;
        }
        String key = String.join("|", mobs);
        if (!key.equals(patternKey)) {
            patternKey = key;
            pattern = compile(mobs);
        }
        boolean walls = cfg.isCommissionMobsEsp();
        Vec3 eye = client.player.getEyePosition();
        Set<Integer> found = new LinkedHashSet<>();
        for (Entity e : client.level.entitiesForRendering()) {
            if (e == client.player || !e.hasCustomName() || e.distanceToSqr(client.player) > RANGE * RANGE) {
                continue;
            }
            String plain = com.killer560.hub.util.ChatObserver.stripCodes(e.getCustomName().getString());
            if (!pattern.matcher(plain).find()) {
                continue;
            }
            LivingEntity mob = e instanceof ArmorStand stand ? mobUnder(client, stand) : e instanceof LivingEntity l ? l : null;
            if (mob == null || !mob.isAlive() || mob.isRemoved() || mob == client.player || isRealPlayer(mob)) {
                continue;
            }
            if (walls || visible(client, eye, mob)) {
                found.add(mob.getId());
            }
        }
        int[] ids = new int[found.size()];
        int n = 0;
        for (int id : found) {
            ids[n++] = id;
        }
        throughWalls = walls;
        targets = ids;
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
        if (!wanted.isEmpty()) {
            wanted = List.of();
        }
        ticks = 0;
    }

    static void render(LevelRenderContext context) {
        int[] ids = targets;
        Minecraft client = Minecraft.getInstance();
        if (ids.length == 0 || client.level == null) {
            return;
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
        WorldRenderUtils.renderOutlineBoxes(context, boxes, rgba, n, 2f, throughWalls);
        frames++;
    }

    // ---- testkit hooks

    /** Entity ids boxed now. */
    public static List<Integer> targetIds() {
        List<Integer> out = new ArrayList<>();
        for (int id : targets) {
            out.add(id);
        }
        return out;
    }

    /** The mob names the open commissions want now (empty while the option is off). */
    public static List<String> wantedMobs() {
        return wanted;
    }

    public static int frames() {
        return frames;
    }

    public static boolean throughWallsNow() {
        return throughWalls;
    }
}
