package com.killer560.hub.croesus;

import com.killer560.hub.croesus.DungeonChestValuer.ChestType;
import com.killer560.hub.croesus.DungeonChestValuer.ChestValue;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Croesus (CHEAT BUILD ONLY - {@link CroesusConfig#isAutoCroesusEnabled()} is hard-gated on
 * {@code BuildVariant.CHEAT_FEATURES_ENABLED}). Starts when the player themselves opens the Croesus menu,
 * then walks every run whose head says "No chests opened yet!", opens its run view, and claims the single
 * most profitable chest if its profit is at least the configured minimum (no Kismet rerolls, no Dungeon
 * Chest Keys). Runs below the minimum are skipped (left unclaimed).
 *
 * <p>Menu layout facts, all from quoi AutoCroesus.kt (a port of UnclaimedBloom6's AutoCroesus): run heads
 * live in slots 10-16/19-25/28-34/37-43 and are PLAYER_HEADs; next page is an ARROW in slot 53; a run view's
 * chests are clicked by slot; the open-chest button in a chest screen is slot 31; after a claim the menu may
 * close, in which case Croesus is re-opened by right-clicking the same NPC again. Clicks use the same
 * {@code handleContainerInput(..., ContainerInput.CLONE, ...)} mechanism as ExperimentsFeature, each after a
 * random human-ish delay between the configured min/max. Any screen that isn't the one expected next stops
 * the run immediately (closing the menu yourself is the stop button).
 */
public final class AutoCroesusFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-croesus");

    private static final int[] RUN_SLOTS = {
            10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43
    };
    private static final int NEXT_PAGE_SLOT = 53;
    private static final long TRANSITION_TIMEOUT_MS = 4000L;
    private static final long ENTITY_BIND_WINDOW_MS = 3000L;
    private static final int MAX_CLICKS_PER_SESSION = 400;
    private static final Pattern PAGE_TITLE = Pattern.compile("^\\((\\d+)/(\\d+)\\) Croesus$");

    private enum State { IDLE, MENU, WAIT_RUN_VIEW, RUN_VIEW, WAIT_CHEST, CHEST, WAIT_AFTER_CLAIM, WAIT_BACK_TO_MENU, WAIT_REOPEN }

    private static State state = State.IDLE;
    private static long stateSinceMs = 0L;
    private static long nextActionAtMs = 0L;
    private static boolean suppressUntilClosed = false;

    private static Entity lastUsedEntity = null;
    private static long lastUsedEntityAtMs = 0L;
    private static Entity croesusEntity = null;
    private static boolean reopenSent = false;

    private static final Set<String> skippedRuns = new HashSet<>();
    private static String currentRunKey = null;
    private static ChestType targetType = null;
    private static int lastPageClickedFrom = -1;
    private static int lastBackContainerId = Integer.MIN_VALUE;
    private static long lastPageClickAtMs = 0L;
    private static int clicks = 0;
    private static int claimed = 0;
    private static long claimedProfit = 0L;
    private static int skipped = 0;

    private AutoCroesusFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoCroesusFeature::tick);
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            if (state != State.IDLE) {
                stop("world changed", false);
            }
            croesusEntity = null;
            // Review fix (2026-09-15): also drop the last right-clicked entity, so a stale Entity (and the
            // whole old ClientLevel it references) isn't kept alive until the next interaction.
            lastUsedEntity = null;
        });
        // Purely observational (always PASS) - same pattern as ExperimentsFeature: remember what the player
        // themselves right-clicked so the Croesus NPC can be right-clicked again after a claim closes the menu.
        // Client side only: UseEntityCallback also fires on the integrated server thread in singleplayer,
        // which must never write these fields or bind a server-side entity.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (level.isClientSide()) {
                lastUsedEntity = entity;
                lastUsedEntityAtMs = System.currentTimeMillis();
            }
            return InteractionResult.PASS;
        });
    }

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    /** Stops a running session from outside (tab button / toggle off). */
    public static void requestStop() {
        if (state != State.IDLE) {
            stop("stopped by user", false);
        }
    }

    private static void tick(Minecraft client) {
        try {
            tickUnsafe(client);
        } catch (Exception e) {
            LOGGER.error("[Croesus] Auto Croesus tick failed - stopping", e);
            if (state != State.IDLE) {
                stop("internal error (see log)", true);
            }
        }
    }

    private static void tickUnsafe(Minecraft client) {
        long now = System.currentTimeMillis();
        // Cheap idle exit first (review fix, 2026-09-15): while idle and off/suppressed, skip the per-tick
        // title regex work entirely. Same behavior as the IDLE branch below.
        if (state == State.IDLE) {
            if (client.screen == null) {
                suppressUntilClosed = false;
                return;
            }
            if (suppressUntilClosed || !CroesusConfig.getInstance().isAutoCroesusEnabled()) {
                return;
            }
        }
        AbstractContainerScreen<?> screen = client.screen instanceof AbstractContainerScreen<?> s ? s : null;
        String title = ChestProfitFeature.titleOf(screen);
        boolean inMenu = screen != null && DungeonChestValuer.CROESUS_MENU_TITLE.matcher(title).matches();
        boolean inRunView = screen != null && DungeonChestValuer.RUN_VIEW_TITLE.matcher(title).matches();
        ChestType chestTitle = screen != null ? ChestType.fromName(title) : null;

        if (state == State.IDLE) {
            if (client.screen == null) {
                suppressUntilClosed = false;
            }
            if (inMenu && !suppressUntilClosed && CroesusConfig.getInstance().isAutoCroesusEnabled()) {
                start(now);
            }
            return;
        }
        if (!CroesusConfig.getInstance().isAutoCroesusEnabled()) {
            stop("Auto Croesus was turned off", false);
            return;
        }
        if (clicks >= MAX_CLICKS_PER_SESSION) {
            stop("click safety limit reached", true);
            return;
        }
        long inState = now - stateSinceMs;

        switch (state) {
            case MENU -> {
                if (!inMenu) {
                    stop(client.screen == null ? "menu closed" : "unexpected screen \"" + title + "\"", client.screen != null);
                    return;
                }
                if (now < nextActionAtMs) {
                    return;
                }
                int page = pageOf(title);
                if (lastPageClickedFrom != -1) {
                    if (page == lastPageClickedFrom) {
                        if (now - lastPageClickAtMs > TRANSITION_TIMEOUT_MS) {
                            stop("next Croesus page never loaded", true);
                        }
                        return;
                    }
                    lastPageClickedFrom = -1;
                    nextActionAtMs = now + randomDelay();
                    return;
                }
                List<ItemStack> stacks = ChestProfitFeature.containerStacks(screen);
                if (stacks.stream().allMatch(ItemStack::isEmpty)) {
                    if (inState > TRANSITION_TIMEOUT_MS) {
                        stop("Croesus menu never loaded", true);
                    }
                    return;
                }
                for (int slot : RUN_SLOTS) {
                    if (slot >= stacks.size()) {
                        break;
                    }
                    ItemStack head = stacks.get(slot);
                    if (head.isEmpty() || !head.is(Items.PLAYER_HEAD)) {
                        continue;
                    }
                    String key = page + ":" + slot;
                    if (skippedRuns.contains(key) || !DungeonChestValuer.cleanLore(head).contains("No chests opened yet!")) {
                        continue;
                    }
                    String floor = ChestProfitFeature.floorFromCroesusHead(head);
                    ChestProfitFeature.rememberRunFloor(floor);
                    currentRunKey = key;
                    click(client, screen, slot);
                    LOGGER.info("[Croesus] Opening run {} (page {}, slot {})", floor, page, slot);
                    setState(State.WAIT_RUN_VIEW, now);
                    return;
                }
                ItemStack next = stacks.size() > NEXT_PAGE_SLOT ? stacks.get(NEXT_PAGE_SLOT) : ItemStack.EMPTY;
                if (next.is(Items.ARROW)) {
                    lastPageClickedFrom = page;
                    lastPageClickAtMs = now;
                    click(client, screen, NEXT_PAGE_SLOT);
                    LOGGER.info("[Croesus] No unopened runs on page {} - next page", page);
                    nextActionAtMs = now + randomDelay();
                    return;
                }
                finish(client);
            }
            case WAIT_RUN_VIEW -> {
                if (inRunView) {
                    setState(State.RUN_VIEW, now);
                } else if (!(inMenu && inState < TRANSITION_TIMEOUT_MS)) {
                    stop(inMenu ? "run view never opened" : describe(client, title), true);
                }
            }
            case RUN_VIEW -> {
                if (!inRunView) {
                    stop(describe(client, title), client.screen != null);
                    return;
                }
                if (now < nextActionAtMs) {
                    return;
                }
                if (!DungeonChestValuer.pricesReady()) {
                    stop("prices aren't loaded yet", true);
                    return;
                }
                List<ChestValue> chests = ChestProfitFeature.valueRunView(screen);
                if (chests.isEmpty()) {
                    if (inState > TRANSITION_TIMEOUT_MS) {
                        stop("no chests found in run view", true);
                    }
                    return;
                }
                ChestValue best = chests.stream().filter(c -> !c.opened()).findFirst().orElse(null);
                long minProfit = CroesusConfig.getInstance().getAutoMinProfitK() * 1000L;
                if (best != null && best.profit() >= minProfit) {
                    targetType = best.type();
                    LOGGER.info("[Croesus] Claiming {} chest in {} (profit {}, min {}, unpriced items {})",
                            best.type().display, ChestProfitFeature.currentRunViewFloor(), best.profit(), minProfit, best.unpricedCount());
                    click(client, screen, best.slot());
                    setState(State.WAIT_CHEST, now);
                    return;
                }
                if (currentRunKey != null) {
                    skippedRuns.add(currentRunKey);
                }
                skipped++;
                LOGGER.info("[Croesus] Skipping run {} - best profit {} below min {}",
                        ChestProfitFeature.currentRunViewFloor(), best == null ? "n/a" : best.profit(), minProfit);
                ModChat.send("Auto Croesus", ModChat.text("Skipped "), ModChat.value(String.valueOf(ChestProfitFeature.currentRunViewFloor())),
                        ModChat.dim(" (best " + (best == null ? "n/a" : DungeonChestValuer.formatCoins(best.profit())) + ")"));
                goBack(client, screen, now);
            }
            case WAIT_CHEST -> {
                if (chestTitle != null && chestTitle == targetType) {
                    setState(State.CHEST, now);
                } else if (!(inRunView && inState < TRANSITION_TIMEOUT_MS)) {
                    stop(inRunView ? "chest never opened" : describe(client, title), true);
                }
            }
            case CHEST -> {
                if (chestTitle == null || chestTitle != targetType) {
                    stop(describe(client, title), client.screen != null);
                    return;
                }
                if (now < nextActionAtMs) {
                    return;
                }
                List<ItemStack> stacks = ChestProfitFeature.containerStacks(screen);
                if (stacks.size() <= DungeonChestValuer.CLAIM_BUTTON_SLOT
                        || !DungeonChestValuer.isClaimableButton(stacks.get(DungeonChestValuer.CLAIM_BUTTON_SLOT))) {
                    if (inState > TRANSITION_TIMEOUT_MS) {
                        stop("chest isn't claimable", true);
                    }
                    return;
                }
                ChestValue value = DungeonChestValuer.fromChestScreen(chestTitle, stacks);
                long minProfit = CroesusConfig.getInstance().getAutoMinProfitK() * 1000L;
                if (value.profit() < minProfit) {
                    // Re-check with the real reward stacks before paying - bail rather than buy below the threshold.
                    stop("chest profit re-check " + DungeonChestValuer.formatCoins(value.profit()) + " is below the minimum", true);
                    return;
                }
                ChestProfitFeature.onClaimClick(screen, "auto");
                click(client, screen, DungeonChestValuer.CLAIM_BUTTON_SLOT);
                claimed++;
                claimedProfit += value.profit();
                if (currentRunKey != null) {
                    skippedRuns.add(currentRunKey); // never re-enter this run even if its lore lags
                }
                setState(State.WAIT_AFTER_CLAIM, now);
            }
            case WAIT_AFTER_CLAIM -> afterTransition(client, screen, title, inMenu, inRunView, now, inState, chestTitle != null && chestTitle == targetType);
            case WAIT_BACK_TO_MENU -> afterTransition(client, screen, title, inMenu, inRunView, now, inState, chestTitle != null && chestTitle == targetType);
            case WAIT_REOPEN -> {
                if (inMenu) {
                    setState(State.MENU, now);
                    return;
                }
                if (client.screen != null) {
                    stop(describe(client, title), true);
                    return;
                }
                if (!reopenSent && now >= nextActionAtMs) {
                    Entity entity = croesusEntity;
                    if (entity == null || !entity.isAlive() || client.player == null || client.gameMode == null
                            || client.player.distanceToSqr(entity) > 36.0) {
                        stop("can't re-open Croesus (not in range) - open it again to continue", false);
                        return;
                    }
                    client.gameMode.interact(client.player, entity, new EntityHitResult(entity), InteractionHand.MAIN_HAND);
                    reopenSent = true;
                    stateSinceMs = now;
                    return;
                }
                if (reopenSent && inState > TRANSITION_TIMEOUT_MS) {
                    stop("Croesus didn't re-open", false);
                }
            }
            default -> {
            }
        }
    }

    /** Shared handling after a claim click or a "go back" click: menu -> continue, closed -> re-open NPC,
     *  run view (after a claim) -> go back to the menu; anything else stops. */
    private static void afterTransition(Minecraft client, AbstractContainerScreen<?> screen, String title, boolean inMenu,
                                        boolean inRunView, long now, long inState, boolean onTargetChest) {
        if (inMenu) {
            setState(State.MENU, now);
            return;
        }
        if (client.screen == null) {
            if (croesusEntity == null) {
                stop("menu closed and no Croesus NPC to re-open - open it again to continue", false);
                return;
            }
            reopenSent = false;
            setState(State.WAIT_REOPEN, now);
            return;
        }
        if (screen == null || !(inRunView || onTargetChest)) {
            stop(describe(client, title), true);
            return;
        }
        if (now < nextActionAtMs) {
            return;
        }
        int containerId = screen.getMenu().containerId;
        boolean chestDone = false;
        if (onTargetChest) {
            List<ItemStack> stacks = ChestProfitFeature.containerStacks(screen);
            chestDone = stacks.size() > DungeonChestValuer.CLAIM_BUTTON_SLOT
                    && !DungeonChestValuer.isClaimableButton(stacks.get(DungeonChestValuer.CLAIM_BUTTON_SLOT));
        }
        // Menu stayed open after the claim (run view, or the chest screen now "Already opened!"): walk back
        // one screen at a time - at most one back click per container instance.
        if ((inRunView || chestDone) && containerId != lastBackContainerId) {
            goBack(client, screen, now);
            return;
        }
        if (inState > TRANSITION_TIMEOUT_MS) {
            stop("stuck on \"" + title + "\"", true);
        }
    }

    private static void goBack(Minecraft client, AbstractContainerScreen<?> screen, long now) {
        lastBackContainerId = screen.getMenu().containerId;
        List<ItemStack> stacks = ChestProfitFeature.containerStacks(screen);
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack.is(Items.ARROW) && DungeonChestValuer.strip(stack.getHoverName().getString()).contains("Back")) {
                click(client, screen, i);
                setState(State.WAIT_BACK_TO_MENU, now);
                return;
            }
        }
        // No "Go Back" arrow found - close instead and re-open the NPC (quoi's own fallback after a claim).
        if (client.player != null) {
            client.player.closeContainer();
        }
        setState(State.WAIT_BACK_TO_MENU, now);
    }

    private static void start(long now) {
        CroesusConfig cfg = CroesusConfig.getInstance();
        skippedRuns.clear();
        currentRunKey = null;
        targetType = null;
        lastPageClickedFrom = -1;
        lastBackContainerId = Integer.MIN_VALUE;
        clicks = 0;
        claimed = 0;
        claimedProfit = 0;
        skipped = 0;
        if (lastUsedEntity != null && now - lastUsedEntityAtMs < ENTITY_BIND_WINDOW_MS) {
            croesusEntity = lastUsedEntity;
        }
        LOGGER.info("[Croesus] Auto Croesus started (min profit {}k, delay {}-{}ms, npc bound={})",
                cfg.getAutoMinProfitK(), cfg.getAutoMinDelayMs(), cfg.getAutoMaxDelayMs(), croesusEntity != null);
        ModChat.send("Auto Croesus", ModChat.text("Started - min profit "),
                ModChat.value(DungeonChestValuer.formatCoins(cfg.getAutoMinProfitK() * 1000L)),
                ModChat.dim(". Close the menu to stop."));
        setState(State.MENU, now);
    }

    private static void finish(Minecraft client) {
        LOGGER.info("[Croesus] Auto Croesus finished: claimed={}, skipped={}, profit={}", claimed, skipped, claimedProfit);
        ModChat.send("Auto Croesus", ModChat.good("Done. "), ModChat.text("Claimed "), ModChat.value(String.valueOf(claimed)),
                ModChat.text(", skipped "), ModChat.value(String.valueOf(skipped)), ModChat.text(", est. profit "),
                claimedProfit >= 0 ? ModChat.good("+" + DungeonChestValuer.formatCoins(claimedProfit))
                        : ModChat.bad(DungeonChestValuer.formatCoins(claimedProfit)));
        state = State.IDLE;
        suppressUntilClosed = true;
        if (client.player != null && client.screen != null) {
            client.player.closeContainer();
        }
    }

    private static void stop(String reason, boolean unexpected) {
        LOGGER.info("[Croesus] Auto Croesus stopped in state {}: {} (claimed={}, skipped={})", state, reason, claimed, skipped);
        ModChat.send("Auto Croesus", unexpected ? ModChat.bad("Stopped: ") : ModChat.text("Stopped: "), ModChat.text(reason),
                ModChat.dim(" (claimed " + claimed + ", skipped " + skipped + ")"));
        state = State.IDLE;
        suppressUntilClosed = true;
    }

    private static void setState(State newState, long now) {
        if (state != newState) {
            LOGGER.info("[Croesus] {} -> {}", state, newState);
            if (newState == State.MENU) {
                lastPageClickedFrom = -1;
            }
        }
        state = newState;
        stateSinceMs = now;
        nextActionAtMs = now + randomDelay();
    }

    private static String describe(Minecraft client, String title) {
        return client.screen == null ? "menu closed" : "unexpected screen \"" + title + "\"";
    }

    private static int pageOf(String title) {
        Matcher m = PAGE_TITLE.matcher(title);
        return m.matches() ? Integer.parseInt(m.group(1)) : 1;
    }

    private static int randomDelay() {
        CroesusConfig cfg = CroesusConfig.getInstance();
        int lo = cfg.getAutoMinDelayMs();
        int hi = Math.max(lo, cfg.getAutoMaxDelayMs());
        return lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
    }

    private static void click(Minecraft client, AbstractContainerScreen<?> screen, int slot) {
        if (client.player == null || client.gameMode == null) {
            return;
        }
        clicks++;
        client.gameMode.handleContainerInput(screen.getMenu().containerId, slot, 0, ContainerInput.CLONE, client.player);
    }
}
