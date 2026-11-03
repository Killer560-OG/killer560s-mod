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

    /** The Watcher's two Watchful Eyes (hypixelskyblock.minecraft.wiki "The Watcher"; owner's 2026-10-18 screenshots read
     *  "Watchful Eye" in their name tags). Named here so a name tag over one never makes it a blood mob. */
    static final List<String> WATCHFUL_EYE_NAMES = List.of("Watchful Eye");
    /** id -> (game tick << 1) | isEye, as {@link #MEMO}. */
    private static final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap EYE_MEMO = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();

    /**
     * Whether {@code e} is named "Watchful Eye": its custom name, or the name-tag stand one id above it (BloodCampFeature).
     * Never a player-model mob, and never by a name tag merely standing near it: the Eyes circle the Watcher with their
     * tags among the blood mobs, and the owner's camp 2026-11-02 run 3 logged "Watchful Eye, never a blood mob: player
     * #481662 (by name)" - a real blood mob, the first one the Trigger Bot killed - from an Eye's tag inside its box.
     */
    static boolean isWatchfulEye(Minecraft client, Entity e) {
        if (client.level == null || e instanceof ArmorStand || e instanceof Player) {
            return false;
        }
        if (client.level != memoLevel || EYE_MEMO.size() > 4096) {
            EYE_MEMO.clear();
        }
        long now = client.level.getGameTime();
        if (EYE_MEMO.containsKey(e.getId())) {
            long v = EYE_MEMO.get(e.getId());
            if (now - (v >> 1) < REFRESH_TICKS && now >= (v >> 1)) {
                return (v & 1L) != 0L;
            }
        }
        boolean eye = (e.hasCustomName() && named(e.getName().getString(), WATCHFUL_EYE_NAMES))
                || (client.level.getEntity(e.getId() + 1) instanceof ArmorStand tag && tag.hasCustomName()
                        && named(tag.getName().getString(), WATCHFUL_EYE_NAMES));
        EYE_MEMO.put(e.getId(), (now << 1) | (eye ? 1L : 0L));
        return eye;
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
        boolean fear = lookUp(client, e, PRIMAL_FEAR_NAMES);
        Long before = MEMO.containsKey(e.getId()) ? MEMO.get(e.getId()) : null;
        if (fear && (before == null || (before & 1L) == 0L)) {
            fearsSeen++;
            BloodCampFeature.LOGGER.info("[BloodCamp] Primal Fear seen, never a blood mob: {} #{}", e.getType().toShortString(),
                    e.getId());
        }
        MEMO.put(e.getId(), (now << 1) | (fear ? 1L : 0L));
        return fear;
    }

    private static boolean lookUp(Minecraft client, Entity e, List<String> names) {
        if (e instanceof Player p && p != client.player && named(p.getName().getString(), names)) {
            return true;
        }
        if (e.hasCustomName() && named(e.getName().getString(), names)) {
            return true;
        }
        // Its name tag: the stand one id above it (Hypixel spawns a mob's tag right after the mob, the pairing
        // BloodMobNames.mobFor reads), or a named stand standing in or just above its box.
        if (client.level.getEntity(e.getId() + 1) instanceof ArmorStand tag && tag.hasCustomName()
                && named(tag.getName().getString(), names)) {
            return true;
        }
        for (Entity t : client.level.getEntities(e, e.getBoundingBox().inflate(0.4, 0.0, 0.4).expandTowards(0.0, 1.6, 0.0),
                x -> x instanceof ArmorStand && x.hasCustomName())) {
            if (named(t.getName().getString(), names)) {
                return true;
            }
        }
        return false;
    }

    static boolean named(String raw) {
        return named(raw, PRIMAL_FEAR_NAMES);
    }

    static boolean named(String raw, List<String> names) {
        if (raw == null || raw.isEmpty() || raw.length() > 96) {
            return false;
        }
        String plain = com.killer560.hub.util.ChatObserver.stripCodes(raw);
        for (String n : names) {
            if (plain.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
