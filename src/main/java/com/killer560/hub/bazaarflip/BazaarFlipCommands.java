package com.killer560.hub.bazaarflip;

import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

import java.util.List;
import java.util.Locale;

/**
 * {@code /bazaarflip} - start, stop, and (the useful one) {@code scan}, which runs the scanner ONCE and prints
 * the ranking without touching a menu. That is how the numbers get checked against the market before any
 * automation is pointed at them, and it is the only scan this mod ever runs outside a live session.
 *
 * <p>Cheat build only, like the feature: the command is simply never registered in the legit jar.
 */
public final class BazaarFlipCommands {

    public static final String FEATURE = "Bazaar Flip";
    private static final int DEFAULT_TOP = 5;

    private BazaarFlipCommands() {
    }

    public static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("bazaarflip")
                        .executes(context -> {
                            help();
                            return 1;
                        })
                        .then(ClientCommands.literal("start").executes(context -> {
                            String refusal = BazaarFlipFeature.start();
                            if (refusal != null) {
                                ModChat.send(FEATURE, ModChat.bad("Can't start: "), ModChat.text(refusal));
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(context -> {
                            if (BazaarFlipFeature.isRunning()) {
                                BazaarFlipFeature.requestStop();
                            } else {
                                ModChat.send(FEATURE, ModChat.dim("Not running."));
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("scan")
                                .executes(context -> {
                                    scan(DEFAULT_TOP);
                                    return 1;
                                })
                                .then(ClientCommands.argument("top", IntegerArgumentType.integer(1, 25))
                                        .executes(context -> {
                                            scan(IntegerArgumentType.getInteger(context, "top"));
                                            return 1;
                                        })))));
    }

    private static void help() {
        ModChat.send(FEATURE, ModChat.text("/bazaarflip scan [n]"), ModChat.dim(" - price the market now, no clicks"));
        ModChat.send(FEATURE, ModChat.text("/bazaarflip start | stop"));
    }

    /** One scan, printed. Uses the real purse, so what it prints is what the bot would actually pick. */
    private static void scan(int top) {
        Double purse = BazaarFlipFeature.readPurse();
        if (purse == null) {
            ModChat.send(FEATURE, ModChat.bad("Couldn't read your purse off the scoreboard - "),
                    ModChat.text("stand somewhere the Purse line is shown."));
            return;
        }
        BazaarFlipConfig cfg = BazaarFlipConfig.getInstance();
        ModChat.send(FEATURE, ModChat.text("Scanning with purse "),
                ModChat.value(String.format(Locale.US, "%,.0f", purse)),
                ModChat.dim(", ranking by " + cfg.getRankingMode().display
                        + ", floor " + String.format(Locale.US, "%,d", cfg.getMinProfitPerRun()) + "..."));
        BazaarFlipScanner.scanAsync(purse, cfg.getRankingMode(), cfg.getMinProfitPerRun())
                .thenAccept(result -> net.minecraft.client.Minecraft.getInstance().execute(() -> print(result, top)));
    }

    private static void print(BazaarFlipScanner.ScanResult result, int top) {
        if (result == null) {
            ModChat.send(FEATURE, ModChat.bad("Scan produced nothing."));
            return;
        }
        if (!result.ok()) {
            ModChat.send(FEATURE, ModChat.bad("Scan failed: "), ModChat.text(result.error()));
            return;
        }
        ModChat.send(FEATURE, ModChat.dim(result.productsWithNpcPrice() + " Bazaar products have an NPC price; "),
                ModChat.value(result.profitableCount() + " profitable"), ModChat.dim("; "),
                ModChat.value(result.ranked().size() + " clear the floor"));
        List<BazaarFlipCandidate> ranked = result.ranked();
        for (int i = 0; i < Math.min(top, ranked.size()); i++) {
            BazaarFlipCandidate c = ranked.get(i);
            ModChat.send(FEATURE, ModChat.dim((i + 1) + ". "), ModChat.text(c.summary()),
                    ModChat.dim(String.format(Locale.US, " [%,.1f/ea vs NPC %,.0f]",
                            c.averageUnitCost(), c.npcSellPrice())));
        }
    }
}
