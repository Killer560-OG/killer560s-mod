package com.killer560.hub.testing;

import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

import java.util.List;

/**
 * {@code /k560tested list}: what killer560 marked in this testing build, so Claude Code can copy the marks into the
 * repo's {@code testing/tested-<variant>.txt}. Testing builds only.
 */
public final class TestingCommands {

    private static final String FEATURE = "Testing";

    private TestingCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("k560tested")
                        .executes(context -> list())
                        .then(ClientCommands.literal("list").executes(context -> list()))));
    }

    private static int list() {
        List<String> tested = TestedFeatures.marksTested();
        List<String> untested = TestedFeatures.marksUntested();
        ModChat.send(FEATURE, ModChat.text(TestingBuild.VARIANT + " testing build - repo list has "),
                ModChat.value(String.valueOf(TestedFeatures.repoList().size())), ModChat.text(" tested tab(s)."));
        ModChat.send(FEATURE, ModChat.good("Marked tested (" + tested.size() + "): "),
                ModChat.value(tested.isEmpty() ? "none" : String.join(", ", tested)));
        ModChat.send(FEATURE, ModChat.bad("Marked untested (" + untested.size() + "): "),
                ModChat.value(untested.isEmpty() ? "none" : String.join(", ", untested)));
        ModChat.send(FEATURE, ModChat.text("Marks file: "), ModChat.value(TestedFeatures.marksFile().toString()));
        return 1;
    }
}
