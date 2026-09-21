package com.killer560.hub.auction;

import com.killer560.hub.auction.screen.AuctionHouseScreen;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * killer560's item 8.1, part 1+2: "Custom AH search/sell menu... /ah replacement toggle or
 * /killer560 ah, a keybind". This class owns opening the browser (command/keybind) and the real
 * {@code /ah} override; {@link AuctionHouseApi} owns the actual scan, {@code AuctionHouseScreen} the UI.
 * <p>
 * Never buys, bids or lists anything itself - clicking a listing in the screen runs Hypixel's own real
 * {@code /viewauction <uuid>} (see {@code AuctionHouseScreen}) so Hypixel's own menu handles the actual
 * transaction; this class's only "action on his behalf" is running the exact same {@code /ah} he would
 * have typed himself, unchanged, when the override is off or he explicitly asks for the real one.
 */
public final class AuctionHouseFeature {

    private static boolean keyWasDown = false;

    private AuctionHouseFeature() {
    }

    public static void register() {
        AuctionConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(AuctionHouseFeature::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Real Hypixel /ah - intercepted client-side only while both the browser is enabled AND
            // killer560 turned the override on; otherwise this sends the exact same "/ah" straight to
            // Hypixel, so a disabled/undecided player sees no behavior change at all.
            dispatcher.register(ClientCommands.literal("ah").executes(ctx -> {
                if (shouldOverrideAh()) {
                    openDeferred();
                } else {
                    forwardToServer("ah");
                }
                return 1;
            }));
            // Explicit bypass - "Hypixel's still reachable by an explicit command" - always the real menu
            // regardless of the override toggle.
            dispatcher.register(ClientCommands.literal("hypixelah").executes(ctx -> {
                forwardToServer("ah");
                return 1;
            }));
            // Self-registered alias, same pattern as ProfileViewerFeature's "/pv" + "/killer560pv" (no
            // edit to the big inline "/killer560 ..." tree needed for this one). The brief's literal
            // "/killer560 ah" (with the space) is a tiny patch to that existing tree instead - see this
            // package's staging notes for the exact snippet, since that file isn't owned by this task.
            dispatcher.register(ClientCommands.literal("killer560ah").executes(ctx -> {
                openOrExplain();
                return 1;
            }));
        });
    }

    private static boolean shouldOverrideAh() {
        AuctionConfig cfg = AuctionConfig.getInstance();
        return cfg.isAhEnabled() && cfg.isOverrideAhCommand();
    }

    /** Used by both "/killer560ah" and the "/killer560 ah" patch (see staging notes) - opens the browser
     *  if the feature is on, otherwise tells him where to turn it on instead of silently doing nothing. */
    public static void openOrExplain() {
        if (!AuctionConfig.getInstance().isAhEnabled()) {
            ModChat.send("Auction House", ModChat.bad("Turn on the Auction House Browser in the New tab first."));
            return;
        }
        openDeferred();
    }

    public static void openDeferred() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.setScreenAndShow(new AuctionHouseScreen(client.screen)));
    }

    private static void forwardToServer(String command) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.player.connection != null) {
            client.player.connection.sendCommand(command);
        }
    }

    private static void tick(Minecraft client) {
        AuctionConfig cfg = AuctionConfig.getInstance();
        int code = cfg.getOpenAhKeyCode();
        if (!cfg.isAhEnabled() || code < 0 || client.player == null || client.getWindow() == null) {
            keyWasDown = false;
            return;
        }
        boolean down = KeyUtil.isKeyDown(client.getWindow(), code);
        if (down && !keyWasDown && client.screen == null) {
            openDeferred();
        }
        keyWasDown = down;
    }

    // ---------------------------------------------------------------- shared filter/sort (used by the screen)

    public static List<AuctionListing> filterAndSort(List<AuctionListing> all, String query, String rarityFilter,
                                                       int minPetLevel, AuctionConfig.SortMode sort) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String rarity = rarityFilter == null ? "" : rarityFilter.toUpperCase(Locale.ROOT);
        List<AuctionListing> out = new ArrayList<>();
        for (AuctionListing l : all) {
            if (!q.isEmpty() && !matchesSearch(l, q)) {
                continue;
            }
            if (!rarity.isEmpty() && !rarity.equals(l.tier() == null ? "" : l.tier().toUpperCase(Locale.ROOT))) {
                continue;
            }
            if (minPetLevel > 0 && (!l.isPet() || l.petLevel() < minPetLevel)) {
                continue;
            }
            out.add(l);
        }
        out.sort(comparatorFor(sort));
        return out;
    }

    /** "NEU-tied" search per the brief: matches the listing's own real display name directly (covers the
     *  common case, e.g. "hyp" -&gt; "Hyperion"), and additionally ties into the shared real item catalog
     *  ({@link SkyblockItemRepository}) by the listing's decoded {@code ExtraAttributes.id} so a query
     *  that matches the catalog's own canonical name for that id also counts, even if this particular
     *  listing's displayed name differs slightly (reforge prefix, master stars, etc). */
    private static boolean matchesSearch(AuctionListing l, String lowerQuery) {
        if (l.itemName().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        if (!l.skyblockId().isEmpty()) {
            SkyblockItemEntry entry = SkyblockItemRepository.findById(l.skyblockId());
            if (entry != null && entry.name() != null
                    && ChatFormatting.stripFormatting(entry.name()).toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                return true;
            }
        }
        return false;
    }

    private static Comparator<AuctionListing> comparatorFor(AuctionConfig.SortMode mode) {
        return switch (mode) {
            case PRICE_LOW -> Comparator.comparingLong(AuctionListing::startingBid);
            case PRICE_HIGH -> Comparator.comparingLong(AuctionListing::startingBid).reversed();
            case ENDING_SOONEST -> Comparator.comparingLong(AuctionListing::end);
            case ULTIMATE_ENCHANT -> Comparator
                    .comparingInt((AuctionListing l) -> l.hasUltimateEnchant() ? 0 : 1)
                    .thenComparing(Comparator.comparingInt(AuctionListing::ultimateEnchantTier).reversed())
                    .thenComparing(Comparator.comparingLong(AuctionListing::end));
        };
    }
}
