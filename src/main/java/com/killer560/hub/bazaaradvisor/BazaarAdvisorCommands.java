package com.killer560.hub.bazaaradvisor;

import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

/**
 * {@code /bzflip} - sets the Bazaar Advisor's four answers (killer560, 2026-10-19). Chat-based for now: each answer is one
 * argument, so {@code /bzflip npc}, {@code /bzflip total}, {@code /bzflip insta} and {@code /bzflip instasell} answer the
 * four questions in order. Bare {@code /bzflip} lists them with the current answers.
 */
public final class BazaarAdvisorCommands {

    private static final String FEATURE = "Bazaar Advisor";

    private BazaarAdvisorCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("bzflip")
                        .executes(context -> {
                            // Opened on the next tick: the chat screen that sent this command closes itself after it returns.
                            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                            client.execute(() -> com.killer560.hub.compat.McCompat.setScreen(client, new BazaarAdvisorSetupScreen()));
                            return 1;
                        })
                        .then(ClientCommands.literal("npc").executes(context -> set(c -> c.setMode(BazaarAdvisorConfig.Mode.NPC), "Sell to the NPC")))
                        .then(ClientCommands.literal("bazaar").executes(context -> set(c -> c.setMode(BazaarAdvisorConfig.Mode.BAZAAR), "Sell on the Bazaar")))
                        .then(ClientCommands.literal("total").executes(context -> set(c -> c.setGoal(BazaarAdvisorConfig.Goal.TOTAL_PROFIT), "Total profit per inventory")))
                        .then(ClientCommands.literal("percent").executes(context -> set(c -> c.setGoal(BazaarAdvisorConfig.Goal.PROFIT_PERCENT), "Best profit percentage")))
                        .then(ClientCommands.literal("insta").executes(context -> set(c -> c.setBuyMethod(BazaarAdvisorConfig.BuyMethod.INSTA_BUY), "Insta Buy")))
                        .then(ClientCommands.literal("order").executes(context -> set(c -> c.setBuyMethod(BazaarAdvisorConfig.BuyMethod.BUY_ORDER), "Buy Order")))
                        .then(ClientCommands.literal("instasell").executes(context -> set(c -> c.setSellMethod(BazaarAdvisorConfig.SellMethod.INSTA_SELL), "Insta Sell")))
                        .then(ClientCommands.literal("offer").executes(context -> set(c -> c.setSellMethod(BazaarAdvisorConfig.SellMethod.SELL_OFFER), "Sell Offer")))
                        .then(ClientCommands.literal("on").executes(context -> toggle(true)))
                        .then(ClientCommands.literal("off").executes(context -> toggle(false)))));
    }

    private static int set(java.util.function.Consumer<BazaarAdvisorConfig> change, String answer) {
        BazaarAdvisorConfig cfg = BazaarAdvisorConfig.getInstance();
        change.accept(cfg);
        cfg.save();
        ModChat.send(FEATURE, ModChat.text("Set to "), ModChat.value(answer), ModChat.text(". Now: "), ModChat.dim(cfg.summary()));
        if (!cfg.isEnabled()) {
            ModChat.send(FEATURE, ModChat.dim("The advisor is off - turn it on in Items > Bazaar Advisor, or /bzflip on."));
        }
        return 1;
    }

    private static int toggle(boolean on) {
        BazaarAdvisorConfig cfg = BazaarAdvisorConfig.getInstance();
        cfg.setEnabled(on);
        cfg.save();
        ModChat.send(FEATURE, ModChat.text("Advisor "), on ? ModChat.good("ON") : ModChat.bad("OFF"), ModChat.text("."));
        return 1;
    }

    private static void list() {
        BazaarAdvisorConfig cfg = BazaarAdvisorConfig.getInstance();
        ModChat.send(FEATURE, ModChat.text("Answers: "), ModChat.value(cfg.summary()));
        ModChat.send(FEATURE, ModChat.dim("1. /bzflip npc | bazaar - where it sells"));
        ModChat.send(FEATURE, ModChat.dim("2. /bzflip total | percent - what it optimises"));
        ModChat.send(FEATURE, ModChat.dim("3. /bzflip insta | order - how it buys"));
        ModChat.send(FEATURE, ModChat.dim("4. /bzflip instasell | offer - how it sells on the Bazaar (Bazaar only)"));
        ModChat.send(FEATURE, ModChat.dim("/bzflip on | off - the advisor itself"));
    }
}
