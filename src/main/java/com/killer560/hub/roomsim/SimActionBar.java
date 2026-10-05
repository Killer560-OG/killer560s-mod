package com.killer560.hub.roomsim;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The room's "x/y Secrets" on the action bar, sent by the sim's server the way Hypixel's sends it while you stand in
 * a room that has secrets.
 *
 * <p>Found 2026-10-05 writing the Auto Routes tests: the sim never sent this line, so everything that reads it saw
 * nothing - an Auto Routes {@code await:<n>} node waited forever in the sim (only secret bats counted), and the live
 * map never learnt a room's found count. Client features read it exactly as they do on Hypixel; nothing on the client
 * special-cases the sim. Only the Secrets part is sent (health and mana are not modelled), every ten server ticks,
 * and only in a room whose database entry has secrets.
 */
public final class SimActionBar {

    private static final int EVERY_TICKS = 10;
    private static int tick;

    private SimActionBar() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SimActionBar::serverTick);
    }

    private static void serverTick(MinecraftServer server) {
        if (++tick % EVERY_TICKS != 0) {
            return;
        }
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (!SimAbilities.simServer(sp)) {
                return;
            }
            String room = SimScore.roomAt(sp.blockPosition());
            if (room == null) {
                continue;
            }
            com.killer560.hub.roomdatabase.RoomEntry entry =
                    com.killer560.hub.roomdatabase.RoomDatabase.lookupByName(room);
            if (entry == null || entry.secrets <= 0) {
                continue;
            }
            int found = Math.min(entry.secrets, SimScore.foundInRoom(room));
            sp.sendSystemMessage(Component.literal("§7" + found + "/" + entry.secrets + " Secrets"), true);
        }
    }
}
