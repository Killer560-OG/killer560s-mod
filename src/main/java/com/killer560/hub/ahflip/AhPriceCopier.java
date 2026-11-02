package com.killer560.hub.ahflip;

import com.killer560.hub.bazaarflip.BazaarFlipConfig;
import com.killer560.hub.bazaarflip.BazaarFlipMenus;
import com.killer560.hub.bazaarflip.mixin.SignEditScreenAccessor;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AH Price Copier (killer560, 2026-11-01, to-do Doc: "For all items if you put it into the ah sell thing it insta copies
 * the price of the item for someone to copy and paste it in. if i just sold an item then click on another item it should
 * copy the price of the item i just put up over the lbin price").
 *
 * <p>Legit: it only reads Hypixel's menu and writes the clipboard, never clicks or types. When an item lands in the item
 * slot (13) of Hypixel's "Create BIN Auction" menu, the price is copied once per item: the price you put the same item
 * (same {@link AhItemKey}) up at in the last {@link #OWN_PRICE_MS}, when Use My Last Price is on, else the lowest BIN of that
 * item from the AH scan, times the stack's count, less the Undercut. Your own price comes from the amount sign you filled
 * after opening Create BIN Auction (or a "Buy it now"/"Item price" line on the menu), kept when Hypixel says "BIN Auction
 * started for NAME!" (his Dungeons logs, 2026-10). The AH Flipper's macro also leaves its relist price here.
 */
public final class AhPriceCopier {

    private static final Logger LOGGER = ModLog.get("killer560smod-ahflip");
    static final String CHAT = "AH Price Copier";
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

    private static String lastCopiedIdentity = "";
    private static String listingKey = "";
    private static String listingName = "";
    private static int listingCount = 1;
    private static long pendingPrice = -1;
    private static boolean wasCreateMenu;
    private static long lastCopied = -1;

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
        if (!cfg.isCopierEnabled()) {
            lastCopiedIdentity = "";
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
            lastCopiedIdentity = "";
            return;
        }
        String name = BazaarFlipMenus.nameOf(stack);
        String key = AhItemKey.of(stack, name);
        if (key.isEmpty()) {
            return; // a pane or a button: no item in the slot yet
        }
        String identity = key + "x" + stack.getCount();
        if (!key.equals(listingKey)) {
            pendingPrice = -1; // a different item: an earlier sign value is not its price
        }
        listingKey = key;
        listingName = name;
        listingCount = Math.max(1, stack.getCount());
        if (identity.equals(lastCopiedIdentity)) {
            return;
        }
        lastCopiedIdentity = identity;
        copyFor(client, key, name, listingCount, cfg);
    }

    /** Copies the price for {@code count} of {@code key} and says so. Returns the coins copied, or -1. */
    static long copyFor(Minecraft client, String key, String name, int count, AhFlipConfig cfg) {
        long now = System.currentTimeMillis();
        long[] own = OWN.get(key);
        long each;
        String source;
        if (cfg.isCopierOwnPrice() && own != null && now - own[1] <= OWN_PRICE_MS) {
            each = own[0];
            source = "your last listing";
        } else {
            each = AhMarketIndex.lowestBin(key);
            source = "lowest BIN";
        }
        if (each <= 0) {
            ModChat.send(CHAT, ModChat.dim("No lowest BIN known for " + name + " yet - the AH scan has not seen one."));
            lastCopied = -1;
            return -1;
        }
        long total = each * count;
        long copy = Math.max(1, total - cfg.getCopierUndercut());
        client.keyboardHandler.setClipboard(Long.toString(copy));
        lastCopied = copy;
        LOGGER.info("[AhPriceCopier] Copied {} for {} x{} ({} {} each)", copy, key, count, source, each);
        ModChat.send(CHAT, ModChat.text("Copied "), ModChat.value(String.format(Locale.US, "%,d", copy)),
                ModChat.dim(" (" + source + " " + DungeonChestValuer.formatCoins(total) + ")"));
        return copy;
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
        // The next copy of the same item is that price, not what the clipboard was given before.
        lastCopiedIdentity = "";
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

    /** Testkit: the last value copied (-1: none). */
    public static long lastCopiedForTest() {
        return lastCopied;
    }

    /** Testkit: forget the remembered prices and the last copy. */
    public static void resetForTest() {
        OWN.clear();
        lastCopiedIdentity = "";
        listingKey = "";
        pendingPrice = -1;
        lastCopied = -1;
    }
}
