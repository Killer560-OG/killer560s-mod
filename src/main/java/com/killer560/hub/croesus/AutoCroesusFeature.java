package com.killer560.hub.croesus;

import com.killer560.hub.croesus.DungeonChestValuer.ChestType;
import com.killer560.hub.croesus.DungeonChestValuer.ChestValue;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 * {@code BuildVariant.CHEAT_FEATURES_ENABLED}). Walks every run whose head says "No chests opened yet!",
 * opens its run view, and claims the profitable chests, optionally spending a Dungeon Chest Key on a second
 * chest and a Kismet Feather to reroll a poor Bedrock chest.
 *
 * <p><b>Starting and stopping (killer560, 2026-09-20).</b> "Make it so there is a button just like for the
 * etable to start it, and it will keep going until i press any key while it is going or it finishes." So it
 * no longer arms itself the moment the Croesus menu opens - a "Start Croesus" button is drawn over the menu
 * (a draggable HUD element, exactly like the Start ETable button) and nothing happens until it is clicked.
 * Any key press while it is running stops it. That also answers "double check why it may not have ran the
 * first time": the old start condition was invisible - it fired only on the transition INTO the Croesus menu
 * and was suppressed until the menu had been closed again, so enabling the toggle with the menu already open,
 * or re-opening after any stop, silently did nothing and printed no reason. There is now exactly one way to
 * start, it is on screen, and every refusal says why in chat.
 *
 * <p>Menu layout facts, all from quoi AutoCroesus.kt (a port of UnclaimedBloom6's AutoCroesus): run heads
 * live in slots 10-16/19-25/28-34/37-43 and are PLAYER_HEADs; next page is an ARROW in slot 53; a run view's
 * chests are clicked by slot; in a chest screen the open button is slot 31, the Kismet "Reroll Chest" button
 * is slot 50 and "Go Back" is slot 49; after a claim the menu may close, in which case Croesus is re-opened
 * by clicking the same NPC again. Clicks use the same
 * {@code handleContainerInput(..., ContainerInput.CLONE, ...)} mechanism as ExperimentsFeature, each after a
 * random human-ish delay between the configured min/max. Any screen that isn't the one expected next stops
 * the run immediately.
 */
public final class AutoCroesusFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-croesus");

    private static final int[] RUN_SLOTS = DungeonChestValuer.RUN_HEAD_SLOTS;
    private static final int NEXT_PAGE_SLOT = 53;
    private static final long TRANSITION_TIMEOUT_MS = 4000L;
    /** A reroll replaces the chest's contents server-side; give that a little longer than a menu hop. */
    private static final long REROLL_TIMEOUT_MS = 6000L;
    /** How long a fresh session waits for the Bazaar/AH feed before giving up (see {@link #start}). */
    private static final long PRICE_WAIT_MS = 10_000L;
    private static final long ENTITY_BIND_WINDOW_MS = 3000L;
    /** How far the Croesus NPC may be for a re-open click, in blocks squared. */
    private static final double NPC_RANGE_SQ = 36.0;
    private static final int MAX_CLICKS_PER_SESSION = 400;
    private static final Pattern PAGE_TITLE = Pattern.compile("^\\((\\d+)/(\\d+)\\) Croesus$");

    private static final String START_BUTTON_ELEMENT_ID = "croesus_start_button";
    private static final int START_BUTTON_WIDTH = 110;
    private static final int START_BUTTON_HEIGHT = 20;
    // Same palette as the Start ETable button, which reuses SettingsButtonWidget's own colours.
    private static final int START_BUTTON_BG = 0xFF1A1A1A;
    private static final int START_BUTTON_BORDER = 0xFFCC6600;
    private static final int START_BUTTON_BORDER_RUNNING = 0xFFFF5555;

    private enum State {
        IDLE, MENU, WAIT_RUN_VIEW, RUN_VIEW, WAIT_CHEST, CHEST, WAIT_REROLL,
        WAIT_AFTER_CLAIM, WAIT_BACK_TO_MENU, WAIT_REOPEN
    }

    private static State state = State.IDLE;
    private static long stateSinceMs = 0L;
    private static long nextActionAtMs = 0L;
    /** Set by the Start button; the session begins on the next tick that finds the Croesus menu open. */
    private static boolean armed = false;
    private static long armedAtMs = 0L;

    private static Entity lastUsedEntity = null;
    private static long lastUsedEntityAtMs = 0L;
    private static Entity croesusEntity = null;
    private static boolean reopenSent = false;
    private static int reopenAttempts = 0;

    private static final Set<String> skippedRuns = new HashSet<>();
    /** "page:slot:chestSlot" of chests this session already decided against, so it can't loop on them. */
    private static final Set<String> refusedChests = new HashSet<>();
    private static String currentRunKey = null;
    private static ChestType targetType = null;
    private static int targetSlot = -1;
    private static boolean pendingReroll = false;
    private static boolean rerolledThisRun = false;
    /** Whether anything was claimed out of the run currently open - a run that gave a chest and then ran out
     *  of profitable ones has not been "skipped" and must not say so. */
    private static boolean claimedThisRun = false;
    private static boolean kismetAvailable = true;
    private static String preRerollSignature = null;
    private static int lastPageClickedFrom = -1;
    private static int lastBackContainerId = Integer.MIN_VALUE;
    private static long lastPageClickAtMs = 0L;
    private static int clicks = 0;
    private static int claimed = 0;
    private static int keysUsed = 0;
    private static int rerolls = 0;
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
            armed = false;
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
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            registerStartButtonElement();
        }
    }

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    /** Stops a running session from outside (tab button / toggle off). */
    public static void requestStop() {
        armed = false;
        if (state != State.IDLE) {
            stop("stopped by user", false);
        }
    }

    /**
     * killer560: "it will keep going until i press any key while it is going or it finishes." Called from
     * {@link ChestProfitFeature}'s container key hook for every key press.
     *
     * @return whether something was actually stopped, so the caller can swallow that key press.
     */
    public static boolean stopOnKeyPress() {
        if (state != State.IDLE) {
            stop("a key was pressed", false);
            return true;
        }
        if (armed) {
            armed = false;
            ModChat.send("Auto Croesus", ModChat.text("Cancelled - press "), ModChat.value("Start Croesus"),
                    ModChat.text(" again to run it."));
            return true;
        }
        return false;
    }

    // ---- start button ----------------------------------------------------------------------------

    /** Whether the Start button should be drawn/clickable right now: cheat jar, feature on, nothing running
     *  yet, and the focused screen really is the Croesus menu. */
    public static boolean shouldShowStartButton() {
        if (!CroesusConfig.getInstance().isAutoCroesusEnabled() || state != State.IDLE || armed) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        return client.screen instanceof AbstractContainerScreen<?> screen
                && DungeonChestValuer.CROESUS_MENU_TITLE.matcher(ChestProfitFeature.titleOf(screen)).matches();
    }

    /** Drawn from {@link ChestProfitFeature}'s {@code ScreenEvents.afterExtract} hook, i.e. the container
     *  screen's OWN render pass - the earlier HUD pass would put it under the menu's darkened background
     *  (the exact z-order bug the Start ETable button hit in 2026-09-06). */
    public static void renderStartButton(GuiGraphicsExtractor graphics) {
        if (!CroesusConfig.getInstance().isAutoCroesusEnabled()) {
            return;
        }
        boolean running = state != State.IDLE || armed;
        if (!running && !shouldShowStartButton()) {
            return;
        }
        if (running && !(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?>)) {
            return;
        }
        int[] pos = resolveStartButtonPosition();
        float scale = resolveStartButtonScale();
        graphics.pose().pushMatrix();
        graphics.pose().translate(pos[0], pos[1]);
        graphics.pose().scale(scale, scale);
        drawButtonBox(graphics, 0, 0, running ? "Running - any key" : "Start Croesus", running);
        graphics.pose().popMatrix();
    }

    /** Called from {@link ChestProfitFeature}'s mouse hook. @return whether the click was consumed. */
    public static boolean tryClickStartButton(double mouseX, double mouseY) {
        if (!shouldShowStartButton()) {
            return false;
        }
        int[] pos = resolveStartButtonPosition();
        float scale = resolveStartButtonScale();
        int w = Math.round(START_BUTTON_WIDTH * scale);
        int h = Math.round(START_BUTTON_HEIGHT * scale);
        if (mouseX < pos[0] || mouseX >= pos[0] + w || mouseY < pos[1] || mouseY >= pos[1] + h) {
            return false;
        }
        armed = true;
        armedAtMs = System.currentTimeMillis();
        LOGGER.info("[Croesus] Start Croesus clicked - arming");
        return true;
    }

    private static void registerStartButtonElement() {
        HudElementRegistry.register(new HudElement() {
            @Override
            public String id() {
                return START_BUTTON_ELEMENT_ID;
            }

            @Override
            public String displayName() {
                return "Start Croesus Button";
            }

            @Override
            public int defaultX() {
                return Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2 - START_BUTTON_WIDTH / 2;
            }

            @Override
            public int defaultY() {
                return Minecraft.getInstance().getWindow().getGuiScaledHeight() / 2 + 90;
            }

            @Override
            public int width() {
                return START_BUTTON_WIDTH;
            }

            @Override
            public int height() {
                return START_BUTTON_HEIGHT;
            }

            @Override
            public boolean isRelevantNow() {
                // The Croesus menu can't be open while the HUD editor is, so "relevant" means "where you'd
                // use it": the feature is on and you're on Skyblock - same rule the ETable button uses.
                return CroesusConfig.getInstance().getAutoCroesusEnabledRaw()
                        && com.killer560.hub.util.SkyblockGate.isOnSkyblock();
            }

            @Override
            public void render(GuiGraphicsExtractor graphics, int x, int y) {
                drawButtonBox(graphics, x, y, "Start Croesus", false);
            }
        });
    }

    private static void drawButtonBox(GuiGraphicsExtractor graphics, int x, int y, String label, boolean running) {
        graphics.fill(x, y, x + START_BUTTON_WIDTH, y + START_BUTTON_HEIGHT, START_BUTTON_BG);
        graphics.outline(x, y, START_BUTTON_WIDTH, START_BUTTON_HEIGHT,
                running ? START_BUTTON_BORDER_RUNNING : START_BUTTON_BORDER);
        graphics.centeredText(Minecraft.getInstance().font, label, x + START_BUTTON_WIDTH / 2, y + 6, 0xFFFFFFFF);
    }

    private static int[] resolveStartButtonPosition() {
        HudElement element = HudElementRegistry.byId(START_BUTTON_ELEMENT_ID);
        return element == null ? new int[]{0, 0} : HudElementRegistry.resolvePosition(element);
    }

    private static float resolveStartButtonScale() {
        HudElement element = HudElementRegistry.byId(START_BUTTON_ELEMENT_ID);
        return element == null ? 1.0f : HudElementRegistry.resolveScale(element);
    }

    // ---- state machine ---------------------------------------------------------------------------

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
        // Cheap idle exit first (review fix, 2026-09-15): while idle and unarmed, skip the per-tick title
        // regex work entirely.
        if (state == State.IDLE && !armed) {
            return;
        }
        AbstractContainerScreen<?> screen = client.screen instanceof AbstractContainerScreen<?> s ? s : null;
        String title = ChestProfitFeature.titleOf(screen);
        boolean inMenu = screen != null && DungeonChestValuer.CROESUS_MENU_TITLE.matcher(title).matches();
        boolean inRunView = screen != null && DungeonChestValuer.RUN_VIEW_TITLE.matcher(title).matches();
        ChestType chestTitle = screen != null ? ChestType.fromName(title) : null;

        if (state == State.IDLE) {
            if (!CroesusConfig.getInstance().isAutoCroesusEnabled()) {
                armed = false;
                return;
            }
            if (inMenu) {
                start(now);
            } else if (now - armedAtMs > TRANSITION_TIMEOUT_MS) {
                armed = false;
                ModChat.send("Auto Croesus", ModChat.bad("Didn't start"),
                        ModChat.dim(" - the Croesus menu closed before it could begin."));
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
                    if (skippedRuns.contains(key)
                            || !DungeonChestValuer.cleanLore(head).contains(DungeonChestValuer.RUN_UNOPENED_LORE)) {
                        continue;
                    }
                    String floor = ChestProfitFeature.floorFromCroesusHead(head);
                    if (!click(client, screen, slot)) {
                        // Gate denied: nothing remembered, no currentRunKey, no state change - this exact
                        // run head is found again next tick and opened then.
                        return;
                    }
                    ChestProfitFeature.rememberRunFloor(floor);
                    currentRunKey = key;
                    rerolledThisRun = false;
                    claimedThisRun = false;
                    LOGGER.info("[Croesus] Opening run {} (page {}, slot {})", floor, page, slot);
                    setState(State.WAIT_RUN_VIEW, now);
                    return;
                }
                ItemStack next = stacks.size() > NEXT_PAGE_SLOT ? stacks.get(NEXT_PAGE_SLOT) : ItemStack.EMPTY;
                if (next.is(Items.ARROW)) {
                    if (!click(client, screen, NEXT_PAGE_SLOT)) {
                        // Gate denied: lastPageClickedFrom stays -1, so the page-transition watchdog isn't
                        // armed for a click that never went out. Retried next tick.
                        return;
                    }
                    lastPageClickedFrom = page;
                    lastPageClickAtMs = now;
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
            case RUN_VIEW -> runView(client, screen, title, inRunView, now, inState);
            case WAIT_CHEST -> {
                if (chestTitle != null && chestTitle == targetType) {
                    setState(State.CHEST, now);
                } else if (!(inRunView && inState < TRANSITION_TIMEOUT_MS)) {
                    stop(inRunView ? "chest never opened" : describe(client, title), true);
                }
            }
            case CHEST -> chest(client, screen, title, chestTitle, now, inState);
            case WAIT_REROLL -> {
                if (inMenu) {
                    setState(State.MENU, now);
                    return;
                }
                if (inRunView) {
                    setState(State.RUN_VIEW, now);
                    return;
                }
                if (client.screen == null) {
                    // Hypixel sometimes re-sends the chest window rather than editing it in place.
                    if (inState > REROLL_TIMEOUT_MS) {
                        stop("the chest never came back after the reroll", true);
                    }
                    return;
                }
                if (chestTitle != targetType) {
                    stop(describe(client, title), true);
                    return;
                }
                String signature = chestSignature(ChestProfitFeature.containerStacks(screen));
                if (!signature.equals(preRerollSignature)) {
                    LOGGER.info("[Croesus] Reroll landed - re-valuing {} chest", targetType.display);
                    setState(State.CHEST, now);
                    return;
                }
                if (inState > REROLL_TIMEOUT_MS) {
                    // Never claim a chest whose contents may still be the pre-reroll ones.
                    refuseCurrentChest();
                    goBack(client, screen, now);
                }
            }
            case WAIT_AFTER_CLAIM, WAIT_BACK_TO_MENU ->
                    afterTransition(client, screen, title, inMenu, inRunView, now, inState,
                            chestTitle != null && chestTitle == targetType);
            case WAIT_REOPEN -> reopen(client, title, inMenu, now, inState);
            default -> {
            }
        }
    }

    // ---- run view --------------------------------------------------------------------------------

    private static void runView(Minecraft client, AbstractContainerScreen<?> screen, String title, boolean inRunView,
                                long now, long inState) {
        if (!inRunView) {
            stop(describe(client, title), client.screen != null);
            return;
        }
        if (now < nextActionAtMs) {
            return;
        }
        if (!DungeonChestValuer.pricesReady()) {
            // killer560, 2026-09-20: "double check why it may not have ran the first time". One real way the
            // very first attempt after a launch does nothing: the Bazaar feed hadn't landed yet and the old
            // code stopped outright on the first run view. start() now kicks a refresh and this waits for it.
            if (inState > PRICE_WAIT_MS) {
                stop("prices still aren't loaded - try again in a moment", true);
            }
            return;
        }
        List<ChestValue> chests = ChestProfitFeature.valueRunView(screen);
        if (chests.isEmpty()) {
            if (inState > TRANSITION_TIMEOUT_MS) {
                stop("no chests found in run view", true);
            }
            return;
        }
        CroesusConfig cfg = CroesusConfig.getInstance();
        long minProfit = cfg.getAutoMinProfitK() * 1000L;
        long keyMinProfit = cfg.getAutoKeyMinProfitK() * 1000L;

        ChestValue target = null;
        for (ChestValue c : chests) {
            if (c.opened() || c.slot() < 0 || refusedChests.contains(chestKey(c.slot()))) {
                continue;
            }
            if (c.requiresKey()) {
                // The lore only lists "Dungeon Chest Key" once a chest in this run has already been claimed,
                // so this branch IS the "second chest" case - its own, higher threshold applies, and the key's
                // Bazaar price is already inside c.profit() (DungeonChestValuer.fromLore).
                if (!cfg.isAutoUseChestKeys() || c.profit() < Math.max(minProfit, keyMinProfit)) {
                    continue;
                }
            } else if (c.profit() < minProfit) {
                continue;
            }
            target = c;
            break;
        }
        if (target != null) {
            if (!click(client, screen, target.slot())) {
                // Gate denied: targetType stays as it was and the state machine doesn't advance, so
                // WAIT_CHEST can't start waiting for a chest nobody asked for. Retried next tick.
                return;
            }
            targetType = target.type();
            targetSlot = target.slot();
            pendingReroll = false;
            if (target.requiresKey()) {
                keysUsed++;
            }
            LOGGER.info("[Croesus] Claiming {} chest in {} (profit {}, min {}, key {}, unpriced items {})",
                    target.type().display, ChestProfitFeature.currentRunViewFloor(), target.profit(),
                    target.requiresKey() ? keyMinProfit : minProfit, target.requiresKey(), target.unpricedCount());
            setState(State.WAIT_CHEST, now);
            return;
        }

        // Nothing worth claiming as it stands - killer560, 2026-09-20: "for auto croeseus add the ability to
        // reroll as well". Only a Bedrock chest can be rerolled, only once per run, and only while a Kismet
        // Feather is actually on you (quoi AutoCroesus.kt reads that off the slot-50 button itself).
        if (cfg.isAutoUseKismets() && kismetAvailable && !rerolledThisRun) {
            ChestValue bedrock = null;
            for (ChestValue c : chests) {
                if (!c.opened() && c.slot() >= 0 && c.type() == ChestType.BEDROCK) {
                    bedrock = c;
                    break;
                }
            }
            long rerollBelow = cfg.getAutoRerollBelowK() * 1000L;
            if (bedrock != null && bedrock.profit() < rerollBelow) {
                if (!click(client, screen, bedrock.slot())) {
                    return;
                }
                targetType = bedrock.type();
                targetSlot = bedrock.slot();
                pendingReroll = true;
                LOGGER.info("[Croesus] Opening Bedrock chest in {} to reroll it (profit {} < {})",
                        ChestProfitFeature.currentRunViewFloor(), bedrock.profit(), rerollBelow);
                setState(State.WAIT_CHEST, now);
                return;
            }
        }

        // The skip bookkeeping and its chat line only happen once the "go back" click has actually gone out -
        // a gate denial here must not count the run as skipped or spam a second message.
        ChestValue best = null;
        for (ChestValue c : chests) {
            if (!c.opened()) {
                best = c;
                break;
            }
        }
        if (!goBack(client, screen, now)) {
            return;
        }
        if (currentRunKey != null) {
            skippedRuns.add(currentRunKey);
        }
        if (claimedThisRun) {
            // Already took a chest out of this run - it is finished, not skipped.
            return;
        }
        skipped++;
        LOGGER.info("[Croesus] Skipping run {} - best profit {} below min {}",
                ChestProfitFeature.currentRunViewFloor(), best == null ? "n/a" : best.profit(), minProfit);
        ModChat.send("Auto Croesus", ModChat.text("Skipped "), ModChat.value(String.valueOf(ChestProfitFeature.currentRunViewFloor())),
                ModChat.dim(" (best " + (best == null ? "n/a" : DungeonChestValuer.formatCoins(best.profit())) + ")"));
    }

    // ---- chest screen ----------------------------------------------------------------------------

    private static void chest(Minecraft client, AbstractContainerScreen<?> screen, String title, ChestType chestTitle,
                              long now, long inState) {
        if (chestTitle == null || chestTitle != targetType) {
            stop(describe(client, title), client.screen != null);
            return;
        }
        if (now < nextActionAtMs) {
            return;
        }
        List<ItemStack> stacks = ChestProfitFeature.containerStacks(screen);

        if (pendingReroll) {
            ItemStack button = stacks.size() > DungeonChestValuer.REROLL_BUTTON_SLOT
                    ? stacks.get(DungeonChestValuer.REROLL_BUTTON_SLOT) : ItemStack.EMPTY;
            if (!DungeonChestValuer.isRerollButton(button)) {
                if (inState > TRANSITION_TIMEOUT_MS) {
                    pendingReroll = false;
                    LOGGER.info("[Croesus] No Reroll Chest button in this chest - claiming/skipping normally");
                }
                return;
            }
            pendingReroll = false;
            if (DungeonChestValuer.rerollNeedsKismet(button)) {
                // quoi does the same: one "you have none" answer turns rerolling off for the whole session
                // instead of re-asking on every run.
                kismetAvailable = false;
                ModChat.send("Auto Croesus", ModChat.bad("No Kismet Feather"),
                        ModChat.dim(" - rerolling is off for the rest of this session."));
                return;
            }
            if (DungeonChestValuer.rerollAlreadyUsed(button)) {
                rerolledThisRun = true;
                return;
            }
            preRerollSignature = chestSignature(stacks);
            if (!click(client, screen, DungeonChestValuer.REROLL_BUTTON_SLOT)) {
                pendingReroll = true; // gate denied - nothing was sent, so ask again next tick
                return;
            }
            rerolls++;
            rerolledThisRun = true;
            LOGGER.info("[Croesus] Rerolled the {} chest in {}", targetType.display, ChestProfitFeature.currentRunViewFloor());
            setState(State.WAIT_REROLL, now);
            return;
        }

        if (stacks.size() <= DungeonChestValuer.CLAIM_BUTTON_SLOT
                || !DungeonChestValuer.isClaimableButton(stacks.get(DungeonChestValuer.CLAIM_BUTTON_SLOT))) {
            if (inState > TRANSITION_TIMEOUT_MS) {
                stop("chest isn't claimable", true);
            }
            return;
        }
        ChestValue value = DungeonChestValuer.fromChestScreen(chestTitle, stacks);
        CroesusConfig cfg = CroesusConfig.getInstance();
        long minProfit = value.requiresKey()
                ? Math.max(cfg.getAutoMinProfitK() * 1000L, cfg.getAutoKeyMinProfitK() * 1000L)
                : cfg.getAutoMinProfitK() * 1000L;
        if (value.profit() < minProfit) {
            // Re-check with the real reward stacks before paying. This used to stop the whole session; with
            // rerolling in the mix a chest legitimately lands here (a reroll that made things worse), so it
            // walks back to the run view and refuses this one chest instead of ending the run.
            LOGGER.info("[Croesus] Re-check says {} < {} - leaving this chest alone", value.profit(), minProfit);
            refuseCurrentChest();
            goBack(client, screen, now);
            return;
        }
        // onClaimClick has to run immediately before the packet leaves, so it is handed to click() as
        // the beforeSend hook rather than being called here - a gate denial must not leave a pending
        // claim registered for a click that was never sent.
        if (!click(client, screen, DungeonChestValuer.CLAIM_BUTTON_SLOT,
                () -> ChestProfitFeature.onClaimClick(screen, "auto"))) {
            return;
        }
        claimed++;
        claimedThisRun = true;
        claimedProfit += value.profit();
        refuseCurrentChest();
        if (!CroesusConfig.getInstance().isAutoUseChestKeys() && currentRunKey != null) {
            // Without chest keys one claim is all a run can give, so never walk back into it.
            skippedRuns.add(currentRunKey);
        }
        setState(State.WAIT_AFTER_CLAIM, now);
    }

    /** Remembers that this exact chest slot in this exact run is done with, so the run view can't pick it
     *  again and spin. */
    private static void refuseCurrentChest() {
        if (targetSlot >= 0) {
            refusedChests.add(chestKey(targetSlot));
        }
    }

    private static String chestKey(int chestSlot) {
        return (currentRunKey == null ? "?" : currentRunKey) + ":" + chestSlot;
    }

    /** The reward stacks as a string, so a reroll can be detected by the contents actually changing rather
     *  than by a fixed wait (which would risk claiming the pre-reroll chest). */
    private static String chestSignature(List<ItemStack> stacks) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(41, stacks.size());
        for (int i = 0; i < limit; i++) {
            if (i == DungeonChestValuer.CLAIM_BUTTON_SLOT) {
                continue;
            }
            ItemStack stack = stacks.get(i);
            if (!stack.isEmpty()) {
                sb.append(i).append('=').append(DungeonChestValuer.strip(stack.getHoverName().getString()))
                        .append('x').append(stack.getCount()).append(';');
            }
        }
        return sb.toString();
    }

    // ---- transitions -----------------------------------------------------------------------------

    /** Shared handling after a claim click or a "go back" click: menu -> continue, closed -> re-open NPC,
     *  run view (after a claim) -> go back to the menu; anything else stops. */
    private static void afterTransition(Minecraft client, AbstractContainerScreen<?> screen, String title, boolean inMenu,
                                        boolean inRunView, long now, long inState, boolean onTargetChest) {
        if (inMenu) {
            setState(State.MENU, now);
            return;
        }
        if (inRunView && CroesusConfig.getInstance().isAutoUseChestKeys()) {
            // With chest keys on, a claim that leaves you in the run view is the chance to spend one on the
            // next chest - re-evaluate here instead of walking straight back out.
            setState(State.RUN_VIEW, now);
            return;
        }
        if (client.screen == null) {
            reopenSent = false;
            reopenAttempts = 0;
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
            // A false return means the gate denied this tick and nothing advanced, so this same branch is
            // reached again next tick and retries - hence no special handling here.
            goBack(client, screen, now);
            return;
        }
        if (inState > TRANSITION_TIMEOUT_MS) {
            stop("stuck on \"" + title + "\"", true);
        }
    }

    /**
     * killer560, 2026-09-20: "after it claims a chest have it left or right click again to reopen croeseus.
     * If it cant then it should stop." So: right-click the NPC, and if the menu still hasn't come back, one
     * left-click as the second and last attempt before stopping cleanly.
     */
    private static void reopen(Minecraft client, String title, boolean inMenu, long now, long inState) {
        if (inMenu) {
            setState(State.MENU, now);
            return;
        }
        if (client.screen != null) {
            stop(describe(client, title), true);
            return;
        }
        if (reopenSent) {
            if (inState <= TRANSITION_TIMEOUT_MS) {
                return;
            }
            if (reopenAttempts >= 2) {
                stop("Croesus didn't re-open after a right- and a left-click", false);
                return;
            }
            reopenSent = false;
        }
        if (now < nextActionAtMs) {
            return;
        }
        Entity entity = resolveCroesusEntity(client);
        if (entity == null) {
            stop("can't re-open Croesus - stand next to him and press Start Croesus again", false);
            return;
        }
        // The one WORLD interaction in this otherwise all-GUI flow, so it takes the tick through CROESUS_NPC
        // rather than CROESUS (which is a SCREEN actor and would be refused here, with no screen open).
        // A denial leaves reopenSent false and retries on the next tick.
        if (!ActionGate.tryAct(ActionGate.Actor.CROESUS_NPC)) {
            return;
        }
        if (reopenAttempts == 0) {
            client.gameMode.interact(client.player, entity, new EntityHitResult(entity), InteractionHand.MAIN_HAND);
        } else {
            client.gameMode.attack(client.player, entity);
        }
        LOGGER.info("[Croesus] Re-opening Croesus ({}-click, attempt {})", reopenAttempts == 0 ? "right" : "left",
                reopenAttempts + 1);
        reopenAttempts++;
        reopenSent = true;
        stateSinceMs = now;
    }

    /**
     * The entity to click to re-open Croesus: the one the player themselves opened him with while it is
     * still valid, otherwise whatever is standing under the "Croesus" nameplate near you.
     * <p>
     * The second half matters now that the session starts from a button instead of from the player's own
     * right-click - by then the interaction that opened the menu can be long past.
     */
    private static Entity resolveCroesusEntity(Minecraft client) {
        if (client.player == null || client.level == null || client.gameMode == null) {
            return null;
        }
        if (inRange(client, croesusEntity)) {
            return croesusEntity;
        }
        if (inRange(client, lastUsedEntity)) {
            croesusEntity = lastUsedEntity;
            return croesusEntity;
        }
        // quoi AutoCroesus.kt matches the NPC by an entity whose CUSTOM NAME is exactly "Croesus" - that is
        // the floating nameplate stand, so the clickable body is the nearest other entity sharing its spot.
        Entity plate = null;
        double bestPlate = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity.getCustomName() == null
                    || !DungeonChestValuer.strip(entity.getCustomName().getString()).trim().equals("Croesus")) {
                continue;
            }
            double distance = client.player.distanceToSqr(entity);
            if (distance <= NPC_RANGE_SQ && distance < bestPlate) {
                bestPlate = distance;
                plate = entity;
            }
        }
        if (plate == null) {
            return null;
        }
        Entity body = null;
        double bestBody = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == plate || entity == client.player || entity.getCustomName() != null) {
                continue;
            }
            double dx = entity.getX() - plate.getX();
            double dz = entity.getZ() - plate.getZ();
            double dy = Math.abs(entity.getY() - plate.getY());
            double flat = dx * dx + dz * dz;
            if (flat <= 2.25 && dy <= 3.0 && flat < bestBody) {
                bestBody = flat;
                body = entity;
            }
        }
        croesusEntity = body != null ? body : plate;
        return croesusEntity;
    }

    private static boolean inRange(Minecraft client, Entity entity) {
        return entity != null && entity.isAlive() && client.player != null
                && entity.level() == client.level && client.player.distanceToSqr(entity) <= NPC_RANGE_SQ;
    }

    /** @return whether the "go back" actually happened. False means the {@link ActionGate} denied the click
     *  this tick and NOTHING moved - {@code lastBackContainerId} in particular, so the caller's
     *  "at most one back click per container instance" check still lets this be retried next tick. */
    private static boolean goBack(Minecraft client, AbstractContainerScreen<?> screen, long now) {
        List<ItemStack> stacks = ChestProfitFeature.containerStacks(screen);
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack.is(Items.ARROW) && DungeonChestValuer.strip(stack.getHoverName().getString()).contains("Back")) {
                if (!click(client, screen, i)) {
                    return false;
                }
                lastBackContainerId = screen.getMenu().containerId;
                setState(State.WAIT_BACK_TO_MENU, now);
                return true;
            }
        }
        // No "Go Back" arrow found - close instead and re-open the NPC (quoi's own fallback after a claim).
        // Not gated: closing a container is not an interaction.
        lastBackContainerId = screen.getMenu().containerId;
        if (client.player != null) {
            client.player.closeContainer();
        }
        setState(State.WAIT_BACK_TO_MENU, now);
        return true;
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    private static void start(long now) {
        CroesusConfig cfg = CroesusConfig.getInstance();
        armed = false;
        skippedRuns.clear();
        refusedChests.clear();
        currentRunKey = null;
        targetType = null;
        targetSlot = -1;
        pendingReroll = false;
        rerolledThisRun = false;
        claimedThisRun = false;
        kismetAvailable = true;
        preRerollSignature = null;
        lastPageClickedFrom = -1;
        lastBackContainerId = Integer.MIN_VALUE;
        reopenAttempts = 0;
        clicks = 0;
        claimed = 0;
        keysUsed = 0;
        rerolls = 0;
        claimedProfit = 0;
        skipped = 0;
        if (lastUsedEntity != null && now - lastUsedEntityAtMs < ENTITY_BIND_WINDOW_MS) {
            croesusEntity = lastUsedEntity;
        }
        // See the RUN_VIEW price wait: a cold launch can reach the first run view before the Bazaar feed has
        // landed, which used to look like "it just didn't run".
        if (!DungeonChestValuer.pricesReady()) {
            com.killer560.hub.rngmeter.RngMeterEngine.PRICES.refreshIfStale();
        }
        LOGGER.info("[Croesus] Auto Croesus started (min profit {}k, delay {}-{}ms, keys={}, kismets={}, npc bound={})",
                cfg.getAutoMinProfitK(), cfg.getAutoMinDelayMs(), cfg.getAutoMaxDelayMs(),
                cfg.isAutoUseChestKeys(), cfg.isAutoUseKismets(), croesusEntity != null);
        ModChat.send("Auto Croesus", ModChat.text("Started - min profit "),
                ModChat.value(DungeonChestValuer.formatCoins(cfg.getAutoMinProfitK() * 1000L)),
                ModChat.dim(". Press any key to stop."));
        setState(State.MENU, now);
    }

    private static void finish(Minecraft client) {
        LOGGER.info("[Croesus] Auto Croesus finished: claimed={}, skipped={}, keys={}, rerolls={}, profit={}",
                claimed, skipped, keysUsed, rerolls, claimedProfit);
        ModChat.send("Auto Croesus", ModChat.good("Done. "), ModChat.text("Claimed "), ModChat.value(String.valueOf(claimed)),
                ModChat.text(", skipped "), ModChat.value(String.valueOf(skipped)),
                ModChat.dim(" (keys " + keysUsed + ", rerolls " + rerolls + ")"),
                ModChat.text(", est. profit "),
                claimedProfit >= 0 ? ModChat.good("+" + DungeonChestValuer.formatCoins(claimedProfit))
                        : ModChat.bad(DungeonChestValuer.formatCoins(claimedProfit)));
        state = State.IDLE;
        armed = false;
        if (client.player != null && client.screen != null) {
            client.player.closeContainer();
        }
    }

    private static void stop(String reason, boolean unexpected) {
        LOGGER.info("[Croesus] Auto Croesus stopped in state {}: {} (claimed={}, skipped={})", state, reason, claimed, skipped);
        ModChat.send("Auto Croesus", unexpected ? ModChat.bad("Stopped: ") : ModChat.text("Stopped: "), ModChat.text(reason),
                ModChat.dim(" (claimed " + claimed + ", skipped " + skipped + ")"));
        state = State.IDLE;
        armed = false;
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

    private static boolean click(Minecraft client, AbstractContainerScreen<?> screen, int slot) {
        return click(client, screen, slot, null);
    }

    /** The one place this feature talks to the server, so it is also the one place the mod-wide
     *  {@link ActionGate} is consulted. Every caller must treat {@code false} as "nothing happened this
     *  tick": no state change, no counter, no {@code setState} - the same check simply runs again next tick.
     *
     *  @param beforeSend bookkeeping that has to be in place before the packet leaves (see
     *                    {@link ChestProfitFeature#onClaimClick}); only run once the gate has accepted, so a
     *                    denied tick can't register a claim for a click that was never sent.
     *  @return whether the click was actually sent. */
    private static boolean click(Minecraft client, AbstractContainerScreen<?> screen, int slot, Runnable beforeSend) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.CROESUS, screen)) {
            return false;
        }
        if (beforeSend != null) {
            beforeSend.run();
        }
        clicks++;
        client.gameMode.handleContainerInput(screen.getMenu().containerId, slot, 0, ContainerInput.CLONE, client.player);
        return true;
    }
}
