package com.killer560.hub.witherdoors;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.HeldMap;
import com.killer560.hub.livemap.LiveMapReads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wither Doors without Show All Doors: only the doors the dungeon map in his hotbar shows. killer560, 2026-10-15: "it
 * will only show the door it can see from the normal map if that makes sense not all doors but it would be waypoint
 * style", "the mod should not show wither doors before mort finishes his countdown", "highlight fairy door should only
 * happen without the cheat see all doors option once you know fairy door is there", "if you dont have see all doors on
 * you cannot see the door after fairy either until fairy is opened and the door is loaded".
 *
 * <p><b>Which doors.</b> Every door cell the map ({@link HeldMap}) colors WITHER or BLOOD with at least one of its two
 * rooms opened - the map shows no door that no opened room reaches, so the door after an unopened Fairy is never one.
 * A door into an opened Fairy room (map color 82) is only drawn once its block is loaded, so its lock state is real.
 * A wither door whose block is loaded and air is open (the map recolors it on its next update) and is dropped.
 *
 * <p><b>The Fairy door.</b> Hypixel's map draws the door into an unexplored Fairy room black, like a wither door, while
 * the doorway is empty. So a map-WITHER door from an opened room into an unopened one, whose doorway he has SEEN to be
 * empty (a ray from his eyes reached the doorway's middle without hitting anything), is the Fairy door: drawn in the
 * Fairy color instead of as a wither door. Until he has seen it, it is what the map says it is, a wither door. It
 * stops being the Fairy door once the room beyond is opened on the map, or he or a teammate stands in it.
 *
 * <p>Nothing here scans: the only blocks read are the anchor block of a door the map already shows (its lock state,
 * which the map itself shows a moment later) and the blocks along a ray from his eyes.
 */
final class MapDoors {

    enum Kind { WITHER, BLOOD, FAIRY }

    /** One door to draw: the 11x11 cell, its kind, and its anchor block. */
    record Door(int idx, Kind kind, BlockPos pos) {
    }

    /** How far a doorway can be and still be looked at for the Fairy check. */
    private static final double SIGHT_RANGE = 64.0;
    /** Door cells whose doorway he has seen into, and what it held: true = empty. Per run. */
    private static final Map<Integer, Boolean> SEEN_EMPTY = new HashMap<>();
    private static int generation = Integer.MIN_VALUE;
    private static Object level = null;
    private static String status = "idle";

    private MapDoors() {
    }

    static void reset() {
        SEEN_EMPTY.clear();
    }

    static String status() {
        return status;
    }

    /** Test hook: door cells whose doorway he has seen, and whether it was empty. */
    static Map<Integer, Boolean> seen() {
        return new HashMap<>(SEEN_EMPTY);
    }

    /** The doors to draw now. Client thread, from the Wither Doors rebuild (every 500 ms). */
    static List<Door> doors(Minecraft client) {
        int gen = com.killer560.hub.livemap.LiveMapFeature.resetGeneration();
        if (gen != generation || client.level != level) {
            generation = gen;
            level = client.level;
            reset();
        }
        List<Door> out = new ArrayList<>();
        if (!HeldMap.available()) {
            status = "no map";
            return out;
        }
        Vec3 eye = client.player.getEyePosition();
        int shown = 0;
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            if (!HeldMap.isDoorCell(idx)) {
                continue;
            }
            int type = HeldMap.doorType(idx);
            if (type != DungeonLayout.DOOR_WITHER && type != DungeonLayout.DOOR_BLOOD) {
                continue;
            }
            int[] s = HeldMap.sides(idx);
            boolean openA = HeldMap.roomOpened(s[0]);
            boolean openB = HeldMap.roomOpened(s[1]);
            if (!openA && !openB) {
                continue; // no opened room reaches it: the door after an unopened Fairy, among others
            }
            BlockPos pos = DungeonLayout.doorBlock(idx);
            boolean loaded = client.level.isLoaded(pos);
            boolean fairySide = "FAIRY".equals(HeldMap.roomType(s[0])) || "FAIRY".equals(HeldMap.roomType(s[1]));
            if (fairySide && !loaded) {
                continue; // past an opened Fairy: only once the door itself is loaded
            }
            shown++;
            if (type == DungeonLayout.DOOR_BLOOD) {
                if (!loaded || !client.level.getBlockState(pos).isAir()) {
                    out.add(new Door(idx, Kind.BLOOD, pos));
                }
                continue;
            }
            // A map-WITHER door from an opened room into an unopened one may be the Fairy door.
            boolean intoUnopened = openA != openB;
            int beyond = openA ? s[1] : s[0];
            if (intoUnopened && loaded) {
                look(client, eye, idx, pos);
            }
            Boolean empty = SEEN_EMPTY.get(idx);
            if (intoUnopened && Boolean.TRUE.equals(empty) && client.level.getBlockState(pos).isAir()) {
                if (!someoneInside(client, beyond)) {
                    out.add(new Door(idx, Kind.FAIRY, pos));
                }
                continue;
            }
            if (loaded && client.level.getBlockState(pos).isAir()) {
                // Seen empty from outside sight, or opened: either way not a shut wither door. Until he has seen it,
                // a doorway the map calls a wither door is drawn as one only while its block is not known to be air.
                if (empty == null && intoUnopened) {
                    out.add(new Door(idx, Kind.WITHER, pos));
                }
                continue;
            }
            out.add(new Door(idx, Kind.WITHER, pos));
        }
        status = "map shows " + shown + " wither/blood door(s), drawing " + out.size();
        return out;
    }

    /** Whether he or a teammate the map places stands inside the tile beyond the door. */
    private static boolean someoneInside(Minecraft client, int tile) {
        BlockPos c = DungeonLayout.cellCenter(tile);
        if (inside(c, client.player.getX(), client.player.getZ())) {
            return true;
        }
        for (LiveMapReads.Mate m : LiveMapReads.teammates(client)) {
            if (inside(c, m.x(), m.z())) {
                return true;
            }
        }
        return false;
    }

    private static boolean inside(BlockPos c, double x, double z) {
        return Math.abs(Math.floor(x) - c.getX()) <= 14 && Math.abs(Math.floor(z) - c.getZ()) <= 14;
    }

    /**
     * Rays from his eyes to the middle of the doorway (the anchor block and the two above it). A ray that reaches its
     * point without hitting a block on the way means he can see that point, and so what is in the doorway: empty when
     * the anchor block is air. Only the blocks along each ray are read - that is what seeing is.
     */
    private static void look(Minecraft client, Vec3 eye, int idx, BlockPos pos) {
        Vec3 mid = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        if (eye.distanceToSqr(mid) > SIGHT_RANGE * SIGHT_RANGE) {
            return;
        }
        for (int dy = 0; dy <= 2; dy++) {
            Vec3 target = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5 + dy, pos.getZ() + 0.5);
            if (clearSight(client, eye, target)) {
                SEEN_EMPTY.put(idx, client.level.getBlockState(pos).isAir());
                return;
            }
        }
    }

    /** True when nothing solid stands between the eye and the target point. */
    static boolean clearSight(Minecraft client, Vec3 eye, Vec3 target) {
        HitResult hit = client.level.clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                client.player));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        if (hit instanceof BlockHitResult bhr) {
            BlockPos tb = BlockPos.containing(target);
            if (bhr.getBlockPos().equals(tb)) {
                return true; // the target block itself is what stopped the ray: it is seen
            }
        }
        return hit.getLocation().distanceToSqr(eye) >= target.distanceToSqr(eye) - 0.01;
    }
}
