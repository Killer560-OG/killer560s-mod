package com.killer560.hub.fastleap;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.List;
import java.util.Locale;
import com.killer560.hub.compat.McCompat;

/**
 * Performs a Spirit Leap - port of QUOI {@code utils/skyblock/player/LeapManager.kt} (+ the parts of its
 * {@code ContainerManager}/{@code ContainerTask} a leap uses), shared by {@link FastLeapFeature} and
 * {@link I4LeapFeature}.
 * <p>
 * Flow (all on the client thread, driven from START_CLIENT_TICK like QUOI's task runner): resolve the target teammate
 * (by name, or first alive teammate of a class) -&gt; if the leap menu is already open click straight away, if another
 * screen is open queue it until the screen closes, else, one step a tick and each through {@link ActionGate} (killer560,
 * 2026-10-13: "swap to your leap, open it, then leap to the person, then swap back to the item you want"): swap to the
 * Spirit Leap (skyblock id {@code INFINITE_SPIRIT_LEAP}/{@code SPIRIT_LEAP}) when it is not held, only ever from the
 * hotbar -&gt; use it -&gt; wait up to {@link #MENU_TIMEOUT_TICKS} for the "Spirit Leap" container -&gt; wait up to
 * {@link #ITEM_TIMEOUT_TICKS} for a container slot (never the 36 player-inventory slots) whose item name is the target
 * -&gt; PICKUP-click it and close the menu -&gt; on a later tick swap to Return To ({@link FastLeapConfig#getReturnToSlot})
 * when the leap had to swap and the caller asked for it. Every failure says which step in chat and still swaps back.
 * <p>
 * QUOI semantics kept: movement keys are held up for the whole leap (or, in Fast mode, only from the menu opening
 * until the click); "Block inputs" blocks keyboard/mouse input for the same window (see {@link #blocksInput()});
 * right-click (use) input is suppressed after using the leap until the leap finishes.
 * QUOI's leap cooldown is kept: after a successful leap no new leap uses the item for {@code 48 * mage multiplier}
 * ticks ("On cooldown"), so a queued/auto leap never blocks inputs waiting for a menu that can't open.
 * Escape is never blocked (see {@link FastLeapInput}).
 * <p>
 * Hidden menu (2026-09-15, killer560: "for fastleap dont render the leap menu opening"): when the leap menu this
 * manager's own item use opened arrives, {@code FastLeapHideMenuMixin} cancels {@code Minecraft.setScreen} for it
 * ({@link #interceptLeapScreen}). Vanilla has already assigned {@code player.containerMenu} by then
 * ({@code MenuScreens.ScreenConstructor.fromPacket}), so the slot packets still fill the menu and the click works
 * exactly as before - but no screen is ever shown: no chest, no custom leap menu, no darkened background, the mouse
 * stays grabbed and the hotbar/crosshair stay up. Every exit path ({@link #finish}, {@link #abort}, world change)
 * closes the headless menu, so it can never linger. A leap menu the player opened themselves, an already-open menu,
 * or one that arrives after the leap timed out is shown normally.
 */
public final class LeapManager {

    private static final String LEAP_MENU_TITLE = "spirit leap";
    /** Ticks from the item use to the leap menu arriving before "the leap menu did not open" (polled once a tick). */
    static final int MENU_TIMEOUT_TICKS = 40;
    /** Ticks from the menu arriving to the target's head showing before "not in the leap menu". */
    static final int ITEM_TIMEOUT_TICKS = 40;
    /** Ticks a step may wait for {@link ActionGate} to give it a tick before the leap gives up on it. */
    static final int GATE_TIMEOUT_TICKS = 20;
    private static final long QUEUE_EXPIRY_MS = 10_000L;
    private static final int FALLBACK_AFTER_LOADED_TICKS = 2;

    /** A leap target: a teammate name to click in the menu, plus a display string for messages. */
    public record Target(String name, String display) {
    }

    private static final class Request {
        final Target target;
        final Target fallback;
        final boolean blockInput;
        final boolean fastMode;
        final boolean swapBack;
        final long createdAtMs = System.currentTimeMillis();

        Request(Target target, Target fallback, boolean blockInput, boolean fastMode, boolean swapBack) {
            this.target = target;
            this.fallback = fallback;
            this.blockInput = blockInput;
            this.fastMode = fastMode;
            this.swapBack = swapBack;
        }
    }

    private enum Step {
        /** Swap to the leap item's hotbar slot (only when it is not held). */
        SWAP,
        /** Use the leap item. */
        USE,
        /** Wait for the menu and click the target's head. */
        MENU
    }

    private static final class Active {
        final Request request;
        final boolean preOpened;
        Target current;
        Step step = Step.MENU;
        int leapSlot = -1;
        int gateWaitTicks;
        boolean usedItem;
        /** The hotbar slot to swap to once the leap is done, or -1 for none. */
        int returnSlot = -1;
        int waitTicks;
        AbstractContainerMenu menu;
        int itemWaitTicks;
        int loadedNoMatchTicks;
        boolean fastBlockActive;
        boolean fastBlockFinished;
        /** The menu was swallowed by {@link #interceptLeapScreen} - it has no screen, so it must be closed by hand. */
        boolean hidden;

        Active(Request request, boolean preOpened) {
            this.request = request;
            this.preOpened = preOpened;
            this.current = request.target;
        }
    }

    private static Request incoming;
    private static Request pending;
    private static Active active;
    private static boolean useInputSuppressed = false;
    private static boolean movementSuppressed = false;
    private static long lastLeapMs = 0L;
    /** QUOI {@code leapCD}: ticks until the Spirit Leap is usable again ({@code 48 * mage multiplier} after a leap). */
    private static double leapCdTicks = 0.0;
    private static final double LEAP_COOLDOWN_TICKS = 48.0;
    /** Return To still to be done after the leap: the slot to swap to (-1 none) and the leap slot it swaps away from. */
    private static int returnTo = -1;
    private static int returnFrom = -1;
    private static int returnWaitTicks = 0;
    /** The last leap's steps, "tick step" (ActionGate ticks), for the testkit. */
    private static final List<String> STEP_LOG = new java.util.ArrayList<>();

    private LeapManager() {
    }

    // ------------------------------------------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------------------------------------------

    /** QUOI {@code LeapManager.leap(name)} - leap to a teammate by name. */
    public static void leap(String name, boolean blockInput, boolean fastMode, boolean swapBack) {
        leap(name, null, blockInput, fastMode, swapBack);
    }

    /** Leap to {@code name}; if they can't be leapt to (not a live teammate / not in the menu), leap to {@code fallback}. */
    public static void leap(String name, Target fallback, boolean blockInput, boolean fastMode, boolean swapBack) {
        if (name == null || name.isBlank() || !DungeonState.isInDungeon()) {
            return;
        }
        Target target = resolveName(name.trim());
        if (target == null) {
            if (fallback != null) {
                submit(new Request(fallback, null, blockInput, fastMode, swapBack));
            } else {
                ModChat.send("Fast Leap", ModChat.bad("Failed to leap! "), ModChat.value(name.trim()), ModChat.bad(" not found"));
            }
            return;
        }
        submit(new Request(target, fallback, blockInput, fastMode, swapBack));
    }

    /** QUOI {@code LeapManager.leap(clazz)} - leap to the first alive teammate of that class. */
    public static void leap(DungeonClass clazz, boolean blockInput, boolean fastMode, boolean swapBack) {
        if (clazz == null || !DungeonState.isInDungeon()) {
            return;
        }
        Target target = resolveClass(clazz);
        if (target == null) {
            ModChat.send("Fast Leap", ModChat.bad("Failed to leap! "), ModChat.value(clazz.displayName()), ModChat.bad(" not found"));
            return;
        }
        submit(new Request(target, null, blockInput, fastMode, swapBack));
    }

    /** @return a target for {@code name} (canonical teammate spelling), or null if the tab list shows they're not a
     *  live teammate. When the tab list couldn't be read at all (e.g. an unfamiliar server layout) the name is still
     *  tried against the leap menu. */
    public static Target resolveName(String name) {
        Teammates.Teammate t = Teammates.byName(name);
        if (t != null) {
            return t.dead() ? null : new Target(t.name(), t.name());
        }
        if (name.equalsIgnoreCase(Teammates.selfName())) {
            return null;
        }
        return Teammates.isKnown() ? null : new Target(name, name);
    }

    /** @return a target for the first alive teammate of {@code clazz}, or null. */
    public static Target resolveClass(DungeonClass clazz) {
        Teammates.Teammate t = Teammates.firstAliveOfClass(clazz);
        return t == null ? null : new Target(t.name(), t.name() + " (" + clazz.displayName() + ")");
    }

    /** True while a leap is queued, waiting, running, or still has its Return To swap to make. */
    public static boolean isBusy() {
        return incoming != null || pending != null || active != null || returnTo >= 0;
    }

    /** The last leap's steps as "tick step", oldest first (testkit). */
    public static List<String> stepLog() {
        return new java.util.ArrayList<>(STEP_LOG);
    }

    private static void logStep(String step) {
        STEP_LOG.add(ActionGate.currentTick() + " " + step);
        FastLeapFeature.LOGGER.info("[FastLeap] step {} at tick {}", step, ActionGate.currentTick());
    }

    public static long lastLeapMs() {
        return lastLeapMs;
    }

    /** QUOI {@code ContainerTask.blocksInput}: {@code blockInput && (!fastMode || fastBlockActive)} while a leap runs. */
    public static boolean blocksInput() {
        Active a = active;
        return a != null && a.request.blockInput && (!a.request.fastMode || a.fastBlockActive);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Driving
    // ------------------------------------------------------------------------------------------------------------

    private static void submit(Request request) {
        incoming = request;
    }

    static void onChat(String unformatted) {
        if (!"You cannot use this in a solo dungeon!".equals(unformatted)
                && !"There are no other players to teleport to!".equals(unformatted)) {
            return;
        }
        if (active != null) {
            ModChat.send("Fast Leap", ModChat.bad("Failed to leap! You're in a solo dungeon!"));
            finish(active, Result.CANCELLED, null);
        }
    }

    static void onWorldChange() {
        incoming = null;
        pending = null;
        returnTo = -1;
        if (active != null) {
            Active a = active;
            active = null;
            closeHiddenMenu(a);
            a.menu = null;
        }
        restoreUseInput();
        restoreMovement();
    }

    /** Safety net: something threw mid-leap - drop every leap and give the player their inputs back. */
    static void abort(Throwable t) {
        FastLeapFeature.LOGGER.error("[FastLeap] Leap aborted after an exception - inputs restored", t);
        incoming = null;
        pending = null;
        returnTo = -1;
        Active a = active;
        active = null;
        if (a != null) {
            try {
                closeHiddenMenu(a);
            } catch (RuntimeException ignored) {
                // already failing - never throw out of the safety net
            }
        }
        restoreUseInput();
        restoreMovement();
    }

    /**
     * Called from {@code FastLeapHideMenuMixin} at the HEAD of {@code Minecraft.setScreen}. Swallows the leap menu
     * screen when it is the one this manager is waiting for, so a fast/auto leap never shows it.
     *
     * @return true to cancel {@code setScreen} (the menu stays open headless, {@link #poll} clicks it)
     */
    public static boolean interceptLeapScreen(Minecraft client, Screen screen) {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return false;
        }
        Active a = active;
        // only a leap WE started by using the item (not an already-open / manually opened menu), still waiting for
        // its menu (or re-adopting a menu Hypixel re-sent while we were already hiding)
        if (a == null || a.preOpened || !a.usedItem || (a.menu != null && !a.hidden)) {
            return false;
        }
        // nothing else on screen: replacing e.g. chat/pause would close it, so let vanilla show the menu there
        if (McCompat.screen(client) != null || !(screen instanceof AbstractContainerScreen<?> container)) {
            return false;
        }
        LocalPlayer player = client.player;
        // fromPacket assigns containerMenu right before setScreen - this proves we are on the open-screen packet path.
        // Same title test poll() uses to accept the menu (so p3sim.net's menu is hidden exactly when it would be clicked);
        // safe because it only applies within the container timeout of our own item use.
        if (player == null || player.containerMenu != container.getMenu() || leapMenuScreen(container) == null) {
            return false;
        }
        a.menu = container.getMenu();
        a.hidden = true;
        beginFastBlock(a);
        return true;
    }

    /** Close a headless leap menu: tell the server and drop back to the inventory menu - without {@code setScreen(null)},
     *  which would also close whatever screen (pause menu, chat) the player opened in the meantime. */
    private static void closeHiddenMenu(Active a) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (!a.hidden || a.menu == null || player == null || player.containerMenu != a.menu) {
            return;
        }
        player.connection.send(new ServerboundContainerClosePacket(a.menu.containerId));
        player.containerMenu = player.inventoryMenu;
    }

    static void onStartTick(Minecraft client) {
        // QUOI TickEvent.Server: leapCD -= 1
        if (leapCdTicks > 0) {
            leapCdTicks = Math.max(0.0, leapCdTicks - 1.0);
        }
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            return;
        }
        if (useInputSuppressed) {
            suppressUseInput(client);
        }
        Active a = active;
        // ContainerManager: stop movement while the task runs (Fast mode: only while the fast block is active)
        if (a != null && (!a.request.fastMode || a.fastBlockActive)) {
            client.options.keyUp.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyJump.setDown(false);
            client.options.keySprint.setDown(false);
            movementSuppressed = true;
        }

        if (active == null && returnTo >= 0) {
            tickReturn(client, player);
        }
        if (incoming != null) {
            Request req = incoming;
            incoming = null;
            startLeap(client, req);
        }
        // QUOI TickEvent.Server: run a queued leap once no screen/container/task (or Return To swap) is open
        if (pending != null && active == null && returnTo < 0 && McCompat.screen(client) == null
                && player.containerMenu == player.inventoryMenu) {
            Request req = pending;
            pending = null;
            if (System.currentTimeMillis() - req.createdAtMs <= QUEUE_EXPIRY_MS) {
                doLeap(client, req, false);
            }
        }

        a = active;
        if (a == null) {
            return;
        }
        switch (a.step) {
            case SWAP -> stepSwap(client, player, a);
            case USE -> stepUse(client, player, a);
            case MENU -> poll(client);
        }
    }

    /** Step 1: swap to the leap item's hotbar slot, on its own tick. */
    private static void stepSwap(Minecraft client, LocalPlayer player, Active a) {
        if (!isLeapStack(player.getInventory().getItem(a.leapSlot))) {
            finish(a, Result.FAILURE, "the leap item left hotbar slot " + (a.leapSlot + 1));
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.LEAP_HOTBAR)) {
            if (++a.gateWaitTicks > GATE_TIMEOUT_TICKS) {
                finish(a, Result.FAILURE, "no free tick to swap to the leap");
            }
            return;
        }
        select(client, player, a.leapSlot);
        logStep("swap " + (a.leapSlot + 1));
        a.step = Step.USE;
        a.gateWaitTicks = 0;
    }

    /** Step 2: use the leap item, on its own tick; the menu is polled from the next one. */
    private static void stepUse(Minecraft client, LocalPlayer player, Active a) {
        if (player.getInventory().getSelectedSlot() != a.leapSlot || !isLeapStack(player.getMainHandItem())) {
            finish(a, Result.FAILURE, "the leap item is no longer held");
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.LEAP_USE)) {
            if (++a.gateWaitTicks > GATE_TIMEOUT_TICKS) {
                finish(a, Result.FAILURE, "no free tick to use the leap");
            }
            return;
        }
        // action { PlayerUtils.interact() }
        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        a.usedItem = true;
        logStep("use");
        a.step = Step.MENU;
        a.gateWaitTicks = 0;
    }

    /** Last step: the Return To swap, on a tick after the click. Skipped if he changed slot himself meanwhile. */
    private static void tickReturn(Minecraft client, LocalPlayer player) {
        int selected = player.getInventory().getSelectedSlot();
        if (selected == returnTo || selected != returnFrom) {
            returnTo = -1;
            return;
        }
        if (McCompat.screen(client) == null && player.containerMenu == player.inventoryMenu
                && ActionGate.tryAct(ActionGate.Actor.LEAP_HOTBAR)) {
            int slot = returnTo;
            returnTo = -1;
            select(client, player, slot);
            logStep("return " + (slot + 1));
            return;
        }
        if (++returnWaitTicks > GATE_TIMEOUT_TICKS && McCompat.screen(client) == null) {
            ModChat.send("Fast Leap", ModChat.bad("Could not swap back to hotbar slot " + (returnTo + 1)));
            returnTo = -1;
        }
    }

    /** A hotbar swap the game mode knows about, so its own tick does not send the slot a second time (LESSONS.md). */
    private static void select(Minecraft client, LocalPlayer player, int slot) {
        if (player.getInventory().getSelectedSlot() == slot) {
            return;
        }
        player.getInventory().setSelectedSlot(slot);
        if (client.gameMode instanceof com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker invoker) {
            invoker.killer560smod$invokeEnsureHasSentCarriedItem();
        } else {
            player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
        }
    }

    static void onEndTick(Minecraft client) {
        // Deliberately does NOT poll.
        //
        // This was a second poll(client) in the same tick - onStartTick already ends with one, unconditionally
        // (see the end of that method). The two did the same work, but this one ran AFTER the player's own
        // movement packet, so the leap-menu container click it sends is exactly the `Post` shape GrimAC
        // flagged 808 times on Breaker Aura before that feature was moved to START.
        //
        // Removing it costs at most one tick of latency: a leap that becomes ready during this tick now fires
        // at the next tick's START instead of at this tick's END. The poll itself is unchanged and still runs
        // every tick.
    }

    private static void startLeap(Minecraft client, Request req) {
        // QUOI: an already-open menu only counts if its title is exactly "Spirit Leap" (a substring match would also
        // take e.g. a Bazaar/AH page for the item and close it on timeout)
        boolean menuOpen = isExactLeapMenu(McCompat.screen(client));
        if (active == null && pending == null && menuOpen) {
            doLeap(client, req, true);
        } else if (McCompat.screen(client) != null || active != null) {
            pending = req;
            ModChat.send("Fast Leap", ModChat.text("Queued leap to "), ModChat.value(req.target.display()));
        } else {
            doLeap(client, req, false);
        }
    }

    private static void doLeap(Minecraft client, Request req, boolean preOpened) {
        if (active != null) {
            return;
        }
        // QUOI doLeap: never use the item (and block inputs/movement waiting for a menu that can't open) on cooldown
        // (an already-open menu proves the leap is usable, so the estimate never refuses clicking it)
        if (leapCdTicks > 0 && !preOpened) {
            ModChat.send("Fast Leap", ModChat.bad("Failed to leap! On cooldown: "
                    + String.format(Locale.ROOT, "%.1f", leapCdTicks / 20.0) + "s"));
            return;
        }
        LocalPlayer player = client.player;
        Active a = new Active(req, preOpened);
        STEP_LOG.clear();
        if (!preOpened) {
            int selected = player.getInventory().getSelectedSlot();
            // the hotbar only: an item in the inventory is never moved (killer560, 2026-10-13)
            int leapSlot = isLeapStack(player.getInventory().getItem(selected)) ? selected : findLeapSlot(player);
            if (leapSlot < 0) {
                ModChat.send("Fast Leap", ModChat.bad("No Spirit Leap or Infinileap in your hotbar - not leaping"));
                return;
            }
            a.leapSlot = leapSlot;
            boolean already = leapSlot == selected;
            a.step = already ? Step.USE : Step.SWAP;
            suppressUseInput(client);
            if (req.swapBack && !already) {
                int want = FastLeapConfig.getInstance().getReturnToSlot();
                int slot = want == FastLeapConfig.RETURN_PREVIOUS ? selected : want;
                a.returnSlot = slot == leapSlot ? -1 : slot;
            }
            // nothing is sent here: each step goes out on its own tick from onStartTick
        }
        active = a;
        if (preOpened) {
            beginFastBlock(a);
        }
        FastLeapFeature.LOGGER.info("[FastLeap] Leap started -> {} (preOpened={}, fallback={})", req.target.name(), preOpened,
                req.fallback == null ? "none" : req.fallback.name());
        if (preOpened) {
            poll(client);
        }
    }

    private enum Result {
        SUCCESS, FAILURE, CANCELLED
    }

    private static void poll(Minecraft client) {
        Active a = active;
        LocalPlayer player = client.player;
        if (a == null || player == null || client.gameMode == null) {
            return;
        }
        if (a.menu == null) {
            if (!a.preOpened && !a.usedItem) {
                return;
            }
            AbstractContainerScreen<?> screen = leapMenuScreen(McCompat.screen(client));
            if (screen != null) {
                a.menu = screen.getMenu();
                beginFastBlock(a);
            } else if (McCompat.screen(client) instanceof AbstractContainerScreen<?> other && player.containerMenu != player.inventoryMenu) {
                finish(a, Result.FAILURE, "Wrong container name. Got " + other.getTitle().getString() + ", needed Spirit Leap");
                return;
            } else {
                if (++a.waitTicks > MENU_TIMEOUT_TICKS) {
                    finish(a, Result.FAILURE, "the leap menu did not open");
                }
                return;
            }
        }
        if (player.containerMenu != a.menu) {
            finish(a, Result.FAILURE, "Container changed before click");
            return;
        }
        List<Slot> slots = a.menu.slots;
        int containerSlotCount = Math.max(0, slots.size() - 36);
        boolean loaded = false;
        Slot match = null;
        // Names in real container-slot order, so the Leap Order editor can show the same order the live menu uses
        // even for someone who never opens the custom leap menu (2026-09-16).
        List<String> slotOrder = new java.util.ArrayList<>(4);
        for (int i = 0; i < containerSlotCount; i++) {
            Slot slot = slots.get(i);
            ItemStack item = slot.getItem();
            if (item.isEmpty()) {
                continue;
            }
            loaded = true;
            // Heads only: a leap menu's filler/"Close" items would otherwise be recorded as teammates and as the
            // leap order (PartyTracker normalises the rank prefix off what it is given).
            if (item.is(net.minecraft.world.item.Items.PLAYER_HEAD)) {
                String plain = com.killer560.hub.util.ChatObserver.strip(item.getHoverName().getString());
                if (!plain.isEmpty()) {
                    slotOrder.add(plain);
                }
            }
            if (match == null && matchesName(item, a.current.name())) {
                match = slot;
            }
        }
        if (loaded && !slotOrder.isEmpty()) {
            com.killer560.hub.leapmenu.PartyTracker.noteTeammates(slotOrder);
        }
        if (match == null && loaded && a.request.fallback != null && a.current == a.request.target) {
            if (++a.loadedNoMatchTicks >= FALLBACK_AFTER_LOADED_TICKS) {
                FastLeapFeature.LOGGER.info("[FastLeap] {} not in leap menu - using backup {}", a.current.name(), a.request.fallback.name());
                a.current = a.request.fallback;
                a.itemWaitTicks = 0;
            }
            return;
        }
        if (match == null) {
            if (++a.itemWaitTicks > ITEM_TIMEOUT_TICKS) {
                finish(a, Result.FAILURE, "not in the leap menu");
            }
            return;
        }
        Screen shown = McCompat.screen(client);
        boolean mayClick = a.hidden ? ActionGate.tryActHeadless(ActionGate.Actor.LEAP_MENU, a.menu)
                : ActionGate.tryAct(ActionGate.Actor.LEAP_MENU, leapMenuScreen(shown) != null ? shown : null);
        if (!mayClick) {
            if (++a.gateWaitTicks > GATE_TIMEOUT_TICKS) {
                finish(a, Result.FAILURE, "no free tick to click the leap menu");
            }
            return;
        }
        client.gameMode.handleContainerInput(a.menu.containerId, match.index, 0, ContainerInput.PICKUP, player);
        logStep("click " + match.index);
        // finishClick(): the fast block ends with the last click
        a.fastBlockActive = false;
        a.fastBlockFinished = true;
        finish(a, Result.SUCCESS, null);
    }

    private static void beginFastBlock(Active a) {
        if (a.request.fastMode && !a.fastBlockFinished) {
            a.fastBlockActive = true;
        }
    }

    private static void finish(Active a, Result result, String failure) {
        if (active != a) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        // ContainerManager cleanup: close the task's own menu if it's still open
        if (player != null && a.menu != null && player.containerMenu == a.menu) {
            if (a.hidden) {
                closeHiddenMenu(a);
            } else {
                player.closeContainer();
            }
        }
        switch (result) {
            case SUCCESS -> {
                lastLeapMs = System.currentTimeMillis();
                leapCdTicks = LEAP_COOLDOWN_TICKS * Teammates.mageCooldownMultiplier();
                ModChat.send("Fast Leap", ModChat.good("Leaping to "), ModChat.value(a.current.display()));
            }
            case FAILURE -> ModChat.send("Fast Leap", ModChat.bad("Failed to leap to "), ModChat.value(a.current.display()),
                    ModChat.bad(": " + failure));
            case CANCELLED -> {
            }
        }
        FastLeapFeature.LOGGER.info("[FastLeap] Leap to {} finished: {}{}", a.current.name(), result, failure == null ? "" : " (" + failure + ")");
        // Return To goes out on a later tick (tickReturn), after the click and the menu's close - whatever the result
        if (player != null && a.returnSlot >= 0 && a.returnSlot <= 8 && a.leapSlot >= 0) {
            returnTo = a.returnSlot;
            returnFrom = a.leapSlot;
            returnWaitTicks = 0;
        }
        active = null;
        restoreUseInput();
        restoreMovement();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------

    static AbstractContainerScreen<?> leapMenuScreen(Screen screen) {
        if (screen instanceof AbstractContainerScreen<?> container) {
            // stripped first: a title with formatting codes between the words ("§dSpirit §dLeap") would
            // otherwise never contain the plain phrase
            String title = ChatObserver.strip(container.getTitle().getString());
            if (title.toLowerCase(Locale.ROOT).contains(LEAP_MENU_TITLE) || isLeapMenuTitle(title)) {
                return container;
            }
        }
        return null;
    }

    private static boolean isExactLeapMenu(Screen screen) {
        return screen instanceof AbstractContainerScreen<?> container && isLeapMenuTitle(container.getTitle().getString());
    }

    /** Exact leap menu titles - QUOI {@code equalsOneOf("Spirit Leap", "Teleport to Player")} (the second is the
     *  Infinileap title). Exact so a Bazaar/AH page for the item is never hidden or clicked. */
    static boolean isLeapMenuTitle(String title) {
        String t = ChatObserver.strip(title);
        return t.equalsIgnoreCase("Spirit Leap") || t.equalsIgnoreCase("Teleport to Player");
    }

    /** A leap item by id, or (p3sim.net, no Hypixel id) by name - the same test {@link #findLeapSlot} makes. */
    private static boolean isLeapStack(ItemStack stack) {
        return isLeapItem(stack) || (skyblockId(stack) == null && !stack.isEmpty()
                && ChatObserver.strip(stack.getHoverName().getString()).contains("Spirit Leap"));
    }

    public static boolean isLeapItem(ItemStack stack) {
        String id = skyblockId(stack);
        return "SPIRIT_LEAP".equals(id) || "INFINITE_SPIRIT_LEAP".equals(id);
    }

    private static int findLeapSlot(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (isLeapItem(player.getInventory().getItem(i))) {
                return i;
            }
        }
        // p3sim.net items may lack Hypixel's custom_data id - fall back to the display name
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && ChatObserver.strip(stack.getHoverName().getString()).contains("Spirit Leap")) {
                return i;
            }
        }
        return -1;
    }

    static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.contains("id") ? tag.getStringOr("id", null) : null;
    }

    /** Exact (case-insensitive) stripped item name, else the name as a whole word inside it (e.g. "[MVP+] Name"). */
    private static boolean matchesName(ItemStack item, String name) {
        // both sides stripped: a head named "§b[MVP§c+§b] Name" and a target name that arrived formatted
        // (from chat / another feature) must still match
        String h = ChatObserver.strip(item.getHoverName().getString()).toLowerCase(Locale.ROOT);
        String n = ChatObserver.strip(name).toLowerCase(Locale.ROOT);
        if (h.isEmpty() || n.isEmpty()) {
            return false;
        }
        if (h.equals(n)) {
            return true;
        }
        int from = 0;
        while (true) {
            int idx = h.indexOf(n, from);
            if (idx < 0) {
                return false;
            }
            int end = idx + n.length();
            boolean startOk = idx == 0 || !isNameChar(h.charAt(idx - 1));
            boolean endOk = end >= h.length() || !isNameChar(h.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = idx + 1;
        }
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static void suppressUseInput(Minecraft client) {
        useInputSuppressed = true;
        KeyMapping use = client.options.keyUse;
        use.setDown(false);
        while (use.consumeClick()) {
            // drain
        }
    }

    private static void restoreUseInput() {
        if (!useInputSuppressed) {
            return;
        }
        useInputSuppressed = false;
        resyncKeys();
    }

    private static void restoreMovement() {
        if (!movementSuppressed) {
            return;
        }
        movementSuppressed = false;
        resyncKeys();
    }

    /** Re-read held keys - only in the world: with a screen open that would press movement keys inside the GUI, and
     *  closing the screen re-syncs them anyway ({@code MouseHandler.grabMouse} calls {@code KeyMapping.setAll()}). */
    private static void resyncKeys() {
        if (McCompat.screen(Minecraft.getInstance()) == null) {
            KeyMapping.setAll();
        }
    }
}
