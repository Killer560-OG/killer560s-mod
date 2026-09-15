package com.killer560.hub.puzzlesolvers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Real Hypixel dungeon "Water Board" puzzle solver, ported from Odin's own {@code WaterSolver.kt}. This
 * is the most involved real puzzle mechanic ported so far: exactly 3 of 5 real wool colors get pushed out
 * (a real 5-choose-3 = 10 combinations) and one of 4 real marker blocks identifies which physical board
 * layout is active; together these key into a bundled real lever-timing database
 * ({@code data/killer560smod/puzzles/water-solutions.json}, copied verbatim from Odin) giving each of 7
 * real interactable blocks (6 levers + the water bucket) a real list of click times in seconds, relative
 * to the moment the water lever is first flicked (that's the real puzzle-wide synchronized start signal).
 * Highlights the single soonest real next click with a tracer, and floats a real live countdown ("Xs" /
 * "CLICK ME!") above every remaining click. Never clicks anything - only tracks real clicks (via a
 * non-cancelling {@code useItemOn} TAIL injection, same technique as Boulder Solver) to know which times
 * have already been consumed per lever.
 * <p>
 * Like Boulder/Quiz/Ice Fill Solver, the 7 real lever positions are bundled FIXED relative coordinates
 * (ported from Odin), not derived live - so this carries the same one flagged cross-mod corner/rotation
 * convention risk documented for those solvers (see TESTING.md Round 76). Unlike Weirdos Solver, there's
 * no live-entity self-reference here to sidestep that risk.
 */
public final class WaterSolverFeature {

    public enum WoolColor {
        PURPLE(19), ORANGE(18), BLUE(17), GREEN(16), RED(15);

        final int z;

        WoolColor(int z) {
            this.z = z;
        }
    }

    public enum LeverBlock {
        COAL(20, 61, 10),
        GOLD(20, 61, 15),
        QUARTZ(20, 61, 20),
        DIAMOND(10, 61, 20),
        EMERALD(10, 61, 15),
        CLAY(10, 61, 10),
        WATER(15, 60, 5);

        final int x;
        final int y;
        final int z;
        int clicked = 0;

        LeverBlock(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final Type SOLUTIONS_TYPE =
            new TypeToken<Map<String, Map<String, Map<String, Map<String, List<Double>>>>>>() {
            }.getType();
    private static final Map<String, Map<String, Map<String, Map<String, List<Double>>>>> SOLUTIONS = loadSolutions();

    private static final Map<LeverBlock, List<Double>> solutions = new EnumMap<>(LeverBlock.class);
    private static int patternIdentifier = -1;
    private static long openedWaterTick = -1;
    private static long tickCounter = 0;
    private static RoomEntry lastRoomEntry = null;

    private WaterSolverFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(WaterSolverFeature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(WaterSolverFeature::onWorldRender);
    }

    private static Map<String, Map<String, Map<String, Map<String, List<Double>>>>> loadSolutions() {
        try (InputStream stream = WaterSolverFeature.class.getClassLoader()
                .getResourceAsStream("data/killer560smod/puzzles/water-solutions.json")) {
            if (stream == null) {
                return Map.of();
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Map<String, Map<String, Map<String, Map<String, List<Double>>>>> parsed =
                        new Gson().fromJson(reader, SOLUTIONS_TYPE);
                return parsed != null ? parsed : Map.of();
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("killer560smod-puzzles").warn("[WaterSolver] Failed to load solutions", e);
            return Map.of();
        }
    }

    // [WaterSolver] diagnostics - logging only.
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-puzzles");
    private static String lastLoggedState = null;
    private static String lastScanOutcome = "none";

    private static void tick(Minecraft client) {
        tickInner(client);
        RoomEntry current = WaterSolverConfig.getInstance().isEnabled() && DungeonState.isInDungeon()
                ? LiveMapFeature.currentRoomEntry() : null;
        String state = current == null || !"Water Board".equals(current.name)
                ? "notInRoom(enabled=" + WaterSolverConfig.getInstance().isEnabled() + ")"
                : "inRoom clayRot=" + java.util.Arrays.toString(LiveMapFeature.currentRoomClayAndRotation())
                + " scan=" + lastScanOutcome + " pattern=" + patternIdentifier + " levers=" + solutions.size()
                + " waterOpened=" + (openedWaterTick != -1) + " solutionsLoaded=" + SOLUTIONS.size();
        if (!state.equals(lastLoggedState)) {
            LOGGER.info("[WaterSolver] State: {}", state);
            lastLoggedState = state;
        }
    }

    private static void tickInner(Minecraft client) {
        if (!WaterSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon()) {
            reset();
            lastRoomEntry = null;
            return;
        }
        tickCounter++;
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current != lastRoomEntry) {
            lastRoomEntry = current;
            reset();
        }
        if (current == null || !"Water Board".equals(current.name) || patternIdentifier != -1) {
            return;
        }
        scan(client);
    }

    private static void scan(Minecraft client) {
        Level level = client.level;
        int[] clayAndRotation = LiveMapFeature.currentRoomClayAndRotation();
        if (level == null || clayAndRotation == null) {
            return;
        }

        StringBuilder extendedSlots = new StringBuilder();
        WoolColor[] colors = WoolColor.values();
        for (int i = 0; i < colors.length; i++) {
            BlockPos real = realPos(15, 56, colors[i].z, clayAndRotation);
            if (!level.getBlockState(real).isAir()) {
                extendedSlots.append(i);
            }
        }
        if (extendedSlots.length() != 3) {
            lastScanOutcome = "extendedSlots='" + extendedSlots + "'(need 3)";
            return;
        }

        int identifier;
        if (level.getBlockState(realPos(14, 77, 27, clayAndRotation)).is(Blocks.TERRACOTTA)) {
            identifier = 0;
        } else if (level.getBlockState(realPos(16, 78, 27, clayAndRotation)).is(Blocks.EMERALD_BLOCK)) {
            identifier = 1;
        } else if (level.getBlockState(realPos(14, 78, 27, clayAndRotation)).is(Blocks.DIAMOND_BLOCK)) {
            identifier = 2;
        } else if (level.getBlockState(realPos(14, 78, 27, clayAndRotation)).is(Blocks.QUARTZ_BLOCK)) {
            identifier = 3;
        } else {
            lastScanOutcome = "slots=" + extendedSlots + " noMarker(14,78,27="
                    + level.getBlockState(realPos(14, 78, 27, clayAndRotation)).getBlock() + ")";
            return;
        }

        Map<String, List<Double>> leverTimes = SOLUTIONS
                .getOrDefault(String.valueOf(WaterSolverConfig.getInstance().isOptimizedPath()), Map.of())
                .getOrDefault(String.valueOf(identifier), Map.of())
                .get(extendedSlots.toString());
        if (leverTimes == null) {
            lastScanOutcome = "slots=" + extendedSlots + " id=" + identifier + " noSolutionEntry";
            return;
        }
        lastScanOutcome = "slots=" + extendedSlots + " id=" + identifier + " solved";

        solutions.clear();
        for (LeverBlock lever : LeverBlock.values()) {
            lever.clicked = 0;
        }
        for (Map.Entry<String, List<Double>> entry : leverTimes.entrySet()) {
            LeverBlock lever = fromKey(entry.getKey());
            if (lever != null) {
                solutions.put(lever, entry.getValue());
            }
        }
        patternIdentifier = identifier;
    }

    private static LeverBlock fromKey(String key) {
        return switch (key) {
            case "coal_block" -> LeverBlock.COAL;
            case "gold_block" -> LeverBlock.GOLD;
            case "quartz_block" -> LeverBlock.QUARTZ;
            case "diamond_block" -> LeverBlock.DIAMOND;
            case "emerald_block" -> LeverBlock.EMERALD;
            case "hardened_clay" -> LeverBlock.CLAY;
            case "water" -> LeverBlock.WATER;
            default -> null;
        };
    }

    private static BlockPos realPos(int x, int y, int z, int[] clayAndRotation) {
        RoomEntry.Pos relative = new RoomEntry.Pos();
        relative.x = x;
        relative.y = y;
        relative.z = z;
        return RoomDatabase.toRealCoord(relative, clayAndRotation[0], clayAndRotation[1], clayAndRotation[2]);
    }

    private static BlockPos leverRealPos(LeverBlock lever) {
        int[] clayAndRotation = LiveMapFeature.currentRoomClayAndRotation();
        if (clayAndRotation == null) {
            return BlockPos.ZERO;
        }
        return realPos(lever.x, lever.y, lever.z, clayAndRotation);
    }

    /** Called from {@code WaterSolverMixin} on every real successful block interact. */
    public static void onLeverClick(BlockPos clicked) {
        if (solutions.isEmpty()) {
            return;
        }
        for (LeverBlock lever : solutions.keySet()) {
            if (leverRealPos(lever).equals(clicked)) {
                if (lever == LeverBlock.WATER && openedWaterTick == -1) {
                    openedWaterTick = tickCounter;
                }
                lever.clicked++;
                LOGGER.info("[WaterSolver] Lever click counted: {} at {} (clicked={}, tick={})",
                        lever, clicked, lever.clicked, tickCounter);
                return;
            }
        }
    }

    private static void onWorldRender(LevelRenderContext context) {
        WaterSolverConfig cfg = WaterSolverConfig.getInstance();
        if (!cfg.isEnabled() || solutions.isEmpty()) {
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current == null || !"Water Board".equals(current.name)) {
            return;
        }

        List<Map.Entry<LeverBlock, Double>> flat = new ArrayList<>();
        for (Map.Entry<LeverBlock, List<Double>> entry : solutions.entrySet()) {
            LeverBlock lever = entry.getKey();
            List<Double> times = entry.getValue();
            for (int i = lever.clicked; i < times.size(); i++) {
                flat.add(Map.entry(lever, times.get(i)));
            }
        }
        flat.sort(Comparator
                .comparing((Map.Entry<LeverBlock, Double> e) -> e.getValue() != 0.0)
                .thenComparingInt(e -> e.getValue() == 0.0 ? e.getKey().ordinal() : Integer.MAX_VALUE)
                .thenComparingDouble(e -> e.getValue() != 0.0 ? e.getValue() : 0.0));

        if (cfg.isShowTracer() && !flat.isEmpty()) {
            LeverBlock first = flat.get(0).getKey();
            BlockPos firstPos = leverRealPos(first);
            WorldRenderUtils.renderOutlineBox(context, new AABB(firstPos), 0.3f, 1.0f, 0.5f, 1f, 3f);
            if (flat.size() > 1) {
                LeverBlock second = flat.get(1).getKey();
                BlockPos secondPos = leverRealPos(second);
                if (!secondPos.equals(firstPos)) {
                    List<Vec3> line = List.of(
                            new Vec3(firstPos.getX() + 0.5, firstPos.getY() + 0.5, firstPos.getZ() + 0.5),
                            new Vec3(secondPos.getX() + 0.5, secondPos.getY() + 0.5, secondPos.getZ() + 0.5));
                    WorldRenderUtils.renderLineStrip(context, line, 1.0f, 0.8f, 0.2f, 1f, 2f);
                }
            }
        }

        for (Map.Entry<LeverBlock, List<Double>> entry : solutions.entrySet()) {
            LeverBlock lever = entry.getKey();
            List<Double> times = entry.getValue();
            BlockPos pos = leverRealPos(lever);
            for (int i = lever.clicked; i < times.size(); i++) {
                int timeInTicks = Math.round(times.get(i).floatValue() * 20f);
                String label = labelFor(timeInTicks);
                double yOffset = i * 0.5 + 1.5;
                renderWorldText(context, pos.getX() + 0.5, pos.getY() + yOffset, pos.getZ() + 0.5, label);
            }
        }
    }

    private static String labelFor(int timeInTicks) {
        if (openedWaterTick == -1) {
            return timeInTicks == 0 ? "§a§lCLICK ME!" : String.format(Locale.US, "§e%.1fs", timeInTicks / 20f);
        }
        long remaining = openedWaterTick + timeInTicks - tickCounter;
        return remaining > 0 ? String.format(Locale.US, "§e%.1fs", remaining / 20f) : "§a§lCLICK ME!";
    }

    private static void renderWorldText(LevelRenderContext context, double worldX, double worldY, double worldZ,
                                         String text) {
        var bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        var mainCamera = client.gameRenderer.getMainCamera();
        Vec3 cam = mainCamera.position();
        float scale = 0.02f;

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(worldX - cam.x, worldY - cam.y, worldZ - cam.z);
        poseStack.mulPose(mainCamera.rotation());
        poseStack.scale(-scale, -scale, scale);

        float width = font.width(text);
        int background = (int) (0.4f * 255f) << 24;
        font.drawInBatch(text, -width / 2f, 0f, 0xFFFFFFFF, false, poseStack.last().pose(),
                bufferSource, Font.DisplayMode.SEE_THROUGH, background, 0xF000F0);

        poseStack.popPose();
    }

    private static void reset() {
        solutions.clear();
        for (LeverBlock lever : LeverBlock.values()) {
            lever.clicked = 0;
        }
        patternIdentifier = -1;
        openedWaterTick = -1;
        tickCounter = 0;
    }
}
