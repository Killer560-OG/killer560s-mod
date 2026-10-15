package com.killer560.hub.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The tab list's entries in the order the tab list DRAWS them.
 *
 * <p>{@code ClientPacketListener.getListedOnlinePlayers()} is a {@code ReferenceOpenHashSet} (javap, 26.1.2 and 26.2):
 * its iteration order is identity-hash order, different every run and unrelated to the slots Hypixel puts players in.
 * Anything that pairs the tab list with something else Hypixel sends in party order - the dungeon map's player markers
 * above all - must read the tab list sorted the way {@code PlayerTabOverlay} sorts it: list order (highest first),
 * spectators last, team name, then profile name ignoring case (the overlay's {@code PLAYER_COMPARATOR}, read with javap
 * on 26.1.2). Hypixel orders its dungeon party lines through those profile names.
 */
public final class TabOrder {

    public static final Comparator<PlayerInfo> COMPARATOR = Comparator
            .comparingInt((PlayerInfo p) -> -p.getTabListOrder())
            .thenComparingInt(p -> p.getGameMode() == GameType.SPECTATOR ? 1 : 0)
            .thenComparing(p -> p.getTeam() == null ? "" : p.getTeam().getName())
            .thenComparing(p -> p.getProfile().name(), String::compareToIgnoreCase);

    private TabOrder() {
    }

    /** The listed players in tab order; empty with no connection. */
    public static List<PlayerInfo> listed(Minecraft client) {
        ClientPacketListener connection = client == null ? null : client.getConnection();
        if (connection == null) {
            return new ArrayList<>();
        }
        List<PlayerInfo> out = new ArrayList<>(connection.getListedOnlinePlayers());
        out.sort(COMPARATOR);
        return out;
    }
}
