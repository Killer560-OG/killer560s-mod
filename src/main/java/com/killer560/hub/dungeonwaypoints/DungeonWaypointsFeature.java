package com.killer560.hub.dungeonwaypoints;

import com.killer560.hub.compat.McRender;
import com.killer560.hub.dungeonwaypoints.DungeonWaypointsConfig.Wp;
import com.killer560.hub.etherwarp.EtherwarpFeature;
import com.killer560.hub.etherwarp.EtherwarpFeature.RoomContext;
import com.killer560.hub.etherwarp.EtherwarpWaypointsConfig;
import com.killer560.hub.etherwarp.EtherwarpWaypointsRenderer;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.BoxVerts;
import com.killer560.hub.util.EspTypes;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Dungeon Waypoints (killer560, 2026-11-08, featsweep item 10): the player's own boxes and lines per dungeon room, placed
 * on the block under the crosshair with {@code /dwp add}, kept room-relative ({@link DungeonWaypointsConfig}) and drawn in
 * that room on every run. Packs are plain text: {@code /dwp export} copies them, {@code /dwp import} reads the clipboard.
 * Render cost is one cache rebuild per room / list change; a frame only draws the cached entries.
 */
public final class DungeonWaypointsFeature {

    private static final String NAME = "Dungeon Waypoints";
    private static final double MAX_REACH = 64;

    private DungeonWaypointsFeature() {
    }

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(FeatureGuard.collectFeatures(
                FeatureBits.register("Dungeon Waypoints render", () -> DungeonWaypointsConfig.getInstance().isEnabledRaw()),
                DungeonWaypointsFeature::onRender));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommands.literal("dwp")
                        .then(ClientCommands.literal("add").executes(c -> say(add(false, null)))
                                .then(ClientCommands.literal("box").executes(c -> say(add(false, null)))
                                        .then(ClientCommands.argument("hex", StringArgumentType.word())
                                                .executes(c -> say(add(false, StringArgumentType.getString(c, "hex"))))))
                                .then(ClientCommands.literal("line").executes(c -> say(add(true, null)))
                                        .then(ClientCommands.argument("hex", StringArgumentType.word())
                                                .executes(c -> say(add(true, StringArgumentType.getString(c, "hex")))))))
                        .then(ClientCommands.literal("remove").executes(c -> say(removeNearest())))
                        .then(ClientCommands.literal("clear").executes(c -> say(clearRoom())))
                        .then(ClientCommands.literal("export").executes(c -> say(export())))
                        .then(ClientCommands.literal("import").executes(c -> say(importClipboard())))));
    }

    // ---- drawing ----

    /** What one room draws, rebuilt only when the list, the room or its placement changes. */
    private record Cache(int version, String room, int[] frame, int yOffset, List<EtherwarpWaypointsRenderer.Entry> boxes,
                         List<Vec3[]> segments, List<float[]> segmentColors) {
    }

    private static Cache cache;

    private static void onRender(LevelRenderContext context) {
        DungeonWaypointsConfig cfg = DungeonWaypointsConfig.getInstance();
        if (!cfg.isEnabled() || cfg.count() == 0 || !DungeonState.isInDungeon()) {
            return;
        }
        RoomContext ctx = EtherwarpFeature.currentRoomContext();
        if (ctx == null) {
            return;
        }
        Cache c = cache(cfg, ctx);
        if (!c.boxes().isEmpty()) {
            EtherwarpWaypointsRenderer.draw(context, c.boxes(), EtherwarpWaypointsConfig.Style.FILL_AND_OUTLINE, cfg.isThroughWalls());
        }
        if (!c.segments().isEmpty()) {
            McRender.inCameraSpace(context, cfg.isThroughWalls() ? EspTypes.LINES_THRU : EspTypes.LINES, (pose, buffer) -> {
                for (int i = 0; i < c.segments().size(); i++) {
                    float[] rgba = c.segmentColors().get(i);
                    BoxVerts.lineStrip(pose, buffer, c.segments().get(i), rgba[0], rgba[1], rgba[2], 1f, 3f);
                }
            });
        }
    }

    private static Cache cache(DungeonWaypointsConfig cfg, RoomContext ctx) {
        Cache c = cache;
        int yOffset = com.killer560.hub.livemap.DungeonLayout.simYOffset();
        if (c != null && c.version() == cfg.version() && c.room().equals(ctx.room().name) && c.yOffset() == yOffset
                && java.util.Arrays.equals(c.frame(), ctx.frame())) {
            return c;
        }
        List<EtherwarpWaypointsRenderer.Entry> boxes = new ArrayList<>();
        List<Vec3[]> segments = new ArrayList<>();
        List<float[]> colors = new ArrayList<>();
        Vec3 prev = null;
        for (Wp w : cfg.forRoom(ctx.room().name)) {
            BlockPos real = PuzzleCoords.real(w.x(), w.y(), w.z(), ctx.frame());
            Vec3 mid = Vec3.atCenterOf(real);
            float[] rgba = BoxVerts.rgba(w.color(), 1f);
            boxes.add(new EtherwarpWaypointsRenderer.Entry(new AABB(real).inflate(0.002), rgba[0], rgba[1], rgba[2], rgba[3]));
            if (w.line() && prev != null) {
                segments.add(new Vec3[]{prev, mid});
                colors.add(rgba);
            }
            prev = mid;
        }
        c = new Cache(cfg.version(), ctx.room().name, ctx.frame(), yOffset, boxes, segments, colors);
        cache = c;
        return c;
    }

    // ---- commands ----

    private static int say(String message) {
        ModChat.send(NAME, Component.literal(message));
        return 1;
    }

    /** The block under the crosshair within reach, or null. */
    private static BlockPos lookedBlock() {
        Minecraft mc = Minecraft.getInstance();
        HitResult hit = mc.hitResult;
        if (mc.player != null && hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK
                && mc.player.getEyePosition().distanceToSqr(hit.getLocation()) <= MAX_REACH * MAX_REACH) {
            return b.getBlockPos();
        }
        return null;
    }

    /** {@code #RRGGBB} or {@code RRGGBB} to opaque ARGB, or {@code fallback} when it is not hex. */
    static int parseColor(String hex, int fallback) {
        if (hex == null) {
            return fallback;
        }
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return h.length() == 6 ? (int) (0xFF000000L | Long.parseLong(h, 16)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** @return a status line. */
    public static String add(boolean line, String hex) {
        BlockPos at = lookedBlock();
        return at == null ? "Look at a block within " + (int) MAX_REACH + " blocks." : addAt(at, line, hex);
    }

    /** As {@link #add} on the block {@code at} (the testkit's way in; no crosshair needed). */
    public static String addAt(BlockPos at, boolean line, String hex) {
        DungeonWaypointsConfig cfg = DungeonWaypointsConfig.getInstance();
        if (!cfg.isEnabled()) {
            return "Dungeon Waypoints is off.";
        }
        RoomContext ctx = EtherwarpFeature.currentRoomContext();
        if (ctx == null) {
            return "Stand in an identified dungeon room first.";
        }
        BlockPos rel = PuzzleCoords.relative(at, ctx.frame());
        int color = parseColor(hex, cfg.getColor());
        cfg.add(new Wp(ctx.room().name, rel.getX(), rel.getY(), rel.getZ(), color, line));
        cfg.save();
        return "Added a " + (line ? "line" : "box") + " in " + ctx.room().name + " at " + at.toShortString() + ".";
    }

    /** Removes the waypoint of this room nearest the block you look at (or you, with nothing in sight). */
    public static String removeNearest() {
        DungeonWaypointsConfig cfg = DungeonWaypointsConfig.getInstance();
        RoomContext ctx = EtherwarpFeature.currentRoomContext();
        Minecraft mc = Minecraft.getInstance();
        if (ctx == null || mc.player == null) {
            return "Stand in an identified dungeon room first.";
        }
        BlockPos look = lookedBlock();
        Vec3 from = look != null ? Vec3.atCenterOf(look) : mc.player.position();
        Wp best = null;
        double bestSq = Double.MAX_VALUE;
        for (Wp w : cfg.forRoom(ctx.room().name)) {
            double d = Vec3.atCenterOf(PuzzleCoords.real(w.x(), w.y(), w.z(), ctx.frame())).distanceToSqr(from);
            if (d < bestSq) {
                bestSq = d;
                best = w;
            }
        }
        if (best == null || bestSq > 36) {
            return "No waypoint of this room within 6 blocks.";
        }
        cfg.remove(best);
        cfg.save();
        return "Removed a waypoint of " + ctx.room().name + ".";
    }

    public static String clearRoom() {
        RoomContext ctx = EtherwarpFeature.currentRoomContext();
        if (ctx == null) {
            return "Stand in an identified dungeon room first.";
        }
        DungeonWaypointsConfig cfg = DungeonWaypointsConfig.getInstance();
        int n = cfg.clearRoom(ctx.room().name);
        cfg.save();
        return "Cleared " + n + " waypoint(s) of " + ctx.room().name + ".";
    }

    /** The boxes the last render drew (testkit: proves the waypoints reached the screen path), empty before the first. */
    public static List<AABB> drawnBoxes() {
        Cache c = cache;
        List<AABB> out = new ArrayList<>();
        if (c != null) {
            c.boxes().forEach(e -> out.add(e.box()));
        }
        return out;
    }

    /** Line segments the last render drew. */
    public static int drawnSegments() {
        return cache == null ? 0 : cache.segments().size();
    }

    /** The waypoints of the room you stand in, for the tab (empty outside an identified room). */
    public static List<Wp> here() {
        RoomContext ctx = EtherwarpFeature.currentRoomContext();
        return ctx == null ? List.of() : DungeonWaypointsConfig.getInstance().forRoom(ctx.room().name);
    }

    /** Copies every waypoint as pack text. */
    public static String export() {
        DungeonWaypointsConfig cfg = DungeonWaypointsConfig.getInstance();
        Minecraft.getInstance().keyboardHandler.setClipboard(cfg.exportPack());
        return "Copied " + cfg.count() + " waypoint(s) to the clipboard.";
    }

    /** Adds the waypoints of the pack text on the clipboard. */
    public static String importClipboard() {
        DungeonWaypointsConfig cfg = DungeonWaypointsConfig.getInstance();
        int n = cfg.importPack(Minecraft.getInstance().keyboardHandler.getClipboard());
        if (n < 0) {
            return "The clipboard does not hold a Dungeon Waypoints pack.";
        }
        cfg.save();
        return "Imported " + n + " new waypoint(s).";
    }
}
