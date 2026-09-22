package com.killer560.hub.lavalab;

import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

/**
 * The {@code /lavalab} command tree. Its own root (not under {@code /killer560}) - same reasoning as
 * {@code /ar} in {@link com.killer560.hub.autoroutes.AutoRoutesCommands}: this is the syntax the feature
 * request asked for.
 * <p>
 * {@code on}/{@code off} double as both the persisted master switch (so recording still auto-arms on lava
 * contact next time you touch it, even after a restart) AND the manual arm/disarm the feature needs -
 * {@code /lavalab on} arms right now regardless of whether you're in lava yet, {@code /lavalab off} ends
 * and saves the current session early. {@code clear} is different from {@code off}: it throws the
 * in-progress session away instead of saving it, for a test run you don't want kept.
 * <p>
 * No cheat-build gate here (see {@link LavaLabFeature}'s class doc for why) - just the Skyblock/p3sim pause
 * every feature respects, same as {@code Killer560ModClient}'s other commands.
 */
public final class LavaLabCommands {

    private static final String FEATURE = "Lava Lab";

    private LavaLabCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("lavalab")
                        .executes(context -> {
                            help();
                            return 1;
                        })
                        .then(ClientCommands.literal("on").executes(context -> {
                            LavaLabConfig cfg = LavaLabConfig.getInstance();
                            cfg.setEnabled(true);
                            cfg.save();
                            String status = LavaLabFeature.armManual();
                            if (status != null) {
                                ModChat.send(FEATURE, ModChat.text(status));
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(context -> {
                            LavaLabConfig cfg = LavaLabConfig.getInstance();
                            cfg.setEnabled(false);
                            cfg.save();
                            String status = LavaLabFeature.disarmManual();
                            if (status != null) {
                                ModChat.send(FEATURE, ModChat.text(status));
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("status").executes(context -> {
                            ModChat.send(FEATURE, ModChat.text(LavaLabFeature.status()));
                            return 1;
                        }))
                        .then(ClientCommands.literal("clear").executes(context -> {
                            ModChat.send(FEATURE, ModChat.text(LavaLabFeature.clearCurrent()));
                            return 1;
                        }))
                        // "/lavalab tail <ticks>" - sets the auto-arm tail length without needing the
                        // settings menu; the feature has no GUI tab (command + one enable setting only,
                        // see the tool's own build report for why).
                        .then(ClientCommands.literal("tail")
                                .executes(context -> {
                                    ModChat.send(FEATURE, ModChat.text("Auto-arm tail: "),
                                            ModChat.value(LavaLabConfig.getInstance().getAutoArmTailTicks() + " ticks"),
                                            ModChat.dim(" (/lavalab tail <ticks>, " + LavaLabConfig.MIN_AUTO_ARM_TAIL_TICKS
                                                    + "-" + LavaLabConfig.MAX_AUTO_ARM_TAIL_TICKS + ")"));
                                    return 1;
                                })
                                .then(ClientCommands.argument("ticks", IntegerArgumentType.integer(
                                                LavaLabConfig.MIN_AUTO_ARM_TAIL_TICKS, LavaLabConfig.MAX_AUTO_ARM_TAIL_TICKS))
                                        .executes(context -> {
                                            int ticks = IntegerArgumentType.getInteger(context, "ticks");
                                            LavaLabConfig cfg = LavaLabConfig.getInstance();
                                            cfg.setAutoArmTailTicks(ticks);
                                            cfg.save();
                                            ModChat.send(FEATURE, ModChat.text("Auto-arm tail set to "),
                                                    ModChat.value(ticks + " ticks"), ModChat.text("."));
                                            return 1;
                                        })))));
    }

    private static void help() {
        ModChat.send(FEATURE, ModChat.text("Commands:"));
        ModChat.send(FEATURE, ModChat.value("/lavalab on"), ModChat.dim(" - enable + start recording now"));
        ModChat.send(FEATURE, ModChat.value("/lavalab off"), ModChat.dim(" - stop recording and disable"));
        ModChat.send(FEATURE, ModChat.value("/lavalab status"), ModChat.dim(" - current session info"));
        ModChat.send(FEATURE, ModChat.value("/lavalab clear"), ModChat.dim(" - discard the in-progress session"));
        ModChat.send(FEATURE, ModChat.value("/lavalab tail <ticks>"), ModChat.dim(" - auto-arm tail length"));
        ModChat.send(FEATURE, ModChat.dim("While ON, recording also auto-arms the instant you touch lava."));
    }
}
