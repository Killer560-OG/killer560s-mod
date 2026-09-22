package com.killer560.hub.etherwarp;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Personal secret/etherwarp-spot bookmarks for the current dungeon run - killer560's "secret waypoints"
 * request, using {@code /killer560 ew add <name>} to mark whatever block you're currently looking at.
 * <p>
 * Deliberately NOT built on top of {@link com.killer560.hub.posmsg.PosmsgFeature}'s wire format even
 * though the two look similar (name + coords + a HUD list) - Posmsg's {@code addAndSend} immediately
 * broadcasts to your real Party Chat, which is exactly right for sharing a callout with teammates but
 * would be a surprising, unwanted message sent on your behalf every time you privately bookmark a secret
 * spot for yourself. So this is a separate, local-only list: nothing here is ever sent anywhere or
 * written to disk (dungeon room layouts differ every run, so yesterday's coordinates would just be
 * garbage today - {@link EtherwarpWaypointsConfig} only persists the HUD-visibility toggle). The list is
 * cleared automatically the moment a new dungeon run starts, same "leaving true -&gt; false" edge every
 * other per-run reset in this mod ({@link com.killer560.hub.posmsg.PosmsgFeature}, ability timers, etc.)
 * already watches via {@link DungeonState#isInDungeon()}.
 * <p>
 * Does not automate the actual Etherwarp teleport itself - that's a real ability-use action, out of
 * scope here the same way this mod never auto-clicks anything outside its already-disclosed cheat-build
 * gate. This only remembers WHERE to aim it.
 */
public final class EtherwarpFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-etherwarp");
    private static final double MAX_LOOK_DISTANCE = 64.0;

    private static final List<EtherwarpWaypoint> waypoints = new ArrayList<>();
    private static boolean wasInDungeon = false;

    private EtherwarpFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(EtherwarpFeature::onWorldRender);
    }

    /**
     * Draws a box on the block each waypoint marks.
     * <p>
     * killer560, 2026-09-20: "it shows a little hud on the left with distance, instead it should highlight
     * the block I was looking at." A distance readout makes you convert a number into a place; a box on the
     * block just tells you. The HUD list is still available as a setting for anyone who wants both, but it
     * is no longer the only way to find a spot you marked.
     */
    private static void onWorldRender(LevelRenderContext context) {
        EtherwarpWaypointsConfig cfg = EtherwarpWaypointsConfig.getInstance();
        if (!cfg.isHighlightBlocks() || waypoints.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !DungeonState.isInDungeon()) {
            return;
        }
        float[] rgb = cfg.highlightRgb();
        Vec3 eye = client.player.getEyePosition();
        double maxSq = cfg.getHighlightDistance() * cfg.getHighlightDistance();
        for (EtherwarpWaypoint w : waypoints) {
            // Cull by distance rather than drawing every bookmark every frame - the same lesson the secret
            // waypoints renderer learned the hard way when it was one of the real FPS culprits.
            if (eye.distanceToSqr(w.x + 0.5, w.y + 0.5, w.z + 0.5) > maxSq) {
                continue;
            }
            // The block the waypoint is in - floor, so a centred (x.5) position boxes its own block, not a
            // block-and-a-half.
            double bx = Math.floor(w.x);
            double by = Math.floor(w.y);
            double bz = Math.floor(w.z);
            AABB box = new AABB(bx, by, bz, bx + 1, by + 1, bz + 1);
            WorldRenderUtils.renderOutlineBox(context, box, rgb[0], rgb[1], rgb[2], 1f, 2f);
        }
    }

    private static void tick() {
        boolean inDungeonNow = DungeonState.isInDungeon();
        if (inDungeonNow && !wasInDungeon) {
            waypoints.clear();
            LOGGER.info("[Etherwarp] New dungeon run detected - cleared waypoint list.");
        }
        wasInDungeon = inDungeonNow;
    }

    /**
     * {@code /ew waypoint add [name]} - killer560 (2026-09-21): "instead of being on the block the player is looking
     * at instead it is the one they are standing on currently, centered on the block if they are off-centered". The
     * block under your feet, centred. @return a status line.
     */
    public static String addAtFeet(String name) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return "\u00a7c[Etherwarp] You need to be in a world to add a waypoint.";
        }
        if (!EtherwarpWaypointsConfig.getInstance().isHighlightBlocks()) {
            return "\u00a7c[Etherwarp] Etherwarp Waypoints is off (Secrets > Etherwarp Waypoints).";
        }
        var below = net.minecraft.core.BlockPos.containing(client.player.getX(), client.player.getY() - 0.05, client.player.getZ());
        String n = name == null || name.isBlank() ? "Waypoint " + (waypoints.size() + 1) : name.trim();
        waypoints.add(new EtherwarpWaypoint(n, below.getX() + 0.5, below.getY() + 0.5, below.getZ() + 0.5));
        LOGGER.info("[Etherwarp] Added waypoint \"{}\" at block {}", n, below);
        return String.format(Locale.US, "[Etherwarp] Added \"%s\" on (%d, %d, %d)", n, below.getX(), below.getY(), below.getZ());
    }

    /** {@code /ew waypoint remove} - the waypoint closest to you. */
    public static String removeClosest() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || waypoints.isEmpty()) {
            return "\u00a7c[Etherwarp] No waypoints to remove.";
        }
        EtherwarpWaypoint best = null;
        double bestSq = Double.MAX_VALUE;
        for (EtherwarpWaypoint w : waypoints) {
            double d = client.player.distanceToSqr(w.x, w.y, w.z);
            if (d < bestSq) {
                bestSq = d;
                best = w;
            }
        }
        waypoints.remove(best);
        return "[Etherwarp] Removed \"" + best.name + "\"";
    }

    /** {@code /ew waypoint undo} - the last one added. */
    public static String undo() {
        if (waypoints.isEmpty()) {
            return "\u00a7c[Etherwarp] Nothing to undo.";
        }
        EtherwarpWaypoint w = waypoints.remove(waypoints.size() - 1);
        return "[Etherwarp] Undid \"" + w.name + "\"";
    }

    /** @return a user-facing status message for the command/GUI to show. */
    public static String addAtLookTarget(String name) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return "§c[Etherwarp] You need to be in a world to add a waypoint.";
        }
        Vec3 pos = resolveLookTarget(client);
        if (pos == null) {
            return "§c[Etherwarp] Not looking at anything within " + (int) MAX_LOOK_DISTANCE + " blocks.";
        }
        EtherwarpWaypoint waypoint = new EtherwarpWaypoint(name, pos.x, pos.y, pos.z);
        waypoints.add(waypoint);
        LOGGER.info("[Etherwarp] Added waypoint \"{}\" at {}", name, pos);
        return String.format(Locale.US, "[Etherwarp] Added \"%s\" at (%.1f, %.1f, %.1f)",
                name, pos.x, pos.y, pos.z);
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

    public static List<EtherwarpWaypoint> waypoints() {
        return waypoints;
    }

    public static void remove(String id) {
        boolean removed = waypoints.removeIf(w -> w.id.equals(id));
        LOGGER.info("[Etherwarp] remove waypoint id={} -> {} ({} left)", id, removed ? "removed" : "NOT FOUND", waypoints.size());
    }

    public static void clear() {
        LOGGER.info("[Etherwarp] Cleared {} waypoint(s) manually", waypoints.size());
        waypoints.clear();
    }
}
