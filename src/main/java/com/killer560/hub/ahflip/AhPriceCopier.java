package com.killer560.hub.ahflip;

import com.killer560.hub.bazaarflip.BazaarFlipConfig;
import com.killer560.hub.bazaarflip.BazaarFlipMenus;
import com.killer560.hub.bazaarflip.mixin.SignEditScreenAccessor;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The price you last listed each item at, for the AH Price Buttons' "Copy My Last Price" and the cheat build's Auto Relist
 * (killer560, 2026-11-01, to-do Doc: "if i just sold an item then click on another item it should copy the price of the
 * item i just put up over the lbin price").
 *
 * <p>This used to be the AH Price Copier, which copied a price the moment an item landed in Create BIN Auction; on
 * 2026-11-03 he asked for buttons instead ({@link AhPriceButtons}), so nothing here writes the clipboard any more. It only
 * reads: your own price comes from the amount sign you filled after opening Create BIN Auction (or a "Buy it now"/"Item
 * price" line on the menu), kept for the item (same {@link AhItemKey}) when Hypixel says "BIN Auction started for NAME!"
 * (his Dungeons logs, 2026-10), and is fresh for {@link #OWN_PRICE_MS}. The AH Flipper's macro also leaves its relist price
 * here. {@link #relistPrice} is Auto Relist's price.
 */
public final class AhPriceCopier {

    private static final Logger LOGGER = ModLog.get("killer560smod-ahflip");
    public static final String CREATE_BIN_TITLE = "Create BIN Auction";
    /** Slot of the item being listed (SkyHanni and Skyblocker read the same one). */
    public static final int ITEM_SLOT = 13;
    /** How long a price you listed at is preferred over the lowest BIN. */
    public static final long OWN_PRICE_MS = 6 * 60 * 60_000L;

    /** "BIN Auction started for Wither Boots!" - his Dungeons logs, 2026-10. Anchored; a player's line has "name: " first. */
    public static final Pattern STARTED_LINE = Pattern.compile("^BIN Auction started for (.{1,80})!$");
    /** A menu lore line naming the price (SkyHanni's tested shapes: "Buy it now: 1,000,000,000 coins"). */
    static final Pattern PRICE_LORE = Pattern.compile("^(?:Buy it now|Item price|Price): ([\\d,]{1,15}) coins?$");

    /** key -> {price per unit, when}. */
    private static final Map<String, long[]> OWN = new ConcurrentHashMap<>();

    private static String listingKey = "";
    private static String listingName = "";
    private static int listingCount = 1;
    private static long pendingPrice = -1;
    private static boolean wasCreateMenu;

    private AhPriceCopier() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(com.killer560.hub.util.FeatureGuard.end("AhPriceCopier.tick",
                AhPriceCopier::tick));
        com.killer560.hub.util.ChatObserver.subscribe(message ->
                onChatLine(com.killer560.hub.util.ChatObserver.strip(message).trim()));
    }

    private static void tick(Minecraft client) {
        AhFlipConfig cfg = AhFlipConfig.getInstance();
        if (!cfg.isPriceButtonsEnabled()) {
            return;
        }
        Screen screen = McCompat.screen(client);
        if (screen instanceof AbstractSignEditScreen sign) {
            if (wasCreateMenu) {
                // The amount sign Set Price opens: what he types is the price he is listing at (read only, never typed).
                String[] lines = ((SignEditScreenAccessor) sign).killer560smod$getMessages();
                Long coins = lines.length > 0 ? BazaarFlipConfig.parseCoins(lines[0]) : null;
                if (coins != null && coins > 0) {
                    pendingPrice = coins;
                }
            }
            return;
        }
        if (!(screen instanceof AbstractContainerScreen<?> c)) {
            wasCreateMenu = false;
            return;
        }
        String title = BazaarFlipMenus.titleOf(c);
        boolean create = CREATE_BIN_TITLE.equals(title);
        if (!create && !"Confirm BIN Auction".equals(title)) {
            wasCreateMenu = false;
            return;
        }
        wasCreateMenu = true;
        readLorePrice(c);
        if (!create) {
            return;
        }
        var slots = c.getMenu().slots;
        if (slots.size() <= ITEM_SLOT) {
            return;
        }
        ItemStack stack = slots.get(ITEM_SLOT).getItem();
        if (stack.isEmpty()) {
            return;
        }
        String name = BazaarFlipMenus.nameOf(stack);
        String key = AhItemKey.of(stack, name);
        if (key.isEmpty()) {
            return; // a pane or a button: no item in the slot yet
        }
        if (!key.equals(listingKey)) {
            pendingPrice = -1; // a different item: an earlier sign value is not its price
        }
        listingKey = key;
        listingName = name;
        listingCount = Math.max(1, stack.getCount());
    }

    private static void readLorePrice(AbstractContainerScreen<?> c) {
        for (var slot : BazaarFlipMenus.menuSlots(c.getMenu())) {
            ItemStack s = slot.getItem();
            if (s.isEmpty()) {
                continue;
            }
            List<String> lore = DungeonChestValuer.cleanLore(s);
            for (String line : lore) {
                Matcher m = PRICE_LORE.matcher(line.trim());
                if (m.matches()) {
                    try {
                        pendingPrice = Long.parseLong(m.group(1).replace(",", ""));
                    } catch (NumberFormatException ignored) {
                        // bounded to 15 digits: cannot overflow
                    }
                }
            }
        }
    }

    static void onChatLine(String line) {
        Matcher m = STARTED_LINE.matcher(line);
        if (!m.matches() || listingKey.isEmpty() || pendingPrice <= 0) {
            return;
        }
        long each = Math.max(1, pendingPrice / Math.max(1, listingCount));
        remember(listingKey, each);
        LOGGER.info("[AhPriceCopier] Listed {} ({}) at {} each", m.group(1), listingKey, each);
        pendingPrice = -1;
    }

    private static boolean ownFresh(String key, AhFlipConfig cfg) {
        long[] own = OWN.get(key);
        return cfg.isCopierOwnPrice() && own != null && System.currentTimeMillis() - own[1] <= OWN_PRICE_MS;
    }

    /** Auto Relist's price for one of {@code key}: the remembered one when fresh and Relist at My Last Price is on, else
     *  the lowest BIN; -1 when neither is known. */
    private static long eachFor(String key, AhFlipConfig cfg) {
        return ownFresh(key, cfg) ? OWN.get(key)[0] : AhMarketIndex.lowestBin(key);
    }

    /** Auto Relist's price for {@code count} of {@code key} (its price less the Undercut), or -1. Auto Relist types this
     *  into Hypixel's price sign. */
    public static long relistPrice(String key, int count, AhFlipConfig cfg) {
        long each = eachFor(key, cfg);
        if (each <= 0) {
            return -1;
        }
        return Math.max(1, each * Math.max(1, count) - cfg.getCopierUndercut());
    }

    /** Forgets the remembered price of {@code key} (an expired listing: it did not sell at that price). */
    public static void forget(String key) {
        if (key != null) {
            OWN.remove(key);
        }
    }

    /** Remembers {@code each} as the price {@code key} was (or is to be) put up at. */
    public static void remember(String key, long each) {
        if (key != null && !key.isEmpty() && each > 0) {
            OWN.put(key, new long[]{each, System.currentTimeMillis()});
        }
    }

    /** The price remembered for {@code key}, or -1. */
    public static long remembered(String key) {
        long[] own = key == null ? null : OWN.get(key);
        return own == null ? -1 : own[0];
    }

    /** The price per unit you listed {@code key} at in the last {@link #OWN_PRICE_MS}, or -1 (Copy My Last Price). */
    public static long rememberedFresh(String key) {
        long[] own = key == null ? null : OWN.get(key);
        return own == null || System.currentTimeMillis() - own[1] > OWN_PRICE_MS ? -1 : own[0];
    }

    /** Testkit: forget the remembered prices and the last copy. */
    public static void resetForTest() {
        OWN.clear();
        listingKey = "";
        pendingPrice = -1;
    }
}
