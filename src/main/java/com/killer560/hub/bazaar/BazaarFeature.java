package com.killer560.hub.bazaar;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarOrders;
import com.killer560.hub.auction.BoosterCookie;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * The Bazaar: one screen for browsing (drawn from the public Bazaar API, see {@link BazaarView}) and for Hypixel's real
 * Bazaar menus (reskinned into the same screen, see {@link BazaarReskin}).
 * <p>
 * Commands (2026-10-07): {@code /killer560bz} opens the screen without a menu, and only with an active Booster Cookie
 * ({@link BoosterCookie}) - the remote Bazaar a Cookie grants on Hypixel; {@code /bz} is always
 * Hypixel's own command, forwarded through {@link com.killer560.hub.util.ServerCommands#toServer} - never
 * {@code sendCommand}, since "bz" is itself a client command this mod registers and would be handed back to us. Hypixel's
 * Bazaar menu (the NPC, or {@code /bz}) opens as the same screen through the reskin.
 */
public final class BazaarFeature {

    private static boolean keyWasDown = false;

    private BazaarFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BazaarFeature.tick", BazaarFeature::tick));
        BazaarReskin.register();
        BazaarHud.register();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // /killer560bz was removed (killer560, 2026-11-01: "they are not needed"); the keybind and the settings
            // buttons still open the screen without a menu.
            // /bz is always Hypixel's own command. Bare or with anything after it ("/bz rec", "/bz Recombobulator
            // 3000"), the line is forwarded whole. Without the argument node, the client dispatcher rejected every
            // argued line with "Incorrect argument for command at position 3" and Hypixel never saw it (killer560,
            // 2026-10-06).
            dispatcher.register(ClientCommands.literal("bz").executes(ctx -> {
                forwardToServer("bz");
                return 1;
            }).then(ClientCommands.argument("args", StringArgumentType.greedyString()).executes(ctx -> {
                String line = "bz " + StringArgumentType.getString(ctx, "args");
                if (!com.killer560.hub.auction.MenuSearch.tryInMenu(line)) {
                    forwardToServer(line);
                }
                return 1;
            })));
        });
    }

    private static void forwardToServer(String command) {
        com.killer560.hub.util.ServerCommands.toServer(command);
    }

    /**
     * /killer560bz, the keybind and the settings buttons: the screen without a menu is the "remote Bazaar" a Booster
     * Cookie grants on Hypixel, so it opens only while the Cookie Buff is active (killer560, 2026-10-07: "the command
     * /killer560bz should only work with a booster cookie"). Without one, chat says so and points at the Bazaar NPC,
     * whose menu opens as this same screen.
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
        BazaarFollowUp.tick();
        AuctionConfig cfg = AuctionConfig.getInstance();
        if (!cfg.isBazaarEnabled() || client.player == null) {
            keyWasDown = false;
            return;
        }
        // killer560, 2026-09-27: "make sure it auto rescans the ah and bazaar fairly often by default".
        BazaarApi.ensureAutoStarted();
        if (cfg.isTrackBazaarOrders()) {
            BazaarOrders.tick(client);
        }
        int code = cfg.getOpenBazaarKeyCode();
        if (code == com.killer560.hub.util.KeyUtil.NONE || client.getWindow() == null) {
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
