package com.killer560.hub.cheatutils;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.wither.WitherBoss;

/**
 * F7/M7 Wither boss detection (Maxor/Storm/Goldor/Necron), ported from NoammAddons 26.1.2 {@code floor7/WitherESP.kt}.
 * The highlighting itself moved into Dungeon ESP ({@link com.killer560.hub.mobesp.MobEspFeature}, 2026-09-15) - this
 * class only answers "is this the real boss Wither" and "are we in the part of the boss fight where one exists":
 * <ul>
 * <li>A real boss is a {@link WitherBoss} that is NOT invisible and whose invulnerable ticks != 800 (Noamm's exact
 * filter - the invisible/800-tick withers are Hypixel's fake display withers).
 * <li>Only in F7/M7 boss, never in P5 (Noamm {@code isValidLoc}): P5 starts at the first "[BOSS] Wither King:" line.
 * </ul>
 * Still ticked and fed chat by {@link CheatUtils} (registered on both builds - it holds no cheat behaviour).
 */
public final class WitherEspFeature {

    private static boolean witherKingPhase = false;
    private static Object lastLevel = null;

    private WitherEspFeature() {
    }

    /** Noamm's real-boss filter: a visible WitherBoss that isn't one of the 800-invulnerable-tick display withers. */
    public static boolean isRealWither(Entity entity) {
        return entity instanceof WitherBoss wither && !wither.isRemoved() && !wither.isInvisible()
                && wither.getInvulnerableTicks() != 800;
    }

    /** F7/M7 boss room, P1-P4 (not the Wither King phase). */
    public static boolean isWitherBossActive(Minecraft client) {
        return client.level != null && client.player != null && DungeonState.isF7OrM7()
                && LiveMapFeature.isInBoss() && !witherKingPhase;
    }

    static void onChat(String plain) {
        if (plain.startsWith("[BOSS] Wither King:") && !witherKingPhase) {
            witherKingPhase = true;
            CheatUtils.LOGGER.info("[CheatUtils] Wither detection: Wither King phase - boss withers ignored");
        }
    }

    static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            witherKingPhase = false;
        }
    }
}
