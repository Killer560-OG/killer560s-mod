package com.killer560.hub.carrytracker;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

/** {@code /carry add <name> [runs]}, {@code /carry remove <name>}, {@code /carry list}: the Carry Tracker's client list from chat. */
public final class CarryCommands {

    private static final String FEATURE = "Carry Tracker";

    private CarryCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("carry")
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .executes(c -> add(StringArgumentType.getString(c, "name"), 0))
                                        .then(ClientCommands.argument("runs", IntegerArgumentType.integer(CarryTrackerConfig.MIN_RUNS, CarryTrackerConfig.MAX_RUNS))
                                                .executes(c -> add(StringArgumentType.getString(c, "name"), IntegerArgumentType.getInteger(c, "runs"))))))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .executes(c -> remove(StringArgumentType.getString(c, "name")))))
                        .then(ClientCommands.literal("list").executes(c -> list()))));
    }

    /** Adds a client (runs 0 = the default); a new client starts at 0 runs done. False when the name is invalid, listed already or the list is full. */
    public static boolean addClient(String name, int runs) {
        CarryTrackerConfig cfg = CarryTrackerConfig.getInstance();
        if (!cfg.add(name, runs > 0 ? runs : cfg.getDefaultRuns())) {
            return false;
        }
        cfg.save();
        CarryTracker.reset(name);
        return true;
    }

    /** Adds every party member except you; returns how many were added. */
    public static int addParty() {
        int n = 0;
        for (String name : PartyTracker.teammates()) {
            if (addClient(name, 0)) {
                n++;
            }
        }
        return n;
    }

    private static int add(String name, int runs) {
        if (addClient(name, runs)) {
            int r = CarryTrackerConfig.getInstance().getRuns(name);
            ModChat.send(FEATURE, ModChat.text("Carrying " + name + " for " + r + " runs."));
        } else {
            ModChat.send(FEATURE, ModChat.text("Could not add " + name + " (invalid name, already listed or the list is full)."));
        }
        return 1;
    }

    private static int remove(String name) {
        CarryTrackerConfig cfg = CarryTrackerConfig.getInstance();
        boolean had = cfg.isTracked(name);
        cfg.remove(name);
        cfg.save();
        CarryTracker.reset(name);
        ModChat.send(FEATURE, ModChat.text(had ? "Removed " + name + "." : name + " is not on the list."));
        return 1;
    }

    private static int list() {
        var tracked = CarryTrackerConfig.getInstance().getTracked();
        if (tracked.isEmpty()) {
            ModChat.send(FEATURE, ModChat.text("No clients. Use /carry add <name> [runs]."));
        }
        for (CarryTrackerConfig.Client c : tracked) {
            ModChat.send(FEATURE, ModChat.text(c.name() + ": " + CarryTracker.count(c.name()) + "/" + c.runs() + " runs"));
        }
        return 1;
    }
}
