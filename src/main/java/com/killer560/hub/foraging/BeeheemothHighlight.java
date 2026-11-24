package com.killer560.hub.foraging;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.miningqol.GoldenGoblin;
import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Beeheemoth Highlight (Galatea): the Beeheemoth is a flying critter whose name tag reads "Beeheemoth" (the game says
 * "BEEHEEMOTH! The Beeheemoth is getting more fatigued!" as it tires). The entity itself was never recorded, so it is
 * found by name tag like the Golden Goblin: a named mob, or the mob under a named armor stand, else the stand's own spot.
 * Highlight draws a box on it while it is in plain sight (depth-tested); ESP (cheat build only) draws it through walls.
 * Draw-only: nothing is clicked or sent.
 */
public final class BeeheemothHighlight {

    /** The saved color while the player has not picked one: it follows the menu theme's accent. */
    public static final int DEFAULT_COLOR = 0xFFCC6600;

    public static final IslandConfig CFG = IslandConfig.of("foraging", "beeheemoth")
            .flag("box", true)
            .flag("esp", false)
            .num("color", DEFAULT_COLOR, Integer.MIN_VALUE, Integer.MAX_VALUE);

    private static final Set<String> ISLANDS = Set.of("GALATEA", "TORRHUS_CANYON", "SAFARI");
    private static final int REFRESH_TICKS = 4;
    private static final int MAX_KNOWN = 16;

    private static final Set<Integer> KNOWN = new HashSet<>();
    private static Object knownLevel;
    private static int ticks;
    private static volatile int[] targets = new int[0];
    private static volatile boolean throughWalls;
    private static int boxFrames;
    private static int drawnArgb;
    private static int flip;
    private static final AABB[][] boxes = {new AABB[0], new AABB[0]};
    private static final float[][] rgba = {new float[0], new float[0]};

    private BeeheemothHighlight() {
    }

    public static void register() {
        int bit = FeatureBits.register("Beeheemoth Highlight", CFG::isEnabledRaw);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BeeheemothHighlight.tick", bit, BeeheemothHighlight::tick));
        LevelRenderEvents.COLLECT_SUBMITS.register(FeatureGuard.collectFeatures(bit, BeeheemothHighlight::render));
        WorldRenderUtils.initThroughWalls();
    }

    private static boolean walls() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && CFG.on("esp");
    }

    private static void tick(Minecraft mc) {
        if (!CFG.isEnabled() || mc.level == null || mc.player == null
                || !ISLANDS.contains(String.valueOf(IslandDetector.graphIsland()))) {
            clear();
            return;
        }
        if (ticks++ % REFRESH_TICKS != 0) {
            return;
        }
        if (knownLevel != mc.level) {
            KNOWN.clear();
            knownLevel = mc.level;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || !e.hasCustomName()) {
                continue;
            }
            String plain = ChatObserver.stripCodes(e.getCustomName().getString());
            if (plain == null || !plain.toLowerCase(java.util.Locale.ROOT).contains("beeheemoth")) {
                continue;
            }
            LivingEntity mob = e instanceof ArmorStand stand ? GoldenGoblin.mobUnder(mc, stand) : e instanceof LivingEntity l ? l : null;
            Entity target = mob != null ? mob : e;
            if (target.isRemoved() || (KNOWN.size() >= MAX_KNOWN && !KNOWN.contains(target.getId()))) {
                continue;
            }
            KNOWN.add(target.getId());
        }
        boolean walls = walls();
        Vec3 eye = mc.player.getEyePosition();
        List<Integer> found = new ArrayList<>();
        var it = KNOWN.iterator();
        while (it.hasNext()) {
            Entity e = mc.level.getEntity(it.next());
            if (e == null || e.isRemoved() || !e.isAlive()) {
                it.remove();
            } else if (walls || !(e instanceof LivingEntity l) || GoldenGoblin.visible(mc, eye, l)) {
                found.add(e.getId());
            }
        }
        int[] ids = new int[found.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = found.get(i);
        }
        throughWalls = walls;
        targets = ids;
    }

    private static void clear() {
        if (targets.length != 0) {
            targets = new int[0];
        }
        KNOWN.clear();
        ticks = 0;
    }

    private static void render(LevelRenderContext context) {
        int[] ids = targets;
        Minecraft mc = Minecraft.getInstance();
        if (ids.length == 0 || mc.level == null || !CFG.isEnabled() || !CFG.on("box")) {
            return;
        }
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        // Two grow-only scratch sets used turn about (the draw reads them later in the frame on 26.2).
        flip ^= 1;
        if (boxes[flip].length < ids.length) {
            boxes[flip] = new AABB[ids.length];
            rgba[flip] = new float[ids.length * 4];
        }
        int argb = CFG.num("color");
        if (argb == DEFAULT_COLOR) {
            argb = ThemeAccent.accent(argb);
        }
        float[] k = WorldRenderUtils.argbToFloats(argb);
        int n = 0;
        for (int id : ids) {
            Entity e = mc.level.getEntity(id);
            if (!WorldRenderUtils.boxable(e)) {
                continue;
            }
            AABB box = e.getBoundingBox().move(Mth.lerp(pt, e.xo, e.getX()) - e.getX(),
                    Mth.lerp(pt, e.yo, e.getY()) - e.getY(), Mth.lerp(pt, e.zo, e.getZ()) - e.getZ());
            boxes[flip][n] = e instanceof ArmorStand ? box.inflate(1.0, 1.5, 1.0) : box;
            float[] c = rgba[flip];
            c[n * 4] = k[0];
            c[n * 4 + 1] = k[1];
            c[n * 4 + 2] = k[2];
            c[n * 4 + 3] = k[3] <= 0f ? 1f : k[3];
            n++;
        }
        if (n > 0) {
            WorldRenderUtils.renderOutlineBoxes(context, boxes[flip], rgba[flip], n, 2f, throughWalls);
            boxFrames++;
            drawnArgb = argb;
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

    public static int boxFrames() {
        return boxFrames;
    }

    public static int drawnArgb() {
        return drawnArgb;
    }
}
