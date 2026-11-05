package com.killer560.hub.invsort;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.invwalk.InventoryWalkFeature;
import com.killer560.hub.itemprotect.ItemProtect;
import com.killer560.hub.itemprotect.ItemProtectConfig;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies a saved {@link InventoryLayout} with the fewest clicks there are (killer560: "I should be able to save item
 * locations in my inventory and have them sorted there"; 2026-11-01: "double check that any move you make is the most
 * optimized method that there is no way to do it in less clicks").
 * <p>
 * <b>The plan</b> comes from {@link InvSortPlanner}: number-key swaps around a hotbar slot for every cycle that has one,
 * the cursor carried round a cycle for the rest, displaced items put back where the moved item came from. Its minimality
 * argument and the exhaustive check against every click a player can send are in docs/INVSORT-OPTIMALITY.md. The plan is
 * made from the live inventory once the sort starts; before every click the inventory must still be what the plan
 * predicted, or it is planned again from what is there (a click the server refused), at most {@link #MAX_REPLANS} times.
 * <p>
 * <b>Normal</b>: the vanilla inventory opens as a {@link SortingInventoryScreen}, which swallows every click and key but
 * Escape / the inventory key while it works (killer560, 2026-11-01: "blocks all clicks while the thing is happening and
 * you cannot override it"), and one click goes out every {@code Ticks Between Moves} (+ random) ticks through
 * {@link ActionGate}. <b>Instant (0-tick)</b>: every click in ONE client tick, on the inventory menu without opening any
 * screen (the player's own inventory has no open packet), so nothing is ever on screen to click into.
 * <p>
 * <b>GrimAC</b>: MultiActionsC flags a container click while the server's last input from him has a movement key, a
 * sprint or a sneak (docs/LESSONS-AUTOMATION.md, Inventory Walk). So the run holds every movement key and sneak off from
 * its first tick, and no click goes out until the input that LEFT the connection is still
 * ({@link InventoryWalkFeature#serverHasStillInput}).
 * <p>
 * <b>Storage mode</b> ({@link InvSortStorage}) runs first when the layout has it on.
 * <p>
 * <b>Stops cleanly</b> on: the inventory closing, a world change, another screen, and the inventory's CONTENTS changing
 * under the sort (every move only rearranges, so the multiset of items, cursor included, must hold until it finishes).
 */
public final class InventorySorterExecutor {

    private static final Logger LOGGER = ModLog.get("killer560smod-invsort");

    private static final int MAX_CLICKS = 250;
    private static final int MAX_REPLANS = 3;
    /** The planner's search budget here (it runs on the render thread): every plan is still legal and at its own cycle
     *  bound; past the budget it is not proven to be the best choice of copies (docs/INVSORT-OPTIMALITY.md). */
    private static final int PLAN_BUDGET = 20_000;
    /** Ticks to wait for the inventory screen to come up (and for a closing screen to go away). */
    private static final int WAIT_FOR_SCREEN_TICKS = 40;
    /** Ticks to wait for the server to have him standing still before giving up. */
    private static final int STILL_TIMEOUT_TICKS = 40;
    /** Ticks the loadout swap may take (the menu has 5 s to open, then a click and a close). */
    private static final int LOADOUT_TIMEOUT_TICKS = 240;

    private enum State { IDLE, WAITING_LOBBY, LOADOUT, STORAGE, WAIT_SCREEN, SORTING }

    /** Hypixel will not open a backpack, the Ender Chest or the storage menu for about this long after a warp or server
     *  switch (killer560, 2026-11-03: "it needs to wait to open backpacks and stuff for 4 seconds after a warp"; his own
     *  measurement - no Hypixel message for it exists in the mod or his logs). */
    public static final long LOBBY_WAIT_MS = 4000;
    private static long lastWorldChange;

    /** Records a world change (warp, server switch, join) now. Public for tests. */
    public static void noteWorldChange() {
        lastWorldChange = System.currentTimeMillis();
    }

    /** True while an apply is held back waiting for {@link #LOBBY_WAIT_MS} after a world change. For tests. */
    public static boolean isWaitingForLobby() {
        return state == State.WAITING_LOBBY;
    }

    private static long lobbyLeftMs() {
        return lastWorldChange + LOBBY_WAIT_MS - System.currentTimeMillis();
    }

    private static State state = State.IDLE;
    private static InventoryLayout targetLayout;
    private static InvSortStorage storage;
    private static int stateTicks;
    /** Ticks still to wait before the next click. */
    private static int waitTicks;
    private static int stillWait;
    private static boolean openedByUs;
    private static boolean instantRun;

    private static List<InvSortPlanner.Click> plan;
    private static int planStep;
    private static String[] expected;
    private static int replans;
    private static int planned;
    private static boolean planProven;

    private static int clicksSent;
    private static int hotbarSwaps;
    private static int pickupClicks;
    private static int maxClicksOneTick;
    private static final Set<Integer> unresolved = new HashSet<>();
    /** The items (identity x count, cursor included) the sort started with - see the class doc. */
    private static Map<String, Integer> startingItems = Map.of();

    /** Bound keys held last tick, so a held key applies its layout once. */
    private static final Set<Integer> keysDown = new HashSet<>();
    private static boolean openKeyDown;

    private InventorySorterExecutor() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("InventorySorterExecutor.tick", InventorySorterExecutor::tick));
        // Escape (which also closes the inventory) and the Emergency Cancel Key stop it (killer560, 2026-11-01).
        com.killer560.hub.macrostop.MacroStop.register("Inventory Sorter", com.killer560.hub.macrostop.MacroStop.Kind.CLICK, InventorySorterExecutor::isRunning,
                why -> stop("you pressed " + why));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("InventorySorterExecutor.keys", InventorySorterExecutor::pollKeys));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("InvSortPagePick.tick", InvSortPagePick::tick));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("InvSortLoadoutCache.tick", InvSortLoadoutCache::tick));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> noteWorldChange());
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            noteWorldChange();
            if (state == State.WAITING_LOBBY) {
                return; // leaving again just restarts the wait (lobbyLeftMs counts from this change)
            }
            if (isRunning()) {
                stop("world changed");
            }
        });
    }

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    /** Starts applying {@code layout}.
     *  @return false if a session is already running, the layout is null, or this is not the cheat build. */
    public static boolean start(InventoryLayout layout) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED || isRunning() || layout == null) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return false;
        }
        targetLayout = layout;
        if (layout.usesStorage() && lobbyLeftMs() > 0) {
            // A storage sort right after a warp: Hypixel will not open the pages yet. Wait it out, then go (once).
            setState(State.WAITING_LOBBY);
            ModChat.send(InventorySorterCommands.FEATURE, ModChat.text("You just changed worlds: waiting "
                    + String.format(java.util.Locale.ROOT, "%.1f", lobbyLeftMs() / 1000.0)
                    + "s for Hypixel to allow storage, then sorting. Escape cancels."));
            return true;
        }
        beginRun(client);
        return true;
    }

    /** Sets up and starts the run for {@link #targetLayout}. */
    private static void beginRun(Minecraft client) {
        InventoryLayout layout = targetLayout;
        clicksSent = 0;
        hotbarSwaps = 0;
        pickupClicks = 0;
        maxClicksOneTick = 0;
        planned = 0;
        replans = 0;
        unresolved.clear();
        plan = null;
        waitTicks = 0;
        stillWait = 0;
        openedByUs = false;
        instantRun = InventorySorterConfig.getInstance().isInstant();
        storage = null;
        loadoutSent = false;
        if (layout.looseSlots() > 0) {
            ModChat.log(InventorySorterCommands.FEATURE, ModChat.text(layout.name() + ": " + layout.looseSlots()
                    + " slot(s) were saved before exact items existed, so any copy of that item fills them. Save the layout again to pin the exact one."));
        }
        if (layout.loadout() > 0) {
            // The layout's Hypixel loadout first (killer560, 2026-11-04): a loadout swap can change what he carries, so
            // storage and the sort plan from the inventory it leaves, not the one it started with.
            setState(State.LOADOUT);
            return;
        }
        afterLoadout(client);
    }

    /** True once the loadout swap of this run was started. */
    private static boolean loadoutSent;

    /** The loadout step is done (or the layout has none): storage visit if the layout uses it, else the sort. */
    private static void afterLoadout(Minecraft client) {
        InventoryLayout layout = targetLayout;
        if (layout.usesStorage()) {
            storage = new InvSortStorage(layout);
            storage.plan(client);
            setState(storage.hasWork() ? State.STORAGE : State.WAIT_SCREEN);
        } else {
            setState(State.WAIT_SCREEN);
        }
    }

    /** Stops a running session from outside (a command). */
    public static void requestStop() {
        if (isRunning()) {
            stop("stopped by user");
        }
    }

    /** Counters of the last/current run, for the chat line and tests:
     *  {clicks, hotbar swaps, pickup clicks, planned clicks, replans, most clicks in one tick, storage clicks}. */
    public static int[] counters() {
        return new int[]{clicksSent, hotbarSwaps, pickupClicks, planned, replans, maxClicksOneTick,
                storage == null ? 0 : storage.clicks()};
    }

    /** Whether the last plan was proven minimal (false only past the planner's search budget). For tests. */
    public static boolean lastPlanProven() {
        return planProven;
    }

    private static void pollKeys(Minecraft client) {
        InventorySorterConfig cfg = InventorySorterConfig.getInstance();
        if (!cfg.isEnabledRaw() || client.getWindow() == null) {
            keysDown.clear();
            openKeyDown = false;
            return;
        }
        int openKey = cfg.getOpenMenuKey();
        boolean openDown = openKey != KeyUtil.NONE && KeyUtil.isBindDown(client.getWindow(), openKey);
        if (openDown && !openKeyDown && McCompat.screen(client) == null && cfg.isEnabled() && client.player != null
                && !isRunning()) {
            client.setScreenAndShow(new InventorySorterScreen(null));
        }
        openKeyDown = openDown;
        Map<String, Integer> binds = cfg.layoutKeysView();
        if (binds.isEmpty()) {
            keysDown.clear();
            return;
        }
        for (Map.Entry<String, Integer> e : binds.entrySet()) {
            int code = e.getValue();
            boolean down = KeyUtil.isBindDown(client.getWindow(), code);
            boolean was = keysDown.contains(code);
            if (down) {
                keysDown.add(code);
            } else {
                keysDown.remove(code);
            }
            // A key typed into chat or a menu is not a keybind press.
            if (!down || was || McCompat.screen(client) != null || !cfg.isEnabled() || client.player == null) {
                continue;
            }
            applyByName(e.getKey());
        }
    }

    /** Applies the named layout with a chat line either way - the keybind, the menu and {@code /invsort apply}. */
    public static void applyByName(String rawName) {
        InventorySorterConfig cfg = InventorySorterConfig.getInstance();
        if (!cfg.isEnabled()) {
            ModChat.send(InventorySorterCommands.FEATURE, ModChat.bad("Auto Inventory Sorter is OFF - turn it on in its tab first."));
            return;
        }
        if (isRunning()) {
            ModChat.send(InventorySorterCommands.FEATURE, ModChat.bad("Already sorting - /invsort stop first."));
            return;
        }
        String name = rawName == null ? "" : rawName.trim();
        InventoryLayout layout = InventoryLayoutStore.getInstance().get(name);
        if (layout == null) {
            ModChat.send(InventorySorterCommands.FEATURE, ModChat.bad("No layout called "), ModChat.value(name),
                    ModChat.text(" - "), ModChat.value("/invsort"), ModChat.text(" opens the layouts menu."));
            return;
        }
        if (!start(layout)) {
            ModChat.send(InventorySorterCommands.FEATURE, ModChat.bad("Couldn't start - no player, or a run is already going."));
            return;
        }
        ModChat.send(InventorySorterCommands.FEATURE, ModChat.good("Sorting to "), ModChat.value(layout.name()),
                ModChat.dim(" (" + layout.size() + " slot" + (layout.size() == 1 ? "" : "s")
                        + (layout.loadout() > 0 ? ", loadout " + layout.loadout() + " first" : "") + ")"));
    }

    private static void tick(Minecraft client) {
        try {
            tickUnsafe(client);
        } catch (Exception e) {
            LOGGER.error("[InvSort] tick failed - stopping", e);
            if (isRunning()) {
                stop("internal error (see log)");
            }
        }
    }

    private static void tickUnsafe(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        if (state == State.WAITING_LOBBY) {
            if (client.player == null) {
                stop("player gone");
            } else if (McCompat.screen(client) != null) {
                stop("you opened a menu while it waited for the lobby");
            } else if (lobbyLeftMs() <= 0) {
                beginRun(client);
            } else {
                com.killer560.hub.notify.ModOverlayMessage.show("Waiting for the lobby: sorting in "
                        + String.format(java.util.Locale.ROOT, "%.1f", lobbyLeftMs() / 1000.0) + "s", 300);
            }
            return;
        }
        blockMovementKeys(client);
        if (client.player == null) {
            stop("player gone");
            return;
        }
        stateTicks++;
        Screen screen = McCompat.screen(client);
        switch (state) {
            case LOADOUT -> loadoutTick(client);
            case STORAGE -> {
                boolean mayClick = pacedAndStill();
                if (state == State.IDLE) {
                    return; // gave up waiting for a still tick
                }
                InvSortStorage.Result r = storage.tick(client, mayClick);
                if (r == InvSortStorage.Result.FAILED) {
                    stop(storage.failure());
                } else if (r == InvSortStorage.Result.DONE) {
                    LOGGER.info("[InvSort] Storage done: {} fetched, {} dumped, {} clicks, not found {}",
                            storage.fetched(), storage.dumped(), storage.clicks(), storage.notFound());
                    setState(State.WAIT_SCREEN);
                }
            }
            case WAIT_SCREEN -> {
                if (instantRun) {
                    if (screen != null) {
                        if (stateTicks > WAIT_FOR_SCREEN_TICKS) {
                            stop("close the open menu first");
                        }
                        return;
                    }
                    beginSorting(client);
                } else if (screen instanceof SortingInventoryScreen) {
                    beginSorting(client);
                } else if (screen == null || screen instanceof InventoryScreen) {
                    // Opened here, not from the command: the chat screen closes itself after a command runs. A plain
                    // inventory the player had open is replaced by the one that blocks clicks.
                    openedByUs = openedByUs || screen == null;
                    client.setScreenAndShow(new SortingInventoryScreen(client.player));
                } else if (ActionGate.containerScreenOpen(client)) {
                    stop("close the open menu first - it only sorts in your own inventory");
                } else if (stateTicks > WAIT_FOR_SCREEN_TICKS) {
                    stop("could not open your inventory (another screen stayed open)");
                }
            }
            case SORTING -> sortTick(client, screen);
            default -> {
            }
        }
    }

    /** Selects the layout's Hypixel loadout through Loadout Keybinds' own "Open, Swap, Close" (one /loadouts, one click, one
     *  close, all through ActionGate), then goes on to storage or the sort. */
    private static void loadoutTick(Minecraft client) {
        if (!loadoutSent) {
            if (com.killer560.hub.loadoutkeybinds.LoadoutSwapper.requestLoadout(client, targetLayout.loadout())) {
                loadoutSent = true;
                stateTicks = 0;
            } else if (stateTicks > WAIT_FOR_SCREEN_TICKS) {
                stop("could not start the loadout swap (close the open menu first)");
            }
            return;
        }
        if (com.killer560.hub.loadoutkeybinds.LoadoutSwapper.isBusy()) {
            if (stateTicks > LOADOUT_TIMEOUT_TICKS) {
                stop("the loadout swap took too long");
            }
            return;
        }
        if (!com.killer560.hub.loadoutkeybinds.LoadoutSwapper.lastSwapped()) {
            stop("loadout " + targetLayout.loadout() + " was not selected, so nothing was sorted");
            return;
        }
        LOGGER.info("[InvSort] Loadout {} selected for {}", targetLayout.loadout(), targetLayout.name());
        afterLoadout(client);
    }

    /**
     * The pacing wait and the still-input wait, shared by the storage visits and the sort.
     * @return true when a click may go out this tick
     */
    private static boolean pacedAndStill() {
        if (waitTicks > 0) {
            waitTicks--;
            return false;
        }
        if (!InventoryWalkFeature.serverHasStillInput()) {
            if (++stillWait > STILL_TIMEOUT_TICKS) {
                stop("you kept moving - the server never saw you stand still");
            }
            return false;
        }
        stillWait = 0;
        return true;
    }

    private static void beginSorting(Minecraft client) {
        startingItems = itemCounts(client);
        waitTicks = 0;
        if (!replan(client)) {
            return;
        }
        setState(State.SORTING);
    }

    /** Plans from the live inventory. @return false if it stopped instead. */
    private static boolean replan(Minecraft client) {
        Inventory inv = client.player.getInventory();
        String[] cur = new String[36];
        boolean[] locked = new boolean[36];
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            // The exact key (InvSortMatch): a layout slot saved with one takes that copy only (two runebooks are two
            // items, killer560 2026-11-02); an older slot holding just the id still takes any copy.
            cur[i] = stack.isEmpty() ? null : InvSortMatch.exact(stack);
            if (!stack.isEmpty() && cur[i] == null) {
                cur[i] = "?" + i; // unidentifiable: never matches a layout slot, never moved for one
            }
            locked[i] = lockedInPlace(stack) || InvSortStorage.SKYBLOCK_MENU.equalsIgnoreCase(InvSortMatch.id(cur[i]))
                    || targetLayout.isFree(i); // a Free slot is left out of the layout: never claimed, never moved
        }
        long t0 = System.nanoTime();
        InvSortPlanner.Plan p = InvSortPlanner.plan(cur, locked, targetLayout.wants(), 9, PLAN_BUDGET);
        plan = p.clicks();
        planStep = 0;
        planProven = p.provenMinimal();
        planned += plan.size();
        unresolved.clear();
        unresolved.addAll(p.unresolved());
        expected = cur;
        expectedCursor = null;
        LOGGER.info("[InvSort] Plan for {}: {} click(s) {} (bound {}, {}), {} unresolved, {} us", targetLayout.name(),
                plan.size(), InvSortPlanner.describe(plan), p.lowerBound(),
                p.provenMinimal() ? "proven minimal" : "search budget hit", unresolved.size(),
                (System.nanoTime() - t0) / 1000);
        return true;
    }

    /** Holds every real movement key and sneak off, discrete key state only - the same "stop all movement" step
     *  {@code Ap3Executor} already uses, never a position/velocity write. Sneak too: GrimAC MultiActionsC flags a click
     *  made while sneaking. */
    private static void blockMovementKeys(Minecraft client) {
        if (client.options == null) {
            return;
        }
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyShift.setDown(false);
    }

    private static void sortTick(Minecraft client, Screen current) {
        AbstractContainerMenu menu;
        Screen ownScreen = null;
        if (instantRun) {
            if (current != null) {
                stop("a menu opened");
                return;
            }
            menu = client.player.inventoryMenu;
        } else {
            if (!(current instanceof SortingInventoryScreen screen)) {
                stop("inventory closed");
                return;
            }
            ownScreen = screen;
            menu = screen.getMenu();
        }
        if (!itemCounts(client).equals(startingItems)) {
            stop("your inventory changed while sorting");
            return;
        }
        if (!pacedAndStill()) {
            return;
        }
        if (clicksSent > MAX_CLICKS) {
            stop("safety limit reached");
            return;
        }
        if (planStep >= plan.size()) {
            finish(client);
            return;
        }
        if (!matchesExpected(client)) {
            if (!menu.getCarried().isEmpty() || ++replans > MAX_REPLANS) {
                stop("the server did not take a move");
                return;
            }
            LOGGER.info("[InvSort] Inventory is not what the plan predicted at step {} - planning again", planStep);
            replan(client);
            return;
        }
        boolean gate = instantRun ? ActionGate.tryActHeadless(ActionGate.Actor.INVENTORY_SORTER, menu)
                : ActionGate.tryAct(ActionGate.Actor.INVENTORY_SORTER, ownScreen);
        if (!gate) {
            return; // denied this tick - nothing moved, retry next tick
        }
        int thisTick = 0;
        do {
            send(client, menu, plan.get(planStep++));
            thisTick++;
        } while (instantRun && planStep < plan.size());
        maxClicksOneTick = Math.max(maxClicksOneTick, thisTick);
        if (instantRun) {
            finish(client);
            return;
        }
        paced();
    }

    private static void send(Minecraft client, AbstractContainerMenu menu, InvSortPlanner.Click c) {
        clicksSent++;
        if (c.swap()) {
            hotbarSwaps++;
            client.gameMode.handleContainerInput(menu.containerId, toContainerSlot(c.slot()), c.hotbar(),
                    ContainerInput.SWAP, client.player);
        } else {
            pickupClicks++;
            client.gameMode.handleContainerInput(menu.containerId, toContainerSlot(c.slot()), 0, ContainerInput.PICKUP,
                    client.player);
        }
        String[] next = InvSortPlanner.simulateWithCursor(expected, expectedCursor, c);
        expectedCursor = next[next.length - 1];
        expected = java.util.Arrays.copyOf(next, next.length - 1);
    }

    private static String expectedCursor;

    /** Whether the live inventory (and cursor) holds what the plan predicts at this step. */
    private static boolean matchesExpected(Minecraft client) {
        Inventory inv = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            String id = stack.isEmpty() ? null : InvSortMatch.exact(stack);
            if (!stack.isEmpty() && id == null) {
                id = "?" + i;
            }
            String want = expected[i];
            boolean ok = want == null ? id == null
                    : id != null && (want.equalsIgnoreCase(id) || (want.startsWith("?") && id.startsWith("?")));
            if (!ok) {
                return false;
            }
        }
        ItemStack carried = client.player.containerMenu == null ? ItemStack.EMPTY : client.player.containerMenu.getCarried();
        return (expectedCursor == null) == carried.isEmpty();
    }

    static boolean lockedInPlace(ItemStack stack) {
        ItemProtectConfig protect = ItemProtectConfig.getInstance();
        return protect.isLockInPlace() && ItemProtect.isExplicitlyProtected(stack);
    }

    /** Sets the wait before the next click: {@code Ticks Between Moves} plus a random extra. */
    static void paced() {
        InventorySorterConfig cfg = InventorySorterConfig.getInstance();
        int extra = cfg.getRandomExtraTicks();
        // The click itself took this tick; wait the rest so clicks land ticksBetweenMoves (+ random) apart.
        waitTicks = Math.max(0, cfg.getTicksBetweenMoves() - 1)
                + (extra <= 0 ? 0 : ThreadLocalRandom.current().nextInt(extra + 1));
    }

    /**
     * {@link Inventory} index (0-35) -&gt; the vanilla Inventory screen's own container-slot index: 9-35 map to
     * themselves, the hotbar 0-8 is container slots 36-44 ({@code InventoryMenu}'s layout, also relied on by
     * {@code slotbinds/SlotBindsFeature}).
     */
    static int toContainerSlot(int invIndex) {
        return invIndex >= 9 ? invIndex : invIndex + 36;
    }

    /** Identity -&gt; total count over inventory slots 0-35 and the cursor. */
    private static Map<String, Integer> itemCounts(Minecraft client) {
        Map<String, Integer> out = new HashMap<>();
        Inventory inv = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            add(out, inv.getItem(i));
        }
        if (client.player.containerMenu != null) {
            add(out, client.player.containerMenu.getCarried());
        }
        return out;
    }

    private static void add(Map<String, Integer> out, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String id = ItemIdentity.of(stack);
        out.merge(id == null ? "?" + stack.getItem() : id, stack.getCount(), Integer::sum);
    }

    private static void finish(Minecraft client) {
        String storageNote = "";
        if (storage != null && (storage.fetched() > 0 || storage.dumped() > 0)) {
            storageNote = ", " + storage.fetched() + " taken from storage, " + storage.dumped() + " put away";
        }
        ModChat.send(InventorySorterCommands.FEATURE, ModChat.good("Sorted "), ModChat.value(targetLayout.name()),
                ModChat.text(" (" + clicksSent + " click" + (clicksSent == 1 ? "" : "s") + storageNote + ")"),
                unresolved.isEmpty() ? ModChat.text("") : ModChat.dim(" - " + unresolved.size()
                        + " slot(s) of the layout could not be filled (item not in your inventory, or locked in place)."));
        boolean fullTold = storage != null && storage.dumpFull(); // the page-full line already gave the count
        if (!fullTold) {
            // Put Others In: None leaves them where they are, by design - say so (killer560, 2026-11-03: "it kept a bunch of
            // items in my inventory that arent a part of it").
            Inventory inv = client.player.getInventory();
            int left = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty() && !targetLayout.isFree(i) && targetLayout.wantAt(i) == null
                        && !InvSortStorage.SKYBLOCK_MENU.equalsIgnoreCase(ItemIdentity.of(st))) {
                    left++;
                }
            }
            if (left > 0) {
                ModChat.send(InventorySorterCommands.FEATURE, ModChat.dim(left + " item" + (left == 1 ? " was" : "s were")
                        + " not in the layout and left in your inventory ("
                        + (targetLayout.dumpPage() == null ? "Put Others In: None"
                        : "they could not be put into " + targetLayout.dumpPage()) + ")."));
            }
        }
        if (storage != null && !storage.notFound().isEmpty()) {
            ModChat.send(InventorySorterCommands.FEATURE, ModChat.dim("Not in any storage page the Storage Overlay has seen: "
                    + storage.notFound().keySet()));
        }
        LOGGER.info("[InvSort] Sorted {}: {} clicks ({} hotbar swaps, {} pickups; planned {}, {} replans, {} in one tick at"
                        + " most, {}), {} unresolved", targetLayout.name(), clicksSent, hotbarSwaps, pickupClicks, planned,
                replans, maxClicksOneTick, instantRun ? "instant" : "paced", unresolved.size());
        end(client);
    }

    private static void stop(String reason) {
        if (state == State.LOADOUT) {
            com.killer560.hub.loadoutkeybinds.LoadoutSwapper.cancel(Minecraft.getInstance());
        }
        ModChat.send(InventorySorterCommands.FEATURE, ModChat.bad("Stopped: "), ModChat.text(reason));
        LOGGER.info("[InvSort] Stopped: {} ({} clicks)", reason, clicksSent);
        end(Minecraft.getInstance());
    }

    private static void end(Minecraft client) {
        boolean close = openedByUs && McCompat.screen(client) instanceof InventoryScreen;
        setState(State.IDLE);
        plan = null;
        openedByUs = false;
        if (close && client.player != null) {
            // It opened the inventory itself, so it closes it again.
            client.player.closeContainer();
        }
    }

    private static void setState(State newState) {
        state = newState;
        stateTicks = 0;
    }
}
