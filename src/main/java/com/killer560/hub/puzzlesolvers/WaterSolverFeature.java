package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.FeatureGuard;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
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
import com.killer560.hub.compat.McRender;
import com.killer560.hub.util.ChatColors;

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

    /**
     * The five real wool colours, in the order their indices are written into the solution key.
     *
     * <p>{@link #z} is the room-relative z of the colour's own column; the block that says whether it is
     * pushed out is {@code (15, 56, z)} - see {@link #scan}. The wool itself sits one lower when retracted.
     */
    public enum WoolColor {
        PURPLE(19), ORANGE(18), BLUE(17), GREEN(16), RED(15);

        final int z;

        WoolColor(int z) {
            this.z = z;
        }

        /** Room-relative z of this colour's column, for the sim's practice copy of the board. */
        public int relZ() {
            return z;
        }
    }

    public enum LeverBlock {
        COAL(20, 61, 10, "coal_block"),
        GOLD(20, 61, 15, "gold_block"),
        QUARTZ(20, 61, 20, "quartz_block"),
        DIAMOND(10, 61, 20, "diamond_block"),
        EMERALD(10, 61, 15, "emerald_block"),
        CLAY(10, 61, 10, "hardened_clay"),
        WATER(15, 60, 5, "water");

        final int x;
        final int y;
        final int z;
        /** The key this lever is stored under in {@code water-solutions.json} - the file's own spelling. */
        final String solutionKey;
        int clicked = 0;

        LeverBlock(int x, int y, int z, String solutionKey) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.solutionKey = solutionKey;
        }

        /** Room-relative position, for the sim's practice copy of the board. */
        public int relX() {
            return x;
        }

        public int relY() {
            return y;
        }

        public int relZ() {
            return z;
        }

        public String solutionKey() {
            return solutionKey;
        }
    }

    /**
     * One of the four real blocks that say which physical board layout is active, with the identifier it means.
     *
     * <p>Order matters and is the order {@link #scan} tests them in - two of them share a position and are
     * told apart only by which block is there. Published because the sim's practice copy of this puzzle has to
     * read the SAME markers off the SAME room to pick the same solution his solver will; a second copy of the
     * list in {@code roomsim} is exactly the kind of pair that drifts.
     */
    public record IdentifierMarker(int x, int y, int z, net.minecraft.world.level.block.Block block,
                                   int identifier) {
    }

    public static final List<IdentifierMarker> IDENTIFIER_MARKERS = List.of(
            new IdentifierMarker(14, 77, 27, Blocks.TERRACOTTA, 0),
            new IdentifierMarker(16, 78, 27, Blocks.EMERALD_BLOCK, 1),
            new IdentifierMarker(14, 78, 27, Blocks.DIAMOND_BLOCK, 2),
            new IdentifierMarker(14, 78, 27, Blocks.QUARTZ_BLOCK, 3));

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
        // Shared solver highlight pipelines must exist before the level renderer precompiles them.
        SolverEspRender.init();
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("WaterSolverFeature.tick", WaterSolverFeature::tick));
        // After translucent TERRAIN, not features: water is drawn after the features pass, so a highlight
        // drawn there ended up painted over by any water behind/around it (killer560, 2026-09-21).
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(WaterSolverFeature::onWorldRender);
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
            com.killer560.hub.util.ModLog.get("killer560smod-puzzles").warn("[WaterSolver] Failed to load solutions", e);
            return Map.of();
        }
    }

    private static void tick(Minecraft client) {
        tickInner(client);
    }

    private static void tickInner(Minecraft client) {
        // Boss check: NoammAddons e42d3316 "reset when entering boss" (2026-09-14 port).
        if (!WaterSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
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
            boolean extended = !level.getBlockState(real).isAir();
            if (extended) {
                extendedSlots.append(i);
            }
        }
        if (extendedSlots.length() != 3) {
            return;
        }

        int identifier = -1;
        for (IdentifierMarker marker : IDENTIFIER_MARKERS) {
            if (level.getBlockState(realPos(marker.x(), marker.y(), marker.z(), clayAndRotation))
                    .is(marker.block())) {
                identifier = marker.identifier();
                break;
            }
        }
        if (identifier == -1) {
            return;
        }

        Map<String, List<Double>> leverTimes = bundledSolution(
                WaterSolverConfig.getInstance().isOptimizedPath(), identifier, extendedSlots.toString());
        if (leverTimes == null) {
            return;
        }

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
        for (LeverBlock lever : LeverBlock.values()) {
            if (lever.solutionKey.equals(key)) {
                return lever;
            }
        }
        return null;
    }

    /**
     * One board's real bundled lever timings, straight out of {@code water-solutions.json}.
     *
     * <p>Published for the dungeon sim's practice copy of this puzzle, which has to be driven by the SAME
     * numbers the solver is about to display or the two disagree on screen - including on
     * {@code optimizedPath}, which is his own setting and changes the whole sequence.
     *
     * @param extendedSlots the indices into {@link WoolColor#values()} of the colours pushed out, in order,
     *                      as a string - the file's own key, e.g. {@code "012"}
     * @return the times per {@link LeverBlock#solutionKey()}, or null when that board is not in the file
     */
    public static Map<String, List<Double>> bundledSolution(boolean optimizedPath, int identifier,
                                                            String extendedSlots) {
        return SOLUTIONS
                .getOrDefault(String.valueOf(optimizedPath), Map.of())
                .getOrDefault(String.valueOf(identifier), Map.of())
                .get(extendedSlots);
    }

    /** {@link #bundledSolution} keyed by the lever enum rather than the file's strings. */
    public static Map<LeverBlock, List<Double>> bundledSolutionByLever(boolean optimizedPath, int identifier,
                                                                      String extendedSlots) {
        Map<String, List<Double>> raw = bundledSolution(optimizedPath, identifier, extendedSlots);
        if (raw == null) {
            return null;
        }
        Map<LeverBlock, List<Double>> out = new EnumMap<>(LeverBlock.class);
        for (Map.Entry<String, List<Double>> entry : raw.entrySet()) {
            LeverBlock lever = fromKey(entry.getKey());
            if (lever != null) {
                out.put(lever, entry.getValue());
            }
        }
        return out;
    }

    /**
     * The solver's own click ORDER for one board: every remaining click, soonest first.
     *
     * <p>This is the order the tracer walks and therefore the order he is told to click in, so the sim scores
     * his clicks against the same list rather than inventing a second ordering. Same comparator as
     * {@link #onWorldRender}'s {@code flat} sort: the zero-time clicks first in lever order, then the timed
     * ones by time.
     */
    public static List<Map.Entry<LeverBlock, Double>> bundledClickOrder(boolean optimizedPath, int identifier,
                                                                       String extendedSlots) {
        Map<LeverBlock, List<Double>> byLever = bundledSolutionByLever(optimizedPath, identifier, extendedSlots);
        if (byLever == null) {
            return null;
        }
        List<Map.Entry<LeverBlock, Double>> flat = new ArrayList<>();
        for (Map.Entry<LeverBlock, List<Double>> entry : byLever.entrySet()) {
            for (double time : entry.getValue()) {
                flat.add(Map.entry(entry.getKey(), time));
            }
        }
        flat.sort(Comparator
                .comparing((Map.Entry<LeverBlock, Double> e) -> e.getValue() != 0.0)
                .thenComparingInt(e -> e.getValue() == 0.0 ? e.getKey().ordinal() : Integer.MAX_VALUE)
                .thenComparingDouble(e -> e.getValue() != 0.0 ? e.getValue() : 0.0));
        return flat;
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

    /** The soonest remaining click (same ordering as the tracer/QUOI {@code solutionList}), for AutoPuzzles. */
    public record NextClick(boolean water, BlockPos pos, int relativeZ, double time) {
    }

    public static NextClick nextClick() {
        if (patternIdentifier == -1 || solutions.isEmpty()) {
            return null;
        }
        LeverBlock bestLever = null;
        double bestTime = 0.0;
        for (Map.Entry<LeverBlock, List<Double>> entry : solutions.entrySet()) {
            LeverBlock lever = entry.getKey();
            List<Double> times = entry.getValue();
            for (int i = lever.clicked; i < times.size(); i++) {
                double t = times.get(i);
                if (bestLever == null || compareClick(lever, t, bestLever, bestTime) < 0) {
                    bestLever = lever;
                    bestTime = t;
                }
            }
        }
        if (bestLever == null || LiveMapFeature.currentRoomClayAndRotation() == null) {
            return null;
        }
        return new NextClick(bestLever == LeverBlock.WATER, leverRealPos(bestLever), bestLever.z, bestTime);
    }

    private static int compareClick(LeverBlock a, double ta, LeverBlock b, double tb) {
        boolean aZero = ta == 0.0;
        boolean bZero = tb == 0.0;
        if (aZero != bZero) {
            return aZero ? -1 : 1;
        }
        if (aZero) {
            return Integer.compare(a.ordinal(), b.ordinal());
        }
        return Double.compare(ta, tb);
    }

    public static long getOpenedWaterTick() {
        return openedWaterTick;
    }

    public static long getTickCounter() {
        return tickCounter;
    }

    /** Total lever clicks counted so far this room (lets the auto confirm its click registered). */
    public static int getCountedClicks() {
        int total = 0;
        for (LeverBlock lever : solutions.keySet()) {
            total += lever.clicked;
        }
        return total;
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
            // Just the lever's own hitbox, not the whole block (killer560, 2026-09-21: "shrink the highlight down to
            // just the levers hitbox"); the full block only if the lever isn't loaded.
            AABB leverBox = new AABB(firstPos);
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                var shape = mc.level.getBlockState(firstPos).getShape(mc.level, firstPos);
                if (!shape.isEmpty()) {
                    leverBox = shape.bounds().move(firstPos);
                }
            }
            // killer560, 2026-09-27: "make it so the hitbox is yellow just like the timer text until it is
            // actually time to click it then it turns green" - same §e/§a colours the floating countdown label
            // already uses (see labelFor/isReadyNow below), not a separately-picked yellow.
            int timeInTicks = Math.round(flat.get(0).getValue().floatValue() * 20f);
            float[] boxColor = isReadyNow(timeInTicks) ? textColor(ChatFormatting.GREEN) : textColor(ChatFormatting.YELLOW);
            SolverEspRender.renderWaypoint(context, leverBox, boxColor[0], boxColor[1], boxColor[2], 3f);
            if (flat.size() > 1) {
                LeverBlock second = flat.get(1).getKey();
                BlockPos secondPos = leverRealPos(second);
                if (!secondPos.equals(firstPos)) {
                    List<Vec3> line = List.of(
                            new Vec3(firstPos.getX() + 0.5, firstPos.getY() + 0.5, firstPos.getZ() + 0.5),
                            new Vec3(secondPos.getX() + 0.5, secondPos.getY() + 0.5, secondPos.getZ() + 0.5));
                    SolverEspRender.renderLineStrip(context, line, 1.0f, 0.8f, 0.2f, 1f, 2f);
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
        if (isReadyNow(timeInTicks)) {
            return "§a§lCLICK ME!";
        }
        if (openedWaterTick == -1) {
            return String.format(Locale.US, "§e%.1fs", timeInTicks / 20f);
        }
        long remaining = openedWaterTick + timeInTicks - tickCounter;
        return String.format(Locale.US, "§e%.1fs", remaining / 20f);
    }

    /** Same "is it actually time to click this" check the countdown label uses, shared with the tracer box's
     *  colour so both flip from waiting to ready at the exact same instant. */
    private static boolean isReadyNow(int timeInTicks) {
        if (openedWaterTick == -1) {
            return timeInTicks == 0;
        }
        long remaining = openedWaterTick + timeInTicks - tickCounter;
        return remaining <= 0;
    }

    /** The exact RGB a §-colour code renders text in, as 0..1 floats - so a world-space highlight can match a
     *  chat/label colour exactly instead of a separately hand-picked one. */
    private static float[] textColor(ChatFormatting formatting) {
        Integer packed = ChatColors.color(formatting);
        int rgb = packed != null ? packed : 0xFFFFFF;
        return new float[] {
                ((rgb >> 16) & 0xFF) / 255f,
                ((rgb >> 8) & 0xFF) / 255f,
                (rgb & 0xFF) / 255f
        };
    }

    private static void renderWorldText(LevelRenderContext context, double worldX, double worldY, double worldZ,
                                         String text) {
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        Vec3 cam = McRender.cameraPos(context);
        float scale = 0.02f;

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(worldX - cam.x, worldY - cam.y, worldZ - cam.z);
        poseStack.mulPose(McRender.cameraRotation(context));
        // Real bug found and fixed (2026-09-14, same root cause as SimonSaysFeature.renderNumber): the
        // old pre-1.21.2 (-s, -s, s) nametag scale mirrors X on top of 26.1.2's already-flipped camera
        // quaternion, reversing glyph winding so every lever countdown was back-face culled and never
        // visible. Vanilla 26.1.2 NameTagFeatureRenderer uses (+s, -s, +s).
        poseStack.scale(scale, -scale, scale);

        float width = font.width(text);
        int background = (int) (0.4f * 255f) << 24;
        McRender.drawText(context, font, text, -width / 2f, -font.lineHeight / 2f, 0xFFFFFFFF, false, poseStack, Font.DisplayMode.SEE_THROUGH, background, 0xF000F0);

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
