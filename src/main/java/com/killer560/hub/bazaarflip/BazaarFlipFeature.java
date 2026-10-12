package com.killer560.hub.bazaarflip;

import com.killer560.hub.scoreboard.ScoreboardData;
import com.killer560.hub.scoreboard.ScoreboardPattern;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ServerCommands;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import com.killer560.hub.bazaar.BazaarPages;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.compat.McCompat;

/**
 * Bazaar-to-NPC Flipper (CHEAT BUILD ONLY - {@link BazaarFlipConfig#isEnabled()} is hard-gated on
 * {@code BuildVariant.CHEAT_FEATURES_ENABLED}, so the whole thing folds out of the legit jar).
 *
 * <p>killer560, 2026-09-29: "a bazaar flipper... a setting that needs a keybind to start it and any key stops
 * it. the bot will look at the whole bazaar cost at insta buy price compared to npc sell price, and find the
 * item with the highest margin. Then it will get the item on the bazaar, buy as many as possible or a full
 * inventory, then open an npc by doing /trades and sell all the items. I need a slider for speed parts of
 * this." Plus, later: "only have the rescan go while the bot is running, so before it starts it would scan in
 * the bazaar then every minute during it"; "let me set stuff like target purse so if it hits that purse then
 * it stops flipping"; "make failsafes for stuff like if i dont have the money for the best thing then it buys
 * the best thing i can afford if nothing is profitable have it not work".
 *
 * <h2>Scanning only while running</h2>
 * One scan on start, then one every {@link #RESCAN_INTERVAL_MS} <b>while running only</b>. Nothing scans while
 * idle, and this path never calls {@code BazaarApi.ensureAutoStarted()}, whose five-minute background loop is
 * the always-on behaviour that made the Auction House scan a performance complaint in the first place.
 *
 * <h2>Starting and stopping</h2>
 * The configured bind starts it. Any key stops it, and the stop message names the key - "key pressed" cannot
 * tell a walk from the feature killing itself.
 *
 * <p>The awkward part, and the reason CLAUDE.md has a lesson about it: an "any key stops it" guard that fires
 * with a screen open stops the feature instantly, because the Return that submits a command and the Escape
 * that closes the settings tab are both key presses. So the world-side watcher in {@link #tick} only looks at
 * keys while {@code McCompat.screen(client) == null}, and the screen-side watcher is registered on CONTAINER screens
 * only ({@link #onScreenInit}) - never on chat, never on this mod's own menu, never on the pause screen. A
 * container screen is a Hypixel menu the bot is driving, so a key press there really is him intervening.
 * On top of that {@link #START_GRACE_MS} swallows everything for a moment after a start, so the bind itself
 * (still physically held) cannot stop the session it just began.
 *
 * <h2>The cycle (2026-10-11: "make the bazaar flipper work")</h2>
 * Until then every session stopped at the first menu and clicked nothing: the dry run killer560 ran on
 * 2026-10-08 showed {@code /bz Lump of Magma} opens a SEARCH page ({@code Bazaar ➜ "Lump of Magma"}), not the
 * product page the old step assumed. Now: scan, {@code /bz <name>}, then every Bazaar page is read through
 * {@link BazaarPages} (the reskin's classifier) and driven by name - the product in the search results, Buy
 * Instantly on the product page, then on the Instant Buy page the largest of Fill my inventory! / Buy a stack! /
 * Buy only one! that fits the scan's profitable size, the inventory, the NPC price and the purse. Each buy is
 * confirmed by the items arriving (or "[Bazaar] You cannot afford this!"); then the Bazaar is closed, {@code
 * /trades} opened, and every unit shift-clicked to the NPC, each confirmed by the stack leaving. Then it scans
 * again. The Bazaar reskin keeps drawing while this runs (killer560, 2026-10-11) and shows each page it reaches;
 * the reads and clicks here go to the real menu underneath, never through the reskin, so they are the same either
 * way. A key or press on the reskin stops this ({@code BazaarReskin}). Start refuses without a Booster Cookie: both the remote {@code /bz} and
 * selling from {@code /trades} need one.
 *
 * <h2>Safety rules this obeys</h2>
 * Ticks on {@code START_CLIENT_TICK}, never END: END runs after the player's own movement packet and GrimAC
 * flags every interaction that follows it as {@code Post}. Goes through {@link ActionGate}. Never writes
 * position or velocity, never sends fractional movement input, never presses a movement key at all - it does
 * not move the player.
 */
public final class BazaarFlipFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-bazaarflip");

    /** A page's items must sit unchanged this long before it is judged or clicked. */
    private static final long SETTLE_MS = 150L;
    /** How long an instant buy may take to land in the inventory before the session gives up. */
    private static final long BUY_CONFIRM_TIMEOUT_MS = 5_000L;
    /** How long a sold stack may take to leave the inventory. */
    private static final long SELL_CONFIRM_TIMEOUT_MS = 4_000L;
    /** "Buy only one!" is only for a tail smaller than a stack, and only this many times a cycle. */
    private static final int MAX_SINGLE_BUYS = 8;
    /** The Trades menu; the exact title is not documented, so anything naming Trades. */
    private static final java.util.regex.Pattern TRADES_TITLE = java.util.regex.Pattern.compile("(?i)^.{0,24}\\btrades?\\b.{0,24}$");
    private static final java.util.regex.Pattern CANNOT_AFFORD = java.util.regex.Pattern.compile("^\\[Bazaar\\] You cannot afford this!$");

    /** killer560: "then every minute during it". */
    private static final long RESCAN_INTERVAL_MS = 60_000L;
    /** How long a menu is waited for before the session gives up. A safety limit, NOT paced by the speed
     *  slider - a timeout that shrinks with speed turns a laggy moment into a spurious stop. */
    private static final long MENU_TIMEOUT_MS = 6_000L;
    /** Keys are ignored for this long after a start so the bind that started it cannot stop it. */
    private static final long START_GRACE_MS = 600L;
    /** Hard ceiling on automated container clicks in one session, same idea as Auto Croesus's. */
    private static final int MAX_CLICKS_PER_SESSION = 400;

    private enum State {
        IDLE,
        /** Waiting for the scan that decides what to buy. */
        SCANNING,
        /** {@code /bz <item>} sent, waiting for Hypixel's menu. */
        WAIT_BAZAAR_MENU,
        /** In the Bazaar: search result -> product page -> Instant Buy -> buying. */
        BAZAAR,
        /** Bazaar closed, {@code /trades} about to be sent. */
        OPEN_TRADES,
        /** {@code /trades} sent, waiting for the NPC menu. */
        WAIT_TRADES_MENU,
        /** The NPC menu is open: selling. */
        SELL
    }

    private static State state = State.IDLE;
    private static long stateSinceMs = 0L;
    private static long nextActionAtMs = 0L;
    private static long startedAtMs = 0L;
    private static long lastScanAtMs = 0L;
    private static boolean scanPending = false;
    /**
     * Bumped by every {@link #beginScan}. A scan that completes after a newer one was already kicked off (a
     * stop-and-restart while the HTTP request was still out) must not clear {@link #scanPending}, or the tick
     * would consume the OLD scan's ranking - priced against the OLD purse - as if it were the new one.
     */
    private static int scanGeneration = 0;
    private static boolean keyWasDown = false;
    /**
     * True from a start until the start bind is seen UP again. Without it, holding the start key for longer
     * than {@link #START_GRACE_MS} makes the session stop itself on the very key that began it, which is
     * exactly the "I turn it on and it auto turns off" failure CLAUDE.md warns about - the grace window alone
     * only covers a quick tap.
     */
    private static boolean startBindStillHeld = false;
    private static int clicks = 0;
    private static int cycles = 0;
    private static double startPurse = 0;
    private static BazaarFlipCandidate target = null;
    // Per cycle: what the page reader saw, the last page-changing click, the buy and sell in flight.
    private static int seenContainerId = Integer.MIN_VALUE;
    private static String seenSignature = "";
    private static long seenSinceMs = 0L;
    private static int clickedContainerId = Integer.MIN_VALUE;
    private static int carriedAtStart = -1;
    private static int singleBuys = 0;
    private static boolean pendingBuy = false;
    private static int pendingBuyCount = 0;
    private static long pendingBuyAtMs = 0L;
    private static volatile boolean cannotAfford = false;
    private static int sellPendingSlot = -1;
    private static long sellPendingAtMs = 0L;

    private BazaarFlipFeature() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("BazaarFlipFeature.tick", BazaarFlipFeature::tick));
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            if (state != State.IDLE) {
                stop("world changed");
            }
        });
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            ScreenEvents.AFTER_INIT.register(BazaarFlipFeature::onScreenInit);
            com.killer560.hub.util.ChatObserver.subscribe(message -> {
                if (state == State.BAZAAR && CANNOT_AFFORD.matcher(
                        com.killer560.hub.util.ChatObserver.strip(message).trim()).matches()) {
                    cannotAfford = true;
                }
            });
        }
    }

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    /** The candidate the current session is working on, for the settings tab. Null when idle. */
    public static BazaarFlipCandidate getTarget() {
        return target;
    }

    public static int getCycles() {
        return cycles;
    }

    // ---- starting -------------------------------------------------------------------------------------

    /**
     * Starts a session. Every refusal returns a reason instead of failing silently.
     *
     * @return null on success, else why it refused
     */
    public static String start() {
        if (state != State.IDLE) {
            return "already running.";
        }
        BazaarFlipConfig cfg = BazaarFlipConfig.getInstance();
        if (!cfg.isEnabled()) {
            return "the Bazaar Flipper is OFF - turn it on in its tab first.";
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return "not in a world.";
        }
        Double purse = readPurse();
        if (purse == null) {
            return "couldn't read your purse off the scoreboard, and every size and failsafe here depends on"
                    + " it - stand somewhere the Purse line is shown (the Hub) and try again.";
        }
        if (com.killer560.hub.auction.BoosterCookie.state(client) == com.killer560.hub.auction.BoosterCookie.State.INACTIVE) {
            return "you have no active Booster Cookie - the remote /bz and selling from /trades both need one.";
        }
        if (cfg.getTargetPurse() > 0 && purse >= cfg.getTargetPurse()) {
            return String.format(Locale.US, "your purse is already at the target (%,.0f of %,d).",
                    purse, cfg.getTargetPurse());
        }
        BazaarFlipScanner.clear();
        target = null;
        clicks = 0;
        cycles = 0;
        startPurse = purse;
        startedAtMs = System.currentTimeMillis();
        startBindStillHeld = cfg.getStartKeyCode() != KeyUtil.NONE && client.getWindow() != null
                && KeyUtil.isBindDown(client.getWindow(), cfg.getStartKeyCode());
        beginScan(purse, "starting");
        ModChat.send("Bazaar Flip", ModChat.text("Scanning the Bazaar... purse "),
                ModChat.value(String.format(Locale.US, "%,.0f", purse)),
                ModChat.dim(", ranking by " + cfg.getRankingMode().display));
        return null;
    }

    public static void requestStop() {
        if (state != State.IDLE) {
            stop("stopped by user");
        }
    }

    // ---- the any-key stop ----------------------------------------------------------------------------

    /**
     * Called for every key press that reaches a watcher this feature owns. Names the key in the stop message
     * (CLAUDE.md: "key pressed" cannot tell a walk from the feature killing itself).
     *
     * @return true if a session was actually stopped, so the caller can swallow the key press
     */
    public static boolean stopOnKeyPress(int keyCode) {
        if (state == State.IDLE) {
            return false;
        }
        if (System.currentTimeMillis() - startedAtMs < START_GRACE_MS) {
            // The bind that started this is very likely still physically down.
            return false;
        }
        if (startBindStillHeld && keyCode == BazaarFlipConfig.getInstance().getStartKeyCode()) {
            return false;
        }
        stop("you pressed " + KeyUtil.bindDisplayName(keyCode));
        return true;
    }

    /**
     * Registered on CONTAINER screens only. A container screen is a Hypixel menu, i.e. one this bot is
     * driving, so a key press there is really him intervening - unlike chat, this mod's settings screen or the
     * pause menu, where the Return/Escape that got the feature started would otherwise kill it instantly.
     */
    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            return;
        }
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
            try {
                if (!stopOnKeyPress(event.key())) {
                    return true;
                }
                // Escape still closes the menu the way it normally would; everything else is swallowed,
                // because a stray number key inside a Hypixel menu is a real hotbar-swap packet.
                return event.key() == InputConstants.KEY_ESCAPE;
            } catch (Exception e) {
                LOGGER.error("[BazaarFlip] Key hook failed", e);
                return true;
            }
        });
    }

    // ---- the tick -------------------------------------------------------------------------------------

    private static void tick(Minecraft client) {
        try {
            tickUnsafe(client);
        } catch (Exception e) {
            LOGGER.error("[BazaarFlip] tick failed - stopping", e);
            if (state != State.IDLE) {
                stop("internal error (see log)");
            }
        }
    }

    private static void tickUnsafe(Minecraft client) {
        BazaarFlipConfig cfg = BazaarFlipConfig.getInstance();
        pollStartKey(client, cfg);
        if (state == State.IDLE) {
            return;
        }
        if (!cfg.isEnabled()) {
            stop("the Bazaar Flipper was turned off");
            return;
        }
        if (client.player == null || client.level == null) {
            stop("left the world");
            return;
        }
        // Any key, while no screen is open. With a screen open the container watcher above handles it, and
        // nothing else is watched at all - see this class's doc for why.
        if (McCompat.screen(client) == null && anyKeyDownInWorld(client)) {
            return;
        }
        if (clicks >= MAX_CLICKS_PER_SESSION) {
            stop("safety limit of " + MAX_CLICKS_PER_SESSION + " clicks reached");
            return;
        }
        Double purse = readPurse();
        if (purse != null && cfg.getTargetPurse() > 0 && purse >= cfg.getTargetPurse()) {
            finish(String.format(Locale.US, "target purse reached (%,.0f of %,d)", purse, cfg.getTargetPurse()));
            return;
        }
        // killer560: "then every minute during it" - while running only, in any state, so the book the NEXT
        // decision is made from is never a minute stale. Deliberately not tied to a particular state: the
        // states that decide things are short-lived, and gating the rescan on one of those meant the
        // once-a-minute rescan almost never actually fired.
        if (!scanPending && purse != null && System.currentTimeMillis() - lastScanAtMs >= RESCAN_INTERVAL_MS) {
            beginScan(purse, "rescan");
        }
        switch (state) {
            case SCANNING -> tickScanning(cfg);
            case WAIT_BAZAAR_MENU -> tickWaitMenu(client, "the Bazaar menu", State.BAZAAR);
            case BAZAAR -> tickBazaar(client, cfg);
            case OPEN_TRADES -> tickOpenTrades();
            case WAIT_TRADES_MENU -> tickWaitMenu(client, "the /trades NPC menu", State.SELL);
            case SELL -> tickSell(client, cfg);
            case IDLE -> {
            }
        }
    }

    private static void pollStartKey(Minecraft client, BazaarFlipConfig cfg) {
        if (!cfg.isEnabled() || client.getWindow() == null) {
            keyWasDown = false;
            return;
        }
        int code = cfg.getStartKeyCode();
        if (code == KeyUtil.NONE) {
            keyWasDown = false;
            return;
        }
        boolean down = KeyUtil.isBindDown(client.getWindow(), code);
        if (!down) {
            startBindStillHeld = false;
        }
        // Rising edge, and only with no screen open, so the bind can't fire while he is typing in chat or
        // clicking around this mod's own settings.
        if (down && !keyWasDown && McCompat.screen(client) == null && state == State.IDLE) {
            String refusal = start();
            if (refusal != null) {
                ModChat.send("Bazaar Flip", ModChat.bad("Can't start: "), ModChat.text(refusal));
            }
        }
        keyWasDown = down;
    }

    /** Any key or mouse button at all, polled only when no screen is open. */
    private static boolean anyKeyDownInWorld(Minecraft client) {
        if (client.getWindow() == null) {
            return false;
        }
        if (System.currentTimeMillis() - startedAtMs < START_GRACE_MS) {
            return false;
        }
        long handle = client.getWindow().handle();
        // The start bind is skipped only while it has been continuously held since the start - see
        // startBindStillHeld. The moment it comes up, it stops the session like any other key.
        int startCode = startBindStillHeld ? BazaarFlipConfig.getInstance().getStartKeyCode() : KeyUtil.NONE;
        for (int key = org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE; key <= org.lwjgl.glfw.GLFW.GLFW_KEY_LAST; key++) {
            if (key == startCode) {
                continue;
            }
            if (org.lwjgl.glfw.GLFW.glfwGetKey(handle, key) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                return stopOnKeyPress(key);
            }
        }
        for (int button = 0; button <= KeyUtil.MAX_MOUSE_BUTTON; button++) {
            if (KeyUtil.codeForMouseButton(button) == startCode) {
                continue;
            }
            if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(handle, button) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                return stopOnKeyPress(KeyUtil.codeForMouseButton(button));
            }
        }
        return false;
    }

    // ---- states ---------------------------------------------------------------------------------------

    private static void beginScan(double purse, String why) {
        scanPending = true;
        lastScanAtMs = System.currentTimeMillis();
        final int generation = ++scanGeneration;
        if (state == State.IDLE) {
            setState(State.SCANNING);
        }
        BazaarFlipConfig cfg = BazaarFlipConfig.getInstance();
        BazaarFlipScanner.scanAsync(purse, cfg.getRankingMode(), cfg.getMinProfitPerRun())
                .whenComplete((result, error) -> {
                    if (generation == scanGeneration) {
                        scanPending = false;
                    }
                    if (error != null) {
                        LOGGER.warn("[BazaarFlip] {} scan failed", why, error);
                    }
                });
    }

    private static void tickScanning(BazaarFlipConfig cfg) {
        if (scanPending) {
            return;
        }
        BazaarFlipScanner.ScanResult result = BazaarFlipScanner.getLast();
        // A decision is never made off a book older than the rescan interval - that covers both the first
        // tick after a cycle finishes and a scan whose result was superseded.
        if (result == null || System.currentTimeMillis() - result.atMs() > RESCAN_INTERVAL_MS) {
            Double purse = readPurse();
            if (purse == null) {
                stop("lost the Purse line off the scoreboard");
                return;
            }
            beginScan(purse, "cycle");
            return;
        }
        if (!result.ok()) {
            stop(result.error());
            return;
        }
        BazaarFlipCandidate best = result.best();
        if (best == null) {
            // killer560: "if nothing is profitable have it not work". The purse was already passed into the
            // sizing, so "nothing cleared the floor" already accounts for what he can afford - there is no
            // cheaper fallback left to try.
            stop(String.format(Locale.US,
                    "nothing clears your %,d coin minimum profit - %d of %d products with an NPC price are"
                            + " profitable at all right now",
                    cfg.getMinProfitPerRun(), result.profitableCount(), result.productsWithNpcPrice()));
            return;
        }
        target = best;
        carriedAtStart = -1;
        singleBuys = 0;
        pendingBuy = false;
        cannotAfford = false;
        sellPendingSlot = -1;
        clickedContainerId = Integer.MIN_VALUE;
        seenContainerId = Integer.MIN_VALUE;
        ModChat.send("Bazaar Flip", ModChat.text("Best flip: "), ModChat.value(best.summary()));
        LOGGER.info("[BazaarFlip] Target {} - {} units, spend {}, profit {} ({}%)", best.productId(),
                best.units(), Math.round(best.spend()), Math.round(best.profit()),
                String.format(Locale.US, "%.2f", best.marginPercent()));
        // Hypixel's own /bz takes the item's display name. ServerCommands.toServer sends BELOW this mod's own
        // client dispatcher, which matters because "bz" is itself a client command this mod registers (see
        // bazaar.BazaarFeature) - sendCommand would recurse straight back into our own handler.
        if (!sendCommand("bz " + best.displayName())) {
            return;
        }
        setState(State.WAIT_BAZAAR_MENU);
    }

    // ---- the Bazaar: search -> product -> Instant Buy -> buy ---------------------------------------------

    /**
     * True once the open menu's items have sat unchanged for {@link #SETTLE_MS}. Hypixel sends a page's items a
     * moment after the window, so a page is never judged (or clicked) half-loaded.
     */
    private static boolean settled(AbstractContainerScreen<?> screen) {
        long now = System.currentTimeMillis();
        int id = screen.getMenu().containerId;
        String sig = BazaarFlipMenus.signature(screen.getMenu());
        if (id != seenContainerId || !sig.equals(seenSignature)) {
            seenContainerId = id;
            seenSignature = sig;
            seenSinceMs = now;
            return false;
        }
        return !sig.isEmpty() && now - seenSinceMs >= SETTLE_MS;
    }

    /** After a click that should change the page: true while the same page is still up (nothing to judge yet). */
    private static boolean stillOnClickedPage(AbstractContainerScreen<?> screen) {
        if (clickedContainerId == Integer.MIN_VALUE) {
            return false;
        }
        // Every Hypixel page is a new container. The items of the old one are no signal: a left click predicts the
        // clicked item onto the cursor client-side until the server re-sends the page.
        if (screen.getMenu().containerId != clickedContainerId) {
            clickedContainerId = Integer.MIN_VALUE;
            return false;
        }
        return true;
    }

    private static void tickWaitMenu(Minecraft client, String what, State next) {
        if (McCompat.screen(client) instanceof AbstractContainerScreen<?>) {
            setState(next);
            return;
        }
        if (System.currentTimeMillis() - stateSinceMs > MENU_TIMEOUT_MS) {
            stop(what + " never opened");
        }
    }

    private static void tickBazaar(Minecraft client, BazaarFlipConfig cfg) {
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
            stop("the Bazaar menu closed");
            return;
        }
        long now = System.currentTimeMillis();
        if (pendingBuy) {
            int have = BazaarFlipMenus.countProduct(screen.getMenu(), target.productId(), target.displayName());
            if (have > pendingBuyCount) {
                pendingBuy = false;
                LOGGER.info("[BazaarFlip] Bought {} {} (now carrying {})", have - pendingBuyCount,
                        target.productId(), have);
            } else if (cannotAfford) {
                pendingBuy = false;
                LOGGER.info("[BazaarFlip] Hypixel said \"You cannot afford this!\" - selling what was bought");
                doneBuying("Hypixel said you can't afford more");
                return;
            } else if (now - pendingBuyAtMs > BUY_CONFIRM_TIMEOUT_MS) {
                stop("Hypixel never delivered the instant buy (nothing arrived in your inventory)");
                return;
            } else {
                return;
            }
        }
        if (now < nextActionAtMs) {
            return;
        }
        if (!settled(screen) || stillOnClickedPage(screen)) {
            if (now - stateSinceMs > MENU_TIMEOUT_MS && now - seenSinceMs > MENU_TIMEOUT_MS) {
                stop("the Bazaar page never changed - it was " + BazaarFlipMenus.describe(screen));
            }
            return;
        }
        BazaarPages.Page page = BazaarFlipMenus.page(screen);
        String name = target.displayName();
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
                    stop("\"" + name + "\" is not on the Bazaar page " + BazaarFlipMenus.describe(screen));
                    return;
                }
                click(client, screen, product.slot(), ContainerInput.PICKUP, cfg, true);
            }
            case PRODUCT -> {
                if (BazaarFlipMenus.item(page, name) == null) {
                    stop("the product page is not " + name + ": " + BazaarFlipMenus.describe(screen));
                    return;
                }
                BazaarPages.Item buy = BazaarFlipMenus.item(page, "Buy Instantly");
                if (buy == null) {
                    stop("no Buy Instantly on " + BazaarFlipMenus.describe(screen));
                    return;
                }
                click(client, screen, buy.slot(), ContainerInput.PICKUP, cfg, true);
            }
            case INSTANT_BUY -> buyStep(client, screen, page, cfg);
            default -> {
                LOGGER.info("[BazaarFlip] Unexpected Bazaar page: {}", BazaarFlipMenus.describe(screen));
                stop("unexpected menu " + BazaarFlipMenus.describe(screen));
            }
        }
    }

    /**
     * One purchase on the "&lt;item&gt; ➜ Instant Buy" page. Hypixel's options are Buy only one!, Buy a stack!, Fill my
     * inventory! and Custom Amount; Custom Amount opens a sign, which is not a container, so it is never used. The
     * biggest option that still fits what the scan sized (never more than the profitable part of the book), fits his
     * inventory, and - when its lore states a price - costs less per unit than the NPC pays and no more than his purse.
     */
    private static void buyStep(Minecraft client, AbstractContainerScreen<?> screen, BazaarPages.Page page,
                                BazaarFlipConfig cfg) {
        var menu = screen.getMenu();
        int have = BazaarFlipMenus.countProduct(menu, target.productId(), target.displayName());
        if (carriedAtStart < 0) {
            carriedAtStart = have;
        }
        int bought = have - carriedAtStart;
        int remaining = target.units() - bought;
        int stack = Math.max(1, target.stackSize());
        int room = BazaarFlipMenus.roomFor(menu, target.productId(), target.displayName(), stack);
        if (remaining <= 0 || room <= 0) {
            doneBuying(remaining <= 0 ? "bought the planned " + target.units() : "inventory full");
            return;
        }
        Double purse = readPurse();
        String[] names = {"Fill my inventory!", "Buy a stack!", "Buy only one!"};
        long[] fallbackAmount = {room, stack, 1};
        String refused = null;
        for (int i = 0; i < names.length; i++) {
            BazaarPages.Item option = BazaarFlipMenus.item(page, names[i]);
            if (option == null) {
                continue;
            }
            long amount = BazaarFlipMenus.loreAmount(option.lore());
            if (amount <= 0) {
                amount = fallbackAmount[i];
            }
            if (amount > remaining || amount > room) {
                continue;
            }
            if (i == 2 && singleBuys >= MAX_SINGLE_BUYS) {
                continue;
            }
            double price = BazaarFlipMenus.lorePrice(option.lore());
            if (price > 0) {
                if (price / amount >= target.npcSellPrice()) {
                    refused = String.format(Locale.US, "the instant-buy price (%,.1f each) is no longer below the NPC's %,.1f",
                            price / amount, target.npcSellPrice());
                    continue;
                }
                if (purse != null && price > purse) {
                    refused = "you can't afford " + names[i];
                    continue;
                }
            }
            if (!click(client, screen, option.slot(), ContainerInput.PICKUP, cfg, false)) {
                return; // gate denied - retried next tick
            }
            if (i == 2) {
                singleBuys++;
            }
            pendingBuy = true;
            pendingBuyCount = have;
            pendingBuyAtMs = System.currentTimeMillis();
            cannotAfford = false;
            LOGGER.info("[BazaarFlip] {} ({} units, lore price {}) - bought {} of {} so far", names[i], amount,
                    price > 0 ? String.format(Locale.US, "%.1f", price) : "not shown", bought, target.units());
            return;
        }
        doneBuying(refused != null ? refused : "nothing smaller left to buy");
    }

    /** Buying is over for this cycle: sell what was bought, or stop if that was nothing. */
    private static void doneBuying(String why) {
        Minecraft client = Minecraft.getInstance();
        int bought = 0;
        if (McCompat.screen(client) instanceof AbstractContainerScreen<?> screen && target != null && carriedAtStart >= 0) {
            bought = BazaarFlipMenus.countProduct(screen.getMenu(), target.productId(), target.displayName()) - carriedAtStart;
        }
        if (bought <= 0) {
            stop("bought nothing - " + why);
            return;
        }
        ModChat.send("Bazaar Flip", ModChat.text("Bought "), ModChat.value(String.format(Locale.US, "%,d", bought)),
                ModChat.text(" " + target.displayName()), ModChat.dim(" (" + why + ") - selling to the NPC"));
        if (client.player != null && McCompat.screen(client) != null) {
            client.player.closeContainer();
        }
        setState(State.OPEN_TRADES);
    }

    private static void tickOpenTrades() {
        if (System.currentTimeMillis() < nextActionAtMs) {
            return;
        }
        if (sendCommand("trades")) {
            setState(State.WAIT_TRADES_MENU);
        }
    }

    // ---- /trades: sell every unit back to the NPC ---------------------------------------------------------

    private static void tickSell(Minecraft client, BazaarFlipConfig cfg) {
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
            stop("the /trades menu closed");
            return;
        }
        long now = System.currentTimeMillis();
        String title = BazaarFlipMenus.titleOf(screen);
        if (!TRADES_TITLE.matcher(title).matches()) {
            if (now - stateSinceMs > MENU_TIMEOUT_MS) {
                stop("/trades opened \"" + title + "\", not the Trades menu");
            }
            return;
        }
        var menu = screen.getMenu();
        if (sellPendingSlot >= 0) {
            ItemStack still = sellPendingSlot < menu.slots.size() ? menu.slots.get(sellPendingSlot).getItem() : ItemStack.EMPTY;
            if (!BazaarFlipMenus.isProduct(still, target.productId(), target.displayName())) {
                sellPendingSlot = -1;
            } else if (now - sellPendingAtMs > SELL_CONFIRM_TIMEOUT_MS) {
                stop("the NPC didn't take the " + target.displayName()
                        + " (selling from /trades needs an active Booster Cookie)");
                return;
            } else {
                return;
            }
        }
        if (now < nextActionAtMs || !settledTrades(screen)) {
            return;
        }
        for (Slot slot : BazaarFlipMenus.playerSlots(menu)) {
            if (!BazaarFlipMenus.isProduct(slot.getItem(), target.productId(), target.displayName())) {
                continue;
            }
            // A shift-click on his own stack sells it, the way any Hypixel shop takes items.
            if (click(client, screen, slot.index, ContainerInput.QUICK_MOVE, cfg, false)) {
                sellPendingSlot = slot.index;
                sellPendingAtMs = now;
            }
            return;
        }
        cycles++;
        LOGGER.info("[BazaarFlip] Cycle {} done: sold every {}", cycles, target.productId());
        ModChat.send("Bazaar Flip", ModChat.good("Sold. "), ModChat.dim("Cycle " + cycles + " done."));
        if (client.player != null) {
            client.player.closeContainer();
        }
        target = null;
        setState(State.SCANNING);
    }

    /** The Trades menu's own items may be anything; it is judged settled once something has been in it briefly. */
    private static boolean settledTrades(AbstractContainerScreen<?> screen) {
        return settled(screen) || System.currentTimeMillis() - stateSinceMs > SETTLE_MS * 3;
    }

    /**
     * The one place an automated container click is sent: through {@link ActionGate} as
     * {@link ActionGate.Actor#BAZAAR_FLIP_MENU}, on {@code START_CLIENT_TICK}, as the same left click (or shift-click)
     * a player's press on that slot sends.
     *
     * @param changesPage the click should bring up a different page; nothing is judged until it does
     * @return whether the click was sent (false: the gate denied this tick, nothing happened)
     */
    private static boolean click(Minecraft client, AbstractContainerScreen<?> screen, int slotIndex,
                                 ContainerInput input, BazaarFlipConfig cfg, boolean changesPage) {
        if (client.gameMode == null || client.player == null) {
            return false;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.BAZAAR_FLIP_MENU, screen)) {
            return false;
        }
        if (changesPage) {
            clickedContainerId = screen.getMenu().containerId;
        }
        String label = slotIndex < screen.getMenu().slots.size()
                ? BazaarFlipMenus.nameOf(screen.getMenu().slots.get(slotIndex).getItem()) : "?";
        client.gameMode.handleContainerInput(screen.getMenu().containerId, slotIndex, 0, input, client.player);
        LOGGER.info("[BazaarFlip] Click {} slot {} ({}) on \"{}\"", input, slotIndex, label,
                BazaarFlipMenus.titleOf(screen));
        clicks++;
        stateSinceMs = System.currentTimeMillis();
        nextActionAtMs = System.currentTimeMillis() + randomDelay(cfg);
        return true;
    }

    /** A chat command through the gate, sent below this mod's own client dispatcher. */
    private static boolean sendCommand(String command) {
        if (!ActionGate.tryAct(ActionGate.Actor.BAZAAR_FLIP_CMD)) {
            return false; // retried next tick
        }
        if (!ServerCommands.toServer(command)) {
            stop("couldn't send /" + command);
            return false;
        }
        nextActionAtMs = System.currentTimeMillis() + randomDelay(BazaarFlipConfig.getInstance());
        return true;
    }

    // ---- purse ----------------------------------------------------------------------------------------

    /**
     * The purse, off the Skyblock sidebar, through the same {@code ScoreboardPattern.COINS} the Custom
     * Scoreboard's own Purse line uses - not a second parser.
     *
     * @return the coin count, or null when the sidebar has no Purse line (in a dungeon, in a lobby, mid
     * world-switch). Null is a refusal to act, never a zero: every size and failsafe here depends on it.
     */
    public static Double readPurse() {
        try {
            String raw = ScoreboardData.group(ScoreboardPattern.COINS,
                    ScoreboardData.sidebarNow(Minecraft.getInstance()), "coins");
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String cleaned = raw.replace(",", "").trim();
            if (cleaned.isEmpty()) {
                return null;
            }
            double value = Double.parseDouble(cleaned);
            return value < 0 ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- bookkeeping ----------------------------------------------------------------------------------

    private static void setState(State next) {
        state = next;
        stateSinceMs = System.currentTimeMillis();
    }

    /** Pacing between two consecutive automated actions - the speed slider's only job. */
    private static long randomDelay(BazaarFlipConfig cfg) {
        int lo = cfg.getActionDelayMinMs();
        int hi = Math.max(lo, cfg.getActionDelayMaxMs());
        return lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
    }

    private static void finish(String why) {
        Double purse = readPurse();
        ModChat.send("Bazaar Flip", ModChat.good("Done - "), ModChat.text(why),
                ModChat.dim(purse != null
                        ? String.format(Locale.US, " (%d cycle(s), purse %,.0f from %,.0f)", cycles, purse, startPurse)
                        : " (" + cycles + " cycle(s))"));
        LOGGER.info("[BazaarFlip] Finished: {} after {} cycle(s)", why, cycles);
        state = State.IDLE;
        target = null;
    }

    private static void stop(String reason) {
        LOGGER.info("[BazaarFlip] Stopped: {}", reason);
        ModChat.send("Bazaar Flip", ModChat.bad("Stopped: "), ModChat.text(reason),
                ModChat.dim(" (" + cycles + " cycle(s))"));
        state = State.IDLE;
        target = null;
    }
}
