package com.killer560.hub.miningqol;

import com.killer560.hub.util.ChatObserver;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Golden Goblin (killer560, 2026-11-03: "the Golden Goblin (and Diamond Goblin) ... box + tracer from the crosshair, color
 * option"): a box and a tracer on the Golden and Diamond Goblins on a mining island.
 *
 * <p>The mob is found by its name tag, which Hypixel puts on the mob or on an armor stand just above it ("[Lv?] Golden Goblin
 * 1,500/1,500"): the name is matched as whole words ({@link CommissionMobs#tagMatches}), a stand is mapped to the mob under
 * it, and a real player (v4 UUID) is never it. A goblin once identified is remembered by entity id while it stays loaded
 * and alive, because the server stops sending a stand at range (as Commission Mobs does). Nothing is matched by entity type.
 *
 * <p>Legit: only a goblin in plain sight (a block raycast from the eyes) is drawn, box and tracer depth-tested. Through
 * Walls (cheat build only) skips the raycast and the depth test. Draw-only: nothing is clicked or sent.
 */
public final class GoldenGoblin {

    static final List<String> NAMES = List.of("Golden Goblin", "Diamond Goblin");
    private static final int REFRESH_TICKS = 4;
    private static final int MAX_KNOWN = 64;

    private static int ticks = 0;
    private static final Map<Integer, String> KNOWN = new LinkedHashMap<>();
    private static Object knownLevel = null;
    private static volatile int[] targets = new int[0];
    private static volatile boolean throughWalls = false;
    private static int boxFrames = 0;
    private static int tracerFrames = 0;
    private static int flip = 0;
    private static final AABB[][] boxes = {new AABB[0], new AABB[0]};
    private static final float[][] rgba = {new float[0], new float[0]};
    /** The color last used for a Golden (0) and a Diamond (1) Goblin's box and tracer (testkit hook). */
    private static final int[] drawnArgb = new int[2];

    private GoldenGoblin() {
    }

    static void tick(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        if (!cfg.isGoldenGoblin() || client.level == null || client.player == null || !MiningState.onMiningIsland()) {
            clear();
            return;
        }
        if (ticks++ % REFRESH_TICKS != 0) {
            return;
        }
        if (knownLevel != client.level) {
            KNOWN.clear();
            knownLevel = client.level;
        }
        for (Entity e : client.level.entitiesForRendering()) {
            if (e == client.player || !e.hasCustomName()) {
                continue;
            }
            String plain = ChatObserver.stripCodes(e.getCustomName().getString());
            if (plain == null || !CommissionMobs.tagMatches(plain, NAMES)) {
                continue;
            }
            LivingEntity mob = e instanceof ArmorStand stand ? mobUnder(client, stand) : e instanceof LivingEntity l ? l : null;
            if (mob == null || !mob.isAlive() || mob.isRemoved() || mob == client.player || isRealPlayer(mob)) {
                continue;
            }
            if (KNOWN.size() < MAX_KNOWN || KNOWN.containsKey(mob.getId())) {
                KNOWN.put(mob.getId(), plain);
            }
        }
        boolean walls = cfg.isGoldenGoblinEsp();
        Vec3 eye = client.player.getEyePosition();
        List<Integer> found = new ArrayList<>();
        java.util.Iterator<Map.Entry<Integer, String>> it = KNOWN.entrySet().iterator();
        while (it.hasNext()) {
            Entity e = client.level.getEntity(it.next().getKey());
            if (!(e instanceof LivingEntity mob) || !mob.isAlive() || mob.isRemoved()) {
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
        KNOWN.clear();
        ticks = 0;
    }

    static void render(LevelRenderContext context) {
        int[] ids = targets;
        Minecraft client = Minecraft.getInstance();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        if (ids.length == 0 || client.level == null || client.player == null || !cfg.isGoldenGoblin()) {
            return;
        }
        float pt = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        // Two grow-only scratch sets, used turn about: on 26.2 the draw reads them later in the frame, after the next
        // render call could otherwise have rewritten them.
        flip ^= 1;
        if (boxes[flip].length < ids.length) {
            boxes[flip] = new AABB[ids.length];
            rgba[flip] = new float[ids.length * 4];
        }
        AABB[] bx = boxes[flip];
        float[] col = rgba[flip];
        int n = 0;
        for (int id : ids) {
            Entity e = client.level.getEntity(id);
            if (e == null || e.isRemoved()) {
                continue;
            }
            bx[n] = e.getBoundingBox().move(Mth.lerp(pt, e.xo, e.getX()) - e.getX(),
                    Mth.lerp(pt, e.yo, e.getY()) - e.getY(), Mth.lerp(pt, e.zo, e.getZ()) - e.getZ());
            boolean diamond = KNOWN.getOrDefault(id, "").contains("Diamond");
            int argb = diamond ? cfg.getDiamondGoblinColor() : cfg.getGoldenGoblinColor();
            float[] k = WorldRenderUtils.argbToFloats(argb);
            col[n * 4] = k[0];
            col[n * 4 + 1] = k[1];
            col[n * 4 + 2] = k[2];
            col[n * 4 + 3] = k[3] <= 0f ? 1f : k[3];
            drawnArgb[diamond ? 1 : 0] = argb;
            n++;
        }
        if (n == 0) {
            return;
        }
        boolean walls = throughWalls;
        if (cfg.isGoldenGoblinBox()) {
            WorldRenderUtils.renderOutlineBoxes(context, bx, col, n, 2f, walls);
            boxFrames++;
        }
        if (cfg.isGoldenGoblinTracer()) {
            Vec3 from = WorldRenderUtils.tracerOrigin(context);
            for (int i = 0; i < n; i++) {
                WorldRenderUtils.renderLineStrip(context, new Vec3[]{from, bx[i].getCenter()}, col[i * 4], col[i * 4 + 1],
                        col[i * 4 + 2], 1f, 2f, walls);
            }
            tracerFrames++;
        }
    }

    // ---- testkit hooks

    public static List<Integer> targetIds() {
        List<Integer> out = new ArrayList<>();
        for (int id : targets) {
            out.add(id);
        }
        return out;
    }

    public static int knownCount() {
        return KNOWN.size();
    }

    public static int drawnArgb(boolean diamond) {
        return drawnArgb[diamond ? 1 : 0];
    }

    public static int boxFrames() {
        return boxFrames;
    }

    public static int tracerFrames() {
        return tracerFrames;
    }

    public static boolean throughWallsNow() {
        return throughWalls;
    }
}
