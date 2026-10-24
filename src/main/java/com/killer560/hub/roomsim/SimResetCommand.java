package com.killer560.hub.roomsim;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * {@code /reset} (and {@code /reload}): rebuild the solo room he is practicing and stand him back at its spawn (see
 * {@link SimBuilder#resetSingleRoom}). In a full floor it only says that it is for solo rooms.
 *
 * <p><b>Outside the sim the name does not exist.</b> killer560 (2026-10-16): "make the /reset not show up on main as
 * a command only in sim." Two halves, both read off Fabric API's client command code (fabric-command-api-v2 3.0.5
 * and 3.1.0, javap, the same in both):
 * <ul>
 *   <li>Running it. {@code ClientCommandInternals.executeCommand} parses the line against the client dispatcher;
 *   Brigadier skips a node whose {@code requires} is false, so the parse fails with {@code dispatcherUnknownCommand},
 *   which {@code isIgnoredException} treats as "not ours": it returns false, the send mixin does not cancel, and the
 *   line goes to the server exactly as typed. A server's own {@code /reset} still runs.</li>
 *   <li>Seeing it. Completion, suggestions and the chat's usage hints read the SERVER's command tree, into which
 *   {@code ClientCommandInternals.addCommands} copies only the client nodes whose {@code canUse} is true at the moment
 *   the server's {@code ClientboundCommandsPacket} arrives - a snapshot. That packet comes during the join, which can
 *   be before {@link SimState#enter} turns the sim on, so the sim's commands could be missing from the sim; and
 *   nothing would take them back out. {@link #tick} therefore re-applies the last commands packet
 *   ({@code ClientCommands.refreshCommandCompletions}) whenever {@link SimState#canAct} changes, which covers every
 *   {@code requires(canAct)} sim command ({@code /goto}, {@code /c}, {@code /next}, {@code /back}), not only this one.</li>
 * </ul>
 */
public final class SimResetCommand {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    /** {@link SimState#canAct} as of the last command-tree refresh. */
    private static boolean treeShowsSim;

    private SimResetCommand() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient}, beside the other sim registrations. */
    public static void register() {
        // /reload too (killer560, 2026-10-16: "have /reset and /reload both work"). In the sim it shadows vanilla's
        // datapack /reload, which the sim never needs; outside the sim the same gate leaves /reload to the server.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            for (String name : new String[]{"reset", "reload"}) {
                dispatcher.register(ClientCommands.literal(name)
                        .requires(src -> SimState.canAct(Minecraft.getInstance()))
                        .executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            // After the chat screen has closed, so the loading screen is not closed with it.
                            mc.execute(() -> SimBuilder.resetSingleRoom(mc));
                            return 1;
                        }));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("SimResetCommand.tree", SimResetCommand::tick));
    }

    private static void tick(Minecraft client) {
        if (client.getConnection() == null) {
            // No tree to fix; the next join's commands packet is read with the gate as it is then.
            treeShowsSim = false;
            return;
        }
        boolean now = SimState.canAct(client);
        if (now == treeShowsSim) {
            return;
        }
        treeShowsSim = now;
        try {
            ClientCommands.refreshCommandCompletions();
            LOGGER.info("[SimReset] command tree refreshed: sim commands {}", now ? "shown" : "hidden");
        } catch (IllegalStateException e) {
            // No commands packet yet. When it arrives it is read with the gate already as it is now.
            LOGGER.info("[SimReset] command tree not refreshed yet: {}", e.getMessage());
        }
    }
}
