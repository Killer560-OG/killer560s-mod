package com.killer560.hub.etherwarp;

import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
    }

    private static void tick() {
        boolean inDungeonNow = DungeonState.isInDungeon();
        if (inDungeonNow && !wasInDungeon) {
            waypoints.clear();
            LOGGER.info("[Etherwarp] New dungeon run detected - cleared waypoint list.");
        }
        wasInDungeon = inDungeonNow;
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
