package com.killer560.hub.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Tells a real player from the mob and NPC entities Hypixel builds as players. */
public final class PlayerKind {
    private PlayerKind() {
    }

    /** Hypixel's NPCs and player-model mobs (Shadow Assassin, minibosses, Livid clones) carry version 2 UUIDs; real accounts do not. */
    public static boolean isRealPlayer(Entity e) {
        return e instanceof Player p && p.getUUID().version() != 2;
    }
}
