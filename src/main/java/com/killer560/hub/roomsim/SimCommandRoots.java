package com.killer560.hub.roomsim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Set;

/**
 * The client commands only the dungeon sim answers (simcmdkeys-1117, killer560 2026-11-17: a keybind bound to /stats or /wd did
 * nothing in the sim while typing it worked). A command the mod SENDS (a custom shortcut's key, Party Commands, any feature)
 * leaves through {@code ServerCommands.toServer}, below Fabric's dispatcher, so these roots never saw it; there, while the
 * sim runs, one of these roots is dispatched client-side exactly as typed. Only these roots: a mod command that forwards to
 * the server itself (/ah, /bz) would loop. Outside the sim nothing changes, the command goes to the server.
 */
public final class SimCommandRoots {

    /** Every root a sim feature registers behind {@code requires(SimState.canAct)}; keep in step with their register() calls. */
    private static final Set<String> ROOTS = Set.of("wardrobe", "wd", "equipment", "stats", "eq", "simitem", "item", "items",
            "goto", "tpto", "warp", "c");

    private SimCommandRoots() {
    }

    /** True when {@code command} (no leading slash) was a sim root and the sim's own handler ran it. */
    public static boolean dispatch(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (command == null || mc.getConnection() == null || !SimState.canAct(mc)) return false;
        String line = command.strip();
        int sp = line.indexOf(' ');
        if (!ROOTS.contains((sp < 0 ? line : line.substring(0, sp)).toLowerCase(Locale.ROOT))) return false;
        CommandDispatcher<FabricClientCommandSource> d = ClientCommandInternals.getActiveDispatcher();
        if (d == null) return false;
        FabricClientCommandSource src = (FabricClientCommandSource) mc.getConnection().getSuggestionsProvider();
        try {
            d.execute(line, src);
        } catch (CommandSyntaxException e) {
            src.sendError(Component.literal(e.getMessage()));
        }
        return true;
    }
}
