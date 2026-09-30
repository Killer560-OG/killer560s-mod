package com.killer560.hub.roomsim;

import com.killer560.hub.secrets.DungeonState;
import net.minecraft.client.Minecraft;
import com.killer560.hub.compat.McCompat;

/**
 * When a new dungeon instance can be created, and when it is worth trying to rejoin after being dropped.
 *
 * <p>Hypixel puts a <b>30 second cooldown</b> between creating dungeon instances - the thing SkyHanni surfaces
 * with its instance-creation cooldown display, which also offers to block {@code /joininstance} while it is
 * running. That number is the reason killer560's recorder loop was written around 30 seconds in the first place.
 *
 * <p><b>How this detects it, and how it does not.</b> I could not read SkyHanni's own detection to copy its chat
 * patterns, and inventing a regex for a message I have never seen is how you get a timer that silently never
 * fires. So this watches something we can see for certain instead: the moment the client actually enters a
 * dungeon is the moment an instance was created, and the cooldown runs from there. No chat matching, nothing to
 * go stale when Hypixel rewords a line.
 *
 * <p>The rejoin side is deliberately not a fixed wait either. Being dropped to limbo ends when the client is
 * somewhere real again, and that is observable - so the recorder can wait for the world rather than for a
 * number that is too long on a good day and too short on a bad one.
 */
public final class DungeonInstanceCooldown {

    /** Hypixel's gap between creating dungeon instances. */
    public static final long INSTANCE_COOLDOWN_MS = 30_000L;

    private static long lastInstanceCreatedMs;
    private static boolean wasInDungeon;

    private DungeonInstanceCooldown() {
    }

    /**
     * Call every client tick. Cheap, and it only records a transition.
     *
     * <p>The transition, not the state: entering a dungeon is what creates an instance, so the clock starts on
     * the tick {@code isInDungeon} first becomes true and not on every tick afterwards.
     */
    public static void tick(Minecraft client) {
        if (client == null || client.level == null) {
            return;
        }
        boolean now = DungeonState.isInDungeon();
        if (now && !wasInDungeon) {
            lastInstanceCreatedMs = System.currentTimeMillis();
        }
        wasInDungeon = now;
    }

    /** Milliseconds until a new instance may be created; 0 when it is already allowed. */
    public static long instanceCooldownRemainingMs() {
        if (lastInstanceCreatedMs == 0L) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - lastInstanceCreatedMs;
        return Math.max(0L, INSTANCE_COOLDOWN_MS - elapsed);
    }

    public static boolean canCreateInstance() {
        return instanceCooldownRemainingMs() <= 0L;
    }

    /**
     * Whether the client is somewhere it could act - a real world with a player in it.
     *
     * <p>What "back from limbo" means in practice. A fixed wait after being dropped is a guess in both
     * directions; this is the condition the guess was standing in for.
     */
    public static boolean inPlayableWorld(Minecraft client) {
        return client != null && client.level != null && client.player != null && McCompat.screen(client) == null;
    }

    /** True while the player is below the world, which is what being dumped to limbo looks like from here. */
    public static boolean looksLikeLimbo(Minecraft client) {
        return client != null && client.player != null && client.player.position().y < 0;
    }

    /** Seconds remaining, for a display. */
    public static int instanceCooldownSeconds() {
        return (int) Math.ceil(instanceCooldownRemainingMs() / 1000.0);
    }
}
