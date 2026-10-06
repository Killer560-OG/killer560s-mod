package com.killer560.hub.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.minecraft.client.Minecraft;

/**
 * Re-adds the mod's client commands to the tab-completion tree when a setting that gates them turns on.
 * <p>
 * killer560 (2026-10-05): "the /ap3 commands are not auto filling." Fabric copies client commands into the
 * client's command tree once, when the server's command list arrives, and only those whose {@code requires()}
 * passes at that moment (ClientCommandInternals.addCommands, checked in fabric-command-api-v2 3.0.5). So /ap3
 * and /ar, gated on their feature being on, never auto-filled when the feature was switched on after joining -
 * they still ran, because execution re-checks against Fabric's own dispatcher. Turning one off leaves its
 * completions until the next join; running it is still refused.
 */
public final class CommandTreeRefresh {

    private static final List<BooleanSupplier> GATES = new ArrayList<>();
    private static boolean[] last = new boolean[0];
    private static boolean registered;

    private CommandTreeRefresh() {
    }

    /** Watch {@code gate}; whenever it goes from false to true while connected, re-merge the command tree. */
    public static synchronized void watch(BooleanSupplier gate) {
        GATES.add(gate);
        last = java.util.Arrays.copyOf(last, GATES.size());
        last[last.length - 1] = gate.getAsBoolean();
        if (!registered) {
            registered = true;
            ClientTickEvents.END_CLIENT_TICK.register(CommandTreeRefresh::tick);
        }
    }

    private static void tick(Minecraft mc) {
        boolean turnedOn = false;
        for (int i = 0; i < GATES.size(); i++) {
            boolean now = GATES.get(i).getAsBoolean();
            turnedOn |= now && !last[i];
            last[i] = now;
        }
        if (!turnedOn || mc.getConnection() == null || ClientCommandInternals.getActiveDispatcher() == null) {
            return;
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        CommandDispatcher<FabricClientCommandSource> target = (CommandDispatcher) mc.getConnection().getCommands();
        ClientCommandInternals.addCommands(target, (FabricClientCommandSource) mc.getConnection().getSuggestionsProvider());
    }
}
