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
import com.killer560.hub.bazaar.BazaarPages;
import com.killer560.hub.bazaarflip.mixin.SignEditScreenAccessor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * the best thing i can afford if nothing is profitable have it not work"; and 2026-10-16 "just remove the min profit
 * slider. I want it to always choose the best" - the best candidate by Rank By whose net profit is above 0.
 *
 * <h2>Scanning only while running</h2>
 * One scan on start, then one every {@link #RESCAN_INTERVAL_MS} <b>while running only</b>. Nothing scans while
 * idle, and this path never calls {@code BazaarApi.ensureAutoStarted()}, whose five-minute background loop is
 * the always-on behavior that made the Auction House scan a performance complaint in the first place.
 *
 * <h2>Starting and stopping</h2>
 * The configured bind starts it. Any key or mouse button he presses while it runs stops it, and the stop message names
 * it. Until 2026-10-16 the watcher was a per-screen key hook plus a poll that only looked with no screen open, and he
 * reported "right now only escape stopped it": it is now a raw hook at the head of Minecraft's key and mouse handlers
 * ({@code mixin/BazaarFlipStopKeyMixin}, {@code BazaarFlipStopMouseMixin}, {@link #onRawKeyPress}), ahead of the Bazaar
 * menus, the reskin and the sign editor alike. The bind's own press that started a session happened while it was idle
 * (and {@link #START_GRACE_MS} plus {@link #startBindStillHeld} cover the bind still being held), and the amount the
 * flipper types into the sign goes straight to the screen, never through the key handler - so neither can stop it.
 *
 * <h2>The cycle (2026-10-11: "make the bazaar flipper work"; Custom Amount 2026-10-16)</h2>
 * Scan, {@code /bz <name>}, then every Bazaar page is read through {@link BazaarPages} (the reskin's classifier) and
 * driven by name - the product in the search results, Buy Instantly on the product page, then on the Instant Buy page
 * the exact amount the scan sized: a preset (Buy only one! / Buy a stack! / Fill my inventory!) when its amount is
 * exactly that, otherwise Custom Amount, whose sign gets the number typed on line 1 and Done pressed ({@link #buyStep},
 * {@link #fillSign}). Hypixel then either delivers or shows a Confirm Instant Buy page, which is clicked only when its
 * one item states exactly that amount at a price under the NPC's ({@link #confirmStep}). Each buy is confirmed by the
 * items arriving (or "[Bazaar] You cannot afford this!"); then the Bazaar is closed, {@code /trades} opened, and every
 * unit shift-clicked to the NPC, each confirmed by the stack leaving. Then it scans again. The Bazaar reskin keeps
 * drawing while this runs and shows each page it reaches; the reads and clicks here go to the real menu underneath.
 * Start refuses without a Booster Cookie: both the remote {@code /bz} and selling from {@code /trades} need one.
 *
 * <h2>Money</h2>
 * The purse caps every buy, as does the daily NPC sell limit ({@link BazaarFlipNpcLimit}: nothing is bought that the
 * NPCs will not take today). Each finished cycle goes to the profit tracker ({@link BazaarFlipProfitLog}) from the coins
 * Hypixel's own chat reported.
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
    static final Pattern TRADES_TITLE =Pattern.compile("(?i)^.{0,24}\\btrades?\\b.{0,24}$");
    private static final Pattern CANNOT_AFFORD = Pattern.compile("^\\[Bazaar\\] You cannot afford this!$");
    /** "[Bazaar] Bought 16x Ender Pearl for 112.0 coins!" (his 2026-10-08 dump and 62 lines in his logs). */
    static final Pattern BOUGHT_LINE =
            Pattern.compile("^\\[Bazaar\\] Bought ([\\d,]{1,9})x (.{1,64}) for ([\\d,]{1,15}(?:\\.\\d{1,2})?) coins?!$");
    /** Hypixel's title for the step some instant buys get after the amount (SkyHanni's repo patterns list it). */
    static final String CONFIRM_INSTANT_BUY = "Confirm Instant Buy";
    /** Custom Amount's lore: "Buy up to 71,680x." (his screenshot, 2026-10-16). */
    static final Pattern BUY_UP_TO = Pattern.compile("^Buy up to ([\\d,]{1,12})x\\.?$");
    /** The sign Custom Amount opens: line 2 is a row of carets under the input line, line 3 "Enter amount". */
    private static final Pattern SIGN_CARETS = Pattern.compile("^\\^{3,40}$");

    /** killer560: "then every minute during it". */
    private static final long RESCAN_INTERVAL_MS = 60_000L;
    /** How long a menu is waited for before the session gives up. A safety limit, NOT paced by the speed
     *  slider - a timeout that shrinks with speed turns a laggy moment into a spurious stop. */
    private static final long MENU_TIMEOUT_MS = 6_000L;
    /** After the last unit leaves for the NPC, how long its "You sold" line may take before the cycle is logged
     *  with what arrived. A bound, not a wait: the cycle is logged the moment every line is in. */
    private static final long SOLD_LINES_TIMEOUT_MS = 2_000L;
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
        SELL,
        /** Everything is sold; waiting (bounded) for the last "You sold" lines before the cycle is logged. */
        LOG_CYCLE,
        /** The autopilot ({@link BazaarFlipAutopilot}) is running instead of the cycle above. */
        AUTOPILOT
    }

    /** Clicks one autopilot session may send (each order is about five; the cycle's cap is {@link #MAX_CLICKS_PER_SESSION}). */
    private static final int MAX_AUTOPILOT_CLICKS = 1500;

    /** Where a Custom Amount purchase is. */
    private enum Custom {
        NONE,
        /** Custom Amount clicked: waiting for Hypixel's sign. */
        AWAIT_SIGN,
        /** The amount was typed and Done pressed: waiting for a Confirm page or the items. */
        AFTER_SIGN
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
    /** When a screen last closed, and whether one was open on the previous tick - the start key waits
     *  {@link #MENU_CLOSE_DELAY_MS} after a menu closes (killer560, 2026-10-18). */
    private static boolean screenWasOpen = false;
    private static long menuClosedAtMs = 0L;
    static final long MENU_CLOSE_DELAY_MS = 250L;
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
    /** Units bought beyond the scan's plan this cycle, once the plan ran out and the purse and room still covered more. */
    private static int topUpUnits = 0;
    private static int singleBuys = 0;
    private static boolean pendingBuy = false;
    private static int pendingBuyCount = 0;
    private static long pendingBuyAtMs = 0L;
    private static volatile boolean cannotAfford = false;
    private static int sellPendingSlot = -1;
    private static int sellPendingCount = 0;
    private static long sellPendingAtMs = 0L;
    // Custom Amount.
    private static Custom custom = Custom.NONE;
    private static int customAmount = 0;
    private static int customClickedContainerId = Integer.MIN_VALUE;
    private static long customSinceMs = 0L;
    private static long signSeenAtMs = 0L;
    private static boolean confirmClicked = false;
    /** buyStep turned an option down on its live price (not on space or purse). */
    private static boolean priceRefused = false;
    /** This session's products the live page refused, with the scan's unit cost at the time ({@link #fallThrough}). */
    private static final java.util.Map<String, Double> rejected = new java.util.HashMap<>();
    /** A Custom Amount purchase landed with no menu open: when that started (0 = not waiting for the page). */
    private static long awaitingReopenSinceMs = 0L;
    /** How long the Bazaar page may take to come back after a Custom Amount purchase. A bound, not a wait: buying
     *  resumes the tick the page is up. */
    private static final long REOPEN_TIMEOUT_MS = 2_000L;
    // What Hypixel reported this cycle (BazaarFlipProfitLog).
    private static double cycleSpent = 0;
    private static int cycleBoughtLines = 0;
    private static double cycleReceived = 0;
    private static int cycleSoldLines = 0;
    private static int cycleBought = 0;
    private static int cycleSold = 0;
    private static Double cyclePurseStart = null;
    /** Set by the NPC sell limit's chat line; read on the next tick. */
    private static volatile boolean npcLimitHit = false;
    /** What stopped the last session, for the testkit and the tab. */
    private static volatile String lastStopReason = "";

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
            com.killer560.hub.util.ChatObserver.subscribe(message ->
                    onChatLine(com.killer560.hub.util.ChatObserver.strip(message).trim()));
        }
    }

    /** One chat line, color-stripped. Every pattern here is anchored on the whole line. */
    static void onChatLine(String line) {
        if (line.startsWith("[Bazaar Flip]")) {
            return; // this feature's own lines
        }
        if (state == State.AUTOPILOT) {
            BazaarFlipAutopilot.onChatLine(line);
        }
        if (state == State.BAZAAR && CANNOT_AFFORD.matcher(line).matches()) {
            cannotAfford = true;
            return;
        }
        Matcher m = BOUGHT_LINE.matcher(line);
        if (m.matches()) {
            if (state != State.IDLE && target != null && m.group(2).equalsIgnoreCase(target.displayName())) {
                cycleSpent += parseCoins(m.group(3));
                cycleBoughtLines += (int) parseCoins(m.group(1));
            }
            return;
        }
        m = BazaarFlipNpcLimit.SOLD_LINE.matcher(line);
        if (m.matches()) {
            double coins = parseCoins(m.group(3));
            BazaarFlipNpcLimit.onSold(coins);
            if (state != State.IDLE && target != null && m.group(1).equalsIgnoreCase(target.displayName())) {
                cycleReceived += coins;
                cycleSoldLines += (int) parseCoins(m.group(2));
            }
            return;
        }
        if (BazaarFlipNpcLimit.LIMIT_LINE.matcher(line).matches()) {
            LOGGER.info("[BazaarFlip] NPC sell limit line: {}", line);
            BazaarFlipNpcLimit.markReached();
            if (state != State.IDLE) {
                npcLimitHit = true;
            }
        }
    }

    private static double parseCoins(String digits) {
        try {
            return Double.parseDouble(digits.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    /** Why the last session stopped ("" before any); the testkit reads it. */
    public static String lastStopReason() {
        return lastStopReason;
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
        if (!com.killer560.hub.bazaaradvisor.BazaarAdvisorConfig.answeredThisBoot()) {
            // Not answered since the game started: ask again rather than flip on answers from an earlier boot.
            client.execute(() -> com.killer560.hub.compat.McCompat.setScreen(client,
                    new com.killer560.hub.bazaaradvisor.BazaarAdvisorSetupScreen()));
            return "answer the advisor's questions first - they are asked again each time the game starts.";
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
        if (cfg.isAutopilot()) {
            if (com.killer560.hub.bazaaradvisor.BazaarAdvisorFeature.answers(
                    com.killer560.hub.bazaaradvisor.BazaarAdvisorConfig.getInstance()).npc()
                    && BazaarFlipNpcLimit.remaining() <= 0) {
                // Selling to the NPC is the plan and the NPCs take nothing more today: a session would only stop again.
                return npcLimitText();
            }
            String refusal = BazaarFlipAutopilot.begin(client, purse);
            if (refusal != null) {
                return refusal;
            }
            target = null;
            clicks = 0;
            cycles = 0;
            npcLimitHit = false;
            lastStopReason = "";
            startPurse = purse;
            startedAtMs = System.currentTimeMillis();
            startBindStillHeld = cfg.getStartKeyCode() != KeyUtil.NONE && client.getWindow() != null
                    && KeyUtil.isBindDown(client.getWindow(), cfg.getStartKeyCode());
            setState(State.AUTOPILOT);
            return null;
        }
        if (BazaarFlipNpcLimit.remaining() <= 0) {
            return npcLimitText();
        }
        BazaarFlipScanner.clear();
        target = null;
        clicks = 0;
        cycles = 0;
        npcLimitHit = false;
        lastStopReason = "";
        rejected.clear();
        lastScanAtMs = 0L;
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

    private static String npcLimitText() {
        return String.format(Locale.US, "the daily NPC sell limit (%,.0f coins a day) is reached - it resets at"
                + " 00:00 UTC, in %s", BazaarFlipNpcLimit.DAILY_LIMIT, BazaarFlipNpcLimit.resetsIn());
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
     * Every key and mouse press he makes, raw, before any screen sees it ({@code BazaarFlipStopKeyMixin},
     * {@code BazaarFlipStopMouseMixin}). A press while running stops the session. With no screen open the press then
     * does what he pressed it for; with a Bazaar or /trades menu open it is swallowed - a stray number key there is a
     * real hotbar-swap packet - except Escape, which still closes the menu; in the amount sign it is always swallowed,
     * because Escape there would send whatever the sign holds.
     *
     * @return true to swallow the press
     */
    public static boolean onRawKeyPress(int code) {
        if (state == State.IDLE || !stopOnKeyPress(code)) {
            return false;
        }
        Screen screen = McCompat.screen(Minecraft.getInstance());
        if (screen == null) {
            return false;
        }
        if (screen instanceof AbstractSignEditScreen) {
            return true;
        }
        return code != InputConstants.KEY_ESCAPE;
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
        // A key held down from before the start (a walk) stops it once the grace is over. Fresh presses anywhere are
        // the raw hook's (onRawKeyPress).
        if (McCompat.screen(client) == null && anyKeyDownInWorld(client)) {
            return;
        }
        int clickCap = state == State.AUTOPILOT ? MAX_AUTOPILOT_CLICKS : MAX_CLICKS_PER_SESSION;
        if (clicks >= clickCap) {
            stop("safety limit of " + clickCap + " clicks reached");
            return;
        }
        if (state == State.AUTOPILOT) {
            // The autopilot reads the NPC limit itself (BazaarFlipNpcLimit) and simply stops selling to the NPC.
            npcLimitHit = false;
            BazaarFlipAutopilot.tick(client, cfg);
            return;
        }
        if (npcLimitHit && state != State.LOG_CYCLE) {
            npcLimitHit = false;
            onNpcLimit(client);
            return;
        }
        Double purse = readPurse();
        if (purse != null && cfg.getTargetPurse() > 0 && purse >= cfg.getTargetPurse() && state == State.SCANNING) {
            finish(String.format(Locale.US, "target purse reached (%,.0f of %,d)", purse, cfg.getTargetPurse()));
            return;
        }
        // killer560: "then every minute during it" - while running only, in any state, so the book the NEXT
        // decision is made from is never a minute stale.
        if (!scanPending && purse != null && System.currentTimeMillis() - lastScanAtMs >= RESCAN_INTERVAL_MS) {
            beginScan(purse, "rescan");
        }
        switch (state) {
            case SCANNING -> tickScanning();
            case WAIT_BAZAAR_MENU -> tickWaitMenu(client, "the Bazaar menu", State.BAZAAR);
            case BAZAAR -> tickBazaar(client, cfg);
            case OPEN_TRADES -> tickOpenTrades();
            case WAIT_TRADES_MENU -> tickWaitMenu(client, "the /trades NPC menu", State.SELL);
            case SELL -> tickSell(client, cfg);
            case LOG_CYCLE -> tickLogCycle(client);
            case IDLE, AUTOPILOT -> {
            }
        }
    }

    private static void pollStartKey(Minecraft client, BazaarFlipConfig cfg) {
        // Remember when the last screen closed (killer560, 2026-10-18: the button only works a quarter of a second after
        // a menu closes) - the key that closed the menu must not also start the flip.
        boolean screenOpen = McCompat.screen(client) != null;
        if (screenWasOpen && !screenOpen) {
            menuClosedAtMs = System.currentTimeMillis();
        }
        screenWasOpen = screenOpen;
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
        boolean afterMenuDelay = System.currentTimeMillis() - menuClosedAtMs >= MENU_CLOSE_DELAY_MS;
        if (down && !keyWasDown && !screenOpen && afterMenuDelay && state == State.IDLE) {
            String refusal = start();
            if (refusal != null) {
                ModChat.send("Bazaar Flip", ModChat.bad("Can't start: "), ModChat.text(refusal));
            }
        }
        keyWasDown = down;
    }

    /** Any key or mouse button held, polled only when no screen is open. */
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
        BazaarFlipScanner.scanAsync(purse, cfg.getRankingMode(), BazaarFlipNpcLimit.remaining())
                .whenComplete((result, error) -> {
                    if (generation == scanGeneration) {
                        scanPending = false;
                    }
                    if (error != null) {
                        LOGGER.warn("[BazaarFlip] {} scan failed", why, error);
                    }
                });
    }

    /** The best candidate the live page has not already refused at this price or lower ({@link #fallThrough}). */
    static BazaarFlipCandidate pick(List<BazaarFlipCandidate> ranked) {
        for (BazaarFlipCandidate c : ranked) {
            Double refusedAt = rejected.get(c.productId());
            if (refusedAt == null || c.averageUnitCost() < refusedAt - 1e-6) {
                return c;
            }
        }
        return null;
    }

    private static void tickScanning() {
        if (scanPending) {
            return;
        }
        if (BazaarFlipNpcLimit.remaining() <= 0) {
            stop(npcLimitText());
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
        BazaarFlipCandidate best = pick(result.ranked());
        if (best == null) {
            // killer560: "if nothing is profitable have it not work". The purse was already passed into the
            // sizing, so "nothing profitable" already accounts for what he can afford.
            stop(rejected.isEmpty()
                    ? String.format(Locale.US, "nothing is profitable after fees right now (%d products with an NPC"
                    + " price checked against your purse and today's NPC sell limit)", result.productsWithNpcPrice())
                    : "nothing is profitable after fees right now - the live Bazaar priced " + rejected.size()
                    + " pick(s) out this session");
            return;
        }
        target = best;
        carriedAtStart = -1;
        topUpUnits = 0;
        singleBuys = 0;
        pendingBuy = false;
        cannotAfford = false;
        sellPendingSlot = -1;
        clickedContainerId = Integer.MIN_VALUE;
        seenContainerId = Integer.MIN_VALUE;
        custom = Custom.NONE;
        awaitingReopenSinceMs = 0L;
        cycleSpent = 0;
        cycleBoughtLines = 0;
        cycleReceived = 0;
        cycleSoldLines = 0;
        cycleBought = 0;
        cycleSold = 0;
        cyclePurseStart = readPurse();
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
    static boolean settled(AbstractContainerScreen<?> screen) {
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
    static boolean stillOnClickedPage(AbstractContainerScreen<?> screen) {
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

    /** Units of the product he carries, from his own 36 slots, whatever screen is (or is not) open. */
    private static int carried(Minecraft client) {
        if (client.player == null || target == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (BazaarFlipMenus.isProduct(stack, target.productId(), target.displayName())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void tickBazaar(Minecraft client, BazaarFlipConfig cfg) {
        long now = System.currentTimeMillis();
        Screen open = McCompat.screen(client);
        if (custom == Custom.AWAIT_SIGN) {
            tickAwaitSign(client, open, cfg, now);
            return;
        }
        if (pendingBuy) {
            int have = carried(client);
            if (have > pendingBuyCount) {
                pendingBuy = false;
                LOGGER.info("[BazaarFlip] Bought {} {} (now carrying {})", have - pendingBuyCount,
                        target.productId(), have);
                boolean wasCustom = custom == Custom.AFTER_SIGN;
                custom = Custom.NONE;
                if (wasCustom && (!(open instanceof AbstractContainerScreen<?>) || isConfirmPage(open))) {
                    // The items can land before the page the sign came from is shown again (testkit 2764 on the
                    // merged jar: 40 of 100 arrived with no menu up, and buying ended there). Wait for the page,
                    // bounded; only if no menu comes back is this cycle's buying over. A Confirm Instant Buy page left
                    // up after the buy is not a Bazaar page: it is closed here, and the same bounded wait applies
                    // (killer560, 2026-10-18: "Unexpected Bazaar page: Confirm Instant Buy" stopped a live run).
                    if (isConfirmPage(open) && client.player != null) {
                        client.player.closeContainer();
                    }
                    awaitingReopenSinceMs = now;
                    return;
                }
            } else if (cannotAfford) {
                pendingBuy = false;
                custom = Custom.NONE;
                LOGGER.info("[BazaarFlip] Hypixel said \"You cannot afford this!\" - selling what was bought");
                doneBuying("Hypixel said you can't afford more");
                return;
            } else if (now - pendingBuyAtMs > BUY_CONFIRM_TIMEOUT_MS) {
                stop("Hypixel never delivered the instant buy (nothing arrived in your inventory)");
                return;
            } else if (custom == Custom.AFTER_SIGN) {
                tickAfterSign(client, open, cfg, now);
                return;
            } else {
                return;
            }
        }
        if (awaitingReopenSinceMs > 0L) {
            if (open instanceof AbstractContainerScreen<?> && !isConfirmPage(open)) {
                awaitingReopenSinceMs = 0L; // the page is back: carry on buying from it
            } else if (open != null || now - awaitingReopenSinceMs > REOPEN_TIMEOUT_MS) {
                awaitingReopenSinceMs = 0L;
                if (reopenForTopUp(client)) {
                    return;
                }
                doneBuying("bought the custom amount; the Bazaar did not reopen");
                return;
            } else {
                return;
            }
        }
        if (!(open instanceof AbstractContainerScreen<?> screen)) {
            stop("the Bazaar menu closed");
            return;
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
            case INSTANT_BUY -> {
                // Hypixel cuts long titles ("Enchanted Pufferfish ➜ Instant"): the page must be THIS product's, cut
                // or not, or nothing is bought.
                if (!BazaarPages.isInstantBuyTitleFor(BazaarFlipMenus.titleOf(screen), name)) {
                    stop("the Instant Buy page is not " + name + "'s: " + BazaarFlipMenus.describe(screen));
                    return;
                }
                buyStep(client, screen, page, cfg);
            }
            default -> {
                LOGGER.info("[BazaarFlip] Unexpected Bazaar page: {}", BazaarFlipMenus.describe(screen));
                stop("unexpected menu " + BazaarFlipMenus.describe(screen));
            }
        }
    }

    /** One option on the Instant Buy page, as its lore states it. */
    private record Option(String name, int slot, long amount, double price) {
    }

    /**
     * One purchase on the "&lt;item&gt; ➜ Instant Buy" page (killer560, 2026-10-16: "it needs to click custom amount then
     * paste in the amount").
     *
     * <p>The amount is what is still wanted this cycle: the scan's profitable size, less what already arrived, capped
     * by free inventory space, by what the NPCs will still pay for today ({@link BazaarFlipNpcLimit}), and by Custom
     * Amount's own "Buy up to N x." line. Then:
     * <ul>
     *   <li>a preset (Buy only one! / Buy a stack! / Fill my inventory!) whose stated amount is EXACTLY that is
     *       clicked - one screen fewer and the same purchase;</li>
     *   <li>otherwise Custom Amount, and {@link #fillSign} types the number. Before that, the price is bounded from
     *       the presets: the book is eaten cheapest-first, so the average unit price of N units is at most that of any
     *       preset of N or more units. If that bound is not under the NPC price, Custom Amount is not used; if the
     *       purse cannot cover N at the bound, N shrinks to what it can;</li>
     *   <li>when Custom Amount cannot be used safely, the old rule is the fallback: the largest preset that fits
     *       everything above and whose own price is under the NPC's and within the purse.</li>
     * </ul>
     */
    private static void buyStep(Minecraft client, AbstractContainerScreen<?> screen, BazaarPages.Page page,
                                BazaarFlipConfig cfg) {
        priceRefused = false;
        var menu = screen.getMenu();
        int have = BazaarFlipMenus.countProduct(menu, target.productId(), target.displayName());
        if (carriedAtStart < 0) {
            carriedAtStart = have;
        }
        int bought = have - carriedAtStart;
        int stack = Math.max(1, target.stackSize());
        int room = BazaarFlipMenus.roomFor(menu, target.productId(), target.displayName(), stack);
        long sellable = (long) Math.floor(BazaarFlipNpcLimit.remaining() / target.npcSellPrice()) - bought;
        int remaining = target.units() + topUpUnits - bought;
        if (remaining <= 0 && room > 0 && sellable > 0) {
            // The planned units are in, but the purse and the free room may still take more of this product (killer560,
            // 2026-10-19: "it still doesnt buy every last item it could"). Top up by what they cover, then keep buying.
            long extra = topUpExtra(room, sellable, readPurse());
            if (extra >= 1) {
                topUpUnits += (int) extra;
                remaining = target.units() + topUpUnits - bought;
                LOGGER.info("[BazaarFlip] Topping up {} more {} (room {}, NPC room {})", extra, target.productId(), room, sellable);
            }
        }
        if (remaining <= 0 || room <= 0 || sellable <= 0) {
            doneBuying(remaining <= 0 ? "bought the planned " + target.units()
                    : room <= 0 ? "inventory full" : "the daily NPC sell limit has room for no more");
            return;
        }
        long want = Math.min(remaining, Math.min(room, sellable));
        BazaarPages.Item customItem = BazaarFlipMenus.item(page, "Custom Amount");
        long upTo = customItem == null ? -1 : loreBuyUpTo(customItem.lore());
        if (upTo > 0) {
            want = Math.min(want, upTo);
        }
        Double purse = readPurse();
        String[] names = {"Fill my inventory!", "Buy a stack!", "Buy only one!"};
        long[] fallbackAmount = {room, stack, 1};
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            BazaarPages.Item option = BazaarFlipMenus.item(page, names[i]);
            if (option == null) {
                continue;
            }
            long amount = BazaarFlipMenus.loreAmount(option.lore());
            options.add(new Option(names[i], option.slot(), amount > 0 ? amount : fallbackAmount[i],
                    BazaarFlipMenus.lorePrice(option.lore())));
        }
        // 1. A preset that is exactly the amount.
        for (Option o : options) {
            if (o.amount() == want && priceOk(o.price(), o.amount(), purse) == null) {
                if (o.name().equals("Buy only one!") && singleBuys >= MAX_SINGLE_BUYS) {
                    break;
                }
                clickBuy(client, screen, cfg, o.name(), o.slot(), o.amount(), o.price(), have, bought);
                return;
            }
        }
        // 2. Custom Amount, bounded by the presets' prices.
        String refused = null;
        if (customItem != null && want >= 1) {
            Option bound = null;
            for (Option o : options) {
                if (o.price() > 0 && o.amount() >= want && (bound == null || o.amount() < bound.amount())) {
                    bound = o;
                }
            }
            long n = want;
            if (bound != null) {
                double quoteEach = bound.price() / bound.amount();
                double each = bookPrice(quoteEach);
                if (each >= target.npcSellPrice()) {
                    refused = String.format(Locale.US, "the instant-buy price (up to %,.1f each, quoted %,.1f) is no"
                            + " longer below the NPC's %,.1f", each, quoteEach, target.npcSellPrice());
                    priceRefused = true;
                    n = 0;
                } else if (purse != null && n * quoteEach > purse) {
                    // Hypixel wants the whole quote in the purse before it runs the buy.
                    n = (long) Math.floor(purse / quoteEach);
                    if (n < 1) {
                        refused = "you can't afford even one";
                    }
                }
            }
            if (n >= 1) {
                if (!click(client, screen, customItem.slot(), ContainerInput.PICKUP, cfg, false)) {
                    return; // gate denied - retried next tick
                }
                custom = Custom.AWAIT_SIGN;
                customAmount = (int) n;
                customClickedContainerId = menu.containerId;
                customSinceMs = System.currentTimeMillis();
                signSeenAtMs = 0L;
                confirmClicked = false;
                pendingBuyCount = have;
                LOGGER.info("[BazaarFlip] Custom Amount for {} units (wanted {}, bought {} of {} so far, Buy up to {})",
                        n, want, bought, target.units(), upTo > 0 ? upTo : "not shown");
                return;
            }
        }
        // 3. Fallback: the largest preset that fits.
        for (Option o : options) {
            if (o.amount() > want) {
                continue;
            }
            if (o.name().equals("Buy only one!") && singleBuys >= MAX_SINGLE_BUYS) {
                continue;
            }
            String why = priceOk(o.price(), o.amount(), purse);
            if (why != null) {
                refused = why;
                continue;
            }
            clickBuy(client, screen, cfg, o.name(), o.slot(), o.amount(), o.price(), have, bought);
            return;
        }
        if (bought <= 0 && priceRefused) {
            // The live page says this flip no longer pays: try the next best instead of stopping (and never come back
            // to this product until a fresh scan prices it cheaper than now).
            fallThrough(refused);
            return;
        }
        doneBuying(refused != null ? refused : "nothing smaller left to buy");
    }

    /** The book price behind an Instant Buy page price: the page quotes the book plus {@code INSTANT_BUY_QUOTE_MARGIN}. */
    static double bookPrice(double quoted) {
        return quoted / (1 + BazaarFlipScanner.INSTANT_BUY_QUOTE_MARGIN);
    }

    /**
     * The live Instant Buy page refused the scan's pick before anything was bought: remember the product at the price
     * the scan had (a later scan only brings it back if it prices it LOWER), leave the Bazaar, rescan, and take the next
     * best (killer560, 2026-10-17: "It keeps going back to Enchanted Pufferfish").
     */
    private static void fallThrough(String why) {
        Minecraft client = Minecraft.getInstance();
        rejected.put(target.productId(), target.averageUnitCost());
        ModChat.log("Bazaar Flip", ModChat.text("Skipping " + target.displayName() + ": " + why + " - trying the next best"));
        LOGGER.info("[BazaarFlip] Skipping {} at {}/ea: {}", target.productId(),
                String.format(Locale.US, "%.1f", target.averageUnitCost()), why);
        custom = Custom.NONE;
        if (client.player != null && McCompat.screen(client) != null) {
            client.player.closeContainer();
        }
        target = null;
        Double purse = readPurse();
        setState(State.SCANNING);
        if (purse != null && !scanPending) {
            beginScan(purse, "after a refused price");
        }
    }

    /** Null when a preset's stated price (if any) is under the NPC's per unit and within the purse, else why not.
     *  {@code price} is the page's QUOTE (book + 4%): its book price is compared with the NPC, the quote with the purse. */
    private static String priceOk(double price, long amount, Double purse) {
        if (price <= 0) {
            return null;
        }
        if (bookPrice(price / amount) >= target.npcSellPrice()) {
            priceRefused = true;
            return String.format(Locale.US, "the instant-buy price (%,.1f each, quoted %,.1f) is no longer below the"
                    + " NPC's %,.1f", bookPrice(price / amount), price / amount, target.npcSellPrice());
        }
        if (purse != null && price > purse) {
            return "you can't afford " + amount;
        }
        return null;
    }

    private static void clickBuy(Minecraft client, AbstractContainerScreen<?> screen, BazaarFlipConfig cfg, String name,
                                 int slot, long amount, double price, int have, int bought) {
        if (!click(client, screen, slot, ContainerInput.PICKUP, cfg, false)) {
            return; // gate denied - retried next tick
        }
        if (name.equals("Buy only one!")) {
            singleBuys++;
        }
        pendingBuy = true;
        pendingBuyCount = have;
        pendingBuyAtMs = System.currentTimeMillis();
        cannotAfford = false;
        LOGGER.info("[BazaarFlip] {} ({} units, lore price {}) - bought {} of {} so far", name, amount,
                price > 0 ? String.format(Locale.US, "%.1f", price) : "not shown", bought, target.units());
    }

    /** "Buy up to 71,680x." off Custom Amount, or -1. */
    static long loreBuyUpTo(List<String> lore) {
        for (String line : lore) {
            Matcher m = BUY_UP_TO.matcher(line);
            if (m.matches()) {
                return Long.parseLong(m.group(1).replace(",", ""));
            }
        }
        return -1;
    }

    /** Custom Amount was clicked: the next thing up must be Hypixel's amount sign. */
    private static void tickAwaitSign(Minecraft client, Screen open, BazaarFlipConfig cfg, long now) {
        if (open instanceof AbstractSignEditScreen sign) {
            fillSign(sign, cfg, now);
            return;
        }
        if (open instanceof AbstractContainerScreen<?> screen && screen.getMenu().containerId != customClickedContainerId) {
            stop("Custom Amount opened " + BazaarFlipMenus.describe(screen) + " instead of the amount sign");
            return;
        }
        if (open != null && !(open instanceof AbstractContainerScreen<?>)) {
            stop("Custom Amount opened " + open.getClass().getSimpleName() + " instead of the amount sign");
            return;
        }
        if (now - customSinceMs > MENU_TIMEOUT_MS) {
            stop("the Custom Amount sign never opened");
        }
    }

    /** Whether the sign is Hypixel's amount prompt: carets under the input line, then "Enter amount". */
    static boolean isAmountSign(String[] lines) {
        return lines != null && lines.length == 4 && SIGN_CARETS.matcher(lines[1].trim()).matches()
                && lines[2].trim().equalsIgnoreCase("Enter amount");
    }

    /**
     * Types {@link #customAmount} on line 1 of the sign and presses Done, in one tick - so there is never a moment
     * with a number sitting in the sign that a stray Escape could send. The digits go to the screen's own
     * {@code charTyped}, as the keyboard would deliver them (identical on 26.1.2 and 26.2: {@code CharacterEvent(int)});
     * Done is the screen's own button, whose press closes the editor and makes its {@code removed()} send the
     * {@code ServerboundSignUpdatePacket} with the four lines. Waits the speed slider's pacing after the sign appears,
     * like any other step.
     */
    private static void fillSign(AbstractSignEditScreen sign, BazaarFlipConfig cfg, long now) {
        if (signSeenAtMs == 0L) {
            signSeenAtMs = now;
            nextActionAtMs = Math.max(nextActionAtMs, now + randomDelay(cfg));
            return;
        }
        if (now < nextActionAtMs) {
            return;
        }
        String[] lines = ((SignEditScreenAccessor) sign).killer560smod$getMessages();
        if (!isAmountSign(lines)) {
            stop("the sign that opened is not Hypixel's amount prompt: " + Arrays.toString(lines));
            return;
        }
        if (!lines[0].isEmpty()) {
            stop("the amount sign's first line already holds \"" + lines[0] + "\"");
            return;
        }
        Button done = null;
        for (GuiEventListener child : sign.children()) {
            if (child instanceof Button b && b.getMessage().getString().equals(CommonComponents.GUI_DONE.getString())) {
                done = b;
            }
        }
        if (done == null) {
            stop("the amount sign has no Done button");
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.BAZAAR_FLIP_CMD)) {
            return; // retried next tick
        }
        String text = Integer.toString(customAmount);
        for (int i = 0; i < text.length(); i++) {
            sign.charTyped(new CharacterEvent(text.charAt(i)));
        }
        String typed = ((SignEditScreenAccessor) sign).killer560smod$getMessages()[0];
        if (!text.equals(typed)) {
            for (int i = 0; i < typed.length(); i++) {
                sign.keyPressed(new KeyEvent(InputConstants.KEY_BACKSPACE, 0, 0));
            }
            stop("typing " + text + " into the amount sign left \"" + typed + "\" - nothing was sent");
            return;
        }
        done.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        LOGGER.info("[BazaarFlip] Typed {} into the amount sign and pressed Done", text);
        custom = Custom.AFTER_SIGN;
        pendingBuy = true;
        pendingBuyAtMs = now;
        cannotAfford = false;
        stateSinceMs = now;
        clicks++;
        nextActionAtMs = now + randomDelay(cfg);
    }

    /** The Confirm Instant Buy page, open at all (it is not a Bazaar page and is never dispatched as one). */
    private static boolean isConfirmPage(Screen open) {
        return open instanceof AbstractContainerScreen<?> c && CONFIRM_INSTANT_BUY.equals(BazaarFlipMenus.titleOf(c));
    }

    /** After Done: a Confirm Instant Buy page is confirmed (once, if it states the amount); anything else waits. */
    private static void tickAfterSign(Minecraft client, Screen open, BazaarFlipConfig cfg, long now) {
        if (open instanceof AbstractSignEditScreen) {
            stop("Hypixel asked for the amount again (it did not take " + customAmount + ")");
            return;
        }
        if (!(open instanceof AbstractContainerScreen<?> screen) || confirmClicked) {
            return; // the items, the closed menu, or the page after a confirm: the pendingBuy checks above decide
        }
        if (now < nextActionAtMs || !settled(screen)) {
            return;
        }
        String title = BazaarFlipMenus.titleOf(screen);
        if (title.equals(CONFIRM_INSTANT_BUY)) {
            confirmStep(client, screen, cfg);
            return;
        }
        BazaarPages.Page page = BazaarFlipMenus.page(screen);
        if (page.kind() != BazaarPages.Kind.INSTANT_BUY) {
            stop("after the amount sign Hypixel showed " + BazaarFlipMenus.describe(screen));
        }
        // Back on the Instant Buy page: the purchase lands (or times out) via the pendingBuy checks.
    }

    /**
     * The Confirm Instant Buy page. No source shows its layout, so nothing is clicked by position or by a guessed name:
     * the one item whose lore states "Amount: N" for exactly the typed amount, with a price under the NPC's per unit
     * and within the purse, is clicked; anything else stops with the page described.
     */
    private static void confirmStep(Minecraft client, AbstractContainerScreen<?> screen, BazaarFlipConfig cfg) {
        BazaarPages.Item match = null;
        int matches = 0;
        double price = -1;
        for (BazaarPages.Item it : confirmItems(screen)) {
            if (BazaarFlipMenus.loreAmount(it.lore()) == customAmount) {
                double p = BazaarFlipMenus.lorePrice(it.lore());
                if (p > 0) {
                    match = it;
                    price = p;
                    matches++;
                }
            }
        }
        if (matches != 1) {
            stop("couldn't find one confirm button for " + customAmount + " on " + BazaarFlipMenus.describe(screen));
            return;
        }
        String why = priceOk(price, customAmount, readPurse());
        if (why != null) {
            stop(why + " - not confirming");
            return;
        }
        // changesPage: nothing more is judged on this page; the purchase, a closed menu or the next page decides.
        if (click(client, screen, match.slot(), ContainerInput.PICKUP, cfg, true)) {
            confirmClicked = true;
            pendingBuyAtMs = System.currentTimeMillis();
            LOGGER.info("[BazaarFlip] Confirmed {} units for {} coins", customAmount, Math.round(price));
        }
    }

    private static List<BazaarPages.Item> confirmItems(AbstractContainerScreen<?> screen) {
        List<BazaarPages.Item> out = new ArrayList<>();
        for (Slot slot : BazaarFlipMenus.menuSlots(screen.getMenu())) {
            ItemStack stack = slot.getItem();
            String name = BazaarFlipMenus.nameOf(stack);
            if (stack.isEmpty() || name.isBlank()) {
                continue;
            }
            out.add(new BazaarPages.Item(slot.index, name,
                    com.killer560.hub.croesus.DungeonChestValuer.cleanLore(stack), false));
        }
        return out;
    }

    /** Buying is over for this cycle: sell what was bought, or stop if that was nothing. */
    private static void doneBuying(String why) {
        Minecraft client = Minecraft.getInstance();
        int bought = 0;
        if (target != null && carriedAtStart >= 0) {
            bought = carried(client) - carriedAtStart;
        }
        // Diagnostic (2026-10-18: flips ended with purse and inventory room left): the three limits at the moment buying stops.
        if (target != null && client.player != null) {
            LOGGER.info("[BazaarFlip] Buying ends ({}): bought {} of {}, purse {}, free room {}, NPC sell room {} coins",
                    why, bought, target.units(), readPurse(),
                    BazaarFlipMenus.roomFor(client.player.containerMenu, target.productId(), target.displayName(), Math.max(1, target.stackSize())),
                    BazaarFlipNpcLimit.remaining());
        }
        custom = Custom.NONE;
        if (bought <= 0) {
            stop("bought nothing - " + why);
            return;
        }
        cycleBought = bought;
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
                cycleSold += sellPendingCount;
                sellPendingSlot = -1;
            } else if (now - sellPendingAtMs > SELL_CONFIRM_TIMEOUT_MS) {
                if (BazaarFlipNpcLimit.remaining() < sellPendingCount * target.npcSellPrice()) {
                    BazaarFlipNpcLimit.markReached();
                    endCycleEarly(client, "the NPC didn't take the " + target.displayName() + " - " + npcLimitText());
                } else {
                    endCycleEarly(client, "the NPC didn't take the " + target.displayName()
                            + " (selling from /trades needs an active Booster Cookie, and the NPCs pay at most "
                            + String.format(Locale.US, "%,.0f", BazaarFlipNpcLimit.DAILY_LIMIT) + " coins a day)");
                }
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
            int count = slot.getItem().getCount();
            // A shift-click on his own stack sells it, the way any Hypixel shop takes items.
            if (click(client, screen, slot.index, ContainerInput.QUICK_MOVE, cfg, false)) {
                sellPendingSlot = slot.index;
                sellPendingCount = count;
                sellPendingAtMs = now;
                // Selling runs on its own pace: the sell slider, not the menu one (killer560, 2026-10-19).
                nextActionAtMs = now + randomSellDelay(cfg);
            }
            return;
        }
        LOGGER.info("[BazaarFlip] Sold every {}", target.productId());
        if (client.player != null) {
            client.player.closeContainer();
        }
        setState(State.LOG_CYCLE);
    }

    /** Every unit left for the NPC: log the cycle as soon as each "You sold" line is in (bounded). */
    private static void tickLogCycle(Minecraft client) {
        if (cycleSoldLines < cycleSold && System.currentTimeMillis() - stateSinceMs < SOLD_LINES_TIMEOUT_MS) {
            return;
        }
        cycles++;
        logCycle();
        ModChat.send("Bazaar Flip", ModChat.good("Sold. "), ModChat.dim("Cycle " + cycles + " done."));
        target = null;
        setState(State.SCANNING);
    }

    /**
     * The cycle's numbers to the profit tracker, from Hypixel's lines: spent = its "[Bazaar] Bought" coins, received =
     * its "You sold" coins. Units that were bought and not sold add their cost and no revenue. When a line was missed
     * (bought units with no Bought line), the log says so; the record still holds what Hypixel did report.
     */
    private static void logCycle() {
        if (target == null || (cycleBought <= 0 && cycleSpent <= 0)) {
            return;
        }
        Double purseNow = readPurse();
        double purseDelta = cyclePurseStart != null && purseNow != null ? purseNow - cyclePurseStart : Double.NaN;
        if (cycleBoughtLines != cycleBought || cycleSoldLines != cycleSold) {
            LOGGER.warn("[BazaarFlip] Cycle lines vs inventory: bought {} (lines {}), sold {} (lines {})", cycleBought,
                    cycleBoughtLines, cycleSold, cycleSoldLines);
        }
        double tax = cycleSpent * BazaarFlipScanner.INSTANT_BUY_TAX + cycleReceived * BazaarFlipScanner.NPC_SELL_FEE;
        BazaarFlipProfitLog.Cycle c = BazaarFlipProfitLog.record(target.productId(), target.displayName(),
                Math.max(cycleBought, cycleBoughtLines), cycleSoldLines > 0 ? cycleSoldLines : cycleSold, cycleSpent,
                cycleReceived, tax, purseDelta);
        LOGGER.info("[BazaarFlip] Logged cycle: spent {}, received {}, net {} (purse moved {})", Math.round(c.spent()),
                Math.round(c.received()), Math.round(c.net()), Double.isNaN(purseDelta) ? "?" : Math.round(purseDelta));
    }

    /** Selling stopped part-way (the NPC sell limit, a refused sale): log what was sold, then stop. */
    private static void endCycleEarly(Minecraft client, String why) {
        logCycle();
        if (client.player != null && McCompat.screen(client) instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
        }
        stop(why);
    }

    /** Hypixel said the daily NPC sell limit is reached: no more buying; whatever was bought stays unsold. */
    private static void onNpcLimit(Minecraft client) {
        if (state == State.SELL && sellPendingSlot >= 0) {
            sellPendingSlot = -1;
        }
        if (state == State.BAZAAR || state == State.OPEN_TRADES || state == State.WAIT_TRADES_MENU || state == State.SELL) {
            if (cycleBought <= 0 && target != null && carriedAtStart >= 0) {
                cycleBought = Math.max(0, carried(client) - carriedAtStart);
            }
            endCycleEarly(client, npcLimitText());
            return;
        }
        stop(npcLimitText());
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
    static boolean click(Minecraft client, AbstractContainerScreen<?> screen, int slotIndex,
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
    static boolean sendCommand(String command) {
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

    /** Pacing between two consecutive automated actions in the menus - the menu speed slider's only job. */
    static long randomDelay(BazaarFlipConfig cfg) {
        int lo = cfg.getActionDelayMinMs();
        int hi = Math.max(lo, cfg.getActionDelayMaxMs());
        return lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
    }

    /** Pacing between two consecutive sell shift-clicks - the sell speed slider's only job. */
    static long randomSellDelay(BazaarFlipConfig cfg) {
        int lo = cfg.getSellDelayMinMs();
        int hi = Math.max(lo, cfg.getSellDelayMaxMs());
        return lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
    }

    /** The Bazaar did not reopen after a Custom Amount buy. If the purse and free room still cover more of this cycle's
     *  product, sends {@code /bz <item>} again and keeps the cycle going (killer560, 2026-10-19: the cycle used to end
     *  here with 2m in the purse). Returns false when there is nothing more to buy or the command could not be sent. */
    private static boolean reopenForTopUp(Minecraft client) {
        if (target == null || carriedAtStart < 0 || client.player == null || McCompat.screen(client) != null) {
            return false;
        }
        int room = BazaarFlipMenus.roomFor(client.player.inventoryMenu, target.productId(), target.displayName(),
                Math.max(1, target.stackSize()));
        long sellable = (long) Math.floor(BazaarFlipNpcLimit.remaining() / target.npcSellPrice())
                - (carried(client) - carriedAtStart);
        if (topUpExtra(room, sellable, readPurse()) < 1) {
            return false;
        }
        if (!sendCommand("bz " + target.displayName())) {
            return false;
        }
        setState(State.WAIT_BAZAAR_MENU);
        LOGGER.info("[BazaarFlip] Bazaar did not reopen with {} units to top up - sent /bz again", target.productId());
        return true;
    }

    /** How many more units of this cycle's product the purse and the free room still cover, after the planned units
     *  are in: at the book price the scan priced it at (plus the quote margin), capped by inventory room and NPC room. */
    private static long topUpExtra(int room, long sellable, Double purse) {
        if (purse == null || target == null) {
            return 0;
        }
        double each = target.averageUnitCost() * (1 + BazaarFlipScanner.INSTANT_BUY_QUOTE_MARGIN);
        if (each <= 0) {
            return 0;
        }
        long byPurse = (long) Math.floor(purse / each);
        return Math.max(0, Math.min(byPurse, Math.min(room, sellable)));
    }

    private static void finish(String why) {
        Double purse = readPurse();
        ModChat.send("Bazaar Flip", ModChat.good("Done - "), ModChat.text(why),
                ModChat.dim(purse != null
                        ? String.format(Locale.US, " (%d cycle(s), purse %,.0f from %,.0f)", cycles, purse, startPurse)
                        : " (" + cycles + " cycle(s))"));
        LOGGER.info("[BazaarFlip] Finished: {} after {} cycle(s)", why, cycles);
        lastStopReason = why;
        reset();
    }

    private static void stop(String reason) {
        LOGGER.info("[BazaarFlip] Stopped: {}", reason);
        ModChat.send("Bazaar Flip", ModChat.bad("Stopped: "), ModChat.text(reason),
                ModChat.dim(" (" + cycles + " cycle(s))"));
        lastStopReason = reason;
        reset();
    }

    // ---- the autopilot's way in (BazaarFlipAutopilot shares this class's pacing, gate, click cap and stop) ----------

    /** Whether the pacing since the last automated action has passed. */
    static boolean paced() {
        return System.currentTimeMillis() >= nextActionAtMs;
    }

    /** Holds the next automated action back by at least {@code ms}. */
    static void delayNext(long ms) {
        nextActionAtMs = Math.max(nextActionAtMs, System.currentTimeMillis() + ms);
    }

    /** Forgets the page the last click was made on (after the autopilot closes a menu itself). */
    static void forgetPage() {
        clickedContainerId = Integer.MIN_VALUE;
        seenContainerId = Integer.MIN_VALUE;
    }

    static void stopFromAutopilot(String reason) {
        if (state == State.AUTOPILOT) {
            stop(reason);
        }
    }

    static void finishFromAutopilot(String why) {
        if (state == State.AUTOPILOT) {
            finish(why);
        }
    }

    private static void reset() {
        if (state == State.AUTOPILOT) {
            BazaarFlipAutopilot.reset();
        }
        state = State.IDLE;
        target = null;
        custom = Custom.NONE;
        pendingBuy = false;
        npcLimitHit = false;
        awaitingReopenSinceMs = 0L;
    }
}
