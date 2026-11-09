package com.killer560.hub.mobesp;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

/**
 * Hide Ghost Mobs (killer560, 2026-11-09: "if a teammate is kind of far away and kills mobs in dungeons they will create
 * ghost mobs. on my screen they are there but the actual mob doesnt exist").
 * <p>
 * The signal: a server sends every entity it tracks for you a position packet at least every 60 server ticks even when
 * it stands still (26.3 {@code ServerEntity.sendChanges}: {@code tickCount % 60 == 0} forces the move packet, javap; the
 * 1.8 tracker Hypixel runs has the same {@code m % 60}). A mob the server no longer tracks for you (dead, gone) gets
 * nothing more, yet the client keeps drawing it until a remove packet that never comes. So a dungeon mob that heard no
 * move, look, data or motion packet for {@link #QUIET_TICKS} while other entities did is a ghost; the first packet for
 * it brings it back. Real players, passengers (the server sends no position for a rider) and, among armor stands, all
 * but mob name tags are never judged. Off the real server (the dungeon sim) nothing is judged.
 * <p>
 * Packets are counted by {@code mobesp.mixin.GhostMobPacketMixin} whenever you are in a dungeon, setting on or off, so the
 * once-a-world census line (logger killer560smod-ghostmobs) shows on Hypixel how long real mobs go between packets and how
 * many ghosts were flagged and later heard again (a false hide) - the proof the threshold is right.
 */
public final class GhostMobs {

    private static final Logger LOGGER = ModLog.get("killer560smod-ghostmobs");
    /** 10 s: over three of the server's 60-tick position heartbeats (four at Hypixel's 15 TPS lows). */
    static final int QUIET_TICKS = 200;
    /** Ghosts are judged only while some entity was heard within this many ticks, a little over one heartbeat (a stalled
     *  server is not a ghost). */
    private static final int LIVE_TICKS = 80;
    private static final int SCAN_EVERY = 10;

    /** Entity id -> client tick it was last heard of; cleared on world change and out of a dungeon. */
    private static final Int2LongOpenHashMap HEARD = new Int2LongOpenHashMap();
    /** Ids judged ghosts right now. */
    private static final IntOpenHashSet GHOSTS = new IntOpenHashSet();

    static {
        HEARD.defaultReturnValue(-1L);
    }

    private static long now;
    private static long lastHeard = Long.MIN_VALUE / 2;
    private static boolean active;
    private static boolean hide;
    private static Object level;
    // Census of this world (diagnostic): the longest gap between two packets of one entity, how many gaps passed 60 and
    // QUIET_TICKS ticks, and how many ghosts were flagged, heard again (a false hide) or removed by the server.
    private static int maxGap;
    private static int gapsOver60;
    private static int gapsOverQuiet;
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
        lastHeard = now;
        if (prev < 0) {
            return;
        }
        int gap = (int) (now - prev);
        maxGap = Math.max(maxGap, gap);
        if (gap > 60) {
            gapsOver60++;
        }
        if (gap > QUIET_TICKS) {
            gapsOverQuiet++;
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
        boolean live = now - lastHeard <= LIVE_TICKS;
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity) || e == client.player || e.isPassenger()
                    || e instanceof Player p && p.getUUID().version() != 2) {
                continue;
            }
            int id = e.getId();
            long h = HEARD.get(id);
            if (h < 0L) {
                HEARD.put(id, now);
            } else if (live && now - h > QUIET_TICKS && !GHOSTS.contains(id) && judged(e)) {
                GHOSTS.add(id);
                flagged++;
                LOGGER.info("[GhostMobs] #{} {} at {} blocks: no packet for {} ticks while others updated", id,
                        e.getName().getString(), (int) e.distanceTo(client.player), now - h);
            }
        }
        if (now % 200 == 0) {
            HEARD.keySet().removeIf(i -> client.level.getEntity(i) == null);
            GHOSTS.removeIf(i -> client.level.getEntity(i) == null);
        }
    }

    /** Armor stands only as a mob's health name tag (never a hologram or a decoration). */
    private static boolean judged(Entity e) {
        if (!(e instanceof ArmorStand)) {
            return true;
        }
        Component name = e.getCustomName();
        return name != null && name.getString().contains("❤");
    }

    /** One line per dungeon world (the evidence for the threshold), then a clean slate. */
    private static void census() {
        if (active && !HEARD.isEmpty()) {
            LOGGER.info("[GhostMobs] census: {} entities heard; longest gap between packets {} ticks, {} gaps over 60,"
                            + " {} over {}; {} ghosts flagged, {} heard again (false), {} removed by the server", HEARD.size(),
                    maxGap, gapsOver60, gapsOverQuiet, QUIET_TICKS, flagged, heardAgain, removed);
        }
        active = false;
        HEARD.clear();
        GHOSTS.clear();
        lastHeard = Long.MIN_VALUE / 2;
        maxGap = gapsOver60 = gapsOverQuiet = flagged = heardAgain = removed = 0;
    }

    /** Testkit: ids judged ghosts now, and the census counters {flagged, heardAgain, removed}. */
    public static int[] ghostIdsForTest() {
        return GHOSTS.toIntArray();
    }

    public static int[] countsForTest() {
        return new int[] {flagged, heardAgain, removed};
    }
}
