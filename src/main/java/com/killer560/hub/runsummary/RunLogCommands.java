package com.killer560.hub.runsummary;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

/**
 * {@code /log} - opens the run log ({@link RunLogScreen}). killer560, 2026-09-20: "make it its own custom
 * menu from doing /log". {@code /runlog} is accepted too, since {@code /log} is short enough to collide with
 * another mod's command on a modded install.
 */
public final class RunLogCommands {

    private RunLogCommands() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient}. */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("log").executes(context -> open()));
            dispatcher.register(ClientCommands.literal("runlog").executes(context -> open()));
        });
    }

    private static int open() {
        Minecraft client = Minecraft.getInstance();
        // Deferred like /killer560 and /croesus - the chat screen closing after the command would otherwise
        // replace this screen on the same tick.
        client.execute(() -> client.setScreenAndShow(new RunLogScreen(null)));
        return 1;
    }
}
