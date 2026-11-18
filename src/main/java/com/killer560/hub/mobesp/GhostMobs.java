package com.killer560.hub.mobesp;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

/**
 * Hide Ghost Mobs (killer560, 2026-11-09: "if a teammate is kind of far away and kills mobs in dungeons they will create
 * ghost mobs. on my screen they are there but the actual mob doesnt exist").
 * <p>
 * Silence proves nothing on Hypixel (ghostmob-redo-1118, his 26.3 logs of 11-10..11-17, 89 dungeons): it sends no
 * position heartbeat for an idle mob, so the first rule (no packet for 200 ticks) hid 898 real mobs of 4091 flagged, at
 * every distance, after gaps of up to 2227 ticks; most came back the moment their room's door opened. The proof used now
 * is the dungeon map: a room shows its checkmark only once its starred mobs are dead, so a STARRED mob (its "✯ ... ❤"
 * tag, or the body under it) standing in a room the map shows cleared, which has had no packet of its own since before
 * GhostMobs saw that room cleared, is dead. Anything heard after the clear is alive and never hidden; the first packet for a
 * hidden one brings it back. Unstarred mobs (the Blood room's waiting mobs among them) and the boss are never judged, nor
 * anything off the real server (the dungeon sim).
 * <p>
 * Packets are counted by {@code mobesp.mixin.GhostMobPacketMixin} whenever you are in a dungeon, setting on or off, so the
 * once-a-world census line (logger killer560smod-ghostmobs) shows on Hypixel how many were flagged and how many of those
 * were later heard again (a false hide).
 */
public final class GhostMobs {

    private static final Logger LOGGER = ModLog.get("killer560smod-ghostmobs");
    /** Census only: gaps between two packets of one entity longer than this are counted. */
    private static final int LONG_GAP = 200;
    /** A mob that died with its room gets its remove packet when it was near; give that packet this long first. */
    static final int MIN_QUIET = 40;
    private static final int SCAN_EVERY = 10;

    /** Entity id -> client tick it was last heard of; cleared on world change and out of a dungeon. */
    private static final Int2LongOpenHashMap HEARD = new Int2LongOpenHashMap();
    /** Room (its lowest map tile, or the off-map key) -> client tick it was first seen cleared; at most one per room. */
    private static final Long2LongOpenHashMap CLEARED_AT = new Long2LongOpenHashMap();
    /** Ids judged ghosts right now. */
    private static final IntOpenHashSet GHOSTS = new IntOpenHashSet();

    static {
        HEARD.defaultReturnValue(-1L);
        CLEARED_AT.defaultReturnValue(-1L);
    }

    private static long now;
    private static boolean active;
    private static boolean hide;
    private static Object level;
    // Census of this world (diagnostic): the longest gap between two packets of one entity, how many gaps passed 60 and
    // LONG_GAP ticks, and how many ghosts were flagged, heard again (a false hide) or removed by the server.
    private static int maxGap;
    private static int gapsOver60;
    private static int gapsOverLong;
    private static int flagged;
    private static int heardAgain;
    private static int removed;

    private GhostMobs() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("GhostMobs", GhostMobs::tick));
    }

    /** True when the setting is on and {@code e} is a ghost: not drawn, highlighted or targeted. Render thread safe. */
    public static boolean hidden(Entity e) {
        return hide && !GHOSTS.isEmpty() && GHOSTS.contains(e.getId());
    }

    /** A packet about entity {@code id} was handled (client thread). */
    public static void heard(int id) {
        if (!active) {
            return;
        }
        long prev = HEARD.put(id, now);
        if (prev < 0) {
            return;
        }
        int gap = (int) (now - prev);
        maxGap = Math.max(maxGap, gap);
        if (gap > 60) {
            gapsOver60++;
        }
        if (gap > LONG_GAP) {
            gapsOverLong++;
        }
        if (GHOSTS.remove(id)) {
            heardAgain++;
            LOGGER.info("[GhostMobs] #{} heard again after {} ticks: a real mob, shown again", id, gap);
        }
    }

    /** The server removed entity {@code id}: its clock ends (a mob that walks back in starts a fresh one). */
    public static void removed(int id) {
        if (active && HEARD.remove(id) >= 0L && GHOSTS.remove(id)) {
            removed++;
        }
    }

    private static void tick(Minecraft client) {
        now++;
        if (client.level != level) {
            census();
            level = client.level;
        }
        hide = MobEspConfig.getInstance().isHideGhostMobs();
        boolean want = client.level != null && client.player != null && DungeonState.isInDungeon()
                && !DungeonState.isRoomSim() && !DungeonState.isSimOverrideActive();
        if (!want) {
            if (active) {
                census();
            }
            return;
        }
        active = true;
        if (now % SCAN_EVERY != 0) {
            return;
        }
        boolean judge = noteClearedRooms() && !LiveMapFeature.isInBoss();
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity) || e == client.player || e.isPassenger()
                    || e instanceof Player p && p.getUUID().version() != 2) {
                continue;
            }
            int id = e.getId();
            long h = HEARD.get(id);
            if (h < 0L) {
                HEARD.put(id, now);
            } else if (judge && !GHOSTS.contains(id)) {
                judge(client, e, id, h);
            }
        }
        if (now % 200 == 0) {
            HEARD.keySet().removeIf(i -> client.level.getEntity(i) == null);
            GHOSTS.removeIf(i -> client.level.getEntity(i) == null);
        }
    }

    /** Records the first tick each room reads cleared; true when any room has. */
    private static boolean noteClearedRooms() {
        DungeonLayout layout = DungeonLayout.current();
        for (int r = 0; r < layout.roomCount(); r++) {
            if (MobEspFeature.roomCleared(r)) {
                CLEARED_AT.putIfAbsent(roomId(layout, r), now);
            }
        }
        if (!MobEspFeature.TEST_CLEARED_ROOMS.isEmpty()) {
            for (long k : MobEspFeature.TEST_CLEARED_ROOMS) {
                CLEARED_AT.putIfAbsent(k, now);
            }
        }
        return !CLEARED_AT.isEmpty();
    }

    /** A room key that stays the same while the map renumbers its rooms: the room's lowest tile (off-map keys as they are). */
    private static long roomId(DungeonLayout layout, long room) {
        int[] tiles = room >= 0 && room < layout.roomCount() ? layout.tiles((int) room) : null;
        if (tiles == null || tiles.length == 0) {
            return room;
        }
        int min = tiles[0];
        for (int t : tiles) {
            min = Math.min(min, t);
        }
        return min;
    }

    /** A starred tag (with its body) or a remembered starred body, silent since before its room was seen cleared. */
    private static void judge(Minecraft client, Entity e, int id, long h) {
        Entity body = null;
        if (e instanceof ArmorStand stand) {
            String name = MobEspFeature.starredName(stand);
            if (name == null) {
                return;
            }
            body = MobEspFeature.resolveMob(client, stand, name);
        } else if (!MobEspFeature.KNOWN_STARRED.contains(id)) {
            return;
        }
        long room = MobEspFeature.roomKey(e.getX(), e.getZ());
        long clearedAt = MobEspFeature.roomCleared(room) ? CLEARED_AT.get(roomId(DungeonLayout.current(), room)) : -1L;
        if (clearedAt < 0L || h >= clearedAt || now - h < MIN_QUIET) {
            return;
        }
        long hb = body == null ? -1L : HEARD.get(body.getId());
        if (hb >= clearedAt) {
            return;   // its body was heard after the clear: alive
        }
        GHOSTS.add(id);
        flagged++;
        if (body != null && GHOSTS.add(body.getId())) {
            flagged++;
        }
        LOGGER.info("[GhostMobs] #{} {} at {} blocks: no packet for {} ticks, its room cleared {} ticks ago{}", id,
                e.getName().getString(), (int) e.distanceTo(client.player), now - h, now - clearedAt,
                body == null ? "" : " (with its mob #" + body.getId() + ")");
    }

    /** One line per dungeon world (the evidence for the rule), then a clean slate. */
    private static void census() {
        if (active && !HEARD.isEmpty()) {
            LOGGER.info("[GhostMobs] census: {} entities heard; longest gap between packets {} ticks, {} gaps over 60,"
                            + " {} over {}; {} rooms cleared; {} ghosts flagged, {} heard again (false), {} removed by the"
                            + " server", HEARD.size(), maxGap, gapsOver60, gapsOverLong, LONG_GAP, CLEARED_AT.size(), flagged,
                    heardAgain, removed);
        }
        active = false;
        HEARD.clear();
        GHOSTS.clear();
        CLEARED_AT.clear();
        maxGap = gapsOver60 = gapsOverLong = flagged = heardAgain = removed = 0;
    }

    /** Testkit: ids judged ghosts now, and the census counters {flagged, heardAgain, removed}. */
    public static int[] ghostIdsForTest() {
        return GHOSTS.toIntArray();
    }

    public static int[] countsForTest() {
        return new int[] {flagged, heardAgain, removed};
    }
}
