package com.killer560.hub.gui.profit;

import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * {@code /profit} opens the {@link ProfitHubScreen}; {@code /profit <tracker>} opens that tracker directly
 * ({@link ProfitTracker} lists the names and tab-completes them); an unknown name lists the valid ones in chat.
 * {@code /croesus profit} still opens the dungeon tracker. Client-only, nothing is sent to the server.
 */
public final class ProfitCommands {

    public static final String FEATURE = "Profit";

    private ProfitCommands() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient}. */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("profit")
                        .executes(context -> {
                            openHub();
                            return 1;
                        })
                        .then(ClientCommands.argument("tracker", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
                                    for (String word : ProfitTracker.allWords()) {
                                        if (word.startsWith(typed)) {
                                            builder.suggest(word);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    String typed = StringArgumentType.getString(context, "tracker");
                                    ProfitTracker tracker = ProfitTracker.byWord(typed);
                                    if (tracker == null) {
                                        ModChat.send(FEATURE, ModChat.bad("Unknown tracker \"" + typed + "\". "),
                                                ModChat.text("Valid: "),
                                                ModChat.value(String.join(", ", ProfitTracker.allWords())));
                                        return 0;
                                    }
                                    openTracker(tracker);
                                    return 1;
                                }))));
    }

    private static void openHub() {
        Minecraft client = Minecraft.getInstance();
        // Deferred like /croesus: the chat screen closing after the command would replace this screen on the
        // same tick.
        client.execute(() -> client.setScreenAndShow(new ProfitHubScreen(null)));
    }

    private static void openTracker(ProfitTracker tracker) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.setScreenAndShow(tracker.open(null)));
    }
}
