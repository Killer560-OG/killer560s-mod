package com.killer560.hub.autosell;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import com.killer560.hub.compat.McCompat;

/**
 * Auto Sell (killer560: "add an auto sell feature you can refrence quoi for it").
 * <p>
 * <b>What QUOI actually has</b> - read in full before anything here was written, at
 * {@code quoi-1.1.0/src/main/kotlin/quoi/module/impl/**} (every {@code .kt} file under {@code dungeon/},
 * {@code mining/}, {@code misc/}, {@code player/} and {@code render/}): <b>there is no auto-sell module in that
 * source at all.</b> A {@code grep -ri sell} across the whole tree turns up exactly two incidental hits, neither
 * of them a sell feature: {@code dungeon/InventoryWalk.kt}'s {@code Blacklist} switch, whose description just
 * happens to mention "Stops movement in sell guis + terminals" (Inventory Walk is a movement-in-containers
 * feature, not a seller), and {@code misc/ChatReplacements.kt}, which rewrites the NPC broadcast chat line
 * "Selling multiple items for a limited time!" (a chat-spam filter, not a click automation). So this feature is
 * NOT a port of anything QUOI does - there is nothing there to port. It follows this mod's OWN established
 * screen-clicking shape instead ({@code croesus/AutoCroesusFeature}: an explicit start, one state machine tick,
 * one real {@code ContainerInput} click per tick through {@link ActionGate}, stop on any unexpected screen).
 * <p>
 * <b>Real Hypixel Skyblock mechanic, as best documented</b> (Hypixel's own forums; nothing pins down today's
 * exact screen title, so this does not guess one): selling to an NPC is a real vanilla SHIFT-click on the item
 * inside your OWN inventory, which Hypixel's server recognizes and turns into a sale - not a special new click
 * type invented here, the exact same real {@code ContainerInput.QUICK_MOVE} this codebase's own comments already
 * document ("{@code AbstractContainerScreen} sends a {@code ContainerInput.QUICK_MOVE} action whenever Shift is
 * held" - see {@code AutoCroesusFeature}). That is also why a false positive here is not destructive: if the
 * screen that matched isn't really in a sell context, a real vanilla shift-click just reorganizes the item
 * within the inventory instead of selling it - nothing is thrown away by a misfire.
 * <p>
 * <b>The one thing this can't get from a real source</b> is today's exact sell-screen title, so
 * {@link AutoSellConfig#getScreenTitlePattern()} (default {@code (?i).*sell.*}) is what {@link #start} checks the
 * currently open screen's title against - "refuse to run if the expected screen is not the one open" (the task's
 * own safety rule) means refusing here rather than inventing a title and hoping it matches killer560's real
 * client. Tune the pattern with {@code /autosell screentitle <regex>} if his real client's title doesn't contain
 * "sell".
 * <p>
 * <b>Item selection</b>: never fires on anything whose {@code ItemIdentity} isn't explicitly on
 * {@link AutoSellConfig#getSellIdentities()} (no "sell everything else" fallback - "when unsure whether an item
 * is sellable, do not sell it"), and {@link AutoSellConfig#getNeverSellIdentities()} always wins if an id is
 * somehow on both lists. Only the player's own real inventory portion of the open screen is ever touched (the
 * last 36 real container slots - real main storage then real hotbar, the same real vanilla ordering every
 * container appends the player's inventory in), never any custom slot the NPC's own menu might also show.
 */
public final class AutoSellFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-autosell");

    private static final int MAX_CLICKS_PER_SESSION = 200;

    private static boolean running = false;
    /** The sell screen the current session runs on, and the one the last session ended on. A session does not start
     *  again on {@link #doneScreen} unless the sell lists changed since it ended ({@link #doneListVersion}). */
    private static AbstractContainerScreen<?> sessionScreen;
    private static AbstractContainerScreen<?> doneScreen;
    private static int doneListVersion;
    private static long stateSinceMs;
    /** Client ticks left before the next sell click may go out (see {@link #randomTicks}). */
    private static int waitTicks;
    private static int clicksSent;
    private static int attempted;
    /** Ticks since the session started; the clock {@link AutoSellVisit} times the server's answers against. */
    private static int tickNo;
    /** True once nothing is left to click and the session only waits for the server to answer the last clicks
     *  (no click goes out while settling, so this adds no delay between sells). */
    private static boolean settling;
    /** The visit to {@link #visitScreen}, which tracks clicks per SLOT (see {@link AutoSellVisit}). Kept when a
     *  session restarts on the same screen after a list change, so a slot already clicked there is not clicked twice
     *  and an item added since the visit began is named in the log. */
    private static AutoSellVisit visit;
    private static AbstractContainerScreen<?> visitScreen;
    private static List<AutoSellVisit.Stack> lastStacks = List.of();

    private AutoSellFeature() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoSellFeature.tick", AutoSellFeature::tick));
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            if (running) {
                stop("world changed");
            }
        });
    }

    public static boolean isRunning() {
        return running;
    }

    /** The last screen object looked at, so its label is built once per screen and not every tick. */
    private static Object lastSeenObject;
    /** The screen whose title the sell pattern refused, and the pattern that refused it. */
    private static AbstractContainerScreen<?> refusedScreen;
    private static String refusedPattern;
    /** The last screen Auto Sell logged as open, so the log names each one once. */
    private static String lastSeenScreen = "";
    /** The last container title Auto Sell refused, so the log names it once. */
    private static String lastRefusedTitle = "";

    private static void autoStart(Minecraft client) {
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        if (!cfg.isEnabled() || cfg.getSellIdentities().isEmpty()) {
            return;
        }
        // killer560, 2026-10-19: /trades still never sold. Say what screen is open, once per class and title, so the log
        // shows whether it is a container at all and what it is called.
        Object open = McCompat.screen(client);
        if (open == null) {
            lastSeenObject = null;
            refusedScreen = null;
            return;
        }
        // The same screen object is open for many ticks: work out its label and title match once, not every tick.
        if (open != lastSeenObject) {
            lastSeenObject = open;
            String shown = open.getClass().getName() + " \"" + (open instanceof net.minecraft.client.gui.screens.Screen sc
                    ? ChatFormatting.stripFormatting(sc.getTitle().getString()).trim() : "") + "\"";
            if (!shown.equals(lastSeenScreen)) {
                lastSeenScreen = shown;
                LOGGER.info("[AutoSell] screen open: {} (container={})", shown, open instanceof AbstractContainerScreen<?>);
            }
        }
        if (!(open instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (screen == refusedScreen && cfg.getScreenTitlePattern().equals(refusedPattern)) {
            return;
        }
        // killer560, 2026-10-20: an item added to the list while the Trades menu is open is sold in that menu
        // without reopening it, so a finished screen starts again once either list has changed.
        boolean again = screen == doneScreen;
        if (again && cfg.getListVersion() == doneListVersion) {
            return;
        }
        String title = titleOf(screen);
        if (!compileOrDefault(cfg.getScreenTitlePattern()).matcher(title).matches()) {
            // killer560, 2026-10-19: the /trades menu was never sold to. Said once per title, so the real one shows in the log.
            refusedScreen = screen;
            refusedPattern = cfg.getScreenTitlePattern();
            if (!title.equals(lastRefusedTitle)) {
                lastRefusedTitle = title;
                LOGGER.info("[AutoSell] screen title \"{}\" does not match the sell pattern '{}' - not selling in it",
                        title, cfg.getScreenTitlePattern());
            }
            return;
        }
        if (visit == null || visitScreen != screen) {
            visit = new AutoSellVisit(cfg.getSellIdentities());
            visitScreen = screen;
            tickNo = 0;
        }
        if (again) {
            LOGGER.info("[AutoSell] sell list changed while \"{}\" is open - selling again in it", titleOf(screen));
        }
        clicksSent = 0;
        attempted = 0;
        settling = false;
        running = true;
        sessionScreen = screen;
        stateSinceMs = System.currentTimeMillis();
        waitTicks = 0;
    }

    /** Auto Sell runs by itself on any sell screen while it is on - there is no start or stop command (killer560,
     *  2026-10-19). A session that has finished or stopped on a screen starts again on that same screen only when a
     *  sell or never-sell list has changed since. */
    public static void requestStop() {
        if (running) {
            stop("stopped by user");
        }
    }

    private static void tick(Minecraft client) {
        try {
            tickUnsafe(client);
        } catch (Exception e) {
            LOGGER.error("[AutoSell] tick failed - stopping", e);
            if (running) {
                stop("internal error (see log)");
            }
        }
    }

    private static void tickUnsafe(Minecraft client) {
        if (!running) {
            autoStart(client);
            return;
        }
        if (!AutoSellConfig.getInstance().isEnabled()) {
            stop("Auto Sell was turned off");
            return;
        }
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
            stop("screen closed");
            return;
        }
        String title = titleOf(screen);
        Pattern pattern = compileOrDefault(AutoSellConfig.getInstance().getScreenTitlePattern());
        if (!pattern.matcher(title).matches()) {
            stop("screen changed to \"" + title + "\"");
            return;
        }
        tickNo++;
        AbstractContainerMenu menu = screen.getMenu();
        visit.observe(menu.getStateId(), tickNo);
        List<Slot> slots = playerSlots(menu);
        lastStacks = stacksOf(slots);
        if (settling) {
            if (visit.next(lastStacks, lists(AutoSellConfig.getInstance())) != null) {
                settling = false; // an item was added while waiting for the answers: back to clicking
            } else {
                if (visit.settled(tickNo)) {
                    finish();
                }
                return;
            }
        }
        if (clicksSent >= MAX_CLICKS_PER_SESSION) {
            stop("safety limit reached");
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        // The lists are read afresh on every pass, so an item added while this screen is open is picked up here.
        AutoSellVisit.Stack next = visit.next(lastStacks, lists(cfg));
        if (next == null) {
            settling = true;
            if (visit.settled(tickNo)) {
                finish();
            }
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.AUTO_SELL, screen)) {
            return; // gate denied this tick - retry next tick, nothing marked clicked
        }
        if (visit.addedMidVisit(next.identity())) {
            LOGGER.info("[AutoSell] picked up mid-visit: {} x{} in slot {} (added to the sell list while this screen was open)",
                    next.identity(), next.count(), next.slot());
        }
        int stateBefore = menu.getStateId();
        client.gameMode.handleContainerInput(menu.containerId, next.slot(), 0, ContainerInput.QUICK_MOVE, client.player);
        visit.clicked(next, stateBefore, tickNo);
        clicksSent++;
        attempted++;
        waitTicks = randomTicks(cfg);
    }

    private static List<AutoSellVisit.Stack> stacksOf(List<Slot> slots) {
        List<AutoSellVisit.Stack> out = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                out.add(new AutoSellVisit.Stack(slot.index, ItemIdentity.of(stack), stack.getCount()));
            }
        }
        return out;
    }

    /** Sellable = explicitly on the sell list AND not on the never-sell list - both read live on every pass, no
     *  "sell everything not protected" fallback (see {@link AutoSellVisit#sellable}). */
    private static AutoSellVisit.Lists lists(AutoSellConfig cfg) {
        return new AutoSellVisit.Lists() {
            @Override
            public boolean onSell(String identity) {
                return cfg.getSellIdentities().stream().anyMatch(identity::equalsIgnoreCase);
            }

            @Override
            public boolean onNever(String identity) {
                return cfg.getNeverSellIdentities().stream().anyMatch(identity::equalsIgnoreCase);
            }
        };
    }

    /** The real container slots that are the player's OWN inventory - the last 36 real slots of whatever menu
     *  is open, real main storage (27) then real hotbar (9), the same real order every vanilla container
     *  appends them in. Never the NPC's own custom slots ahead of them. */
    private static List<Slot> playerSlots(AbstractContainerMenu menu) {
        int total = menu.slots.size();
        int start = Math.max(0, total - 36);
        return menu.slots.subList(start, total);
    }

    private static String titleOf(AbstractContainerScreen<?> screen) {
        return ChatFormatting.stripFormatting(screen.getTitle().getString()).trim();
    }

    private static String compiledSource;
    private static Pattern compiled;

    /** The compiled pattern, kept until the setting changes (it is asked for every tick a container is open). */
    private static Pattern compileOrDefault(String pattern) {
        if (pattern.equals(compiledSource)) {
            return compiled;
        }
        Pattern p;
        try {
            p = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            p = Pattern.compile(AutoSellConfig.DEFAULT_SCREEN_TITLE_PATTERN);
        }
        compiled = p;
        compiledSource = pattern;
        return p;
    }

    /** Client ticks to wait before the next sell click: uniform from the configured min to max, both 1..10 (never 0). */
    private static int randomTicks(AutoSellConfig cfg) {
        int lo = cfg.getMinTicks();
        int hi = Math.max(lo, cfg.getMaxTicks());
        return lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
    }

    private static void finish() {
        ModChat.send("AutoSell", ModChat.good("Done - "), ModChat.value(attempted + " item stack(s)"),
                ModChat.text(" clicked."));
        end();
    }

    private static void stop(String reason) {
        ModChat.send("AutoSell", ModChat.bad("Stopped: "), ModChat.text(reason),
                ModChat.dim(" (" + attempted + " item stack(s) clicked)"));
        end();
    }

    /** Logs every stack the visit left unsold, with the reason, from the last scan of the inventory. */
    private static void end() {
        if (visit != null) {
            for (String line : visit.report(lastStacks, lists(AutoSellConfig.getInstance()), tickNo)) {
                LOGGER.info("[AutoSell] {}", line);
            }
        }
        doneScreen = sessionScreen;
        doneListVersion = AutoSellConfig.getInstance().getListVersion();
        running = false;
        settling = false;
    }
}
