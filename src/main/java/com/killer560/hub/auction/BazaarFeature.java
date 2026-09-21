package com.killer560.hub.auction;

import com.killer560.hub.auction.screen.BazaarScreen;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * killer560's item 8.1, Bazaar half: "then the same for Bazaar" - same open mechanism as the Auction
 * House browser (its own command + keybind), except real Hypixel {@code /bz} is never touched or
 * overridden - clicking a product in {@code BazaarScreen} runs Hypixel's own real {@code /bz <name>} so
 * Hypixel's own menu handles the actual buy/sell order.
 */
public final class BazaarFeature {

    private static boolean keyWasDown = false;

    private BazaarFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BazaarFeature::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("killer560bz").executes(ctx -> {
                    openOrExplain();
                    return 1;
                })));
    }

    public static void openOrExplain() {
        if (!AuctionConfig.getInstance().isBazaarEnabled()) {
            ModChat.send("Bazaar", ModChat.bad("Turn on the Bazaar Browser in the New tab first."));
            return;
        }
        openDeferred();
    }

    public static void openDeferred() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.setScreenAndShow(new BazaarScreen(client.screen)));
    }

    private static void tick(Minecraft client) {
        AuctionConfig cfg = AuctionConfig.getInstance();
        int code = cfg.getOpenBazaarKeyCode();
        if (!cfg.isBazaarEnabled() || code < 0 || client.player == null || client.getWindow() == null) {
            keyWasDown = false;
            return;
        }
        boolean down = KeyUtil.isKeyDown(client.getWindow(), code);
        if (down && !keyWasDown && client.screen == null) {
            openDeferred();
        }
        keyWasDown = down;
    }
}
