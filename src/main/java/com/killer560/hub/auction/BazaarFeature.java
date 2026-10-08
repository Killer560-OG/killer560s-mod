package com.killer560.hub.auction;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.auction.screen.BazaarScreen;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import com.killer560.hub.compat.McCompat;

/**
 * killer560's item 8.1, Bazaar half: "then the same for Bazaar" - same open mechanism as the Auction
 * House browser (its own command + keybind). Clicking a product in {@code BazaarScreen} runs Hypixel's own
 * real {@code /bz <name>} so Hypixel's own menu handles the actual buy/sell order - that never changes.
 * <p>
 * Commands (2026-10-07): {@code /killer560bz} is this mod's browser, and only with an active Booster Cookie
 * ({@link BoosterCookie}); {@code /bz} and {@code /hypixelbz} are always Hypixel's own command, forwarded through
 * {@link com.killer560.hub.util.ServerCommands#toServer} - never {@code sendCommand}, since "bz" is itself a client
 * command this mod registers and would be handed back to us. The old {@code /bz} override (bare /bz opening this
 * browser) is gone: Hypixel's real Bazaar menu is drawn in this browser's look by
 * {@link com.killer560.hub.auction.screen.BazaarReskin} instead.
 */
public final class BazaarFeature {

    private static boolean keyWasDown = false;

    private BazaarFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BazaarFeature.tick", BazaarFeature::tick));
        com.killer560.hub.auction.screen.BazaarReskin.register();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("killer560bz").executes(ctx -> {
                openOrExplain();
                return 1;
            }));
            // /bz is always Hypixel's own command (2026-10-07: the /bz override is gone - Hypixel's real Bazaar is
            // reskinned instead, see BazaarReskin). Bare or with anything after it ("/bz rec", "/bz Recombobulator
            // 3000"), the line is forwarded whole. Without the argument node, the client dispatcher rejected every
            // argued line with "Incorrect argument for command at position 3" and Hypixel never saw it (killer560,
            // 2026-10-06).
            dispatcher.register(ClientCommands.literal("bz").executes(ctx -> {
                forwardToServer("bz");
                return 1;
            }).then(ClientCommands.argument("args", StringArgumentType.greedyString()).executes(ctx -> {
                forwardToServer("bz " + StringArgumentType.getString(ctx, "args"));
                return 1;
            })));
            // Kept as an alias of /bz for anyone used to typing it.
            dispatcher.register(ClientCommands.literal("hypixelbz").executes(ctx -> {
                forwardToServer("bz");
                return 1;
            }).then(ClientCommands.argument("args", StringArgumentType.greedyString()).executes(ctx -> {
                forwardToServer("bz " + StringArgumentType.getString(ctx, "args"));
                return 1;
            })));
        });
    }

    private static void forwardToServer(String command) {
        com.killer560.hub.util.ServerCommands.toServer(command);
    }

    /**
     * /killer560bz, the keybind and the settings buttons: the API-driven browser is the "remote Bazaar" a Booster
     * Cookie grants on Hypixel, so it opens only while the Cookie Buff is active (killer560, 2026-10-07: "the command
     * /killer560bz should only work with a booster cookie"). Without one, chat says so and points at the Bazaar NPC,
     * where Hypixel's real menu is reskinned.
     */
    public static void openOrExplain() {
        if (!AuctionConfig.getInstance().isBazaarEnabled()) {
            ModChat.send("Bazaar", ModChat.bad("Turn on the Bazaar Browser in the Items tab first."));
            return;
        }
        BoosterCookie.State cookie = BoosterCookie.state(Minecraft.getInstance());
        if (cookie != BoosterCookie.State.ACTIVE) {
            ModChat.send("Bazaar", ModChat.bad(cookie == BoosterCookie.State.INACTIVE
                    ? "You need an active Booster Cookie to use the Bazaar remotely."
                    : "Couldn't find an active Cookie Buff in your tab list, so the remote Bazaar stays closed."),
                    ModChat.dim(" Walk to the Bazaar NPC instead - its menu opens in this look."));
            return;
        }
        openDeferred();
    }

    public static void openDeferred() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.setScreenAndShow(new BazaarScreen(McCompat.screen(client))));
    }

    private static void tick(Minecraft client) {
        AuctionConfig cfg = AuctionConfig.getInstance();
        if (!cfg.isBazaarEnabled() || client.player == null) {
            keyWasDown = false;
            return;
        }
        // killer560, 2026-09-27: "make sure it auto rescans the ah and bazaar fairly often by default" -
        // see AuctionHouseFeature#tick's matching comment; same reasoning, same fix, Bazaar half.
        BazaarApi.ensureAutoStarted();
        if (cfg.isTrackBazaarOrders()) {
            BazaarOrders.tick(client);
        }
        int code = cfg.getOpenBazaarKeyCode();
        if (code < 0 || client.getWindow() == null) {
            keyWasDown = false;
            return;
        }
        boolean down = KeyUtil.isKeyDown(client.getWindow(), code);
        if (down && !keyWasDown && McCompat.screen(client) == null) {
            openOrExplain();
        }
        keyWasDown = down;
    }
}
