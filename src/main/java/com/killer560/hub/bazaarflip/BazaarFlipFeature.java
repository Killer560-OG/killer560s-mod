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
 * <h2>What actually clicks, today</h2>
 * <b>Read {@link #MENU_CLICKS_VERIFIED} before assuming this bot buys anything.</b> The scanner, the purse
 * handling, the failsafes, the pacing, the start/stop and the two command sends are real. The clicks inside
 * Hypixel's Bazaar product menu and inside the {@code /trades} NPC menu are NOT, because no layout for either
 * is documented and neither can be checked from the repository. Until that constant flips, the runner opens
 * each menu, prints and logs exactly what is in it, and stops cleanly. That dry run is what produces the real
 * button names.
 *
 * <h2>Safety rules this obeys</h2>
 * Ticks on {@code START_CLIENT_TICK}, never END: END runs after the player's own movement packet and GrimAC
 * flags every interaction that follows it as {@code Post}. Goes through {@link ActionGate}. Never writes
 * position or velocity, never sends fractional movement input, never presses a movement key at all - it does
 * not move the player.
 */
public final class BazaarFlipFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-bazaarflip");

    /**
     * <b>False, and it is meant to be false.</b> Flip this only after the Bazaar product menu's real
     * instant-buy flow and the {@code /trades} NPC menu's real sell flow have been observed in game and the
     * click steps in {@link #clickVerified} have been written against what was actually seen.
     *
     * <p>What has to be answered first, and what one dry run answers:
     * <ol>
     *   <li>What the instant-buy control in a Bazaar product menu is called today, and whether it leads to a
     *       fixed-quantity page, a "custom amount" sign input, or straight to a confirmation.</li>
     *   <li>How a quantity that is not one of Hypixel's presets is entered, and whether that is a sign screen
     *       (which is not a container screen and would need a different code path entirely).</li>
     *   <li>What the {@code /trades} menu offers: a shift-click sale out of the player's own inventory like
     *       Auto Sell assumes, a per-item button, or a single "sell inventory" control.</li>
     * </ol>
     * A confidently-written wrong click loop in a menu that spends hundreds of millions of coins is far worse
     * than a stub, so this stays false and the runner says so out loud every time it reaches a click step.
     */
    static final boolean MENU_CLICKS_VERIFIED = false;

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
        /** {@code /bz <item>} sent, waiting for Hypixel's product menu. */
        WAIT_BAZAAR_MENU,
        /** The product menu is open: buy step. */
        BUY,
        /** {@code /trades} sent, waiting for the NPC menu. */
        WAIT_TRADES_MENU,
        /** The NPC menu is open: sell step. */
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
            case WAIT_BAZAAR_MENU -> tickWaitMenu(client, "the Bazaar product menu", State.BUY);
            case BUY -> tickBuy(client, cfg);
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

    private static void tickWaitMenu(Minecraft client, String what, State next) {
        if (McCompat.screen(client) instanceof AbstractContainerScreen<?>) {
            setState(next);
            return;
        }
        if (System.currentTimeMillis() - stateSinceMs > MENU_TIMEOUT_MS) {
            stop(what + " never opened");
        }
    }

    private static void tickBuy(Minecraft client, BazaarFlipConfig cfg) {
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
            stop("the Bazaar menu closed");
            return;
        }
        if (System.currentTimeMillis() < nextActionAtMs) {
            return;
        }
        if (!MENU_CLICKS_VERIFIED) {
            reportUnverified("the Bazaar buy flow", screen);
            return;
        }
        // Everything below is unreachable until MENU_CLICKS_VERIFIED flips, and is left as the shape the real
        // steps go in rather than as a guess presented as working. The needles are the plausible names, NOT
        // observed ones - replace them with what the dry run actually printed.
        int buySlot = BazaarFlipMenus.findMenuSlot(screen.getMenu(), "instant buy", "buy instantly");
        if (buySlot < 0) {
            stop("no instant-buy control in " + BazaarFlipMenus.describe(screen));
            return;
        }
        // CLONE is what Auto Croesus uses for a plain left-click on a Hypixel menu button. Whether the buy
        // control is a plain click at all is itself unverified.
        clickVerified(client, screen, buySlot, ContainerInput.CLONE, cfg);
        // The quantity step, the confirmation step and the wait for the items to actually arrive all belong
        // here, and all three depend on facts the dry run has to supply first.
        setState(State.WAIT_TRADES_MENU);
    }

    private static void tickSell(Minecraft client, BazaarFlipConfig cfg) {
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
            stop("the /trades menu closed");
            return;
        }
        if (System.currentTimeMillis() < nextActionAtMs) {
            return;
        }
        if (!MENU_CLICKS_VERIFIED) {
            reportUnverified("the /trades sell flow", screen);
            return;
        }
        // Unreachable until the constant flips - see tickBuy.
        String productName = target != null ? target.displayName() : null;
        if (productName == null) {
            stop("lost track of what it was selling");
            return;
        }
        for (Slot slot : BazaarFlipMenus.playerSlots(screen.getMenu())) {
            if (slot.getItem().isEmpty()) {
                continue;
            }
            if (!BazaarFlipMenus.nameOf(slot.getItem()).toLowerCase(Locale.ROOT)
                    .contains(productName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            // QUICK_MOVE is a real shift-click, which is how Auto Sell believes an NPC sale works. Whether
            // /trades sells that way, or has its own button, is one of the things the dry run has to answer.
            clickVerified(client, screen, slot.index, ContainerInput.QUICK_MOVE, cfg);
            return;
        }
        cycles++;
        setState(State.SCANNING);
    }

    /**
     * Opens the menu, prints and logs exactly what is in it, and stops. This is the honest end of the flow
     * while {@link #MENU_CLICKS_VERIFIED} is false: it produces the real button names and the real layout,
     * which is precisely what the click steps need and what cannot be read out of this repository.
     */
    private static void reportUnverified(String what, AbstractContainerScreen<?> screen) {
        String contents = BazaarFlipMenus.describe(screen);
        LOGGER.info("[BazaarFlip] UNVERIFIED STEP ({}). Menu contents: {}", what, contents);
        ModChat.send("Bazaar Flip", ModChat.bad("Stopping before "), ModChat.text(what),
                ModChat.dim(" - its menu layout has never been verified, so nothing is clicked."));
        ModChat.send("Bazaar Flip", ModChat.dim("Menu was "), ModChat.text(contents));
        ModChat.send("Bazaar Flip", ModChat.dim("That line (also in the log) is what the click step needs."));
        state = State.IDLE;
        target = null;
    }

    /**
     * The one place an automated container click is sent. Goes through {@link ActionGate} as
     * {@link ActionGate.Actor#BAZAAR_FLIP_MENU}, on {@code START_CLIENT_TICK}, using the same real
     * {@code ContainerInput} mechanism Auto Croesus and Auto Sell use. Called only from the paths behind
     * {@link #MENU_CLICKS_VERIFIED}.
     */
    private static void clickVerified(Minecraft client, AbstractContainerScreen<?> screen, int slotIndex,
                                      ContainerInput input, BazaarFlipConfig cfg) {
        if (!ActionGate.tryAct(ActionGate.Actor.BAZAAR_FLIP_MENU, screen)) {
            return; // denied this tick - nothing marked done, retried next tick
        }
        client.gameMode.handleContainerInput(screen.getMenu().containerId, slotIndex, 0,
                input, client.player);
        clicks++;
        nextActionAtMs = System.currentTimeMillis() + randomDelay(cfg);
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
            String raw = ScoreboardData.group(ScoreboardPattern.COINS, ScoreboardData.sidebar(), "coins");
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
