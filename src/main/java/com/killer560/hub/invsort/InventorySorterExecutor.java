package com.killer560.hub.invsort;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.compat.McCompat;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies a saved {@link InventoryLayout} by clicking slots in the player's own vanilla Inventory screen until every
 * slot the layout manages holds the item it wants - killer560: "I should be able to save item locations in my
 * inventory and have them sorted there."
 * <p>
 * <b>The moves</b> (killer560, 2026-10-08: "If something is going to my hotbar use a hotbar keybind to move it; if it
 * is somewhere else then it does that picking-up style"):
 * <ul>
 *   <li>Destination in the hotbar (0-8): ONE {@code ContainerInput.SWAP} click on the source slot with the hotbar
 *       index as the button - exactly what pressing that number key over the item sends.</li>
 *   <li>Destination in main storage (9-35): {@code PICKUP} the source, {@code PICKUP} the destination (the wanted item
 *       lands, its old occupant comes onto the cursor), and if something came onto the cursor, {@code PICKUP} the
 *       source again to put it down.</li>
 * </ul>
 * A source slot is never one the layout already has right (see {@link #findSourceSlot}), so every move fixes one
 * slot and un-fixes none: a 36-slot layout finishes in at most 36 moves.
 * <p>
 * <b>Pacing</b>: every click waits {@code Ticks Between Moves} plus a random 0..{@code Random Extra Ticks} client
 * ticks, and goes through {@link ActionGate} (one automated interaction per tick, mod-wide).
 * <p>
 * <b>"Cannot open inventory" (fixed 2026-10-08)</b>: {@link #start} used to open the inventory from inside the
 * command, and the chat screen that sent the command closes itself right after - replacing the inventory with
 * nothing, so the wait timed out every time from {@code /invsort}. The screen is now opened from the tick, once no
 * screen is up. It is never opened over another screen: a container (a chest, a Hypixel menu) stops the run.
 * <p>
 * <b>Stops cleanly</b> on: the inventory closing, a world change, a non-inventory screen, and the inventory's
 * CONTENTS changing under it (an item picked up, used or taken by the server) - every move only rearranges, so the
 * multiset of items (cursor included) it started with must hold until it finishes. Movement keys are held off while
 * it runs (discrete key state, never position or velocity - the Ap3Executor "stop all movement" step).
 */
public final class InventorySorterExecutor {

    private static final Logger LOGGER = ModLog.get("killer560smod-invsort");

    private static final int MAX_MOVES = 72;
    private static final int MAX_CLICKS = 250;
    /** Ticks to wait for the inventory screen to come up (and for a closing screen to go away). */
    private static final int WAIT_FOR_SCREEN_TICKS = 40;

    private enum State { IDLE, WAIT_SCREEN, SORTING }

    private static State state = State.IDLE;
    private static InventoryLayout targetLayout;
    private static int stateTicks;
    /** Ticks still to wait before the next click. */
    private static int waitTicks;
    private static boolean openedByUs;

    /** Inventory index (0-35) whose item is on the cursor's way to {@link #pendingDestSlot}, or -1 between moves. */
    private static int pendingSourceSlot = -1;
    private static int pendingDestSlot = -1;
    private static boolean clickedDest;

    private static int moves;
    private static int clicksSent;
    private static int hotbarSwaps;
    private static int pickupClicks;
    private static final Set<Integer> unresolved = new HashSet<>();
    /** The items (identity x count, cursor included) the run started with - see the class doc. */
    private static Map<String, Integer> startingItems = Map.of();

    /** Bound keys held last tick, so a held key applies its layout once. */
    private static final Set<Integer> keysDown = new HashSet<>();

    private InventorySorterExecutor() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("InventorySorterExecutor.tick", InventorySorterExecutor::tick));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("InventorySorterExecutor.keys", InventorySorterExecutor::pollKeys));
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            if (isRunning()) {
                stop("world changed");
            }
        });
    }

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    /** Starts applying {@code layout}; the inventory opens on the next tick with no screen up.
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
        moves = 0;
        clicksSent = 0;
        hotbarSwaps = 0;
        pickupClicks = 0;
        unresolved.clear();
        pendingSourceSlot = -1;
        pendingDestSlot = -1;
        clickedDest = false;
        waitTicks = 0;
        openedByUs = false;
        setState(State.WAIT_SCREEN);
        return true;
    }

    /** Stops a running session from outside (a command). */
    public static void requestStop() {
        if (isRunning()) {
            stop("stopped by user");
        }
    }

    /** Counters of the last/current run, for the chat line and tests: {moves, clicks, hotbar swaps, pickup clicks}. */
    public static int[] counters() {
        return new int[]{moves, clicksSent, hotbarSwaps, pickupClicks};
    }

    // ------------------------------------------------------------------------------------------- keybinds

    private static void pollKeys(Minecraft client) {
        InventorySorterConfig cfg = InventorySorterConfig.getInstance();
        Map<String, Integer> binds = cfg.getLayoutKeys();
        if (binds.isEmpty() || client.getWindow() == null) {
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

    /** Applies the named layout with a chat line either way - the keybind and {@code /invsort apply}. */
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
                ModChat.dim(" (" + layout.size() + " slot" + (layout.size() == 1 ? "" : "s") + ") - you can't walk until it's done."));
    }

    // ------------------------------------------------------------------------------------------- tick

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
        blockMovementKeys(client);
        if (client.player == null) {
            stop("player gone");
            return;
        }
        stateTicks++;
        Screen screen = McCompat.screen(client);
        switch (state) {
            case WAIT_SCREEN -> {
                if (screen instanceof InventoryScreen) {
                    startingItems = itemCounts(client);
                    waitTicks = 0;
                    setState(State.SORTING);
                } else if (screen == null) {
                    // Opened here, not from the command: the chat screen closes itself after a command runs.
                    openedByUs = true;
                    client.setScreenAndShow(new InventoryScreen(client.player));
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

    /** Holds every real movement key off, discrete key state only - the same "stop all movement" step
     *  {@code Ap3Executor} already uses, never a position/velocity write. */
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
    }

    private static void sortTick(Minecraft client, Screen current) {
        if (!(current instanceof InventoryScreen screen)) {
            stop("inventory closed");
            return;
        }
        if (!itemCounts(client).equals(startingItems)) {
            stop("your inventory changed while sorting");
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (moves > MAX_MOVES || clicksSent > MAX_CLICKS) {
            stop("safety limit reached");
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (pendingSourceSlot != -1) {
            continuePickupMove(client, screen, menu);
            return;
        }
        Inventory inv = client.player.getInventory();
        for (Map.Entry<Integer, String> entry : targetLayout.entries().entrySet()) {
            int targetSlot = entry.getKey();
            if (unresolved.contains(targetSlot)) {
                continue;
            }
            String wanted = entry.getValue();
            if (wanted.equalsIgnoreCase(ItemIdentity.of(inv.getItem(targetSlot)))) {
                continue; // already right
            }
            if (lockedInPlace(inv.getItem(targetSlot))) {
                unresolved.add(targetSlot); // Item Protect's Lock In Place holds what is there
                continue;
            }
            int source = findSourceSlot(inv, wanted, targetSlot);
            if (source < 0) {
                unresolved.add(targetSlot);
                continue;
            }
            if (targetSlot < 9) {
                // Hotbar destination: the number-key swap, one click.
                if (!click(client, screen, menu, source, targetSlot, ContainerInput.SWAP)) {
                    return; // gate denied this tick - nothing moved, retry next tick
                }
                hotbarSwaps++;
                moves++;
                pace();
                return;
            }
            if (!click(client, screen, menu, source, 0, ContainerInput.PICKUP)) {
                return;
            }
            pickupClicks++;
            pendingSourceSlot = source;
            pendingDestSlot = targetSlot;
            clickedDest = false;
            pace();
            return;
        }
        finish(client);
    }

    private static void continuePickupMove(Minecraft client, InventoryScreen screen, AbstractContainerMenu menu) {
        if (menu.getCarried().isEmpty()) {
            if (!clickedDest) {
                // Nothing came onto the cursor (the server said no) - re-read the slots next tick.
                clearPending();
                return;
            }
            moves++;
            clearPending();
            pace();
            return;
        }
        if (!clickedDest) {
            if (!click(client, screen, menu, pendingDestSlot, 0, ContainerInput.PICKUP)) {
                return;
            }
            pickupClicks++;
            clickedDest = true;
            pace();
            return;
        }
        // The destination's old item is on the cursor: put it down where the moved item came from.
        if (!click(client, screen, menu, pendingSourceSlot, 0, ContainerInput.PICKUP)) {
            return;
        }
        pickupClicks++;
        moves++;
        clearPending();
        pace();
    }

    private static void clearPending() {
        pendingSourceSlot = -1;
        pendingDestSlot = -1;
        clickedDest = false;
    }

    private static boolean lockedInPlace(ItemStack stack) {
        ItemProtectConfig protect = ItemProtectConfig.getInstance();
        return protect.isLockInPlace() && ItemProtect.isExplicitlyProtected(stack);
    }

    /**
     * The first slot (0-35) holding {@code wanted} that the layout does not already have right there - stealing an
     * already-right slot would only move the problem. Items Lock In Place holds are never taken.
     * @return -1 if the item isn't in the inventory at all right now.
     */
    private static int findSourceSlot(Inventory inv, String wanted, int excludeSlot) {
        for (int i = 0; i < 36; i++) {
            if (i == excludeSlot) {
                continue;
            }
            ItemStack stack = inv.getItem(i);
            String id = ItemIdentity.of(stack);
            if (id == null || !id.equalsIgnoreCase(wanted)) {
                continue;
            }
            String required = targetLayout.identityAt(i);
            if (required != null && required.equalsIgnoreCase(id)) {
                continue;
            }
            if (lockedInPlace(stack)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static boolean click(Minecraft client, InventoryScreen screen, AbstractContainerMenu menu, int invIndex,
                                 int button, ContainerInput input) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.INVENTORY_SORTER, screen)) {
            return false;
        }
        clicksSent++;
        client.gameMode.handleContainerInput(menu.containerId, toContainerSlot(invIndex), button, input, client.player);
        return true;
    }

    private static void pace() {
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
        ModChat.send(InventorySorterCommands.FEATURE, ModChat.good("Sorted "), ModChat.value(targetLayout.name()),
                ModChat.text(" (" + moves + " move" + (moves == 1 ? "" : "s") + ")"),
                unresolved.isEmpty() ? ModChat.text("") : ModChat.dim(" - " + unresolved.size()
                        + " slot(s) of the layout could not be filled (item not in your inventory, or locked in place)."));
        LOGGER.info("[InvSort] Sorted {}: {} moves, {} clicks ({} hotbar swaps, {} pickups), {} unresolved",
                targetLayout.name(), moves, clicksSent, hotbarSwaps, pickupClicks, unresolved.size());
        end(client);
    }

    private static void stop(String reason) {
        ModChat.send(InventorySorterCommands.FEATURE, ModChat.bad("Stopped: "), ModChat.text(reason));
        LOGGER.info("[InvSort] Stopped: {} ({} moves, {} clicks)", reason, moves, clicksSent);
        end(Minecraft.getInstance());
    }

    private static void end(Minecraft client) {
        boolean close = openedByUs && McCompat.screen(client) instanceof InventoryScreen;
        setState(State.IDLE);
        clearPending();
        openedByUs = false;
        if (close && client.player != null) {
            // It opened the inventory itself (keybind or /invsort apply), so it closes it again.
            client.player.closeContainer();
        }
    }

    private static void setState(State newState) {
        state = newState;
        stateTicks = 0;
    }
}
