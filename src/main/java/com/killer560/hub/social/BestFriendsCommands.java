package com.killer560.hub.social;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

/** {@code /bestfriends} - opens the Party Time Tracker menu ({@link BestFriendsScreen}). No alias registered
 *  (unlike {@code /log}/{@code /runlog}) since the brief only names this one command - see "Needs his
 *  answer" in the staging notes for whether he'd want a short one too (e.g. {@code /bf}). */
public final class BestFriendsCommands {

    private BestFriendsCommands() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient}. */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("bestfriends").executes(context -> open())));
    }

    private static int open() {
        Minecraft client = Minecraft.getInstance();
        // Deferred like /log and /killer560 - the chat screen closing after the command would otherwise
        // replace this screen on the same tick.
        client.execute(() -> client.setScreenAndShow(new BestFriendsScreen(null)));
        return 1;
    }
}
