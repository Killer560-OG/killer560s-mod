package com.killer560.hub.bloodcamp;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * Mobs that stand in the Blood Room but are never the Watcher's: Primal Fears (killer560, 2026-11-01: "I had a primal fear
 * spawn in the room as blood camp was happening and trigger bot would hit it as well. And the mob esp would highlight it").
 * <p>
 * A Primal Fear is the Great Spook's mob: any kill can summon one beside the player, Blood Room included. Names from
 * hypixelskyblock.minecraft.wiki's "Primal Fear" page (read 2026-11-01): the Primal Fear itself and the six manifestations
 * one turns into when it dies. The wiki does not say what model they use, so a mob counts as a Fear by any of its names:
 * a player-model NPC's own name, the mob's custom name, or a name-tag stand over it (how Hypixel names every mob), each
 * checked as "contains" on the stripped text, so a level prefix or a health suffix does not hide it.
 * <p>
 * Remembered per entity id for {@link #REFRESH_TICKS}: the Trigger Bot asks about every mob near its look several times a
 * tick, and each answer is an entity search.
 */
final class BloodExclusions {

    static final List<String> PRIMAL_FEAR_NAMES = List.of("Primal Fear", "Commitment Phobia", "Darkness Shade",
            "Deadline", "Math Teacher", "Public Speaking Demon", "Vegan Crawler");

    private static final int REFRESH_TICKS = 10;
    /** id -> (game tick << 1) | isFear. */
    private static final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap MEMO = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
    private static Object memoLevel;
    /** Testkit: entities found to be a Primal Fear. */
    private static int fearsSeen;

    private BloodExclusions() {
    }

    static int fearsSeen() {
        return fearsSeen;
    }

    static boolean isPrimalFear(Minecraft client, Entity e) {
        if (client.level == null || e instanceof ArmorStand) {
            return false;
        }
        if (client.level != memoLevel || MEMO.size() > 4096) {
            MEMO.clear();
            memoLevel = client.level;
        }
        long now = client.level.getGameTime();
        if (MEMO.containsKey(e.getId())) {
            long v = MEMO.get(e.getId());
            if (now - (v >> 1) < REFRESH_TICKS && now >= (v >> 1)) {
                return (v & 1L) != 0L;
            }
        }
        boolean fear = lookUp(client, e);
        Long before = MEMO.containsKey(e.getId()) ? MEMO.get(e.getId()) : null;
        if (fear && (before == null || (before & 1L) == 0L)) {
            fearsSeen++;
            BloodCampFeature.LOGGER.info("[BloodCamp] Primal Fear seen, never a blood mob: {} #{}", e.getType().toShortString(),
                    e.getId());
        }
        MEMO.put(e.getId(), (now << 1) | (fear ? 1L : 0L));
        return fear;
    }

    private static boolean lookUp(Minecraft client, Entity e) {
        if (e instanceof Player p && p != client.player && named(p.getName().getString())) {
            return true;
        }
        if (e.hasCustomName() && named(e.getName().getString())) {
            return true;
        }
        // Its name tag: a named stand standing in or just above its box.
        for (Entity t : client.level.getEntities(e, e.getBoundingBox().inflate(0.4, 0.0, 0.4).expandTowards(0.0, 1.6, 0.0),
                x -> x instanceof ArmorStand && x.hasCustomName())) {
            if (named(t.getName().getString())) {
                return true;
            }
        }
        return false;
    }

    static boolean named(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 96) {
            return false;
        }
        String plain = com.killer560.hub.util.ChatObserver.stripCodes(raw);
        for (String n : PRIMAL_FEAR_NAMES) {
            if (plain.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
