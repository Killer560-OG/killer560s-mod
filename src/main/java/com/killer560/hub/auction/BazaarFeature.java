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
 * killer560, 2026-09-27: "have a bz override like it does for ah" - bare {@code /bz} (no item argument) can
 * now optionally open this browser instead, mirroring {@link AuctionHouseFeature#shouldOverrideAh}
 * exactly, including the same crash-avoiding reasoning: forwarding to the real Hypixel {@code /bz} goes
 * through {@link com.killer560.hub.util.ServerCommands#toServer}, never {@code sendCommand}, since "bz" is
 * itself a client command this mod registers (see that class's doc for the real crash this caused on
 * {@code /ah}). {@code /bz <item name>} (with an argument) is untouched either way - this only registers
 * the bare literal, so Fabric's client dispatcher never matches an argued call and it goes straight to
 * Hypixel exactly like it always did.
 */
public final class BazaarFeature {

    private static boolean keyWasDown = false;

    private BazaarFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BazaarFeature.tick", BazaarFeature::tick));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("killer560bz").executes(ctx -> {
                openOrExplain();
                return 1;
            }));
            // Real Hypixel /bz - intercepted client-side only while both the browser is enabled AND
            // killer560 turned the override on; otherwise this sends the exact same "/bz" straight to
            // Hypixel, so a disabled/undecided player sees no behavior change at all. Same pattern as
            // AuctionHouseFeature's own "/ah" registration.
            // With anything after it ("/bz rec", "/bz Recombobulator 3000") it is always Hypixel's own search,
            // forwarded whole. Without this argument node, the client dispatcher rejected every such line with
            // "Incorrect argument for command at position 3" and Hypixel never saw it (killer560, 2026-10-06).
            dispatcher.register(ClientCommands.literal("bz").executes(ctx -> {
                if (shouldOverrideBz()) {
                    openDeferred();
                } else {
                    forwardToServer("bz");
                }
                return 1;
            }).then(ClientCommands.argument("args", StringArgumentType.greedyString()).executes(ctx -> {
                forwardToServer("bz " + StringArgumentType.getString(ctx, "args"));
                return 1;
            })));
            // Explicit bypass - real Hypixel /bz is always still reachable regardless of the override.
            dispatcher.register(ClientCommands.literal("hypixelbz").executes(ctx -> {
                forwardToServer("bz");
                return 1;
            }).then(ClientCommands.argument("args", StringArgumentType.greedyString()).executes(ctx -> {
                forwardToServer("bz " + StringArgumentType.getString(ctx, "args"));
                return 1;
            })));
        });
    }

    private static boolean shouldOverrideBz() {
        AuctionConfig cfg = AuctionConfig.getInstance();
        return cfg.isBazaarEnabled() && cfg.isOverrideBzCommand();
    }

    private static void forwardToServer(String command) {
        com.killer560.hub.util.ServerCommands.toServer(command);
    }

    public static void openOrExplain() {
        if (!AuctionConfig.getInstance().isBazaarEnabled()) {
            ModChat.send("Bazaar", ModChat.bad("Turn on the Bazaar Browser in the Items tab first."));
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
        int code = cfg.getOpenBazaarKeyCode();
        if (code < 0 || client.getWindow() == null) {
            keyWasDown = false;
            return;
        }
        boolean down = KeyUtil.isKeyDown(client.getWindow(), code);
        if (down && !keyWasDown && McCompat.screen(client) == null) {
            openDeferred();
        }
        keyWasDown = down;
    }
}
