package com.killer560.hub.etherwarp;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Personal secret/etherwarp-spot bookmarks - killer560's "secret waypoints" request, using
 * {@code /killer560 ew add <name>} (or {@code /ew waypoint add}) to mark whatever block you're currently
 * standing on.
 * <p>
 * Deliberately NOT built on top of {@link com.killer560.hub.posmsg.PosmsgFeature}'s wire format even
 * though the two look similar (name + coords) - Posmsg's {@code addAndSend} immediately broadcasts to your
 * real Party Chat, which is exactly right for sharing a callout with teammates but would be a surprising,
 * unwanted message sent on your behalf every time you privately bookmark a secret spot for yourself. So
 * this stays a separate, local-only list.
 * <p>
 * <b>Room-relative storage (2026-09-27 rewrite).</b> killer560: "Make sure they dont save based off of
 * location but off of location in a room... Treat them essentially as secret waypoints with how that
 * works." Every waypoint used to be a bare world (x, y, z) and this class doc used to explain why that
 * meant they could never be saved to disk: "dungeon room layouts differ every run, so yesterday's
 * coordinates would just be garbage today." That is no longer true. Each {@link EtherwarpWaypoint} now
 * stores the room TEMPLATE it belongs to plus a position relative to that room's own frame - the exact
 * scheme {@code RoomDatabase}/{@code RoomEntry.Pos} already use for every preloaded secret - so the SAME
 * spot in the SAME room template resolves to the right real-world block however that room happens to be
 * rotated or placed this run. That is also what makes it safe to persist them in
 * {@link EtherwarpWaypointsStore} across restarts instead of clearing the list every time a new dungeon run
 * starts (the old behaviour, kept only because absolute coordinates really were garbage next run).
 * <p>
 * Follows from there: a waypoint only means anything while you are standing in an instance of the room it
 * belongs to (see {@link #onWorldRender}, {@link #currentRoomContext}) - "you cannot see them outside of
 * that room" - and clearing only ever touches the room you're currently in (see
 * {@link #clearCurrentRoom}), never the whole saved list.
 * <p>
 * Does not automate the actual Etherwarp teleport itself - that's a real ability-use action, out of
 * scope here the same way this mod never auto-clicks anything outside its already-disclosed cheat-build
 * gate. This only remembers WHERE to aim it.
 */
public final class EtherwarpFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-etherwarp");
    private static final double MAX_LOOK_DISTANCE = 64.0;

    private EtherwarpFeature() {
    }

    public static void register() {
        EtherwarpWaypointsRenderer.init();
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(EtherwarpFeature::onWorldRender);
    }

    /** Where "here" is for every room-relative operation this class does: the room template you are
     *  currently standing in, plus the clay position/rotation THIS run placed it at (needed to translate a
     *  saved relative position into a real block, or a real block back into a relative one). Null the
     *  moment either half isn't known yet - not in a dungeon, in boss, or this room hasn't been identified
     *  and rotated yet - same gate {@code SecretWaypointsFeature} applies before it will draw anything. */
    private record RoomContext(RoomEntry room, int clayX, int clayZ, int rotation) {
    }

    private static RoomContext currentRoomContext() {
        RoomEntry room = LiveMapFeature.currentRoomEntry();
        int[] clayRot = LiveMapFeature.currentRoomClayAndRotation();
        if (room == null || room.name == null || clayRot == null) {
            return null;
        }
        return new RoomContext(room, clayRot[0], clayRot[1], clayRot[2]);
    }

    /**
     * Draws a numbered box on every waypoint saved for the room you're currently standing in.
     * <p>
     * killer560, 2026-09-20: "it shows a little hud on the left with distance, instead it should highlight
     * the block I was looking at." A distance readout makes you convert a number into a place; a box on the
     * block just tells you. 2026-09-27: "make it so by default they are numbered in order of the room" adds
     * the number floating above each box, the same way {@code SecretWaypointsFeature.drawNames} labels its
     * own waypoints.
     */
    private static void onWorldRender(LevelRenderContext context) {
        EtherwarpWaypointsConfig cfg = EtherwarpWaypointsConfig.getInstance();
        if (!cfg.isHighlightBlocks()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !DungeonState.isInDungeon()) {
            return;
        }
        // "Make it so they only appear in that room and you cannot see them outside of that room."
        RoomContext ctx = currentRoomContext();
        if (ctx == null) {
            return;
        }
        List<EtherwarpWaypoint> saved = EtherwarpWaypointsStore.forRoom(ctx.room().name);
        if (saved.isEmpty()) {
            return;
        }
        float[] rgb = cfg.highlightRgb();
        int argb = cfg.getColor();
        float alpha = ((argb >> 24) & 0xFF) / 255f;
        if (alpha <= 0f) {
            alpha = 1f;
        }
        Vec3 eye = client.player.getEyePosition();
        double maxSq = cfg.getHighlightDistance() * cfg.getHighlightDistance();

        List<EtherwarpWaypointsRenderer.Entry> entries = new ArrayList<>(saved.size());
        List<EtherwarpWaypoint> visible = new ArrayList<>(saved.size());
        List<AABB> visibleBoxes = new ArrayList<>(saved.size());
        for (EtherwarpWaypoint w : saved) {
            BlockPos real = RoomDatabase.toRealCoord(toPos(w), ctx.clayX(), ctx.clayZ(), ctx.rotation());
            if (eye.distanceToSqr(real.getX() + 0.5, real.getY() + 0.5, real.getZ() + 0.5) > maxSq) {
                continue;
            }
            // The block the waypoint is in - a full unit box, same as before this rewrite (no hitbox shape
            // to copy the way Secret Waypoints has one per secret type; this just marks a spot).
            AABB box = new AABB(real).inflate(0.002);
            entries.add(new EtherwarpWaypointsRenderer.Entry(box, rgb[0], rgb[1], rgb[2], alpha));
            visible.add(w);
            visibleBoxes.add(box);
        }
        EtherwarpWaypointsRenderer.draw(context, entries, cfg.getStyle(), cfg.isThroughWalls());
        drawNumbers(context, visible, visibleBoxes);
    }

    /** The waypoint's placement-order number, floating just above its box - see the class doc's "numbered in
     *  order of the room" note. Same camera-relative billboard text {@code SecretWaypointsFeature.drawNames}
     *  uses, kept a separate pass from the boxes above since text always goes through the font's own buffer. */
    private static void drawNumbers(LevelRenderContext ctx, List<EtherwarpWaypoint> waypoints, List<AABB> boxes) {
        var bufferSource = ctx.bufferSource();
        var poseStack = ctx.poseStack();
        if (bufferSource == null || poseStack == null || waypoints.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.getMainCamera();
        var cam = camera.position();
        var font = mc.font;
        for (int i = 0; i < waypoints.size(); i++) {
            AABB box = boxes.get(i);
            double x = (box.minX + box.maxX) * 0.5;
            double y = box.maxY + 0.35;
            double z = (box.minZ + box.maxZ) * 0.5;
            double dist = Math.sqrt(cam.distanceToSqr(x, y, z));
            float s = 0.025f * (float) Math.min(6.0, Math.max(1.0, dist / 10.0));
            String label = "#" + waypoints.get(i).order;
            poseStack.pushPose();
            try {
                poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
                poseStack.mulPose(camera.rotation());
                poseStack.scale(s, -s, s);
                font.drawInBatch(label, -font.width(label) / 2f, -font.lineHeight / 2f, 0xFFFFFFFF, false,
                        poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
                        0, 0xF000F0);
            } finally {
                poseStack.popPose();
            }
        }
    }

    private static RoomEntry.Pos toPos(EtherwarpWaypoint w) {
        RoomEntry.Pos pos = new RoomEntry.Pos();
        pos.x = w.relX;
        pos.y = w.relY;
        pos.z = w.relZ;
        return pos;
    }

    /**
     * {@code /ew waypoint add [name]} - killer560 (2026-09-21): "instead of being on the block the player is
     * looking at instead it is the one they are standing on currently, centered on the block if they are
     * off-centered". The block under your feet. @return a status line.
     */
    public static String addAtFeet(String name) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return "§c[Etherwarp] You need to be in a world to add a waypoint.";
        }
        if (!EtherwarpWaypointsConfig.getInstance().isHighlightBlocks()) {
            return "§c[Etherwarp] Etherwarp Waypoints is off (Secrets > Etherwarp Waypoints).";
        }
        var below = BlockPos.containing(client.player.getX(), client.player.getY() - 0.05, client.player.getZ());
        return addAt(name, below);
    }

    /** @return a user-facing status message for the command/GUI to show. */
    public static String addAtLookTarget(String name) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return "§c[Etherwarp] You need to be in a world to add a waypoint.";
        }
        if (!EtherwarpWaypointsConfig.getInstance().isHighlightBlocks()) {
            return "§c[Etherwarp] Etherwarp Waypoints is off (Secrets > Etherwarp Waypoints).";
        }
        Vec3 pos = resolveLookTarget(client);
        if (pos == null) {
            return "§c[Etherwarp] Not looking at anything within " + (int) MAX_LOOK_DISTANCE + " blocks.";
        }
        return addAt(name, BlockPos.containing(pos.x, pos.y, pos.z));
    }

    /** Shared by {@link #addAtFeet} and {@link #addAtLookTarget}: converts the real block you picked into a
     *  room-relative position for the room you're currently standing in, and saves it - killer560, 2026-09-27:
     *  "Make sure they dont save based off of location but off of location in a room." */
    private static String addAt(String name, BlockPos real) {
        RoomContext ctx = currentRoomContext();
        if (ctx == null) {
            return "§c[Etherwarp] Stand in an identified dungeon room before adding a waypoint.";
        }
        RoomEntry.Pos relative = RoomDatabase.toRelativeCoord(real, ctx.clayX(), ctx.clayZ(), ctx.rotation());
        int order = EtherwarpWaypointsStore.nextOrder(ctx.room().name);
        String n = name == null || name.isBlank() ? "Waypoint " + order : name.trim();
        EtherwarpWaypointsStore.add(new EtherwarpWaypoint(null, n, ctx.room().name, relative.x, relative.y, relative.z, order));
        EtherwarpWaypointsStore.save();
        LOGGER.info("[Etherwarp] Added waypoint \"{}\" at {} (room={}, rel={},{},{}, #{})",
                n, real, ctx.room().name, relative.x, relative.y, relative.z, order);
        // killer560, 2026-09-27: "Do not pop up the hud when they are added." Callers used to wrap this
        // return value in ModOverlayMessage.show(...); they no longer do - see Killer560ModClient's command
        // registrations. The status string is kept (rather than returning void) so a failed add - wrong
        // room, feature off - still has somewhere to report to if a future caller wants it.
        return String.format(Locale.US, "[Etherwarp] Added \"%s\" in %s (#%d)", n, ctx.room().name, order);
    }

    /** {@code /ew waypoint remove} - the waypoint closest to you, among the ones saved for the room you're
     *  currently in (a saved waypoint from some other room template isn't "closest" to anything real right
     *  now - there's no world position for it until you're standing in that room). */
    public static String removeClosest() {
        Minecraft client = Minecraft.getInstance();
        RoomContext ctx = currentRoomContext();
        if (client.player == null || ctx == null) {
            return "§c[Etherwarp] Stand in an identified dungeon room first.";
        }
        List<EtherwarpWaypoint> saved = EtherwarpWaypointsStore.forRoom(ctx.room().name);
        if (saved.isEmpty()) {
            return "§c[Etherwarp] No waypoints in this room to remove.";
        }
        EtherwarpWaypoint best = null;
        double bestSq = Double.MAX_VALUE;
        for (EtherwarpWaypoint w : saved) {
            BlockPos real = RoomDatabase.toRealCoord(toPos(w), ctx.clayX(), ctx.clayZ(), ctx.rotation());
            double d = client.player.distanceToSqr(real.getX() + 0.5, real.getY() + 0.5, real.getZ() + 0.5);
            if (d < bestSq) {
                bestSq = d;
                best = w;
            }
        }
        EtherwarpWaypointsStore.remove(best.id);
        EtherwarpWaypointsStore.save();
        return "[Etherwarp] Removed \"" + best.name + "\"";
    }

    /** {@code /ew waypoint undo} - the last one added to the room you're currently in. */
    public static String undo() {
        RoomContext ctx = currentRoomContext();
        if (ctx == null) {
            return "§c[Etherwarp] Stand in an identified dungeon room first.";
        }
        List<EtherwarpWaypoint> saved = EtherwarpWaypointsStore.forRoom(ctx.room().name);
        if (saved.isEmpty()) {
            return "§c[Etherwarp] Nothing to undo in this room.";
        }
        EtherwarpWaypoint last = saved.get(saved.size() - 1);
        EtherwarpWaypointsStore.remove(last.id);
        EtherwarpWaypointsStore.save();
        return "[Etherwarp] Undid \"" + last.name + "\"";
    }

    /** Prefers whatever block/entity is actually under your crosshair right now (vanilla's own real-time
     *  hit result, the same thing the debug screen's "Looking at" line reads); falls back to a point a
     *  few blocks in front of you if nothing is in range, so the command still does something sensible
     *  rather than silently failing while free-looking at open air. */
    private static Vec3 resolveLookTarget(Minecraft client) {
        HitResult hit = client.hitResult;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
            var blockPos = blockHit.getBlockPos();
            return new Vec3(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
        }
        if (hit != null && hit.getType() != HitResult.Type.MISS) {
            return hit.getLocation();
        }
        if (client.player != null) {
            Vec3 eye = client.player.getEyePosition();
            Vec3 look = client.player.getLookAngle();
            return eye.add(look.scale(5));
        }
        return null;
    }

    /** For the GUI tab: every waypoint saved for the room you're currently standing in, in placement order -
     *  empty (not every waypoint ever saved) while you aren't in an identified room, since that's also all
     *  that will ever render right now. */
    public static List<EtherwarpWaypoint> waypointsHere() {
        RoomContext ctx = currentRoomContext();
        return ctx == null ? List.of() : EtherwarpWaypointsStore.forRoom(ctx.room().name);
    }

    public static void remove(String id) {
        EtherwarpWaypointsStore.remove(id);
        EtherwarpWaypointsStore.save();
        LOGGER.info("[Etherwarp] Removed waypoint id={}", id);
    }

    /** killer560, 2026-09-27: "If i do clear it should only clear the ones in the room I am in." @return a
     *  status line, same as every other command in this class. */
    public static String clearCurrentRoom() {
        RoomContext ctx = currentRoomContext();
        if (ctx == null) {
            return "§c[Etherwarp] Stand in an identified dungeon room first.";
        }
        int removed = EtherwarpWaypointsStore.clearRoom(ctx.room().name);
        EtherwarpWaypointsStore.save();
        LOGGER.info("[Etherwarp] Cleared {} waypoint(s) in room \"{}\"", removed, ctx.room().name);
        return "[Etherwarp] Cleared " + removed + " waypoint(s) in " + ctx.room().name + ".";
    }
}
