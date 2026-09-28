package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

/**
 * {@code /simscore} - where a sim run stands, and what is still missing for 300.
 *
 * <p>The number on its own is not the useful part. "289" tells him the run failed; "two more crypts and the
 * mimic" tells him which of those to go and do, and that is the whole reason the score is modelled rather than
 * just timed.
 */
public final class SimScoreCommand {

    private SimScoreCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("simscore")
                        .then(ClientCommands.literal("paul").executes(ctx -> {
                            boolean on = !SimScore.isPaulEzpz();
                            SimScore.setPaulEzpz(on);
                            ModChat.send("Sim", ModChat.text("Paul's EZPZ: "),
                                    ModChat.value(on ? "ON (+10)" : "OFF"));
                            return 1;
                        }))
                        .executes(ctx -> {
                            if (!SimState.canAct(Minecraft.getInstance())) {
                                ModChat.send("Sim", ModChat.text("Not in the sim."));
                                return 1;
                            }
                            SimScore.announce();
                            if (SimRun.isRunning()) {
                                ModChat.send("Sim", ModChat.text("Run time "),
                                        ModChat.value(SimRun.elapsedText()));
                            }
                            return 1;
                        })));
    }
}
