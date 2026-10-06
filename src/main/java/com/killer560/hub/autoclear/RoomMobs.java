package com.killer560.hub.autoclear;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.mobesp.MobEspFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * The ONE definition of "the mobs a room's clear still needs": the starred mobs (a starred miniboss included) that
 * {@link MobEspFeature#starredMobs} resolves from their name-tag stands, standing in the room's tiles by
 * {@link DungeonLayout#roomAtWorld}. Auto Clear kills these, and Auto Routes' {@code await:kill} waits for them to be
 * dead - both read this, so the two can never disagree about which mobs count. A room is also done once the map shows
 * it cleared ({@link #cleared}). Client thread.
 */
public final class RoomMobs {

    private RoomMobs() {
    }

    /** The counted mobs still alive in {@code room} (a {@link DungeonLayout} room index); empty for a bad index. */
    public static List<Entity> aliveIn(Minecraft client, DungeonLayout layout, int room) {
        List<Entity> out = new ArrayList<>();
        if (layout == null || room < 0) {
            return out;
        }
        for (Entity mob : MobEspFeature.starredMobs(client)) {
            if (layout.roomAtWorld(mob.getX(), mob.getZ()) == room) {
                out.add(mob);
            }
        }
        return out;
    }

    /** Cleared on the dungeon map (the checkmark, green or white) on any of the room's tiles. */
    public static boolean cleared(DungeonLayout layout, int room) {
        return layout != null && room >= 0 && AutoClearTargets.isCleared(layout, room);
    }
}
