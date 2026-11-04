package com.killer560.hub.ahflip;

import com.killer560.hub.bazaarflip.BazaarFlipMenus;
import com.killer560.hub.bazaarflip.mixin.SignEditScreenAccessor;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ServerCommands;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AH Flipper's Auto Relist (cheat build only, off by default; to-do Doc "AH FLIPPER", 2026-11-01: "It needs to scan the full
 * ah and find the selling price of stuff then snipe flips and relist them").
 *
 * <p>One cycle, only while no screen is open, outside dungeons and while the buying macro is idle: {@code /ah}, then
 * <ol>
 *   <li><b>View Bids</b>: Claim All, or each won auction (its BIN Auction View's Collect Auction), so a bought flip reaches
 *       the inventory;</li>
 *   <li><b>Manage Auctions</b>: Claim All, or each Sold / Expired auction's Collect Auction (coins for a sale, the item back
 *       for an expired one - which is then relisted at the current lowest BIN);</li>
 *   <li><b>Create Auction</b>: for each item it has to relist (the flips the macro bought, the expired ones it claimed back),
 *       the item is clicked into Create BIN Auction, Set Price's sign gets Auto Relist's price for it (AhPriceCopier.relistPrice) (the price
 *       the flip was bought to sell at, or the lowest BIN, less the AH Price Buttons' Undercut), the price the menu then shows is
 *       checked against it, and Create BIN Auction, then Confirm, list it. Hypixel's "BIN Auction started for NAME!" counts
 *       it.</li>
 * </ol>
 *
 * <p><b>The menu names are not from a recording yet</b> (no dump of these menus existed on 2026-11-02). They are the
 * best-known ones from public mods (NEU's Create BIN Auction slots 13/29/31, SkyHanni's "Manage Auctions" and
 * "Status: Sold!/Expired!" lore, Skyblocker's "Collect Auction" and "... Auction View" titles), and every match is
 * tolerant (case, a word inside a longer name, a fixed slot only as the last resort) and logged with the whole page
 * ({@code [AhRelist]} lines), so the {@code /ahmenudump} recording ({@link AhMenuRecorder}) can correct them. A page it
 * does not know, a button it cannot find, a price that reads back wrong or a step that times out closes the menu and ends
 * the cycle; three failed cycles in a row halt it until it is switched off and on (or Relist Now is pressed).
 *
 * <p>Every command and click goes through {@link ActionGate} (its own actors) at START_CLIENT_TICK, 250-500 ms apart at
 * random, and the macro is registered with {@link com.killer560.hub.macrostop.MacroStop} as a click macro: Escape or the
 * Emergency Cancel Key stops it.
 */
public final class AhRelistMacro {

    private static final Logger LOGGER = ModLog.get("killer560smod-ahflip");
    static final String CHAT = "AH Relist";

    /** Where each step of the cycle is. */
    enum Phase { BIDS, OWN, LIST }

    private enum State { IDLE, RUNNING }

    // ---- what is matched (all tolerant: lower case, "contains") -------------------------------------------------------
    static final String VIEW_BIDS = "view bids";
    static final String MANAGE = "manage auctions";
    static final String CREATE = "create auction";
    static final String CLAIM_ALL = "claim all";
    static final String COLLECT = "collect auction";
    static final String SWITCH_BIN = "switch to bin";
    static final String GO_BACK = "go back";
    /** NEU's Create BIN Auction slots: the item, the create button, the price. */
    static final int ITEM_SLOT = 13;
    static final int CREATE_SLOT = 29;
    static final int PRICE_SLOT = 31;

    /** "Status: Sold!", "Status: Expired!" (SkyHanni), "Status: Ended!" on a bid page. Anchored, on stripped lore. */
    static final Pattern CLAIMABLE_LORE = Pattern.compile("^Status: (Sold|Expired|Ended)!?$", Pattern.CASE_INSENSITIVE);
    /** A price on the Create BIN Auction page: NEU's "Item price: N coins", SkyHanni's "Buy it now: N coins". */
    static final Pattern PRICE_TEXT = Pattern.compile("^(?:Item price|Buy it now|Price|Starting bid|BIN price)\\s*:\\s*([\\d,]{1,15}) coins?$",
            Pattern.CASE_INSENSITIVE);
    /** "[Auction] Name bought Item for 1,000 coins CLICK" - someone bought his listing: coins to claim. Anchored, bounded. */
    static final Pattern SOLD_LINE = Pattern.compile("^\\[Auction] [A-Za-z0-9_]{1,16} bought (.{1,80}) for ([\\d,]{1,15}) coins CLICK$");

    static final long STEP_TIMEOUT_MS = 6_000L;
    static final long SETTLE_MS = 250L;
    static final long CYCLE_COOLDOWN_MS = 60_000L;
    static final int MAX_FAILS_IN_A_ROW = 3;
    static final int MAX_CLAIM_CLICKS = 30;

    private static State state = State.IDLE;
    private static Phase phase = Phase.BIDS;
    /** key -> plain name: the items it is to relist (bought flips, expired listings claimed back). */
    private static final Map<String, String> TO_LIST = new LinkedHashMap<>();
    private static boolean requested;
    private static volatile boolean claimHint;
    private static volatile String startedLine;
    private static long nextActionAtMs;
    private static long nextCycleAtMs;
    private static long progressAtMs;
    private static int seenContainer = Integer.MIN_VALUE;
    private static String seenSignature = "";
    private static long seenSinceMs;
    private static String loggedPage = "";
    private static int clickedContainer = Integer.MIN_VALUE;
    private static String clickedSignature = "";
    private static long clickedAtMs;
    private static int reopens;
    private static int claimClicks;
    private static boolean expectSign;
    private static boolean signTyped;
    private static boolean awaitStarted;
    private static String currentKey;
    private static String currentName = "";
    private static int currentCount;
    private static long currentPrice;
    private static int listedThisCycle;
    private static int claimedThisCycle;
    private static int listedTotal;
    private static int claimedTotal;
    private static int failsInARow;
    private static boolean halted;
    private static String lastReason = "";

    private AhRelistMacro() {
    }

    static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientTickEvents.START_CLIENT_TICK.register(com.killer560.hub.util.FeatureGuard.start("AhRelistMacro.tick",
                AhRelistMacro::tick));
        com.killer560.hub.macrostop.MacroStop.register("AH Relist", com.killer560.hub.macrostop.MacroStop.Kind.CLICK,
                AhRelistMacro::isRunning, why -> stopByUser("you pressed " + why));
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            if (state != State.IDLE) {
                end("the world changed", false);
            }
        });
        com.killer560.hub.util.ChatObserver.subscribe(message -> {
            String line = com.killer560.hub.util.ChatObserver.strip(message).trim();
            if (AhPriceCopier.STARTED_LINE.matcher(line).matches()) {
                startedLine = line;
            } else if (SOLD_LINE.matcher(line).matches()) {
                claimHint = true;
            }
        });
    }

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    /** The buying macro bought {@code key}: claim it from View Bids and relist it (AhFlipMacro calls this). */
    static void noteBought(String key, String name) {
        if (key == null || key.isEmpty()) {
            return;
        }
        TO_LIST.put(key, name == null ? key : name);
        claimHint = true;
    }

    /** Relist Now: one cycle as soon as nothing else is open, cooldown and halt aside. */
    public static void requestNow() {
        requested = true;
        halted = false;
        failsInARow = 0;
        nextCycleAtMs = 0L;
    }

    private static void tick(Minecraft client) {
        AhFlipConfig cfg = AhFlipConfig.getInstance();
        if (!cfg.isRelistActive() || client.player == null) {
            if (state != State.IDLE) {
                end("Auto Relist was turned off", false);
            }
            if (!cfg.isRelistActive()) {
                halted = false;
                failsInARow = 0;
                requested = false;
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextActionAtMs) {
            return;
        }
        Screen screen = McCompat.screen(client);
        if (state == State.IDLE) {
            maybeStart(client, screen, now);
            return;
        }
        if (now - progressAtMs > STEP_TIMEOUT_MS) {
            fail(client, "nothing happened for " + STEP_TIMEOUT_MS / 1000 + " s"
                    + (screen instanceof AbstractContainerScreen<?> c ? " on " + BazaarFlipMenus.describe(c) : ""));
            return;
        }
        if (awaitStarted) {
            String line = startedLine;
            if (line != null) {
                startedLine = null;
                onListed(client, line, now);
            }
            return;
        }
        if (screen instanceof AbstractSignEditScreen sign) {
            if (!expectSign) {
                fail(client, "a sign opened that it did not ask for: "
                        + Arrays.toString(((SignEditScreenAccessor) sign).killer560smod$getMessages()));
                return;
            }
            fillSign(sign, now);
            return;
        }
        if (screen instanceof AbstractContainerScreen<?> c) {
            int id = c.getMenu().containerId;
            String sig = BazaarFlipMenus.signature(c.getMenu());
            if (id == clickedContainer && sig.equals(clickedSignature)) {
                return; // the page just clicked, unchanged: the server has not answered yet
            }
            if (!settled(c, sig, now)) {
                return;
            }
            onPage(client, c, now);
            return;
        }
        if (screen == null) {
            if (now - clickedAtMs < 1_500L) {
                return; // between pages
            }
            reopen(client, now);
        }
        // Another screen (chat, the pause menu): wait; Escape already stopped it through MacroStop.
    }

    private static void maybeStart(Minecraft client, Screen screen, long now) {
        if (halted || screen != null || com.killer560.hub.secrets.DungeonState.isInDungeon() || AhFlipMacro.isBusy() || !requested && now < nextCycleAtMs || !requested && !claimHint && !anyToListInInventory(client)) {
            return;
        }
        state = State.RUNNING;
        phase = Phase.BIDS;
        reopens = 0;
        claimClicks = 0;
        listedThisCycle = 0;
        claimedThisCycle = 0;
        currentKey = null;
        expectSign = false;
        signTyped = false;
        awaitStarted = false;
        startedLine = null;
        clickedContainer = Integer.MIN_VALUE;
        clickedSignature = "";
        loggedPage = "";
        progressAtMs = now;
        boolean byHand = requested;
        requested = false;
        claimHint = false;
        LOGGER.info("[AhRelist] cycle start ({}), to relist: {}", byHand ? "Relist Now" : "auto", TO_LIST.keySet());
        ModChat.send(CHAT, ModChat.text("Claiming and relisting"),
                ModChat.dim(TO_LIST.isEmpty() ? "." : " (" + TO_LIST.size() + " to relist)."));
        reopen(client, now);
    }

    private static boolean anyToListInInventory(Minecraft client) {
        if (TO_LIST.isEmpty() || client.player == null) {
            return false;
        }
        var inv = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && TO_LIST.containsKey(AhItemKey.of(s, BazaarFlipMenus.nameOf(s)))) {
                return true;
            }
        }
        return false;
    }

    private static void reopen(Minecraft client, long now) {
        int cap = 6 + 3 * Math.max(1, TO_LIST.size()) + claimClicks;
        if (reopens >= cap) {
            fail(client, "the Auction House was opened " + reopens + " times without finishing");
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.AH_RELIST_CMD)) {
            return; // retried next tick
        }
        if (!ServerCommands.toServer("ah")) {
            fail(client, "couldn't send /ah");
            return;
        }
        reopens++;
        clickedAtMs = now;
        progressAtMs = now;
        pace(now);
        LOGGER.info("[AhRelist] /ah ({}), phase {}", reopens, phase);
    }

    private static void onPage(Minecraft client, AbstractContainerScreen<?> c, long now) {
        String title = BazaarFlipMenus.titleOf(c);
        String t = title.toLowerCase(Locale.ROOT);
        String page = BazaarFlipMenus.describe(c);
        if (!page.equals(loggedPage)) {
            loggedPage = page;
            LOGGER.info("[AhRelist] phase {} on {}", phase, page);
        }
        if (t.endsWith("auction house")) {
            onMain(client, c, now);
        } else if (t.contains("bids")) {
            onBids(client, c, now);
        } else if (t.equals(MANAGE)) {
            onManage(client, c, now);
        } else if (t.endsWith("auction view")) {
            onView(client, c, now);
        } else if (t.equals("create bin auction")) {
            onCreateBin(client, c, now);
        } else if (t.startsWith(CREATE)) {
            int s = find(c, SWITCH_BIN);
            if (s < 0) {
                fail(client, "no \"Switch to BIN\" on " + page);
                return;
            }
            click(client, c, s, "Switch to BIN", now);
        } else if (t.startsWith("confirm") && t.contains("auction")) {
            onConfirm(client, c, now);
        } else {
            fail(client, "an unexpected menu opened: " + page);
        }
    }

    private static void onMain(Minecraft client, AbstractContainerScreen<?> c, long now) {
        if (phase == Phase.BIDS) {
            int s = find(c, VIEW_BIDS);
            if (s >= 0) {
                click(client, c, s, "View Bids", now);
                return;
            }
            LOGGER.info("[AhRelist] no View Bids on the Auction House menu: nothing bid on");
            phase = Phase.OWN;
        }
        if (phase == Phase.OWN) {
            int s = find(c, MANAGE);
            if (s >= 0) {
                click(client, c, s, "Manage Auctions", now);
                return;
            }
            LOGGER.info("[AhRelist] no Manage Auctions on the Auction House menu: no listings");
            phase = Phase.LIST;
        }
        if (!anyToListInMenu(c)) {
            finish(client, now);
            return;
        }
        int s = find(c, CREATE);
        if (s < 0) {
            s = find(c, MANAGE);
        }
        if (s < 0) {
            fail(client, "no Create Auction or Manage Auctions on " + BazaarFlipMenus.describe(c));
            return;
        }
        click(client, c, s, BazaarFlipMenus.nameOf(c.getMenu().slots.get(s).getItem()), now);
    }

    private static void onBids(Minecraft client, AbstractContainerScreen<?> c, long now) {
        if (phase == Phase.BIDS && claimClicks < MAX_CLAIM_CLICKS) {
            if (claim(client, c, now)) {
                return;
            }
        }
        phase = phase == Phase.BIDS ? Phase.OWN : phase;
        back(client, c, now);
    }

    private static void onManage(Minecraft client, AbstractContainerScreen<?> c, long now) {
        if (phase == Phase.BIDS) {
            phase = Phase.OWN;
        }
        if (phase == Phase.OWN && claimClicks < MAX_CLAIM_CLICKS) {
            if (claim(client, c, now)) {
                return;
            }
        }
        phase = Phase.LIST;
        if (!anyToListInMenu(c)) {
            finish(client, now);
            return;
        }
        int s = find(c, CREATE);
        if (s < 0) {
            fail(client, "no Create Auction on " + BazaarFlipMenus.describe(c));
            return;
        }
        click(client, c, s, "Create Auction", now);
    }

    /** Claim All, or the first claimable listing. False when there is nothing to claim here. */
    private static boolean claim(Minecraft client, AbstractContainerScreen<?> c, long now) {
        int all = find(c, CLAIM_ALL);
        Slot first = null;
        for (Slot slot : BazaarFlipMenus.menuSlots(c.getMenu())) {
            ItemStack s = slot.getItem();
            if (s.isEmpty() || BazaarFlipMenus.nameOf(s).toLowerCase(Locale.ROOT).contains(CLAIM_ALL)) {
                continue;
            }
            String status = claimStatus(s);
            if (status == null) {
                continue;
            }
            if (status.equalsIgnoreCase("expired") && phase == Phase.OWN) {
                // Back to the inventory, then relisted at the CURRENT price: the old one did not sell.
                String name = BazaarFlipMenus.nameOf(s);
                String key = AhItemKey.of(s, name);
                if (!key.isEmpty() && !AhItemKey.isPet(key)) {
                    TO_LIST.put(key, name);
                    AhPriceCopier.forget(key);
                    LOGGER.info("[AhRelist] expired listing {} ({}) will be relisted at the lowest BIN", name, key);
                }
            }
            if (first == null) {
                first = slot;
            }
        }
        if (all >= 0) {
            claimClicks++;
            claimedThisCycle++;
            click(client, c, all, "Claim All", now);
            return true;
        }
        if (first != null) {
            claimClicks++;
            click(client, c, first.index, "claim " + BazaarFlipMenus.nameOf(first.getItem()), now);
            return true;
        }
        return false;
    }

    /** "Sold", "Expired", "Ended" from a listing's lore, or null. */
    static String claimStatus(ItemStack stack) {
        for (String line : DungeonChestValuer.cleanLore(stack)) {
            Matcher m = CLAIMABLE_LORE.matcher(line.trim());
            if (m.matches()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static void onView(Minecraft client, AbstractContainerScreen<?> c, long now) {
        int s = find(c, COLLECT);
        if (s < 0) {
            LOGGER.info("[AhRelist] nothing to collect on {}", BazaarFlipMenus.describe(c));
            back(client, c, now);
            return;
        }
        claimedThisCycle++;
        click(client, c, s, "Collect Auction", now);
    }

    private static void onCreateBin(Minecraft client, AbstractContainerScreen<?> c, long now) {
        phase = Phase.LIST;
        var slots = c.getMenu().slots;
        if (slots.size() <= Math.max(ITEM_SLOT, PRICE_SLOT)) {
            fail(client, "Create BIN Auction has only " + slots.size() + " slots");
            return;
        }
        ItemStack placed = slots.get(ITEM_SLOT).getItem();
        String placedKey = AhItemKey.of(placed, BazaarFlipMenus.nameOf(placed));
        if (placedKey.isEmpty()) {
            pickNext(client, c, now);
            return;
        }
        if (currentKey == null || !currentKey.equals(placedKey) || placed.getCount() != currentCount) {
            fail(client, "Create BIN Auction holds " + BazaarFlipMenus.nameOf(placed) + " x" + placed.getCount()
                    + ", not what it is listing (" + (currentKey == null ? "nothing" : currentName + " x" + currentCount) + ")");
            return;
        }
        int priceSlot = priceButton(c);
        long shown = priceShown(c, priceSlot);
        if (shown == currentPrice) {
            int create = createButton(c);
            if (create < 0) {
                fail(client, "no Create BIN Auction button on " + BazaarFlipMenus.describe(c));
                return;
            }
            click(client, c, create, "Create BIN Auction at " + currentPrice, now);
            return;
        }
        if (signTyped) {
            fail(client, "the price reads " + shown + " after typing " + currentPrice + " (" + BazaarFlipMenus.describe(c) + ")");
            return;
        }
        if (priceSlot < 0) {
            fail(client, "no price button on " + BazaarFlipMenus.describe(c));
            return;
        }
        expectSign = true;
        click(client, c, priceSlot, "Set Price", now);
    }

    /** Clicks the next item to relist in his inventory into the item slot. */
    private static void pickNext(Minecraft client, AbstractContainerScreen<?> c, long now) {
        AhFlipConfig cfg = AhFlipConfig.getInstance();
        for (Slot slot : BazaarFlipMenus.playerSlots(c.getMenu())) {
            ItemStack s = slot.getItem();
            if (s.isEmpty()) {
                continue;
            }
            String name = BazaarFlipMenus.nameOf(s);
            String key = AhItemKey.of(s, name);
            if (!TO_LIST.containsKey(key)) {
                continue;
            }
            long price = AhPriceCopier.relistPrice(key, s.getCount(), cfg);
            if (price <= 0 || price < cfg.getMinSellPrice()) {
                TO_LIST.remove(key);
                LOGGER.info("[AhRelist] not relisting {} ({}): price {}{}", name, key, price,
                        price <= 0 ? " unknown" : " is under Min Sell Price");
                ModChat.log(CHAT, ModChat.dim("Not relisting " + name + ": "
                        + (price <= 0 ? "no lowest BIN known" : "under Min Sell Price")));
                continue;
            }
            currentKey = key;
            currentName = name;
            currentCount = s.getCount();
            currentPrice = price;
            signTyped = false;
            click(client, c, slot.index, "put up " + name + " x" + currentCount + " for " + price, now);
            return;
        }
        // Nothing (left) to list in the inventory.
        TO_LIST.keySet().removeIf(k -> !inMenuInventory(c, k));
        finish(client, now);
    }

    private static boolean anyToListInMenu(AbstractContainerScreen<?> c) {
        for (String k : TO_LIST.keySet()) {
            if (inMenuInventory(c, k)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inMenuInventory(AbstractContainerScreen<?> c, String key) {
        for (Slot slot : BazaarFlipMenus.playerSlots(c.getMenu())) {
            ItemStack s = slot.getItem();
            if (!s.isEmpty() && key.equals(AhItemKey.of(s, BazaarFlipMenus.nameOf(s)))) {
                return true;
            }
        }
        return false;
    }

    /** The price button: a name about the price, else NEU's slot 31. */
    static int priceButton(AbstractContainerScreen<?> c) {
        for (Slot slot : BazaarFlipMenus.menuSlots(c.getMenu())) {
            if (slot.index == ITEM_SLOT) {
                continue;
            }
            String n = BazaarFlipMenus.nameOf(slot.getItem()).toLowerCase(Locale.ROOT);
            if (!n.contains("create") && (n.contains("set price") || n.contains("item price") || n.contains("buy it now")
                    || n.startsWith("price") || n.contains("starting bid"))) {
                return slot.index;
            }
        }
        var slots = c.getMenu().slots;
        return slots.size() > PRICE_SLOT && !slots.get(PRICE_SLOT).getItem().isEmpty() ? PRICE_SLOT : -1;
    }

    /** The price the page shows (the price button's name or lore first, then any slot's), or -1. */
    static long priceShown(AbstractContainerScreen<?> c, int priceSlot) {
        var slots = c.getMenu().slots;
        if (priceSlot >= 0) {
            long v = priceIn(slots.get(priceSlot).getItem());
            if (v >= 0) {
                return v;
            }
        }
        for (Slot slot : BazaarFlipMenus.menuSlots(c.getMenu())) {
            if (slot.index != ITEM_SLOT) {
                long v = priceIn(slot.getItem());
                if (v >= 0) {
                    return v;
                }
            }
        }
        return -1;
    }

    private static long priceIn(ItemStack stack) {
        if (stack.isEmpty()) {
            return -1;
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(BazaarFlipMenus.nameOf(stack));
        lines.addAll(DungeonChestValuer.cleanLore(stack));
        for (String line : lines) {
            Matcher m = PRICE_TEXT.matcher(line.trim());
            if (m.matches()) {
                try {
                    return Long.parseLong(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    // bounded to 15 digits
                }
            }
        }
        return -1;
    }

    /** The create button: named "Create BIN Auction", else a create/confirm name, else NEU's slot 29 if named so. */
    static int createButton(AbstractContainerScreen<?> c) {
        int fallback = -1;
        for (Slot slot : BazaarFlipMenus.menuSlots(c.getMenu())) {
            if (slot.index == ITEM_SLOT) {
                continue;
            }
            String n = BazaarFlipMenus.nameOf(slot.getItem()).toLowerCase(Locale.ROOT);
            if (n.equals("create bin auction")) {
                return slot.index;
            }
            if (fallback < 0 && n.contains("create") && n.contains("auction")) {
                fallback = slot.index;
            }
        }
        if (fallback >= 0) {
            return fallback;
        }
        var slots = c.getMenu().slots;
        if (slots.size() > CREATE_SLOT) {
            String n = BazaarFlipMenus.nameOf(slots.get(CREATE_SLOT).getItem()).toLowerCase(Locale.ROOT);
            if (n.contains("create") || n.contains("confirm")) {
                return CREATE_SLOT;
            }
        }
        return -1;
    }

    private static void onConfirm(Minecraft client, AbstractContainerScreen<?> c, long now) {
        if (currentKey == null) {
            fail(client, "a confirm page opened with nothing being listed: " + BazaarFlipMenus.describe(c));
            return;
        }
        int s = -1;
        for (Slot slot : BazaarFlipMenus.menuSlots(c.getMenu())) {
            if (BazaarFlipMenus.nameOf(slot.getItem()).equalsIgnoreCase("confirm")) {
                s = slot.index;
                break;
            }
        }
        if (s < 0) {
            s = find(c, "confirm");
        }
        if (s < 0) {
            fail(client, "no Confirm on " + BazaarFlipMenus.describe(c));
            return;
        }
        if (click(client, c, s, "Confirm (" + currentName + " for " + currentPrice + ")", now)) {
            startedLine = null;
            awaitStarted = true;
        }
    }

    private static void onListed(Minecraft client, String line, long now) {
        awaitStarted = false;
        listedThisCycle++;
        listedTotal++;
        TO_LIST.remove(currentKey);
        AhPriceCopier.remember(currentKey, Math.max(1, currentPrice / Math.max(1, currentCount)));
        LOGGER.info("[AhRelist] listed {} x{} at {} ({})", currentName, currentCount, currentPrice, line);
        ModChat.log(CHAT, ModChat.text("Listed "), ModChat.value(currentName),
                ModChat.dim(" for " + DungeonChestValuer.formatCoins(currentPrice)));
        currentKey = null;
        signTyped = false;
        expectSign = false;
        progressAtMs = now;
        pace(now);
        // Hypixel closes the confirm page (or not): the next item starts from /ah again either way.
        if (client.player != null && McCompat.screen(client) instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
        }
        clickedAtMs = now;
    }

    private static void fillSign(AbstractSignEditScreen sign, long now) {
        String[] lines = ((SignEditScreenAccessor) sign).killer560smod$getMessages();
        if (!lines[0].isEmpty()) {
            fail(Minecraft.getInstance(), "the price sign's first line already holds \"" + lines[0] + "\"");
            return;
        }
        Button done = null;
        for (GuiEventListener child : sign.children()) {
            if (child instanceof Button b && b.getMessage().getString().equals(CommonComponents.GUI_DONE.getString())) {
                done = b;
            }
        }
        if (done == null) {
            fail(Minecraft.getInstance(), "the price sign has no Done button");
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.AH_RELIST_CMD)) {
            return; // retried next tick
        }
        String text = Long.toString(currentPrice);
        for (int i = 0; i < text.length(); i++) {
            sign.charTyped(new CharacterEvent(text.charAt(i)));
        }
        String typed = ((SignEditScreenAccessor) sign).killer560smod$getMessages()[0];
        if (!text.equals(typed)) {
            // Never send a wrong price: end() empties the line before it closes the sign (closing sends its lines).
            fail(Minecraft.getInstance(), "typing " + text + " into the price sign left \"" + typed + "\"");
            return;
        }
        LOGGER.info("[AhRelist] typed {} into the price sign {}", text, Arrays.toString(lines));
        done.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        expectSign = false;
        signTyped = true;
        clickedAtMs = now;
        progressAtMs = now;
        pace(now);
    }

    /** First menu slot whose name contains {@code needle}, ignoring case, or -1. */
    static int find(AbstractContainerScreen<?> c, String needle) {
        return BazaarFlipMenus.findMenuSlot(c.getMenu(), needle);
    }

    private static boolean click(Minecraft client, AbstractContainerScreen<?> c, int slot, String what, long now) {
        if (!ActionGate.tryAct(ActionGate.Actor.AH_RELIST_MENU, c)) {
            return false; // retried next tick
        }
        client.gameMode.handleContainerInput(c.getMenu().containerId, slot, 0, ContainerInput.PICKUP, client.player);
        LOGGER.info("[AhRelist] clicked {} (slot {}) on \"{}\"", what, slot, BazaarFlipMenus.titleOf(c));
        clickedContainer = c.getMenu().containerId;
        clickedSignature = BazaarFlipMenus.signature(c.getMenu());
        clickedAtMs = now;
        progressAtMs = now;
        pace(now);
        return true;
    }

    private static void back(Minecraft client, AbstractContainerScreen<?> c, long now) {
        int s = find(c, GO_BACK);
        if (s >= 0) {
            click(client, c, s, "Go Back", now);
            return;
        }
        if (client.player != null) {
            client.player.closeContainer();
        }
        clickedAtMs = now;
        progressAtMs = now;
        pace(now);
    }

    private static boolean settled(AbstractContainerScreen<?> c, String sig, long now) {
        if (c.getMenu().containerId != seenContainer || !sig.equals(seenSignature)) {
            seenContainer = c.getMenu().containerId;
            seenSignature = sig;
            seenSinceMs = now;
            return false;
        }
        return !sig.isEmpty() && now - seenSinceMs >= SETTLE_MS;
    }

    private static void pace(long now) {
        nextActionAtMs = now + ThreadLocalRandom.current().nextLong(250, 501);
    }

    private static void finish(Minecraft client, long now) {
        failsInARow = 0;
        claimedTotal += claimedThisCycle;
        String why = "claimed " + claimedThisCycle + ", listed " + listedThisCycle;
        LOGGER.info("[AhRelist] cycle done: {}", why);
        ModChat.send(CHAT, ModChat.good("Done: "), ModChat.text(why + "."));
        end(why, true);
    }

    private static void fail(Minecraft client, String why) {
        failsInARow++;
        LOGGER.info("[AhRelist] failed in phase {}: {} ({} in a row)", phase, why, failsInARow);
        ModChat.send(CHAT, ModChat.bad("Stopped: "), ModChat.text(why));
        end(why, true);
        if (failsInARow >= MAX_FAILS_IN_A_ROW) {
            halted = true;
            ModChat.send(CHAT, ModChat.text("Auto Relist halted after " + failsInARow + " failed cycles. "),
                    ModChat.dim("Press Relist Now, or switch it off and on, to try again."));
        }
    }

    private static void stopByUser(String why) {
        LOGGER.info("[AhRelist] stopped: {}", why);
        ModChat.send(CHAT, ModChat.text("Stopped (" + why + "). "), ModChat.dim("Press Relist Now to run it again."));
        halted = true;
        end(why, false);
    }

    private static void end(String why, boolean closeMenu) {
        Minecraft client = Minecraft.getInstance();
        if (closeMenu && client.player != null && McCompat.screen(client) instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
        }
        if (client.player != null && McCompat.screen(client) instanceof AbstractSignEditScreen sign) {
            // Never leave a typed price behind: the line is emptied, then the sign closed (which sends its lines).
            ((SignEditScreenAccessor) sign).killer560smod$getMessages()[0] = "";
            McCompat.setScreen(client, null);
        }
        lastReason = why;
        state = State.IDLE;
        currentKey = null;
        expectSign = false;
        signTyped = false;
        awaitStarted = false;
        nextCycleAtMs = System.currentTimeMillis() + CYCLE_COOLDOWN_MS;
    }

    /** listed total|claimed total|halted|state|phase|last reason. */
    public static String statusForTest() {
        return listedTotal + "|" + claimedTotal + "|" + halted + "|" + state + "|" + phase + "|" + lastReason;
    }

    /** Testkit: an item to relist, as if the macro had bought it. */
    public static void noteBoughtForTest(String key, String name) {
        noteBought(key, name);
    }

    public static void resetForTest() {
        TO_LIST.clear();
        requested = false;
        claimHint = false;
        halted = false;
        failsInARow = 0;
        listedTotal = 0;
        claimedTotal = 0;
        nextCycleAtMs = 0L;
        lastReason = "";
        if (state != State.IDLE) {
            end("reset", true);
        }
        nextCycleAtMs = 0L;
    }

}
