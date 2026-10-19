package com.killer560.hub.bazaarflip;

import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarOrderParser;
import com.killer560.hub.auction.BazaarProduct;
import com.killer560.hub.bazaar.BazaarPages;
import com.killer560.hub.bazaaradvisor.BazaarAdvisorConfig;
import com.killer560.hub.bazaaradvisor.BazaarAdvisorFeature;
import com.killer560.hub.bazaarflip.autopilot.FlipAction;
import com.killer560.hub.bazaarflip.autopilot.FlipAutopilot;
import com.killer560.hub.bazaarflip.autopilot.FlipOrder;
import com.killer560.hub.bazaarflip.autopilot.FlipPricing;
import com.killer560.hub.bazaarflip.mixin.SignEditScreenAccessor;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.platform.InputConstants;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Bazaar Flipper's autopilot (CHEAT BUILD ONLY: reached only through {@link BazaarFlipFeature#start()} when
 * {@link BazaarFlipConfig#isAutopilot()}, which is gated on {@code BuildVariant.CHEAT_FEATURES_ENABLED}).
 * <p>
 * killer560, 2026-10-19: a fully autonomous flipping bot - buy orders, sell offers, Insta Sell where it pays, relisting
 * unfilled or expired orders at the current price, collecting what filled and selling or relisting it. WHAT to do comes
 * from {@link FlipAutopilot#decide} (pure; priced by the Bazaar Advisor's model and answers). This class only carries
 * the chosen action out through Hypixel's Bazaar GUI and keeps the books the decision needs: what the session holds,
 * what each unit cost, which open orders are its own.
 *
 * <h2>Menus</h2>
 * Every page is classified by {@link BazaarPages} (the reskin's classifier) and every button found by a name the repo
 * already records from SkyHanni, Skyblocker and the wiki: the product page's Buy Instantly / Sell Instantly / Create Buy
 * Order / Create Sell Offer; "How many do you want?" (Custom Amount, or a preset whose lore states exactly the amount);
 * "How much do you want to pay?" (Top Order +0.1); Confirm Buy Order (Buy Order); "At what price are you selling?" (Best
 * Offer -0.1); Confirm Sell Offer (Sell Offer); the bar's Manage Orders; Your Bazaar Orders (each order read by
 * {@link BazaarOrderParser}); Order options (the item named Cancel Order); Confirm Instant Buy (as the cycle handles it).
 * A page the current action does not expect stops the session with "unknown menu" and the page described. Nothing is
 * clicked by slot number.
 *
 * <h2>Bookkeeping</h2>
 * A session first reads Manage Orders: every order already there is the player's and is never touched. An order that
 * appears later for a product the session just ordered is adopted as its own. Products the player already carries are
 * never flipped (Sell Instantly and a Sell Offer take every unit of the item, not just the bot's).
 *
 * <h2>Discipline</h2>
 * Same as the cycle: START_CLIENT_TICK, every click and command through {@link BazaarFlipFeature#click} /
 * {@link BazaarFlipFeature#sendCommand} (ActionGate, the menu speed slider, the session click cap), a page judged only
 * once its items settle, the amount sign filled in one tick, never any movement.
 */
final class BazaarFlipAutopilot {

    private static final Logger LOGGER = ModLog.get("killer560smod-bazaarflip");
    private static final String CHAT = "Bazaar Flip";

    /** How long one page may take to come up, or a click to show its effect, before the session stops. */
    private static final long STEP_TIMEOUT_MS = 6_000L;
    /** A whole action (several pages) may take this long. */
    private static final long ACTION_TIMEOUT_MS = 30_000L;
    /** A claim that changes nothing visible on the page is taken as done after this. */
    private static final long CLAIM_SOFT_MS = 2_000L;
    /** Bazaar prices are refreshed this often while running (the cycle's "every minute"). */
    private static final long PRODUCTS_REFRESH_MS = 60_000L;
    /** Manage Orders is re-read this often while the session has orders open, so fills and outbids are seen. */
    private static final long ORDERS_REFRESH_MS = 60_000L;
    /** With nothing to do, the next decision waits this long. */
    private static final long IDLE_WAIT_MS = 5_000L;
    /** An order that is claimed instead of opening its options is clicked again at most this often. */
    private static final int MAX_ORDER_CLICKS = 3;
    /** A wrong page is closed and the way back taken at most this often per action. */
    private static final int MAX_REOPENS = 2;

    /** "Price per unit: 2,082.3 coins" - the line BazaarOrderParser reads off Manage Orders. */
    private static final Pattern PRICE_PER_UNIT = Pattern.compile("^Price per unit: ([\\d,.]{1,20}) coins?$");
    /** "Order amount: 2,000x" / "Offer amount: 50x" - BazaarOrderParser's amount line. */
    private static final Pattern ORDER_AMOUNT = Pattern.compile("^(?:Order|Offer) amount: ([\\d,]{1,15})x$");

    /** What the current step is waiting for after its last click. */
    private enum Expect {
        NONE,
        /** Custom Amount was clicked: Hypixel's sign. */
        SIGN,
        /** A purchase or a returned offer: the product's units in the inventory to rise. */
        CARRIED_UP,
        /** A sale or a listing: the product's units in the inventory to fall. */
        CARRIED_DOWN,
        /** A confirm or cancel: the page to change or close. */
        PAGE_CHANGE,
        /** A claim: items arriving, the page changing, or (coins) a short wait. */
        CLAIM
    }

    /** One order the session placed: when, and how often it was relisted. */
    private record Mine(long placedAtMs, int relists) {
    }

    /** An order just placed, waiting to be seen in Manage Orders so it can be adopted. */
    private record Pending(boolean buy, String name, long atMs, int relists) {
    }

    // ---- the current step ----------------------------------------------------------------------------------------------
    private static FlipAction action;
    /** True while the current job is reading Manage Orders (no core action). */
    private static boolean refresh;
    private static String refreshWhy = "";
    private static long actionSinceMs;
    private static long commandSentAtMs;
    private static int reopens;
    private static Expect expect = Expect.NONE;
    private static long expectSinceMs;
    private static int expectBaseline;
    private static int expectContainerId = Integer.MIN_VALUE;
    private static String expectSignature = "";
    private static long signAmount;
    private static long signSeenAtMs;
    private static double quoteEach;
    private static double boughtCoins;
    private static int orderClicks;
    /** Set when Cancel Order was clicked (the next Order options page is not a reason to click again). */
    private static boolean cancelClicked;
    /** The second half of a relist, and the relist count the new order carries. */
    private static FlipAction followUp;
    private static int followUpRelists = -1;
    private static int currentRelists = -1;
    // NPC selling.
    private static int sellPendingSlot = -1;
    private static int sellPendingCount;
    private static long sellPendingAtMs;
    private static long npcSoldUnits;

    // ---- the session's books -------------------------------------------------------------------------------------------
    private static final Map<String, Long> holdings = new HashMap<>();
    private static final Map<String, Double> costBasis = new HashMap<>();
    private static final Set<String> avoid = new HashSet<>();
    private static final Set<String> foreignKeys = new HashSet<>();
    private static final Map<String, Mine> mine = new HashMap<>();
    private static final List<Pending> pending = new ArrayList<>();
    private static List<FlipOrder> orders = List.of();
    private static long ordersReadAtMs;
    private static boolean ordersEverRead;
    private static long productsAskedAtMs;
    private static long idleUntilMs;
    private static int placed;
    private static String lastWaitWhy = "";
    private static FlipAutopilot.Limits limits = FlipAutopilot.Limits.defaults();
    private static double reserve;

    private BazaarFlipAutopilot() {
    }

    // ---- start / stop ---------------------------------------------------------------------------------------------------

    /** Prepares a session. Null on success, else why it refused. */
    static String begin(Minecraft client, double purse) {
        reset();
        BazaarFlipConfig cfg = BazaarFlipConfig.getInstance();
        reserve = cfg.getAutopilotReserve() > 0 ? cfg.getAutopilotReserve() : purse * 0.10;
        if (purse <= reserve) {
            return String.format(Locale.US, "your purse (%,.0f) is not above the autopilot's reserve (%,.0f).", purse, reserve);
        }
        limits = FlipAutopilot.Limits.defaults().withReserve(reserve).withMaxOrdersPerSession(cfg.getAutopilotMaxOrders());
        // Every Skyblock item he already carries is his: never flipped, so no sale ever takes his own units.
        if (client.player != null) {
            for (int i = 0; i < 36; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                String id = com.killer560.hub.autoroutes.ItemIdentity.skyblockId(stack);
                if (id != null && !id.isBlank()) {
                    avoid.add(id);
                }
            }
        }
        BazaarApi.refreshAsync();
        productsAskedAtMs = System.currentTimeMillis();
        BazaarAdvisorConfig adv = BazaarAdvisorConfig.getInstance();
        ModChat.send(CHAT, ModChat.text("Autopilot started - "), ModChat.value(adv.summary()),
                ModChat.dim(String.format(Locale.US, ". Reserve %,.0f, at most %d new orders.", reserve,
                        cfg.getAutopilotMaxOrders())));
        LOGGER.info("[BazaarFlip] Autopilot start: purse {}, reserve {}, cap {}, answers {}, {} carried item type(s) left alone",
                Math.round(purse), Math.round(reserve), cfg.getAutopilotMaxOrders(), adv.summary(), avoid.size());
        return null;
    }

    static void reset() {
        action = null;
        refresh = false;
        followUp = null;
        followUpRelists = -1;
        currentRelists = -1;
        expect = Expect.NONE;
        sellPendingSlot = -1;
        holdings.clear();
        costBasis.clear();
        avoid.clear();
        foreignKeys.clear();
        mine.clear();
        pending.clear();
        orders = List.of();
        ordersReadAtMs = 0L;
        ordersEverRead = false;
        idleUntilMs = 0L;
        placed = 0;
        boughtCoins = 0;
        lastWaitWhy = "";
    }

    /** Colour-stripped chat while the autopilot runs (BazaarFlipFeature forwards it). Every pattern anchored. */
    static void onChatLine(String line) {
        Matcher m = BazaarFlipFeature.BOUGHT_LINE.matcher(line);
        if (m.matches() && action instanceof FlipAction.InstaBuy b && m.group(2).equalsIgnoreCase(b.name())) {
            try {
                boughtCoins += Double.parseDouble(m.group(3).replace(",", ""));
            } catch (NumberFormatException ignored) {
                // a malformed number is no cost information
            }
        }
    }

    // ---- the tick -------------------------------------------------------------------------------------------------------

    static void tick(Minecraft client, BazaarFlipConfig cfg) {
        long now = System.currentTimeMillis();
        if (now - productsAskedAtMs >= PRODUCTS_REFRESH_MS) {
            productsAskedAtMs = now;
            BazaarApi.refreshAsync();
        }
        Screen open = McCompat.screen(client);
        if (expect == Expect.SIGN) {
            tickSign(open, cfg, now);
            return;
        }
        if (expect != Expect.NONE && !checkExpect(client, open, now)) {
            return;
        }
        if (action == null && !refresh) {
            decideNext(client, now);
            return;
        }
        if (!BazaarFlipFeature.paced()) {
            return;
        }
        if (now - actionSinceMs > ACTION_TIMEOUT_MS) {
            fail("the " + jobName() + " never finished" + (open instanceof AbstractContainerScreen<?> c
                    ? " - stuck on " + BazaarFlipMenus.describe(c) : ""));
            return;
        }
        if (action instanceof FlipAction.SellToNpc s) {
            tickNpcSell(client, open, s, cfg, now);
            return;
        }
        boolean orderJob = refresh || action instanceof FlipAction.Collect || action instanceof FlipAction.Cancel
                || action instanceof FlipAction.Relist;
        if (!(open instanceof AbstractContainerScreen<?> screen)) {
            if (open != null) {
                fail("an unexpected screen opened: " + open.getClass().getSimpleName());
                return;
            }
            // No menu: open the Bazaar - the product's own search for a trade, the Bazaar's front for Manage Orders.
            if (commandSentAtMs > 0 && now - commandSentAtMs > STEP_TIMEOUT_MS) {
                fail("the Bazaar menu never opened");
                return;
            }
            if (commandSentAtMs == 0 && BazaarFlipFeature.sendCommand(orderJob ? "bz" : "bz " + productName(action))) {
                commandSentAtMs = now;
            }
            return;
        }
        if (!BazaarFlipFeature.settled(screen) || BazaarFlipFeature.stillOnClickedPage(screen)) {
            return;
        }
        commandSentAtMs = 0L;
        if (BazaarFlipFeature.CONFIRM_INSTANT_BUY.equals(BazaarFlipMenus.titleOf(screen))
                && action instanceof FlipAction.InstaBuy b) {
            confirmInstantBuy(client, screen, b, cfg);
            return;
        }
        BazaarPages.Page page = BazaarFlipMenus.page(screen);
        if (orderJob) {
            tickOrderPages(client, screen, page, cfg, now);
        } else {
            tickProductPages(client, screen, page, cfg, now);
        }
    }

    // ---- deciding -------------------------------------------------------------------------------------------------------

    private static void decideNext(Minecraft client, long now) {
        if (followUp != null) {
            currentRelists = followUpRelists;
            FlipAction next = followUp;
            followUp = null;
            followUpRelists = -1;
            begin(next, now);
            return;
        }
        currentRelists = -1;
        boolean haveOurs = orders.stream().anyMatch(FlipOrder::ours) || !pending.isEmpty();
        if (!ordersEverRead || (haveOurs && now - ordersReadAtMs > ORDERS_REFRESH_MS)) {
            beginRefresh(ordersEverRead ? "checking the session's orders" : "reading your orders before anything else", now);
            return;
        }
        if (now < idleUntilMs) {
            return;
        }
        List<BazaarProduct> products = BazaarApi.peekProducts();
        if (products == null || products.isEmpty()) {
            idleUntilMs = now + IDLE_WAIT_MS;
            return;
        }
        Double purse = BazaarFlipFeature.readPurse();
        if (purse == null) {
            fail("lost the Purse line off the scoreboard");
            return;
        }
        BazaarFlipConfig cfg = BazaarFlipConfig.getInstance();
        if (cfg.getTargetPurse() > 0 && purse >= cfg.getTargetPurse()) {
            BazaarFlipFeature.finishFromAutopilot(String.format(Locale.US, "target purse reached (%,.0f of %,d)", purse,
                    cfg.getTargetPurse()));
            return;
        }
        FlipPricing.Answers answers = BazaarAdvisorFeature.answers(BazaarAdvisorConfig.getInstance());
        FlipPricing.Lookups look = BazaarAdvisorFeature.lookups(client.player == null ? null : client.player.inventoryMenu);
        FlipAction next = FlipAutopilot.decide(new FlipAutopilot.Situation(products, purse, holdings, costBasis, orders,
                answers, look, limits, placed, avoid));
        if (next instanceof FlipAction.Wait w) {
            if (!w.why().equals(lastWaitWhy)) {
                lastWaitWhy = w.why();
                ModChat.log(CHAT, ModChat.dim("Waiting: " + w.why()));
            }
            idleUntilMs = now + IDLE_WAIT_MS;
            return;
        }
        if (next instanceof FlipAction.Stop s) {
            BazaarFlipFeature.finishFromAutopilot(s.why());
            return;
        }
        lastWaitWhy = "";
        if (isSale(next) && client.player != null) {
            String id = productId(next);
            long have = carried(client, id);
            long held = holdings.getOrDefault(id, 0L);
            if (have > held) {
                // Sell Instantly, a Sell Offer and the NPC take every unit: never his own. Leave the stock with him.
                avoid.add(id);
                holdings.remove(id);
                ModChat.send(CHAT, ModChat.bad("Not selling " + productName(next) + ": "), ModChat.text("you carry " + have
                        + ", more than the " + held + " the autopilot bought. It is yours to sell now."));
                return;
            }
        }
        begin(next, now);
    }

    private static boolean isSale(FlipAction a) {
        return a instanceof FlipAction.InstaSell || a instanceof FlipAction.PlaceSellOffer || a instanceof FlipAction.SellToNpc;
    }

    private static void begin(FlipAction next, long now) {
        action = next;
        refresh = false;
        startJob(now);
        LOGGER.info("[BazaarFlip] Autopilot: {} ({})", next, next.why());
        ModChat.log(CHAT, ModChat.text(describe(next)), ModChat.dim(" - " + next.why()));
    }

    private static void beginRefresh(String why, long now) {
        action = null;
        refresh = true;
        refreshWhy = why;
        startJob(now);
        LOGGER.info("[BazaarFlip] Autopilot: Manage Orders ({})", why);
    }

    private static void startJob(long now) {
        actionSinceMs = now;
        commandSentAtMs = 0L;
        reopens = 0;
        expect = Expect.NONE;
        orderClicks = 0;
        cancelClicked = false;
        boughtCoins = 0;
        sellPendingSlot = -1;
        npcSoldUnits = 0;
    }

    private static void done(String what) {
        LOGGER.info("[BazaarFlip] Autopilot done: {}", what);
        action = null;
        refresh = false;
        expect = Expect.NONE;
        commandSentAtMs = 0L;
    }

    /** The live page refused what the snapshot promised: leave the product alone this session, carry on. */
    private static void abandon(Minecraft client, String why) {
        String id = action == null ? null : productId(action);
        if (id != null) {
            avoid.add(id);
        }
        ModChat.log(CHAT, ModChat.text("Skipping " + (action == null ? "it" : productName(action)) + ": " + why));
        LOGGER.info("[BazaarFlip] Autopilot skipped {}: {}", action, why);
        closeMenu(client);
        done("skipped");
    }

    private static void fail(String why) {
        LOGGER.info("[BazaarFlip] Autopilot stopping: {} (job {})", why, jobName());
        BazaarFlipFeature.stopFromAutopilot(why);
    }

    private static void closeMenu(Minecraft client) {
        if (client.player != null && McCompat.screen(client) != null) {
            client.player.closeContainer();
        }
        BazaarFlipFeature.forgetPage();
    }

    /** A wrong page for this step: close it and take the way back, a bounded number of times. */
    private static void reopen(Minecraft client, String why) {
        if (++reopens > MAX_REOPENS) {
            fail(why);
            return;
        }
        LOGGER.info("[BazaarFlip] Autopilot reopening: {}", why);
        closeMenu(client);
        commandSentAtMs = 0L;
    }

    // ---- expectations ---------------------------------------------------------------------------------------------------

    private static void expect(Expect e, int baseline, AbstractContainerScreen<?> screen) {
        expect = e;
        expectSinceMs = System.currentTimeMillis();
        expectBaseline = baseline;
        expectContainerId = screen == null ? Integer.MIN_VALUE : screen.getMenu().containerId;
        expectSignature = screen == null ? "" : BazaarFlipMenus.signature(screen.getMenu());
    }

    /** True once what the last click should cause has happened (the step goes on), false while still waiting. */
    private static boolean checkExpect(Minecraft client, Screen open, long now) {
        String id = action == null ? null : productId(action);
        boolean timedOut = now - expectSinceMs > STEP_TIMEOUT_MS;
        switch (expect) {
            case CARRIED_UP -> {
                int have = carried(client, id);
                if (have > expectBaseline) {
                    expect = Expect.NONE;
                    onCarriedUp(client, have - expectBaseline);
                    return false;
                }
                if (action instanceof FlipAction.InstaBuy && open instanceof AbstractContainerScreen<?> c
                        && BazaarFlipFeature.CONFIRM_INSTANT_BUY.equals(BazaarFlipMenus.titleOf(c))) {
                    expect = Expect.NONE; // Hypixel asked to confirm: the dispatch handles that page
                    return true;
                }
                if (timedOut) {
                    if (action instanceof FlipAction.Cancel || action instanceof FlipAction.Relist) {
                        // A cancelled sell offer's items can go to the stash when the inventory is full.
                        expect = Expect.NONE;
                        onCancelled(client, 0);
                        return false;
                    }
                    fail("nothing arrived in your inventory after the " + jobName());
                }
                return false;
            }
            case CARRIED_DOWN -> {
                int have = carried(client, id);
                if (have < expectBaseline) {
                    expect = Expect.NONE;
                    onCarriedDown(client, expectBaseline - have);
                    return false;
                }
                if (open instanceof AbstractContainerScreen<?> c && c.getMenu().containerId != expectContainerId
                        && BazaarFlipFeature.settled(c)) {
                    BazaarPages.Page page = BazaarFlipMenus.page(c);
                    if (page.kind() != BazaarPages.Kind.PRODUCT && page.kind() != BazaarPages.Kind.SEARCH) {
                        fail("unknown menu after the " + jobName() + ": " + BazaarFlipMenus.describe(c));
                        return false;
                    }
                }
                if (timedOut) {
                    fail("the " + jobName() + " did not take the items out of your inventory");
                }
                return false;
            }
            case PAGE_CHANGE, CLAIM -> {
                boolean changed = !(open instanceof AbstractContainerScreen<?> c)
                        || c.getMenu().containerId != expectContainerId
                        || !BazaarFlipMenus.signature(c.getMenu()).equals(expectSignature);
                int have = id == null ? 0 : carried(client, id);
                boolean arrived = expect == Expect.CLAIM && id != null && have > expectBaseline;
                boolean soft = expect == Expect.CLAIM && now - expectSinceMs > CLAIM_SOFT_MS;
                if (changed || arrived || soft) {
                    Expect was = expect;
                    expect = Expect.NONE;
                    onPageChanged(client, was, id == null ? 0 : Math.max(0, have - expectBaseline));
                    return false;
                }
                if (timedOut) {
                    fail("the page never changed after the " + jobName());
                }
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    private static void onCarriedUp(Minecraft client, int delta) {
        if (action instanceof FlipAction.InstaBuy b) {
            double each = boughtCoins > 0 ? boughtCoins / delta : quoteEach / (1 + FlipAutopilot.INSTANT_BUY_QUOTE_MARGIN);
            addHolding(b.productId(), delta, each);
            placed++;
            closeMenu(client);
            done("bought " + delta + " " + b.name());
        } else if (action instanceof FlipAction.Cancel || action instanceof FlipAction.Relist) {
            onCancelled(client, delta);
        }
    }

    private static void onCarriedDown(Minecraft client, int delta) {
        if (action instanceof FlipAction.InstaSell s) {
            takeHolding(s.productId(), delta);
            logSale(s.productId(), s.name(), delta, s.expectedEach());
            done("insta-sold " + delta + " " + s.name());
        } else if (action instanceof FlipAction.PlaceSellOffer s) {
            takeHolding(s.productId(), delta);
            pending.add(new Pending(false, s.name(), System.currentTimeMillis(), Math.max(0, currentRelists)));
            if (currentRelists < 0) {
                placed++;
            }
            ordersReadAtMs = 0L;
            closeMenu(client);
            done("listed " + delta + " " + s.name() + " at " + s.price());
        }
    }

    private static void onPageChanged(Minecraft client, Expect was, int arrived) {
        if (action instanceof FlipAction.PlaceBuyOrder b) {
            pending.add(new Pending(true, b.name(), System.currentTimeMillis(), Math.max(0, currentRelists)));
            if (currentRelists < 0) {
                placed++;
            }
            ordersReadAtMs = 0L; // read Manage Orders before the next decision: it must see this order
            closeMenu(client);
            done("buy order for " + b.amount() + " " + b.name());
        } else if (action instanceof FlipAction.Collect c) {
            if (arrived > 0 && c.order().buy()) {
                addHolding(c.order().productId(), arrived, c.order().price());
            }
            if (!c.order().buy()) {
                long units = c.order().claimableCoins() > 0 && c.order().price() > 0
                        ? Math.round(c.order().claimableCoins() / (c.order().price() * (1 - FlipPricing.SELL_TAX))) : 0;
                if (units > 0) {
                    logSale(c.order().productId(), c.order().name(), units, c.order().price() * (1 - FlipPricing.SELL_TAX));
                }
            }
            ordersReadAtMs = 0L; // re-read the orders before the next decision
            done("collected " + c.order().name());
        } else if ((action instanceof FlipAction.Cancel || action instanceof FlipAction.Relist) && cancelClicked) {
            FlipOrder o = cancelled();
            if (!o.buy()) {
                // A sell offer's items come back: wait for them (CARRIED_UP's timeout covers the stash case).
                expect(Expect.CARRIED_UP, carried(client, o.productId()), null);
                return;
            }
            onCancelled(client, arrived);
        } else if (action instanceof FlipAction.Cancel || action instanceof FlipAction.Relist) {
            // Clicking the order opened its options (the dispatch clicks Cancel Order next) or claimed what had filled
            // (the dispatch clicks it again): claimed items join the books at the order's price.
            FlipOrder o = cancelled();
            if (arrived > 0 && o.buy()) {
                addHolding(o.productId(), arrived, o.price());
            }
        }
    }

    /** The order of the current cancel or relist. */
    private static FlipOrder cancelled() {
        return action instanceof FlipAction.Cancel c ? c.order() : ((FlipAction.Relist) action).order();
    }

    /** A cancel went through: the books, and for a relist the new order queued. {@code delta} units came back. */
    private static void onCancelled(Minecraft client, int delta) {
        FlipOrder o = cancelled();
        mine.remove(o.key());
        if (delta > 0) {
            // A buy order's filled part or a sell offer's unsold part.
            addHolding(o.productId(), delta, o.buy() ? o.price() : costBasis.getOrDefault(o.productId(), 0.0));
        }
        if (action instanceof FlipAction.Relist r) {
            if (o.buy()) {
                followUp = new FlipAction.PlaceBuyOrder(o.productId(), o.name(), r.newPrice(), o.unfilled(), "relist of " + o.key());
            } else {
                followUp = new FlipAction.PlaceSellOffer(o.productId(), o.name(), r.newPrice(),
                        holdings.getOrDefault(o.productId(), 0L), "relist of " + o.key());
            }
            followUpRelists = o.relists() + 1;
        }
        ordersReadAtMs = 0L;
        closeMenu(client);
        done((action instanceof FlipAction.Relist ? "cancelled for a relist: " : "cancelled: ") + o.key());
    }

    // ---- product pages: Insta Buy, Insta Sell, buy orders, sell offers --------------------------------------------------

    private static void tickProductPages(Minecraft client, AbstractContainerScreen<?> screen, BazaarPages.Page page,
                                         BazaarFlipConfig cfg, long now) {
        String name = productName(action);
        String id = productId(action);
        switch (page.kind()) {
            case SEARCH, GROUP, CATEGORY -> {
                BazaarPages.Item product = null;
                for (BazaarPages.Item it : page.content()) {
                    if (it.name().equalsIgnoreCase(name)) {
                        product = it;
                        break;
                    }
                }
                if (product == null) {
                    reopen(client, "\"" + name + "\" is not on the Bazaar page " + BazaarFlipMenus.describe(screen));
                    return;
                }
                BazaarFlipFeature.click(client, screen, product.slot(), ContainerInput.PICKUP, cfg, true);
            }
            case PRODUCT -> {
                if (BazaarFlipMenus.item(page, name) == null) {
                    reopen(client, "the product page is not " + name + ": " + BazaarFlipMenus.describe(screen));
                    return;
                }
                String button = action instanceof FlipAction.InstaBuy ? "Buy Instantly"
                        : action instanceof FlipAction.InstaSell ? "Sell Instantly"
                        : action instanceof FlipAction.PlaceBuyOrder ? "Create Buy Order" : "Create Sell Offer";
                BazaarPages.Item it = BazaarFlipMenus.item(page, button);
                if (it == null) {
                    fail("unknown menu: no " + button + " on " + BazaarFlipMenus.describe(screen));
                    return;
                }
                if (action instanceof FlipAction.InstaSell) {
                    int have = carried(client, id);
                    if (BazaarFlipFeature.click(client, screen, it.slot(), ContainerInput.PICKUP, cfg, false)) {
                        expect(Expect.CARRIED_DOWN, have, screen);
                    }
                    return;
                }
                BazaarFlipFeature.click(client, screen, it.slot(), ContainerInput.PICKUP, cfg, true);
            }
            case INSTANT_BUY -> {
                if (!(action instanceof FlipAction.InstaBuy b) || !BazaarPages.isInstantBuyTitleFor(
                        BazaarFlipMenus.titleOf(screen), name)) {
                    fail("unknown menu for the " + jobName() + ": " + BazaarFlipMenus.describe(screen));
                    return;
                }
                instantBuyStep(client, screen, page, b, cfg);
            }
            case ORDER_AMOUNT -> {
                if (!(action instanceof FlipAction.PlaceBuyOrder b)) {
                    fail("unknown menu for the " + jobName() + ": " + BazaarFlipMenus.describe(screen));
                    return;
                }
                for (String preset : new String[]{"Get me a stack!", "A big stack!", "One thousand!"}) {
                    BazaarPages.Item it = BazaarFlipMenus.item(page, preset);
                    if (it != null && BazaarFlipMenus.loreAmount(it.lore()) == b.amount()) {
                        BazaarFlipFeature.click(client, screen, it.slot(), ContainerInput.PICKUP, cfg, true);
                        return;
                    }
                }
                BazaarPages.Item custom = BazaarFlipMenus.item(page, "Custom Amount");
                if (custom == null) {
                    fail("unknown menu: no Custom Amount on " + BazaarFlipMenus.describe(screen));
                    return;
                }
                if (BazaarFlipFeature.click(client, screen, custom.slot(), ContainerInput.PICKUP, cfg, false)) {
                    signAmount = b.amount();
                    signSeenAtMs = 0L;
                    expect(Expect.SIGN, 0, screen);
                }
            }
            case ORDER_PRICE -> {
                if (!(action instanceof FlipAction.PlaceBuyOrder b)) {
                    fail("unknown menu for the " + jobName() + ": " + BazaarFlipMenus.describe(screen));
                    return;
                }
                BazaarPages.Item it = BazaarFlipMenus.item(page, "Top Order +0.1");
                if (it == null) {
                    fail("unknown menu: no Top Order +0.1 on " + BazaarFlipMenus.describe(screen));
                    return;
                }
                double live = lorePerUnit(it.lore());
                double sale = saleEach(client, b.productId());
                if (live > 0 && sale > 0 && live >= sale) {
                    abandon(client, String.format(Locale.US, "the live bid (%,.1f) no longer sells at a profit (%,.1f)", live, sale));
                    return;
                }
                BazaarFlipFeature.click(client, screen, it.slot(), ContainerInput.PICKUP, cfg, true);
            }
            case CONFIRM_BUY -> {
                if (!(action instanceof FlipAction.PlaceBuyOrder b)) {
                    fail("unknown menu for the " + jobName() + ": " + BazaarFlipMenus.describe(screen));
                    return;
                }
                BazaarPages.Item it = BazaarFlipMenus.item(page, "Buy Order");
                if (it == null) {
                    fail("unknown menu: no Buy Order on " + BazaarFlipMenus.describe(screen));
                    return;
                }
                long amount = loreOrderAmount(it.lore());
                if (amount > 0 && amount != b.amount()) {
                    fail("Confirm Buy Order says " + amount + ", not the " + b.amount() + " ordered - not confirming");
                    return;
                }
                double live = lorePerUnit(it.lore());
                double sale = saleEach(client, b.productId());
                if (live > 0 && sale > 0 && live >= sale) {
                    abandon(client, String.format(Locale.US, "the order's price (%,.1f) no longer sells at a profit (%,.1f)", live, sale));
                    return;
                }
                if (BazaarFlipFeature.click(client, screen, it.slot(), ContainerInput.PICKUP, cfg, false)) {
                    expect(Expect.PAGE_CHANGE, 0, screen);
                }
            }
            case OFFER_PRICE -> {
                if (!(action instanceof FlipAction.PlaceSellOffer)) {
                    fail("unknown menu for the " + jobName() + ": " + BazaarFlipMenus.describe(screen));
                    return;
                }
                BazaarPages.Item it = BazaarFlipMenus.item(page, "Best Offer -0.1");
                if (it == null) {
                    fail("unknown menu: no Best Offer -0.1 on " + BazaarFlipMenus.describe(screen));
                    return;
                }
                BazaarFlipFeature.click(client, screen, it.slot(), ContainerInput.PICKUP, cfg, true);
            }
            case CONFIRM_SELL -> {
                if (!(action instanceof FlipAction.PlaceSellOffer s)) {
                    fail("unknown menu for the " + jobName() + ": " + BazaarFlipMenus.describe(screen));
                    return;
                }
                BazaarPages.Item it = BazaarFlipMenus.item(page, "Sell Offer");
                if (it == null) {
                    fail("unknown menu: no Sell Offer on " + BazaarFlipMenus.describe(screen));
                    return;
                }
                int have = carried(client, s.productId());
                if (BazaarFlipFeature.click(client, screen, it.slot(), ContainerInput.PICKUP, cfg, false)) {
                    expect(Expect.CARRIED_DOWN, have, screen);
                }
            }
            default -> fail("unknown menu for the " + jobName() + ": " + BazaarFlipMenus.describe(screen));
        }
    }

    /**
     * The Instant Buy page: the amount the decision sized, capped by the room and Custom Amount's "Buy up to", bought
     * through a preset stating exactly that amount, or Custom Amount. The presets' quotes bound the price (the book is
     * eaten cheapest first): if that bound, less Hypixel's 4% quote margin, no longer beats the sale, the product is
     * skipped; the quote must fit the purse above the reserve.
     */
    private static void instantBuyStep(Minecraft client, AbstractContainerScreen<?> screen, BazaarPages.Page page,
                                       FlipAction.InstaBuy b, BazaarFlipConfig cfg) {
        var menu = screen.getMenu();
        int stack = Math.max(1, BazaarAdvisorFeature.lookups(menu).stackSize(b.productId()));
        long want = Math.min(b.amount(), BazaarFlipMenus.roomFor(menu, b.productId(), b.name(), stack));
        BazaarPages.Item custom = BazaarFlipMenus.item(page, "Custom Amount");
        long upTo = custom == null ? -1 : BazaarFlipFeature.loreBuyUpTo(custom.lore());
        if (upTo > 0) {
            want = Math.min(want, upTo);
        }
        if (want < 1) {
            abandon(client, "no room in your inventory");
            return;
        }
        double sale = saleEach(client, b.productId());
        Double purse = BazaarFlipFeature.readPurse();
        double spendable = purse == null ? 0 : purse - reserve;
        BazaarPages.Item exact = null;
        BazaarPages.Item bound = null;
        long boundAmount = Long.MAX_VALUE;
        double boundPrice = -1;
        for (String preset : new String[]{"Buy only one!", "Buy a stack!", "Fill my inventory!"}) {
            BazaarPages.Item it = BazaarFlipMenus.item(page, preset);
            if (it == null) {
                continue;
            }
            long amount = BazaarFlipMenus.loreAmount(it.lore());
            double price = BazaarFlipMenus.lorePrice(it.lore());
            if (amount == want) {
                exact = it;
            }
            if (amount >= want && price > 0 && amount < boundAmount) {
                bound = it;
                boundAmount = amount;
                boundPrice = price;
            }
        }
        if (bound != null) {
            double quote = boundPrice / boundAmount;
            if (sale > 0 && quote / (1 + FlipAutopilot.INSTANT_BUY_QUOTE_MARGIN) >= sale) {
                abandon(client, String.format(Locale.US, "the live price (up to %,.1f each) no longer beats the sale (%,.1f)",
                        quote / (1 + FlipAutopilot.INSTANT_BUY_QUOTE_MARGIN), sale));
                return;
            }
            quoteEach = quote;
            if (want * quote > spendable) {
                want = (long) Math.floor(spendable / quote);
                exact = null;
                if (want < 1) {
                    abandon(client, "the purse above the reserve does not cover even one");
                    return;
                }
            }
        } else {
            quoteEach = b.expectedEach() * (1 + FlipAutopilot.INSTANT_BUY_QUOTE_MARGIN);
        }
        int have = carried(client, b.productId());
        if (exact != null) {
            if (BazaarFlipFeature.click(client, screen, exact.slot(), ContainerInput.PICKUP, cfg, false)) {
                signAmount = want; // a Confirm Instant Buy page, if one follows, must state this amount
                unitsBefore = have;
                expect(Expect.CARRIED_UP, have, screen);
            }
            return;
        }
        if (custom == null) {
            fail("unknown menu: no Custom Amount on " + BazaarFlipMenus.describe(screen));
            return;
        }
        if (BazaarFlipFeature.click(client, screen, custom.slot(), ContainerInput.PICKUP, cfg, false)) {
            signAmount = want;
            signSeenAtMs = 0L;
            unitsBefore = have;
            expect(Expect.SIGN, have, screen);
        }
    }

    /** The carried count when Custom Amount was clicked, for the purchase the sign starts. */
    private static int unitsBefore;

    /** Confirm Instant Buy: the one item stating exactly the typed amount, at a price that still beats the sale. */
    private static void confirmInstantBuy(Minecraft client, AbstractContainerScreen<?> screen, FlipAction.InstaBuy b,
                                          BazaarFlipConfig cfg) {
        Slot match = null;
        int matches = 0;
        double price = -1;
        for (Slot slot : BazaarFlipMenus.menuSlots(screen.getMenu())) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            List<String> lore = com.killer560.hub.croesus.DungeonChestValuer.cleanLore(stack);
            if (BazaarFlipMenus.loreAmount(lore) == signAmount && BazaarFlipMenus.lorePrice(lore) > 0) {
                match = slot;
                price = BazaarFlipMenus.lorePrice(lore);
                matches++;
            }
        }
        if (matches != 1) {
            fail("couldn't find one confirm button for " + signAmount + " on " + BazaarFlipMenus.describe(screen));
            return;
        }
        double sale = saleEach(client, b.productId());
        double each = price / signAmount / (1 + FlipAutopilot.INSTANT_BUY_QUOTE_MARGIN);
        if (sale > 0 && each >= sale) {
            abandon(client, String.format(Locale.US, "the confirm price (%,.1f each) no longer beats the sale (%,.1f)", each, sale));
            return;
        }
        if (BazaarFlipFeature.click(client, screen, match.index, ContainerInput.PICKUP, cfg, true)) {
            expect(Expect.CARRIED_UP, unitsBefore, null);
        }
    }

    // ---- the amount sign ------------------------------------------------------------------------------------------------

    /** Custom Amount was clicked: type {@link #signAmount} on line 1 and press Done, in one tick (as the cycle does). */
    private static void tickSign(Screen open, BazaarFlipConfig cfg, long now) {
        if (!(open instanceof AbstractSignEditScreen sign)) {
            if (open instanceof AbstractContainerScreen<?> c && c.getMenu().containerId != expectContainerId) {
                fail("Custom Amount opened " + BazaarFlipMenus.describe(c) + " instead of the amount sign");
            } else if (open != null && !(open instanceof AbstractContainerScreen<?>)) {
                fail("Custom Amount opened " + open.getClass().getSimpleName() + " instead of the amount sign");
            } else if (now - expectSinceMs > STEP_TIMEOUT_MS) {
                fail("the Custom Amount sign never opened");
            }
            return;
        }
        if (signSeenAtMs == 0L) {
            signSeenAtMs = now;
            BazaarFlipFeature.delayNext(BazaarFlipFeature.randomDelay(cfg));
            return;
        }
        if (!BazaarFlipFeature.paced()) {
            return;
        }
        String[] lines = ((SignEditScreenAccessor) sign).killer560smod$getMessages();
        if (!BazaarFlipFeature.isAmountSign(lines)) {
            fail("the sign that opened is not Hypixel's amount prompt: " + Arrays.toString(lines));
            return;
        }
        if (!lines[0].isEmpty()) {
            fail("the amount sign's first line already holds \"" + lines[0] + "\"");
            return;
        }
        Button doneButton = null;
        for (GuiEventListener child : sign.children()) {
            if (child instanceof Button b && b.getMessage().getString().equals(CommonComponents.GUI_DONE.getString())) {
                doneButton = b;
            }
        }
        if (doneButton == null) {
            fail("the amount sign has no Done button");
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.BAZAAR_FLIP_CMD)) {
            return; // retried next tick
        }
        String text = Long.toString(signAmount);
        for (int i = 0; i < text.length(); i++) {
            sign.charTyped(new CharacterEvent(text.charAt(i)));
        }
        String typed = ((SignEditScreenAccessor) sign).killer560smod$getMessages()[0];
        if (!text.equals(typed)) {
            for (int i = 0; i < typed.length(); i++) {
                sign.keyPressed(new KeyEvent(InputConstants.KEY_BACKSPACE, 0, 0));
            }
            fail("typing " + text + " into the amount sign left \"" + typed + "\" - nothing was sent");
            return;
        }
        doneButton.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        LOGGER.info("[BazaarFlip] Autopilot typed {} into the amount sign", text);
        BazaarFlipFeature.delayNext(BazaarFlipFeature.randomDelay(cfg));
        if (action instanceof FlipAction.InstaBuy) {
            expect(Expect.CARRIED_UP, unitsBefore, null);
        } else {
            expect = Expect.NONE; // a buy order's amount: the price page comes next
        }
    }

    // ---- Manage Orders: read, collect, cancel, relist -------------------------------------------------------------------

    private static void tickOrderPages(Minecraft client, AbstractContainerScreen<?> screen, BazaarPages.Page page,
                                       BazaarFlipConfig cfg, long now) {
        if (page.kind() == BazaarPages.Kind.ORDERS) {
            Map<String, Integer> slots = readOrders(screen);
            if (refresh) {
                done("read " + orders.size() + " order(s) (" + refreshWhy + ")");
                return;
            }
            FlipOrder target = action instanceof FlipAction.Collect c ? c.order()
                    : action instanceof FlipAction.Cancel c ? c.order() : ((FlipAction.Relist) action).order();
            Integer slot = slots.get(target.key());
            if (slot == null) {
                // Filled, claimed or cancelled since the decision: nothing left to do for it.
                done("the order " + target.key() + " is no longer listed");
                return;
            }
            if (++orderClicks > MAX_ORDER_CLICKS) {
                fail("clicking the order " + target.key() + " " + MAX_ORDER_CLICKS + " times did not open its options");
                return;
            }
            int have = target.productId() == null ? 0 : carried(client, target.productId());
            if (BazaarFlipFeature.click(client, screen, slot, ContainerInput.PICKUP, cfg, false)) {
                if (action instanceof FlipAction.Collect) {
                    expect(Expect.CLAIM, have, screen);
                } else {
                    unitsBefore = have; // the units before the cancel, for what comes back
                    expect(Expect.PAGE_CHANGE, have, screen);
                }
            }
            return;
        }
        if (page.kind() == BazaarPages.Kind.ORDER_OPTIONS) {
            if ((action instanceof FlipAction.Cancel || action instanceof FlipAction.Relist) && !cancelClicked) {
                BazaarPages.Item cancel = null;
                for (BazaarPages.Item it : page.content()) {
                    if (it.name().contains("Cancel Order")) {
                        cancel = it;
                    }
                }
                if (cancel == null) {
                    fail("unknown menu: no Cancel Order on " + BazaarFlipMenus.describe(screen));
                    return;
                }
                if (BazaarFlipFeature.click(client, screen, cancel.slot(), ContainerInput.PICKUP, cfg, false)) {
                    cancelClicked = true;
                    expect(Expect.PAGE_CHANGE, unitsBefore, screen);
                }
                return;
            }
            // Not a page this step clicks on (a collect found nothing to claim): close it and go round again.
            reopen(client, "Order options opened for the " + jobName());
            return;
        }
        BazaarPages.Item manage = page.kind() == BazaarPages.Kind.UNKNOWN ? null : BazaarFlipMenus.item(page, "Manage Orders");
        if (manage == null) {
            if (page.kind() == BazaarPages.Kind.UNKNOWN) {
                fail("unknown menu: " + BazaarFlipMenus.describe(screen));
                return;
            }
            // /bz can reopen the page he was last on (a product page has no Manage Orders): Go Back toward the front.
            BazaarPages.Item back = BazaarFlipMenus.item(page, "Go Back");
            if (back != null && ++orderClicks <= MAX_ORDER_CLICKS) {
                BazaarFlipFeature.click(client, screen, back.slot(), ContainerInput.PICKUP, cfg, true);
                return;
            }
            reopen(client, "no Manage Orders on " + BazaarFlipMenus.describe(screen));
            return;
        }
        BazaarFlipFeature.click(client, screen, manage.slot(), ContainerInput.PICKUP, cfg, true);
    }

    /**
     * Reads Your Bazaar Orders into {@link #orders} (each item through {@link BazaarOrderParser}, as
     * {@code BazaarOrders.readContainer} does) and adopts the session's new orders.
     *
     * @return order key to the slot that shows it
     */
    private static Map<String, Integer> readOrders(AbstractContainerScreen<?> screen) {
        Map<String, Integer> slots = new HashMap<>();
        List<FlipOrder> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long now = System.currentTimeMillis();
        for (Slot slot : BazaarFlipMenus.menuSlots(screen.getMenu())) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            BazaarOrderParser.Order o = BazaarOrderParser.parse(stack.getHoverName().getString(),
                    com.killer560.hub.croesus.DungeonChestValuer.cleanLore(stack));
            if (o == null) {
                continue;
            }
            String key = o.key();
            seen.add(key);
            slots.putIfAbsent(key, slot.index);
            boolean buy = o.type() == BazaarOrderParser.Type.BUY;
            Mine m = mine.get(key);
            if (m == null && !foreignKeys.contains(key)) {
                Pending p = ordersEverRead ? takePending(buy, o.productName()) : null;
                if (p != null) {
                    m = new Mine(p.atMs(), p.relists());
                    mine.put(key, m);
                    LOGGER.info("[BazaarFlip] Autopilot adopted its order {}", key);
                } else {
                    foreignKeys.add(key); // his own (there before the session, or placed by hand)
                }
            }
            out.add(new FlipOrder(buy, o.productId(), o.productName(), o.amount(), o.filled(), o.pricePerUnit(),
                    o.claimableItems(), o.claimableCoins(), o.claimable(), o.expired(),
                    m == null ? -1 : now - m.placedAtMs(), m == null ? 0 : m.relists(), m != null && o.productId() != null));
        }
        mine.keySet().removeIf(k -> !seen.contains(k));
        orders = List.copyOf(out);
        ordersReadAtMs = now;
        ordersEverRead = true;
        // A placement that never showed up within two refreshes is forgotten (it failed, or filled and was claimed).
        pending.removeIf(p -> now - p.atMs() > ORDERS_REFRESH_MS * 3);
        return slots;
    }

    private static Pending takePending(boolean buy, String name) {
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.buy() == buy && p.name().equalsIgnoreCase(name)) {
                it.remove();
                return p;
            }
        }
        return null;
    }

    // ---- /trades --------------------------------------------------------------------------------------------------------

    private static void tickNpcSell(Minecraft client, Screen open, FlipAction.SellToNpc s, BazaarFlipConfig cfg, long now) {
        if (open instanceof AbstractContainerScreen<?> screen && !BazaarFlipFeature.TRADES_TITLE.matcher(
                BazaarFlipMenus.titleOf(screen)).matches()) {
            if (commandSentAtMs == 0) {
                closeMenu(client); // a Bazaar page left open: /trades replaces nothing, so close it first
                return;
            }
            if (now - commandSentAtMs > STEP_TIMEOUT_MS) {
                fail("/trades opened \"" + BazaarFlipMenus.titleOf(screen) + "\", not the Trades menu");
            }
            return;
        }
        if (!(open instanceof AbstractContainerScreen<?> screen)) {
            if (open != null) {
                fail("an unexpected screen opened: " + open.getClass().getSimpleName());
                return;
            }
            if (commandSentAtMs > 0) {
                if (now - commandSentAtMs > STEP_TIMEOUT_MS) {
                    fail("the /trades NPC menu never opened");
                }
                return;
            }
            if (BazaarFlipFeature.sendCommand("trades")) {
                commandSentAtMs = now;
            }
            return;
        }
        var menu = screen.getMenu();
        if (sellPendingSlot >= 0) {
            ItemStack still = sellPendingSlot < menu.slots.size() ? menu.slots.get(sellPendingSlot).getItem() : ItemStack.EMPTY;
            if (!BazaarFlipMenus.isProduct(still, s.productId(), s.name())) {
                npcSoldUnits += sellPendingCount;
                sellPendingSlot = -1;
            } else if (now - sellPendingAtMs > 4_000L) {
                // The NPC stopped taking it (its daily limit, or no Booster Cookie): keep what is left.
                finishNpcSell(client, s, "the NPC stopped taking " + s.name());
                return;
            } else {
                return;
            }
        }
        if (npcSoldUnits >= s.amount() || BazaarFlipNpcLimit.remaining() <= 0) {
            finishNpcSell(client, s, "sold " + npcSoldUnits);
            return;
        }
        for (Slot slot : BazaarFlipMenus.playerSlots(menu)) {
            if (!BazaarFlipMenus.isProduct(slot.getItem(), s.productId(), s.name())) {
                continue;
            }
            int count = slot.getItem().getCount();
            if (BazaarFlipFeature.click(client, screen, slot.index, ContainerInput.QUICK_MOVE, cfg, false)) {
                sellPendingSlot = slot.index;
                sellPendingCount = count;
                sellPendingAtMs = now;
                BazaarFlipFeature.delayNext(BazaarFlipFeature.randomSellDelay(cfg));
            }
            return;
        }
        finishNpcSell(client, s, "sold every unit");
    }

    private static void finishNpcSell(Minecraft client, FlipAction.SellToNpc s, String why) {
        if (npcSoldUnits > 0) {
            takeHolding(s.productId(), npcSoldUnits);
            logSale(s.productId(), s.name(), npcSoldUnits, s.npcEach());
        }
        closeMenu(client);
        done("NPC sale of " + s.name() + ": " + why);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------------

    private static void addHolding(String id, long n, double each) {
        if (id == null || n <= 0) {
            return;
        }
        long had = holdings.getOrDefault(id, 0L);
        double old = costBasis.getOrDefault(id, each);
        holdings.put(id, had + n);
        costBasis.put(id, (had * old + n * each) / (had + n));
    }

    private static void takeHolding(String id, long n) {
        long left = holdings.getOrDefault(id, 0L) - n;
        if (left > 0) {
            holdings.put(id, left);
        } else {
            holdings.remove(id);
        }
    }

    /** One sale to the profit tracker: the units at their cost basis against what they brought (estimated from the
     *  price the sale was made at - Hypixel's Bazaar sale lines are not in the repo). */
    private static void logSale(String id, String name, long units, double receivedEach) {
        double cost = costBasis.getOrDefault(id, 0.0);
        BazaarFlipProfitLog.record(id, name, (int) units, (int) units, units * cost, units * receivedEach, 0, Double.NaN);
    }

    /** Units of the product in his own 36 slots, whatever screen is open. */
    private static int carried(Minecraft client, String id) {
        if (client.player == null || id == null) {
            return 0;
        }
        String name = nameOf(id);
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (BazaarFlipMenus.isProduct(stack, id, name)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static String nameOf(String id) {
        for (BazaarProduct p : BazaarApi.peekProducts()) {
            if (p.productId().equals(id)) {
                return p.displayName();
            }
        }
        return null;
    }

    /** What one unit of the product sells for under the advisor's answers, from the latest snapshot (0 when unknown). */
    private static double saleEach(Minecraft client, String id) {
        for (BazaarProduct p : BazaarApi.peekProducts()) {
            if (p.productId().equals(id)) {
                return FlipAutopilot.unitSellNet(BazaarAdvisorFeature.answers(BazaarAdvisorConfig.getInstance()),
                        BazaarAdvisorFeature.lookups(client.player == null ? null : client.player.inventoryMenu), p);
            }
        }
        return 0;
    }

    private static double lorePerUnit(List<String> lore) {
        for (String line : lore) {
            Matcher m = PRICE_PER_UNIT.matcher(line);
            if (m.matches()) {
                try {
                    return Double.parseDouble(m.group(1).replace(",", ""));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static long loreOrderAmount(List<String> lore) {
        for (String line : lore) {
            Matcher m = ORDER_AMOUNT.matcher(line);
            if (m.matches()) {
                return Long.parseLong(m.group(1).replace(",", ""));
            }
        }
        return -1;
    }

    static String productId(FlipAction a) {
        return switch (a) {
            case FlipAction.InstaBuy x -> x.productId();
            case FlipAction.InstaSell x -> x.productId();
            case FlipAction.PlaceBuyOrder x -> x.productId();
            case FlipAction.PlaceSellOffer x -> x.productId();
            case FlipAction.SellToNpc x -> x.productId();
            case FlipAction.Collect x -> x.order().productId();
            case FlipAction.Cancel x -> x.order().productId();
            case FlipAction.Relist x -> x.order().productId();
            case FlipAction.Wait x -> null;
            case FlipAction.Stop x -> null;
        };
    }

    static String productName(FlipAction a) {
        return switch (a) {
            case FlipAction.InstaBuy x -> x.name();
            case FlipAction.InstaSell x -> x.name();
            case FlipAction.PlaceBuyOrder x -> x.name();
            case FlipAction.PlaceSellOffer x -> x.name();
            case FlipAction.SellToNpc x -> x.name();
            case FlipAction.Collect x -> x.order().name();
            case FlipAction.Cancel x -> x.order().name();
            case FlipAction.Relist x -> x.order().name();
            case FlipAction.Wait x -> "";
            case FlipAction.Stop x -> "";
        };
    }

    private static String jobName() {
        if (refresh) {
            return "Manage Orders read";
        }
        if (action == null) {
            return "autopilot";
        }
        return switch (action) {
            case FlipAction.InstaBuy x -> "Insta Buy";
            case FlipAction.InstaSell x -> "Insta Sell";
            case FlipAction.PlaceBuyOrder x -> "buy order";
            case FlipAction.PlaceSellOffer x -> "sell offer";
            case FlipAction.SellToNpc x -> "NPC sale";
            case FlipAction.Collect x -> "collect";
            case FlipAction.Cancel x -> "cancel";
            case FlipAction.Relist x -> "relist";
            case FlipAction.Wait x -> "wait";
            case FlipAction.Stop x -> "stop";
        };
    }

    private static String describe(FlipAction a) {
        return switch (a) {
            case FlipAction.InstaBuy x -> String.format(Locale.US, "Insta Buy %,d %s", x.amount(), x.name());
            case FlipAction.InstaSell x -> String.format(Locale.US, "Insta Sell %,d %s", x.amount(), x.name());
            case FlipAction.PlaceBuyOrder x -> String.format(Locale.US, "Buy order %,d %s at %,.1f", x.amount(), x.name(), x.price());
            case FlipAction.PlaceSellOffer x -> String.format(Locale.US, "Sell offer %,d %s at %,.1f", x.amount(), x.name(), x.price());
            case FlipAction.SellToNpc x -> String.format(Locale.US, "Sell %,d %s to the NPC", x.amount(), x.name());
            case FlipAction.Collect x -> "Collect " + x.order().name();
            case FlipAction.Cancel x -> "Cancel the " + (x.order().buy() ? "buy order" : "sell offer") + " for " + x.order().name();
            case FlipAction.Relist x -> String.format(Locale.US, "Relist %s at %,.1f", x.order().name(), x.newPrice());
            case FlipAction.Wait x -> "Wait";
            case FlipAction.Stop x -> "Stop";
        };
    }
}
