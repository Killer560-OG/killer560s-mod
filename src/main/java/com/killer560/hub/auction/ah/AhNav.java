package com.killer560.hub.auction.ah;

import com.killer560.hub.auction.AuctionHouseConfig;
import com.killer560.hub.auction.AuctionListing;
import com.killer560.hub.auction.screen.AuctionHouseScreen;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ServerCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Moving inside the unified Auction House. Every step is something he could have done himself: opening a listing runs
 * Hypixel's own {@code /viewauction <uuid>} (one command for one click), going back to the browser closes the real menu
 * the way Escape does, and nothing here ever clicks a slot.
 */
public final class AhNav {

    /** How long a listing he just opened is shown as the "opening" frame while Hypixel's page is on its way. */
    public static final long PENDING_MS = 4000L;

    private static volatile AuctionListing pending;
    private static volatile long pendingAtMs;
    private static volatile String lastCommand = "";

    private AhNav() {
    }

    /**
     * Opens one listing on Hypixel: exactly {@code /viewauction <uuid>}, the auction's id as the API publishes it
     * (32 hex digits, no dashes), sent below the client dispatcher ({@link ServerCommands#toServer}).
     */
    public static void openListing(AuctionListing l) {
        if (l == null || l.uuid() == null) {
            return;
        }
        AuctionHouseConfig cfg = AuctionHouseConfig.getInstance();
        cfg.addRecentViewed(new AuctionHouseConfig.Viewed(l.skyblockId(), l.itemName(), l.tier() == null ? "" : l.tier(),
                l.uuid().toString(), System.currentTimeMillis()));
        cfg.save();
        pending = l;
        pendingAtMs = System.currentTimeMillis();
        lastCommand = "viewauction " + l.uuid().toString().replace("-", "");
        ServerCommands.toServer(lastCommand);
    }

    /** The listing being opened, while its page has not arrived yet; null otherwise. */
    public static AuctionListing pending() {
        AuctionListing p = pending;
        return p != null && System.currentTimeMillis() - pendingAtMs < PENDING_MS ? p : null;
    }

    public static void clearPending() {
        pending = null;
    }

    /**
     * To the API browser with a category (null keeps his last), a search (null keeps it) and the search box focused or
     * not. When a real AH menu is open it is closed first, exactly as Escape closes it.
     */
    public static void toBrowser(String category, String query, boolean focusSearch) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null && McCompat.screen(mc) instanceof AbstractContainerScreen<?>) {
                mc.player.closeContainer();
            }
            AuctionHouseScreen screen = new AuctionHouseScreen(null);
            screen.preset(category, query, focusSearch);
            McCompat.setScreen(mc, screen);
        });
    }

    /** The last command sent from the AH (testkit). */
    public static String lastCommandForTest() {
        return lastCommand;
    }
}
