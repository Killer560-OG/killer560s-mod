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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static long stateSinceMs;
    private static long nextActionAtMs;
    private static int clicksSent;
    private static int attempted;
    /** Identities already clicked this session - tracked by IDENTITY, not slot: a shift-click that doesn't
     *  actually sell (a false-positive screen match) relocates the stack within the inventory rather than
     *  removing it, and tracking by slot index would then just find it again at its new position and click it
     *  forever. One attempt per identity per session is also a closer match to the real behaviour the forum
     *  research turned up ("sell every item in your inventory that is of the same block" off one shift-click). */
    private static final Set<String> handled = new HashSet<>();

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

    /** Starts a session against whatever screen is open RIGHT NOW - never opens or navigates to one itself.
     *  @return null on success, else the reason it refused (so the command can print it). */
    public static String start() {
        if (running) {
            return "already running - /autosell stop first.";
        }
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        if (!cfg.isEnabled()) {
            return "Auto Sell is OFF - turn it on in its tab first.";
        }
        if (cfg.getSellIdentities().isEmpty()) {
            return "your sell list is empty - /autosell add <item> first.";
        }
        Minecraft client = Minecraft.getInstance();
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
            return "no container screen is open.";
        }
        String title = titleOf(screen);
        Pattern pattern = compileOrDefault(cfg.getScreenTitlePattern());
        if (!pattern.matcher(title).matches()) {
            return "the open screen (\"" + title + "\") doesn't match the expected sell screen ("
                    + cfg.getScreenTitlePattern() + ") - /autosell screentitle to change it.";
        }
        handled.clear();
        clicksSent = 0;
        attempted = 0;
        running = true;
        stateSinceMs = System.currentTimeMillis();
        nextActionAtMs = stateSinceMs;
        return null;
    }

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
        if (clicksSent >= MAX_CLICKS_PER_SESSION) {
            stop("safety limit reached");
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextActionAtMs) {
            return;
        }
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        AbstractContainerMenu menu = screen.getMenu();
        for (Slot slot : playerSlots(menu)) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String identity = ItemIdentity.of(stack);
            if (identity == null || handled.contains(identity) || !isSellable(cfg, identity)) {
                continue;
            }
            if (!ActionGate.tryAct(ActionGate.Actor.AUTO_SELL, screen)) {
                return; // gate denied this tick - retry next tick, nothing marked handled
            }
            client.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, client.player);
            handled.add(identity);
            clicksSent++;
            attempted++;
            nextActionAtMs = now + randomDelay(cfg);
            return;
        }
        finish();
    }

    /** Sellable = explicitly on the sell list AND not on the never-sell list - both checked every time, no
     *  "sell everything not protected" fallback. */
    static boolean isSellable(AutoSellConfig cfg, String identity) {
        if (identity == null) {
            return false;
        }
        if (cfg.getNeverSellIdentities().stream().anyMatch(identity::equalsIgnoreCase)) {
            return false;
        }
        return cfg.getSellIdentities().stream().anyMatch(identity::equalsIgnoreCase);
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

    private static Pattern compileOrDefault(String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return Pattern.compile(AutoSellConfig.DEFAULT_SCREEN_TITLE_PATTERN);
        }
    }

    private static long randomDelay(AutoSellConfig cfg) {
        int lo = cfg.getMinDelayMs();
        int hi = Math.max(lo, cfg.getMaxDelayMs());
        return lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
    }

    private static void finish() {
        ModChat.send("AutoSell", ModChat.good("Done - "), ModChat.value(attempted + " item stack(s)"),
                ModChat.text(" clicked."));
        running = false;
    }

    private static void stop(String reason) {
        ModChat.send("AutoSell", ModChat.bad("Stopped: "), ModChat.text(reason),
                ModChat.dim(" (" + attempted + " item stack(s) clicked)"));
        running = false;
    }
}
