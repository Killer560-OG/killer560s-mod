package com.killer560.hub.lagdisplay;

import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

import java.util.List;

/**
 * {@code /lag stutters [count]} prints the last stutters (default 5, at most 20) with their attribution, and
 * {@code /lag stutters clear} forgets them (stutter-1026). Both builds; registered from {@link LagDisplayFeature#register}.
 */
final class StutterCommand {

    private static final String FEATURE = "Performance HUD";

    private StutterCommand() {
    }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("lag")
                        .then(ClientCommands.literal("stutters")
                                .executes(c -> list(5))
                                .then(ClientCommands.literal("clear").executes(c -> {
                                    FrameStats.clear();
                                    ModChat.send(FEATURE, ModChat.text("Stutter list cleared."));
                                    return 1;
                                }))
                                .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 20))
                                        .executes(c -> list(IntegerArgumentType.getInteger(c, "count")))))));
    }

    static int list(int count) {
        LagDisplayConfig cfg = LagDisplayConfig.getInstance();
        if (!cfg.wantsFrameTimes()) {
            ModChat.send(FEATURE, ModChat.bad("Frames are not being measured: turn on the Performance HUD with its Frame"
                    + " or 1% Low line, or Log Stutters."));
        }
        List<FrameStats.Stutter> recent = FrameStats.recent(count);
        if (recent.isEmpty()) {
            ModChat.send(FEATURE, ModChat.text("No stutters recorded (threshold " + cfg.getStutterThresholdMs() + " ms, "
                    + FrameStats.frames() + " frames measured)."));
            return 1;
        }
        ModChat.send(FEATURE, ModChat.text("Last " + recent.size() + " stutter(s) of " + FrameStats.stutterTotal()
                + " this session (threshold " + cfg.getStutterThresholdMs() + " ms). Full lines: "
                + FrameStats.file().getFileName()));
        for (FrameStats.Stutter s : recent) {
            ModChat.send(FEATURE, ModChat.value(s.clock() + " "), ModChat.text(s.text()));
        }
        return 1;
    }
}
