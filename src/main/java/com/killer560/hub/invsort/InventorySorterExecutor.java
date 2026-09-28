package com.killer560.hub.invsort;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies a saved {@link InventoryLayout} by clicking container slots in the player's own real vanilla Inventory
 * screen ('e') until every slot the layout manages holds the item it wants - killer560: "I should be able to save
 * item locations in my inventory and have them sorted there."
 * <p>
 * <b>The click mechanic</b>: a real vanilla two-slot swap is exactly 2-3 real {@code ContainerInput.PICKUP} clicks
 * (the same real click type Auto Croesus/Pet Wheel already send via {@code handleContainerInput}, same category
 * of click every menu-driven feature in this mod uses - nothing new invented): click the source slot (its item
 * moves onto the cursor), click the destination slot (cursor and destination swap - the wanted item lands, the
 * slot's old occupant, if any, is now on the cursor), and if that happened, one more click on the source slot to
 * place the displaced item back down. Never picks a source slot away from ANOTHER already-correct target slot
 * (see {@link #findSourceSlot}), so a slot the layout already satisfied is never disturbed again this session -
 * that invariant is also the termination proof: every successful swap fixes exactly one previously-wrong target
 * slot and never un-fixes one, so a 36-slot layout finishes in at most 36 swaps.
 * <p>
 * <b>"Make sure I cannot walk while it is going on"</b> (killer560): the established way this codebase takes
 * input away from the player is {@code ap3/Ap3Executor}'s own "stop all movement" step - forcing the real
 * {@link net.minecraft.client.KeyMapping} states off, discrete key state only, never a position/velocity write
 * and never fractional input. This does the same thing, every tick a session is running
 * ({@link #blockMovementKeys}), starting the instant the session is armed (even before the inventory screen has
 * actually opened) and stopping the instant it ends - nothing to "restore": once this stops forcing the keys off,
 * whatever the physical keyboard is actually doing takes back over on its own, exactly like Ap3's own release.
 * <p>
 * One real click per client tick, mod-wide, through {@link ActionGate.Actor#INVENTORY_SORTER} - same rule every
 * other automated clicker in this mod already follows.
 */
public final class InventorySorterExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-invsort");

    /** More than a 36-slot layout could ever need (see the class doc's termination proof) - a safety net, not a
     *  real limit. */
    private static final int MAX_SWAPS = 72;
    private static final int MAX_CLICKS = 250;
    private static final long WAIT_FOR_SCREEN_MS = 2000L;

    private enum State { IDLE, WAIT_SCREEN, SORTING }

    private static State state = State.IDLE;
    private static InventoryLayout targetLayout;
    private static long stateSinceMs;
    private static long nextActionAtMs;

    /** Inventory index (0-35) currently on the cursor's way to {@link #pendingDestSlot}, or -1 between swaps. */
    private static int pendingSourceSlot = -1;
    private static int pendingDestSlot = -1;
    private static boolean clickedDest;

    private static int swaps;
    private static int clicksSent;
    /** Target slots given up on this session (the wanted item isn't in the inventory at all) - tried once, not
     *  retried every tick. */
    private static final Set<Integer> unresolved = new HashSet<>();

    private InventorySorterExecutor() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("InventorySorterExecutor.tick", InventorySorterExecutor::tick));
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            if (isRunning()) {
                stop("world changed");
            }
        });
    }

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    /** Starts applying {@code layout}. Opens the player's own Inventory screen if it isn't already the one open.
     *  @return false if a session is already running (call {@link #stop} first). */
    public static boolean start(InventoryLayout layout) {
        if (isRunning() || layout == null) {
            return false;
        }
        targetLayout = layout;
        swaps = 0;
        clicksSent = 0;
        unresolved.clear();
        pendingSourceSlot = -1;
        pendingDestSlot = -1;
        clickedDest = false;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return false;
        }
        if (!(client.screen instanceof InventoryScreen)) {
            client.setScreenAndShow(new InventoryScreen(client.player));
        }
        setState(State.WAIT_SCREEN);
        return true;
    }

    /** Stops a running session from outside (a command). */
    public static void requestStop() {
        if (isRunning()) {
            stop("stopped by user");
        }
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
        // "Make sure I cannot walk while it is going on" - every tick a session is active, no exceptions,
        // including the brief window before the Inventory screen has actually opened.
        blockMovementKeys(client);
        if (client.player == null) {
            stop("player gone");
            return;
        }
        long now = System.currentTimeMillis();
        switch (state) {
            case WAIT_SCREEN -> {
                if (client.screen instanceof InventoryScreen) {
                    setState(State.SORTING);
                } else if (now - stateSinceMs > WAIT_FOR_SCREEN_MS) {
                    stop("could not open your inventory");
                }
            }
            case SORTING -> sortTick(client, now);
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

    private static void sortTick(Minecraft client, long now) {
        if (!(client.screen instanceof InventoryScreen screen)) {
            stop("inventory closed");
            return;
        }
        if (now < nextActionAtMs) {
            return;
        }
        if (swaps > MAX_SWAPS || clicksSent > MAX_CLICKS) {
            stop("safety limit reached");
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (pendingSourceSlot != -1) {
            continueSwap(client, screen, menu, now);
            return;
        }
        Inventory inv = client.player.getInventory();
        for (Map.Entry<Integer, String> entry : targetLayout.entries().entrySet()) {
            int targetSlot = entry.getKey();
            if (unresolved.contains(targetSlot)) {
                continue;
            }
            String wanted = entry.getValue();
            String current = ItemIdentity.of(inv.getItem(targetSlot));
            if (wanted.equalsIgnoreCase(current)) {
                continue; // already correct
            }
            int source = findSourceSlot(inv, wanted, targetSlot);
            if (source < 0) {
                unresolved.add(targetSlot);
                continue;
            }
            if (!click(client, screen, menu, source)) {
                return; // gate denied this tick - nothing moved, retry next tick
            }
            pendingSourceSlot = source;
            pendingDestSlot = targetSlot;
            clickedDest = false;
            nextActionAtMs = now + randomDelay();
            return;
        }
        finish();
    }

    private static void continueSwap(Minecraft client, InventoryScreen screen, AbstractContainerMenu menu, long now) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            // Nothing on the cursor when we expected something (a plugin/server-side change under us) - drop
            // this swap attempt and let the next tick re-evaluate from the real, current state.
            pendingSourceSlot = -1;
            pendingDestSlot = -1;
            clickedDest = false;
            return;
        }
        if (!clickedDest) {
            if (!click(client, screen, menu, pendingDestSlot)) {
                return;
            }
            clickedDest = true;
            nextActionAtMs = now + randomDelay();
            return;
        }
        if (!menu.getCarried().isEmpty()) {
            // The destination held something else, now on the cursor - one more click puts it back where the
            // source item came from, completing the swap.
            if (!click(client, screen, menu, pendingSourceSlot)) {
                return;
            }
        }
        swaps++;
        pendingSourceSlot = -1;
        pendingDestSlot = -1;
        clickedDest = false;
        nextActionAtMs = now + randomDelay();
    }

    /**
     * The first slot (0-35) holding {@code wanted}, never one that is ITSELF a layout target already correctly
     * satisfied - stealing an already-fixed slot to feed another would just move the problem, and could thrash
     * forever with duplicate identities. @return -1 if the item isn't in the inventory at all right now.
     */
    private static int findSourceSlot(Inventory inv, String wanted, int excludeSlot) {
        for (int i = 0; i < 36; i++) {
            if (i == excludeSlot) {
                continue;
            }
            String id = ItemIdentity.of(inv.getItem(i));
            if (id == null || !id.equalsIgnoreCase(wanted)) {
                continue;
            }
            String required = targetLayout.identityAt(i);
            if (required != null && required.equalsIgnoreCase(id)) {
                continue; // already correctly placed - not a source, leave it alone
            }
            return i;
        }
        return -1;
    }

    private static boolean click(Minecraft client, InventoryScreen screen, AbstractContainerMenu menu, int invIndex) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.INVENTORY_SORTER, screen)) {
            return false;
        }
        clicksSent++;
        client.gameMode.handleContainerInput(menu.containerId, toContainerSlot(invIndex), 0, ContainerInput.PICKUP, client.player);
        return true;
    }

    /**
     * {@link net.minecraft.world.entity.player.Inventory} index (0-35) -&gt; the real vanilla Inventory screen's
     * OWN container-slot index. Verified against {@code InventoryMenu}'s real slot layout (also relied on by
     * {@code slotbinds/SlotBindsFeature}, which swaps against the same real hotbar range): container slots 9-35
     * are the real main storage, in the exact same order as Inventory index 9-35 - identity mapping; container
     * slots 36-44 are the real hotbar, Inventory index 0-8 in order. Only valid for {@link InventoryScreen}'s own
     * menu - a custom container (a chest) shifts these by its own slot count and is never this executor's target.
     */
    static int toContainerSlot(int invIndex) {
        return invIndex >= 9 ? invIndex : invIndex + 36;
    }

    private static void finish() {
        ModChat.send("InvSort", ModChat.good("Sorted "), ModChat.value(targetLayout.name()),
                ModChat.text(" (" + swaps + " swap" + (swaps == 1 ? "" : "s") + ")"),
                unresolved.isEmpty() ? ModChat.text("") : ModChat.dim(" - " + unresolved.size()
                        + " item(s) from the layout weren't in your inventory."));
        LOGGER.info("[InvSort] Applied layout {} - {} swap(s), {} unresolved slot(s)", targetLayout.name(), swaps, unresolved.size());
        setState(State.IDLE);
    }

    private static void stop(String reason) {
        LOGGER.info("[InvSort] Stopped: {}", reason);
        ModChat.send("InvSort", ModChat.bad("Stopped: "), ModChat.text(reason));
        setState(State.IDLE);
    }

    private static void setState(State newState) {
        state = newState;
        stateSinceMs = System.currentTimeMillis();
    }

    private static long randomDelay() {
        InventorySorterConfig cfg = InventorySorterConfig.getInstance();
        int lo = cfg.getMinDelayMs();
        int hi = Math.max(lo, cfg.getMaxDelayMs());
        return lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
    }
}
