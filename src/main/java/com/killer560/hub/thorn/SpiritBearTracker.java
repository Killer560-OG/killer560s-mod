package com.killer560.hub.thorn;

import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Spirit Bear kill counter + spawn timer + overkill counter for the Thorn fight.
 * <p>
 * <b>Kill counter / timer - a faithful port of Odin {@code features/impl/boss/SpiritBear.kt}</b>
 * (github.com/odtheking/Odin, fetched 2026-09-15; CaribouStonks {@code ThornBossFeature.java} and NoammAddons 26.1.2
 * {@code F4Features.kt} use the identical signal and identical coordinates). The arena has a ring of blocks at y=77
 * that turns from {@code COAL_BLOCK} to {@code SEA_LANTERN} one block per spirit-animal kill; when the ring is full the
 * Spirit Bear spawns. The wiki (hypixelskyblock.minecraft.wiki/w/Thorn) and all three mods agree on the thresholds:
 * <b>F4 = 25 kills (25 ring blocks), M4 = 30 kills (30 ring blocks)</b>. The last block to light is always
 * {@code (7, 77, 34)}; it lighting starts a 68-tick spawn timer (Odin/NoammAddons 68, NoFrills 68, CaribouStonks 69
 * "68-70"); it going back to coal means the bear cycle is over (timer back to "not spawned").
 * <p>
 * Differences from Odin, all behaviour-preserving: Odin reacts to block-update packets through its own mixin event;
 * this polls the 25/30 fixed positions once per client tick (no mixin needed, and a mid-fight join/relog picks up the
 * ring's current state instead of starting at 0). Kills = number of lit ring blocks, which equals Odin's clamped
 * +1/-1 transition count. Odin's timer counts server ticks (ping-packet based); this counts client ticks, so it can
 * drift slightly under lag (same caveat as this mod's Tick Timers).
 * <p>
 * <b>Overkill - this mod's own addition</b> (no public mod has one; searched Odin, NoammAddons, NoFrills,
 * CaribouStonks, Skyblocker, SkyHanni, Skytils). Once the ring is full it can't show further kills, so overkill counts
 * spirit-animal deaths directly: a non-player, non-armor-stand, non-Ghast living entity that goes into its death state
 * ({@link LivingEntity#isDeadOrDying()} - vanilla sets health 0 on the client when the server sends the death entity
 * event) while the fight is active. Each ring block that lights is paired with one observed death (either order, within
 * {@link #PAIR_WINDOW_TICKS}) so the kill that fills the ring is never counted as overkill; a death with no ring block
 * left to pair with while the ring is full is overkill. The count for a bear cycle is kept as "last overkill" once the
 * ring resets.
 */
public final class SpiritBearTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-thorn");

    public static final int F4_KILLS = 25;
    public static final int M4_KILLS = 30;
    /** Odin/NoammAddons {@code bearSpawnTime} = 68 ticks. */
    public static final int BEAR_SPAWN_TICKS = 68;
    private static final int PAIR_WINDOW_TICKS = 40;

    /** Odin {@code lastBlockLocation} - the final ring block to light in both F4 and M4. */
    private static final BlockPos LAST_BLOCK = new BlockPos(7, 77, 34);

    /** Odin/NoammAddons {@code f4BlockLocations} (25). */
    private static final BlockPos[] F4_RING = {
            new BlockPos(-3, 77, 33), new BlockPos(-9, 77, 31), new BlockPos(-16, 77, 26), new BlockPos(-20, 77, 20), new BlockPos(-23, 77, 13),
            new BlockPos(-24, 77, 6), new BlockPos(-24, 77, 0), new BlockPos(-22, 77, -7), new BlockPos(-18, 77, -13), new BlockPos(-12, 77, -19),
            new BlockPos(-5, 77, -22), new BlockPos(1, 77, -24), new BlockPos(8, 77, -24), new BlockPos(14, 77, -23), new BlockPos(21, 77, -19),
            new BlockPos(27, 77, -14), new BlockPos(31, 77, -8), new BlockPos(33, 77, -1), new BlockPos(34, 77, 5), new BlockPos(33, 77, 12),
            new BlockPos(31, 77, 19), new BlockPos(27, 77, 25), new BlockPos(20, 77, 30), new BlockPos(14, 77, 33), new BlockPos(7, 77, 34)
    };

    /** Odin/NoammAddons/CaribouStonks {@code m4BlockLocations} (30). */
    private static final BlockPos[] M4_RING = {
            new BlockPos(-2, 77, 33), new BlockPos(-7, 77, 32), new BlockPos(-13, 77, 28), new BlockPos(-17, 77, 24), new BlockPos(-21, 77, 18),
            new BlockPos(-23, 77, 13), new BlockPos(-24, 77, 7), new BlockPos(-24, 77, 2), new BlockPos(-23, 77, -4), new BlockPos(-21, 77, -9),
            new BlockPos(-17, 77, -14), new BlockPos(-12, 77, -19), new BlockPos(-6, 77, -22), new BlockPos(-1, 77, -23), new BlockPos(5, 77, -24),
            new BlockPos(10, 77, -24), new BlockPos(16, 77, -22), new BlockPos(21, 77, -19), new BlockPos(27, 77, -15), new BlockPos(30, 77, -10),
            new BlockPos(32, 77, -5), new BlockPos(34, 77, 1), new BlockPos(34, 77, 7), new BlockPos(33, 77, 12), new BlockPos(31, 77, 18),
            new BlockPos(28, 77, 23), new BlockPos(23, 77, 28), new BlockPos(18, 77, 31), new BlockPos(12, 77, 33), new BlockPos(7, 77, 34)
    };

    /** Per ring position: null = not seen yet (unloaded / unexpected block), else lit (sea lantern) or not (coal). */
    private static Boolean[] ringState = new Boolean[M4_RING.length];
    private static BlockPos[] activeRing = null;
    private static int kills = 0;
    /** Odin's state: -1 = not spawned, 0 = alive, 1+ = spawning in N ticks. */
    private static int timer = -1;
    private static boolean ringFull = false;

    private static int overkill = 0;
    private static int lastOverkill = -1;
    private static int bearCycle = 1;
    private static boolean bearSeenAlive = false;
    private static boolean bearAliveNow = false;

    private static long tickCount = 0;
    private static final ArrayDeque<Long> pendingLights = new ArrayDeque<>();
    private static final ArrayDeque<Long> pendingDeaths = new ArrayDeque<>();
    private static final Set<Integer> countedDeaths = new HashSet<>();

    private SpiritBearTracker() {
    }

    public static void reset(String reason) {
        if (activeRing != null) {
            LOGGER.info("[Thorn] Spirit Bear tracker reset ({}): kills={} timer={} overkill={} lastOverkill={} cycle={}",
                    reason, kills, timer, overkill, lastOverkill, bearCycle);
        }
        ringState = new Boolean[M4_RING.length];
        activeRing = null;
        kills = 0;
        timer = -1;
        ringFull = false;
        overkill = 0;
        lastOverkill = -1;
        bearCycle = 1;
        bearSeenAlive = false;
        bearAliveNow = false;
        tickCount = 0;
        pendingLights.clear();
        pendingDeaths.clear();
        countedDeaths.clear();
    }

    public static int maxKills() {
        return ThornFeature.isMasterMode() ? M4_KILLS : F4_KILLS;
    }

    /** Called every client tick while the Thorn fight is active (see {@link ThornFeature}). */
    static void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null) {
            return;
        }
        BlockPos[] ring = ThornFeature.isMasterMode() ? M4_RING : F4_RING;
        boolean baseline = activeRing != ring;
        if (baseline) {
            activeRing = ring;
            ringState = new Boolean[ring.length];
        }
        tickCount++;
        if (timer > 0) {
            timer--;
            if (timer == 0) {
                LOGGER.info("[Thorn] Spirit Bear spawn timer done (cycle {}, {} kills)", bearCycle, kills);
            }
        }

        // ---- Ring poll ----
        int lights = 0;
        int lit = 0;
        for (int i = 0; i < ring.length; i++) {
            BlockPos pos = ring[i];
            Boolean now = null;
            if (level.isLoaded(pos)) {
                Block block = level.getBlockState(pos).getBlock();
                if (block == Blocks.SEA_LANTERN) {
                    now = Boolean.TRUE;
                } else if (block == Blocks.COAL_BLOCK) {
                    now = Boolean.FALSE;
                }
            }
            if (now == null) {
                // Unknown this tick - keep the last known state so a chunk hiccup doesn't count as a change.
                if (Boolean.TRUE.equals(ringState[i])) {
                    lit++;
                }
                continue;
            }
            Boolean before = ringState[i];
            ringState[i] = now;
            if (now) {
                lit++;
            }
            boolean isLast = pos.equals(LAST_BLOCK);
            if (before == null) {
                if (isLast && now && timer < 0) {
                    // Joined/relogged with the ring already full: the bear is already out (or about to be).
                    timer = 0;
                    LOGGER.info("[Thorn] Ring already full on first sight - treating Spirit Bear as spawned");
                }
                continue;
            }
            if (!before && now) {
                lights++;
                if (isLast) {
                    timer = BEAR_SPAWN_TICKS;
                    LOGGER.info("[Thorn] Last ring block lit - Spirit Bear #{} spawning in {}t", bearCycle, BEAR_SPAWN_TICKS);
                }
            } else if (before && !now && isLast) {
                timer = -1;
                LOGGER.info("[Thorn] Last ring block back to coal - bear cycle {} over", bearCycle);
            }
        }
        kills = Math.min(lit, ring.length);
        boolean fullNow = kills >= ring.length || Boolean.TRUE.equals(ringState[ring.length - 1]);

        // ---- Deaths + bear presence ----
        int deaths = 0;
        boolean bearNow = false;
        Set<Integer> present = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Player p && isSpiritBear(p)) {
                if (p.isAlive() && !p.isDeadOrDying()) {
                    bearNow = true;
                }
                continue;
            }
            if (!isKillableSpiritMob(entity)) {
                continue;
            }
            present.add(entity.getId());
            if (((LivingEntity) entity).isDeadOrDying() && countedDeaths.add(entity.getId())) {
                deaths++;
                if (!ThornEspFeature.isKnownSpiritAnimal(entity)) {
                    LOGGER.info("[Thorn] Death of unlisted mob type {} (\"{}\") counted as a spirit kill",
                            entity.getType().toShortString(), entity.getName().getString());
                }
            }
        }
        countedDeaths.retainAll(present);
        if (bearNow) {
            bearSeenAlive = true;
        }
        bearAliveNow = bearNow;

        // ---- Pair ring lights with deaths; unpaired deaths on a full ring are overkill ----
        expire(pendingLights);
        expire(pendingDeaths);
        for (int i = 0; i < lights; i++) {
            if (!pendingDeaths.isEmpty()) {
                pendingDeaths.pollFirst();
            } else {
                pendingLights.addLast(tickCount);
            }
        }
        for (int i = 0; i < deaths; i++) {
            if (!pendingLights.isEmpty()) {
                pendingLights.pollFirst();
            } else if (fullNow) {
                overkill++;
            } else {
                pendingDeaths.addLast(tickCount);
            }
        }
        if (fullNow && !pendingDeaths.isEmpty()) {
            // A full ring can't light any more blocks, so deaths still waiting for one (several mobs dying together
            // on the last kill) were beyond the threshold.
            overkill += pendingDeaths.size();
            pendingDeaths.clear();
        }

        // ---- Cycle end: ring stops being full ----
        if (ringFull && !fullNow) {
            finishCycle(client);
        }
        ringFull = fullNow;
    }

    private static void finishCycle(Minecraft client) {
        LOGGER.info("[Thorn] Bear cycle {} finished - overkill {}", bearCycle, overkill);
        lastOverkill = overkill;
        if (ThornConfig.getInstance().isOverkillChatEnabled() && client.player != null) {
            ModChat.send("Thorn", ModChat.text("Spirit Bear #" + bearCycle + " overkill: "),
                    ModChat.value(String.valueOf(overkill)));
        }
        overkill = 0;
        bearCycle++;
        bearSeenAlive = false;
        pendingLights.clear();
        pendingDeaths.clear();
    }

    private static void expire(ArrayDeque<Long> queue) {
        for (Iterator<Long> it = queue.iterator(); it.hasNext(); ) {
            if (tickCount - it.next() > PAIR_WINDOW_TICKS) {
                it.remove();
            }
        }
    }

    /** NoammAddons F4Features: a Player entity whose name starts with "spirit bear" (case-insensitive). */
    static boolean isSpiritBear(Entity entity) {
        return entity instanceof Player p && p.getName().getString().toLowerCase(Locale.ROOT).startsWith("spirit bear");
    }

    /** Anything in the arena that can die and isn't a player (bear / teammates), a name-tag stand, or Thorn (a Ghast). */
    static boolean isKillableSpiritMob(Entity entity) {
        return entity instanceof LivingEntity && !(entity instanceof Player) && !(entity instanceof ArmorStand)
                && !(entity instanceof Ghast);
    }

    // ---- HUD text ----

    /** Odin's HUD states, same colours: "§d17/25", "§e3.40s", "§aAlive!" (+ "§7Killed" once a seen bear is gone). */
    static String stateText() {
        if (timer < 0) {
            return "§d" + kills + "/" + maxKills();
        }
        if (timer > 0) {
            return String.format(Locale.US, "§e%.2fs", timer / 20.0);
        }
        if (bearSeenAlive && !bearAliveNow) {
            return "§7Killed";
        }
        return "§aAlive!";
    }

    /** Current cycle's overkill while the ring is full, otherwise the last finished cycle's (or 0). */
    static String overkillText() {
        if (ringFull) {
            return "§6Overkill: §e" + overkill;
        }
        return lastOverkill >= 0 ? "§6Last Overkill: §e" + lastOverkill : "§6Overkill: §70";
    }

    public static int getKills() { return kills; }
    public static int getOverkill() { return overkill; }
    public static int getLastOverkill() { return lastOverkill; }
    public static boolean isRingFull() { return ringFull; }
}
