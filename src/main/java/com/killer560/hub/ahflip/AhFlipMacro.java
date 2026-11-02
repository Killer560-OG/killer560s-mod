package com.killer560.hub.ahflip;

import com.killer560.hub.bazaarflip.BazaarFlipMenus;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ServerCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The AH Flipper's Main mode (cheat build only, off by default): buys each flip itself (killer560, 2026-11-01: "Make one that
 * is a macro"; owner decision the same day: the macro is cheat-only and off by default).
 *
 * <p>One flip at a time, only while no screen is open and outside a dungeon, so it never takes a menu out from under him:
 * {@code /viewauction <id>}, then "Buy Item Right Now" on Hypixel's "BIN Auction View", then "Confirm" on "Confirm Purchase"
 * (names as SkyHanni matches them; the slots are found by name, never by number), and it waits for Hypixel's
 * "You purchased NAME for N coins!" (his Dungeons logs, 2026-10). A page it does not know, a missing button or a timeout
 * closes the menu and drops that flip. Every command and click goes through {@link ActionGate} on START_CLIENT_TICK, with a
 * random 250-500 ms between steps. It stops buying after Max Buys Per Session, after three failed buys in a row, and when
 * Main mode or the flipper is turned off.
 *
 * <p>Relisting is not automated yet: claiming the item and Hypixel's Create Auction flow have no recorded menu dump in this
 * repo, and a macro that guesses menus is not shipped. After a buy, the relist price is left with the AH Price Copier, so
 * putting the item up copies it.
 */
public final class AhFlipMacro {

    private static final Logger LOGGER = ModLog.get("killer560smod-ahflip");
    static final String CHAT = "AH Flipper";

    public static final String VIEW_TITLE = "BIN Auction View";
    public static final String CONFIRM_TITLE = "Confirm Purchase";
    public static final String BUY_BUTTON = "Buy Item Right Now";
    public static final String CONFIRM_BUTTON = "Confirm";
    /** "You purchased Travel Scroll to the Dwarven Forge for 450,000 coins!" - his Dungeons logs. Anchored; bounded. */
    public static final Pattern PURCHASED_LINE = Pattern.compile("^You purchased (.{1,80}) for ([\\d,]{1,15}) coins!$");
    static final long STEP_TIMEOUT_MS = 5_000L;
    static final long SETTLE_MS = 250L;
    static final int MAX_FAILS_IN_A_ROW = 3;

    private enum Step { IDLE, VIEW, CONFIRM, RESULT }

    private static final ArrayDeque<AhFlipper.Found> QUEUE = new ArrayDeque<>();
    private static Step step = Step.IDLE;
    private static AhFlipper.Found current;
    private static long stepSinceMs;
    private static long nextActionAtMs;
    private static int seenContainer = Integer.MIN_VALUE;
    private static String seenSignature = "";
    private static long seenSinceMs;
    private static int clickedContainer = Integer.MIN_VALUE;
    private static int buys;
    private static int failsInARow;
    private static boolean halted;
    private static String haltReason = "";
    private static volatile String purchasedLine;

    private AhFlipMacro() {
    }

    static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientTickEvents.START_CLIENT_TICK.register(com.killer560.hub.util.FeatureGuard.start("AhFlipMacro.tick",
                AhFlipMacro::tick));
        com.killer560.hub.util.ChatObserver.subscribe(message -> {
            String line = com.killer560.hub.util.ChatObserver.strip(message).trim();
            if (PURCHASED_LINE.matcher(line).matches()) {
                purchasedLine = line;
            }
        });
    }

    /** A flip for the macro (the flipper calls it only while {@link AhFlipConfig#isMacroActive}). */
    static void offer(AhFlipper.Found f) {
        if (halted || QUEUE.size() >= 10) {
            return;
        }
        QUEUE.addLast(f);
    }

    private static void tick(Minecraft client) {
        AhFlipConfig cfg = AhFlipConfig.getInstance();
        if (!cfg.isMacroActive() || client.player == null) {
            if (step != Step.IDLE) {
                drop(client, "Main mode was turned off");
            }
            QUEUE.clear();
            halted = false;
            buys = 0;
            failsInARow = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextActionAtMs) {
            return;
        }
        Screen screen = McCompat.screen(client);
        switch (step) {
            case IDLE -> start(client, cfg, screen, now);
            case VIEW -> onPage(client, screen, VIEW_TITLE, BUY_BUTTON, Step.CONFIRM, now);
            case CONFIRM -> onPage(client, screen, CONFIRM_TITLE, CONFIRM_BUTTON, Step.RESULT, now);
            case RESULT -> awaitResult(client, cfg, now);
        }
    }

    private static void start(Minecraft client, AhFlipConfig cfg, Screen screen, long now) {
        if (halted || QUEUE.isEmpty()) {
            return;
        }
        if (screen != null || com.killer560.hub.secrets.DungeonState.isInDungeon()) {
            return; // never over a menu he has open, never in a run
        }
        AhFlipper.Found f = QUEUE.peekFirst();
        if (now - f.atMs() > AhFlipper.FLIP_MAX_AGE_MS) {
            QUEUE.pollFirst();
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.AH_FLIP_CMD)) {
            return; // retried next tick
        }
        QUEUE.pollFirst();
        String id = AhFlipper.undashed(f.flip().uuid());
        if (!ServerCommands.toServer("viewauction " + id)) {
            fail(client, "couldn't send /viewauction");
            return;
        }
        current = f;
        purchasedLine = null;
        step = Step.VIEW;
        stepSinceMs = now;
        clickedContainer = Integer.MIN_VALUE;
        pace(now);
        LOGGER.info("[AhFlip] Macro: /viewauction {} ({} for {})", id, f.flip().name(), f.flip().price());
    }

    /** On {@code title}, settled: click the slot named {@code button}, then wait for {@code next}. */
    private static void onPage(Minecraft client, Screen screen, String title, String button, Step next, long now) {
        if (now - stepSinceMs > STEP_TIMEOUT_MS) {
            fail(client, "\"" + title + "\" never came up");
            return;
        }
        if (!(screen instanceof AbstractContainerScreen<?> c)) {
            return; // between pages
        }
        if (c.getMenu().containerId == clickedContainer) {
            return; // the page just clicked, still up while the server answers
        }
        if (!settled(c, now)) {
            return;
        }
        String shown = BazaarFlipMenus.titleOf(c);
        if (!title.equals(shown)) {
            fail(client, "an unexpected menu opened: \"" + shown + "\"");
            return;
        }
        Slot target = null;
        for (Slot slot : BazaarFlipMenus.menuSlots(c.getMenu())) {
            if (button.equals(BazaarFlipMenus.nameOf(slot.getItem()))) {
                target = slot;
                break;
            }
        }
        if (target == null) {
            // Sold already, his own auction, or not enough coins: Hypixel shows no buy button. Not a failure of the macro.
            drop(client, "no \"" + button + "\" on " + BazaarFlipMenus.describe(c));
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.AH_FLIP_MENU, c)) {
            return;
        }
        client.gameMode.handleContainerInput(c.getMenu().containerId, target.index, 0, ContainerInput.PICKUP, client.player);
        LOGGER.info("[AhFlip] Macro: clicked {} (slot {}) on \"{}\"", button, target.index, shown);
        clickedContainer = c.getMenu().containerId;
        step = next;
        stepSinceMs = now;
        pace(now);
    }

    private static void awaitResult(Minecraft client, AhFlipConfig cfg, long now) {
        String line = purchasedLine;
        if (line != null) {
            purchasedLine = null;
            Matcher m = PURCHASED_LINE.matcher(line);
            long paid = current.flip().price();
            if (m.matches()) {
                try {
                    paid = Long.parseLong(m.group(2).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    // bounded: cannot overflow
                }
            }
            buys++;
            failsInARow = 0;
            AhFlipPricing.Flip f = current.flip();
            AhPriceCopier.remember(f.key(), f.sellAt());
            ModChat.send(CHAT, ModChat.good("Bought "), ModChat.value(f.name()), ModChat.text(" for "),
                    ModChat.value(DungeonChestValuer.formatCoins(paid)),
                    ModChat.dim(String.format(Locale.US, " - relist at %s (the AH Price Copier will copy it). Buy %d of %d.",
                            DungeonChestValuer.formatCoins(f.sellAt()), buys, cfg.getMacroMaxBuys())));
            LOGGER.info("[AhFlip] Macro bought {} for {} (relist at {})", f.name(), paid, f.sellAt());
            closeIfOpen(client);
            reset();
            if (buys >= cfg.getMacroMaxBuys()) {
                halt("bought " + buys + " (Max Buys Per Session)");
            }
            return;
        }
        if (now - stepSinceMs > STEP_TIMEOUT_MS) {
            fail(client, "Hypixel never said the purchase went through");
        }
    }

    private static boolean settled(AbstractContainerScreen<?> c, long now) {
        String sig = BazaarFlipMenus.signature(c.getMenu());
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

    /** The flip is gone (sold, his own): not counted against the macro. */
    private static void drop(Minecraft client, String why) {
        LOGGER.info("[AhFlip] Macro dropped {}: {}", current == null ? "?" : current.flip().name(), why);
        if (current != null) {
            ModChat.log(CHAT, ModChat.dim("Skipped " + current.flip().name() + ": " + why));
        }
        closeIfOpen(client);
        reset();
    }

    private static void fail(Minecraft client, String why) {
        failsInARow++;
        LOGGER.info("[AhFlip] Macro failed on {}: {} ({} in a row)", current == null ? "?" : current.flip().name(), why,
                failsInARow);
        ModChat.send(CHAT, ModChat.bad("Couldn't buy " + (current == null ? "it" : current.flip().name()) + ": "),
                ModChat.text(why));
        closeIfOpen(client);
        reset();
        if (failsInARow >= MAX_FAILS_IN_A_ROW) {
            halt(failsInARow + " failed buys in a row");
        }
    }

    private static void halt(String why) {
        halted = true;
        haltReason = why;
        QUEUE.clear();
        ModChat.send(CHAT, ModChat.text("Macro stopped buying: " + why + ". "),
                ModChat.dim("Flips still show in chat; switch Run Mode off Main and back to buy again."));
    }

    private static void closeIfOpen(Minecraft client) {
        if (client.player != null && McCompat.screen(client) instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
        }
    }

    private static void reset() {
        step = Step.IDLE;
        current = null;
        clickedContainer = Integer.MIN_VALUE;
    }

    /** Testkit: buys so far this session, whether it halted, and why. */
    public static String statusForTest() {
        return buys + "|" + halted + "|" + haltReason + "|" + step;
    }
}
