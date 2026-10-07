package com.killer560.hub.termlog;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import com.killer560.hub.util.ModChat;

import java.util.List;

/**
 * {@code /termlog on|off|status|clear|summary} for {@link TerminalOpenLogger}. Bare {@code /termlog} is status. The
 * logger records by itself in an F7/M7 boss; on/off is the override (default on).
 * {@code summary} reads the attempts files on a worker thread (up to 10,000 lines) and prints the open rate by eye
 * height, pitch and distance, so the boundary can be read in game.
 */
final class TerminalOpenLoggerCommands {

    private static final String FEATURE = "TermLog";

    private TerminalOpenLoggerCommands() {
    }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("termlog")
                    .executes(c -> status())
                    .then(ClientCommands.literal("on").executes(c -> set(true)))
                    .then(ClientCommands.literal("off").executes(c -> set(false)))
                    .then(ClientCommands.literal("status").executes(c -> status()))
                    .then(ClientCommands.literal("clear").executes(c -> clear()))
                    .then(ClientCommands.literal("summary").executes(c -> summary()));
            dispatcher.register(root);
        });
    }

    private static int set(boolean on) {
        TerminalOpenLoggerConfig cfg = TerminalOpenLoggerConfig.getInstance();
        cfg.setEnabled(on);
        cfg.save();
        ModChat.send(FEATURE, ModChat.text("Terminal Open Logger "),
                on ? ModChat.good("ON") : ModChat.bad("OFF"),
                ModChat.dim(on ? " - records by itself in any F7/M7 boss" : " - nothing is recorded, even in the boss"));
        return 1;
    }

    private static int status() {
        boolean on = TerminalOpenLogger.isEnabled();
        boolean armed = TerminalOpenLogger.isArmed();
        Thread t = new Thread(() -> {
            int inFile = TerminalOpenLogger.recordsInFile();
            ModChat.send(FEATURE, ModChat.text("Logger "), on ? ModChat.good("ON") : ModChat.bad("OFF"),
                    ModChat.text(armed ? " (recording - in boss)" : on ? " (waits for the boss)" : ""),
                    ModChat.text(" | this session "), ModChat.value(String.valueOf(TerminalOpenLogger.sessionRecords())),
                    ModChat.text(" | in file "), ModChat.value(inFile + "/" + TerminalOpenLogger.MAX_RECORDS),
                    ModChat.dim(" | " + TerminalOpenLogger.file()));
        }, "killer560smod-termlog-status");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    private static int clear() {
        Thread t = new Thread(() -> {
            TerminalOpenLogger.clearFiles();
            ModChat.send(FEATURE, ModChat.text("Cleared the attempts log."));
        }, "killer560smod-termlog-clear");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    private static int summary() {
        Thread t = new Thread(() -> {
            List<String> lines = TerminalOpenLogger.summaryLines();
            for (String line : lines) {
                ModChat.send(FEATURE, ModChat.text(line));
            }
        }, "killer560smod-termlog-summary");
        t.setDaemon(true);
        t.start();
        return 1;
    }
}
