package com.killer560.hub.witherdoors;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Wither Doors highlight. killer560: "Also add wither door highlight. Legit should only highlight the
 * closest wither door, cheat should have an option to show every wither door/blood door. Make them red
 * and the closest one green once you get a key and a different color than the other ones."
 * <ul>
 * <li><b>Wither vs Blood:</b> distinguished the same way {@code doorhelpers.DoorScanner} already does it -
 * they are different {@link WitherDoorScanner.DoorType} values, read from the block sitting at the door's
 * anchor position (COAL_BLOCK = Wither, RED_TERRACOTTA = Blood) the first time each door cell is scanned.
 * The legit "closest door" search only ever looks at {@code DoorType.WITHER} cells - a locked Blood door is
 * never considered a candidate for it, so a run with the Blood door closer than every Wither door still
 * highlights the nearest Wither door, not the Blood door.
 * <li><b>Key state:</b> ported from the same three chat lines {@code doorhelpers.LookAtDoorFeature.onChat}
 * already trusts ("has obtained Wither/Blood Key", "Wither/Blood Key was picked up", the "RIGHT CLICK on a
 * WITHER door"/"RIGHT CLICK on the BLOOD DOOR" prompts), independently tracked here since that class's
 * state is private and its listener only fires useful renders while Door Helpers' own cheat-only toggles
 * are on. Like that class, this does not filter by which player's name is in the message - any party
 * member obtaining the key marks it held, same as the existing behaviour it mirrors.
 * <li><b>Legit honesty:</b> the closest-door box is always drawn with {@code WorldRenderUtils.renderOutlineBox}
 * (depth-tested, so it disappears behind geometry) - "Show All Doors" and "Through Walls" only exist as
 * {@link WitherDoorsConfig} getters gated on {@link BuildVariant#CHEAT_FEATURES_ENABLED}, so the legit jar
 * cannot compile a path that sets either true regardless of a hand-edited config file.
 * <li><b>p3sim:</b> gated on {@code CheatUtils.isOnDungeonServer}, same as Door Helpers.
 * <li><b>Render cost:</b> the door grid scan ({@link WitherDoorScanner}) only touches unscanned cells and
 * re-checks every {@code RESCAN_TICKS} (10) ticks, same as the doorhelpers original. The highlight list
 * itself is rebuilt on the client tick, not the render frame, at most every {@link #CACHE_TTL_MS}; the
 * frame path only walks that cached, already distance-culled list. Everything above (scan, cache rebuild,
 * even the chat listener's early-out) short-circuits the moment {@link WitherDoorsConfig#isEnabled()} is
 * false, so an off Wither Doors costs one boolean check per tick and nothing per frame.
 * </ul>
 */
public final class WitherDoorsFeature {

    /** One ready-to-draw box. Built on the tick, consumed by {@link WitherDoorsRenderer} on the frame. */
    record DoorBox(AABB box, float r, float g, float b) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-witherdoors");

    private static boolean witherKeyHeld = false;
    private static boolean bloodKeyHeld = false;
    private static Object lastLevel = null;

    /** The per-tick snapshot the render path walks. Never rebuilt from inside a frame. */
    private static final List<DoorBox> CACHED = new ArrayList<>();
    private static long cacheStampMs = 0L;
    /** Matches the scanner's own 10-tick (~500ms) rescan cadence - rebuilding the highlight list any
     *  faster than the data underneath it can change would just be wasted work. */
    private static final long CACHE_TTL_MS = 500L;

    /** Half-width of the highlighted opening, and half-thickness of the wall it sits in - the real door
     *  frame's exact footprint was not confirmed against a live client, so this is an approximation sized
     *  like a normal dungeon corridor opening (same category of approximation as the aim point
     *  {@code doorhelpers.LookAtDoorFeature.aimPoint} already uses for this same anchor). See staging notes.
     *  <p>
     *  killer560: "there is a 3 thick layer of door, and it only highlights the middle most portion not the
     *  outermost as a whole box. It needs to be pushed back and brought forward one." A wither/blood door
     *  is 3 blocks thick along the axis you walk through it (its anchor block at y=69 is the middle of
     *  those 3), but the box only ever spanned {@code +-0.55} either side of that anchor - just the middle
     *  layer. {@code HALF_THIN} now reaches one full block past the anchor in both directions
     *  ({@code 0.55 + 1.0}), covering all 3 layers, while {@link #HALF_OPEN} (the door's width) and
     *  {@link #DOOR_Y_MIN}/{@link #DOOR_Y_MAX} (its height) are untouched, per the request. */
    private static final double HALF_OPEN = 1.5;
    private static final double HALF_THIN = 1.55;
    private static final double DOOR_Y_MIN = 69.0;
    private static final double DOOR_Y_MAX = 73.0;

    private static String lastLoggedGates = null;

    private WitherDoorsFeature() {
    }

    public static void register() {
        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            WitherDoorsRenderer.init();
        }
        ClientTickEvents.END_CLIENT_TICK.register(WitherDoorsFeature::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                onChat(message.getString());
            }
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(WitherDoorsFeature::onWorldRender);
        LOGGER.info("[WitherDoors] Registered (cheatBuild={})", BuildVariant.CHEAT_FEATURES_ENABLED);
    }

    /** Forces the next client tick to rebuild the highlight snapshot (config change, world change). */
    public static void invalidateCache() {
        cacheStampMs = 0L;
    }

    private static void onChat(String raw) {
        String msg = ChatFormatting.stripFormatting(raw);
        if (msg == null) {
            return;
        }
        if (msg.contains("has obtained Wither Key") || msg.contains("Wither Key was picked up")
                || msg.startsWith("RIGHT CLICK on a WITHER door")) {
            if (!witherKeyHeld) {
                witherKeyHeld = true;
                invalidateCache();
            }
        } else if (msg.contains("has obtained Blood Key") || msg.contains("Blood Key was picked up")
                || msg.startsWith("RIGHT CLICK on the BLOOD DOOR")) {
            if (!bloodKeyHeld) {
                bloodKeyHeld = true;
                invalidateCache();
            }
        } else if (msg.contains("opened a WITHER door")) {
            witherKeyHeld = false;
            invalidateCache();
        } else if (msg.contains("The BLOOD DOOR has been opened")) {
            bloodKeyHeld = false;
            invalidateCache();
        }
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            WitherDoorScanner.reset();
            witherKeyHeld = false;
            bloodKeyHeld = false;
            invalidateCache();
        }
        WitherDoorsConfig cfg = WitherDoorsConfig.getInstance();
        String gate = gate(client, cfg);
        logGate(gate == null ? "active" : gate);
        if (gate != null) {
            CACHED.clear();
            cacheStampMs = 0L;
            return;
        }
        WitherDoorScanner.tick(client);
        long now = System.currentTimeMillis();
        if (cacheStampMs != 0L && now - cacheStampMs < CACHE_TTL_MS) {
            return;
        }
        cacheStampMs = now;
        rebuild(client, cfg);
    }

    /** @return null while Wither Doors may render, else the reason (mirrors doorhelpers' own gate shape). */
    private static String gate(Minecraft client, WitherDoorsConfig cfg) {
        if (!cfg.isEnabled()) {
            return "disabled";
        }
        if (client.level == null || client.player == null) {
            return "no-player";
        }
        if (!CheatUtils.isOnDungeonServer(client)) {
            return "not on hypixel/p3sim";
        }
        if (!DungeonState.isInDungeon()) {
            return "not in dungeon";
        }
        if (DungeonState.isBossPhaseActive()) {
            return "in boss";
        }
        return null;
    }

    private static void rebuild(Minecraft client, WitherDoorsConfig cfg) {
        CACHED.clear();
        List<WitherDoorScanner.Door> locked = WitherDoorScanner.lockedDoors(client);
        if (locked.isEmpty()) {
            return;
        }
        Vec3 eye = client.player.getEyePosition();
        WitherDoorScanner.Door nearestWither = null;
        WitherDoorScanner.Door bloodDoor = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (WitherDoorScanner.Door door : locked) {
            if (door.type() == WitherDoorScanner.DoorType.WITHER) {
                double distSq = eye.distanceToSqr(center(door));
                if (distSq < nearestDistSq) {
                    nearestDistSq = distSq;
                    nearestWither = door;
                }
            } else if (door.type() == WitherDoorScanner.DoorType.BLOOD) {
                bloodDoor = door; // exactly one Blood door per dungeon
            }
        }

        double maxSq = (double) cfg.getRenderDistance() * cfg.getRenderDistance();
        boolean showAll = cfg.isShowAllDoors();

        if (nearestWither != null && nearestDistSq <= maxSq) {
            int color = witherKeyHeld ? cfg.getWitherReadyColor() : cfg.getWitherLockedColor();
            addBox(nearestWither, color);
        }
        if (!showAll) {
            return;
        }
        for (WitherDoorScanner.Door door : locked) {
            if (door.type() == WitherDoorScanner.DoorType.WITHER && door != nearestWither
                    && eye.distanceToSqr(center(door)) <= maxSq) {
                addBox(door, cfg.getWitherLockedColor());
            }
        }
        if (bloodDoor != null && eye.distanceToSqr(center(bloodDoor)) <= maxSq) {
            int color = bloodKeyHeld ? cfg.getBloodReadyColor() : cfg.getBloodLockedColor();
            addBox(bloodDoor, color);
        }
    }

    private static Vec3 center(WitherDoorScanner.Door door) {
        return new Vec3(door.x() + 0.5, (DOOR_Y_MIN + DOOR_Y_MAX) / 2.0, door.z() + 0.5);
    }

    private static void addBox(WitherDoorScanner.Door door, int argb) {
        float[] rgba = com.killer560.hub.util.WorldRenderUtils.argbToFloats(argb);
        double cx = door.x() + 0.5;
        double cz = door.z() + 0.5;
        AABB box = door.wallAlongZ()
                ? new AABB(cx - HALF_THIN, DOOR_Y_MIN, cz - HALF_OPEN, cx + HALF_THIN, DOOR_Y_MAX, cz + HALF_OPEN)
                : new AABB(cx - HALF_OPEN, DOOR_Y_MIN, cz - HALF_THIN, cx + HALF_OPEN, DOOR_Y_MAX, cz + HALF_THIN);
        CACHED.add(new DoorBox(box, rgba[0], rgba[1], rgba[2]));
    }

    private static void onWorldRender(LevelRenderContext context) {
        // The tick decides what is in here; an empty snapshot means "off, not in a dungeon clear, or
        // nothing near" - no per-frame config or gate re-check needed on top of that.
        if (CACHED.isEmpty()) {
            return;
        }
        WitherDoorsRenderer.draw(context, CACHED, WitherDoorsConfig.getInstance().isThroughWalls());
    }

    private static void logGate(String gate) {
        if (!gate.equals(lastLoggedGates)) {
            lastLoggedGates = gate;
            LOGGER.info("[WitherDoors] state: {}", gate);
        }
    }
}
