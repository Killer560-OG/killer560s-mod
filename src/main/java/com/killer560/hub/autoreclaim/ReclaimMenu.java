package com.killer560.hub.autoreclaim;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.macrostop.MacroStop;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Cheat build: pulls every item {@link ReclaimRules} names out of the storage pages and the Accessory Bag's first page
 * (QUICK_MOVE into the inventory), then reclaims each inventory match through Hypixel's /reclaim.
 * <p>
 * The /reclaim flow (killer560s-logger capture, 2026-11-17 2:45 PM): {@code /reclaim} opens "Reclaim Menu" (9x6, 90 slots);
 * a shift-click of an inventory item moves it to slot 22; when /reclaim accepts it slot 25 becomes an anvil "Reclaim item"
 * (else it stays a pane); a PICKUP click on slot 25 answers "Processing your X Reclaim claim..." and "Your X Reclaim claim has
 * been processed!" and slot 25 turns back into a pane; closing the menu returns the item. One item at a time: it reopens
 * the menu for each. Every send goes through {@link ActionGate}. A server correction is not acted on (nothing here moves
 * the player). Not captured: an item /reclaim refuses (assumed: slot 25 never becomes an anvil, so it is skipped after a
 * timeout) and what the Recombobulator / enchant payout looks like.
 */
public final class ReclaimMenu {

    private enum St { IDLE, PULL_OPEN, PULL_CLICK, RECLAIM_OPEN, ANVIL, DONE }

    static final String TITLE = "Reclaim Menu";
    private static final int ITEM_SLOT = 22;
    private static final int ANVIL_SLOT = 25;
    private static final int OPEN_TIMEOUT = 40;
    private static final int SETTLE = 3;
    private static final int ANVIL_TIMEOUT = 20;
    private static final int DONE_TIMEOUT = 40;

    private static St st = St.IDLE;
    private static boolean registered;
    private static final List<String> pulls = new ArrayList<>();
    private static final List<Integer> pending = new ArrayList<>(); // inventory indices to reclaim
    private static int ticks;
    private static int invIndex;
    private static boolean claimed;
    private static int pulled;
    private static int reclaimed;
    private static int refused;
    private static final java.util.Set<Integer> skipSlots = new java.util.HashSet<>();
    private static int retries;
    private static int lastPull;

    private ReclaimMenu() {
    }

    public static boolean isRunning() {
        return st != St.IDLE;
    }

    /** Starts a run over the storage commands in {@code pullCommands} ("enderchest 3", "backpack 2", "accessories"). */
    static void start(List<String> pullCommands) {
        start(pullCommands, false);
    }

    /** The /reclaim menu's "Reclaim Inventory" button: only what the inventory holds right now, in the menu that is open. */
    public static void startInventory() {
        start(List.of(), true);
    }

    private static void start(List<String> pullCommands, boolean menuOpen) {
        Minecraft mc = Minecraft.getInstance();
        if (st != St.IDLE || mc.player == null) { // the chat screen is still open here; the first tick finds it closed
            return;
        }
        if (!registered) {
            registered = true;
            ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("ReclaimMenu.tick", ReclaimMenu::tick));
            MacroStop.register("Auto Reclaim", MacroStop.Kind.CLICK, ReclaimMenu::isRunning, why -> stop("you pressed " + why));
        }
        pulls.clear();
        pulls.addAll(pullCommands);
        pulled = reclaimed = refused = retries = 0;
        skipSlots.clear();
        ticks = 0;
        ModChat.send(AutoReclaimFeature.CHAT, ModChat.text("Started."));
        next();
        if (menuOpen && st == St.RECLAIM_OPEN) {
            ticks = SETTLE; // the menu is already open: the first item goes on the next tick
        }
    }

    static void stop(String why) {
        if (st == St.IDLE) {
            return;
        }
        st = St.IDLE;
        Minecraft mc = Minecraft.getInstance();
        if (McCompat.screen(mc) instanceof AbstractContainerScreen<?> && mc.player != null) {
            mc.player.closeContainer();
        }
        ModChat.send(AutoReclaimFeature.CHAT, ModChat.text("Stopped: " + why + ". Pulled " + pulled + ", reclaimed "
                + reclaimed + (refused > 0 ? ", " + refused + " not accepted by /reclaim" : "") + "."));
    }

    /** Opens the next storage page, or moves on to the reclaim phase. */
    private static void next() {
        if (pulls.isEmpty()) {
            scan();
            st = St.RECLAIM_OPEN;
            ticks = -1; // not sent yet
            if (pending.isEmpty()) {
                stop("nothing in your inventory that Auto Reclaim knows /reclaim takes (Recombobulator, Divine Gift, Chance, Luck, Boosters armor)");
            }
        } else {
            lastPull = -100;
            st = St.PULL_OPEN;
            ticks = -1;
        }
    }

    /** Fills {@link #pending} with the inventory slots /reclaim has not refused yet. */
    private static void scan() {
        pending.clear();
        Inventory inv = Minecraft.getInstance().player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (!skipSlots.contains(i) && ReclaimRules.reason(inv.getItem(i)) != null) {
                pending.add(i);
            }
        }
    }

    private static void tick(Minecraft mc) {
        if (st == St.IDLE) {
            return;
        }
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            st = St.IDLE;
            return;
        }
        var screen = McCompat.screen(mc);
        AbstractContainerScreen<?> c = screen instanceof AbstractContainerScreen<?> a ? a : null;
        if (ticks < 0) { // the command for this step has not gone out yet
            if (screen != null) {
                stop("another screen opened");
            } else if (ActionGate.tryAct(ActionGate.Actor.AUTO_RECLAIM_CMD)) {
                mc.player.connection.sendCommand(st == St.PULL_OPEN ? pulls.remove(0) : "reclaim");
                ticks = 0;
            }
            return;
        }
        ticks++;
        switch (st) {
            case PULL_OPEN -> {
                if (c != null && !TITLE.equals(c.getTitle().getString()) && ticks >= SETTLE) {
                    st = St.PULL_CLICK;
                } else if (ticks > OPEN_TIMEOUT) {
                    next(); // the page never opened (skipped)
                }
            }
            case PULL_CLICK -> pullOne(mc, c);
            case RECLAIM_OPEN -> {
                if (c != null && TITLE.equals(c.getTitle().getString()) && ticks >= SETTLE) {
                    putOne(mc, c);
                } else if (ticks > OPEN_TIMEOUT) {
                    stop("/reclaim did not open");
                }
            }
            case ANVIL -> anvil(mc, c);
            default -> {
            }
        }
    }

    private static void pullOne(Minecraft mc, AbstractContainerScreen<?> c) {
        if (c == null) {
            stop("the storage page closed");
            return;
        }
        for (Slot s : c.getMenu().slots) {
            if (s.container instanceof Inventory || ReclaimRules.reason(s.getItem()) == null) {
                continue;
            }
            Inventory inv = mc.player.getInventory();
            boolean room = false;
            for (int i = 0; i < 36 && !room; i++) {
                room = inv.getItem(i).isEmpty();
            }
            if (!room) {
                stop("inventory full");
            } else if (ActionGate.tryAct(ActionGate.Actor.AUTO_RECLAIM_MENU, c)) {
                String name = s.getItem().getHoverName().getString();
                mc.gameMode.handleContainerInput(c.getMenu().containerId, s.index, 0, ContainerInput.QUICK_MOVE, mc.player);
                pulled++;
                lastPull = ticks;
                ModChat.log(AutoReclaimFeature.CHAT, ModChat.text("Pulled " + name));
            }
            return;
        }
        if (ticks - lastPull < SETTLE + 2) {
            return; // a click that landed beside another one may be undone by the server: look again before leaving
        }
        mc.player.closeContainer(); // this page holds no more matches
        next();
    }

    private static void putOne(Minecraft mc, AbstractContainerScreen<?> c) {
        if (pending.isEmpty()) {
            scan(); // anything left that /reclaim has not refused (a click of his own can spoil one) goes again
            if (!pending.isEmpty() && retries++ < 3) {
                mc.player.closeContainer();
                st = St.RECLAIM_OPEN;
                ticks = -1;
                return;
            }
            mc.player.closeContainer();
            stop("done");
            return;
        }
        int want = pending.get(0);
        Slot slot = null;
        for (Slot s : c.getMenu().slots) {
            if (s.container instanceof Inventory && s.getContainerSlot() == want) {
                slot = s;
            }
        }
        if (slot == null || ReclaimRules.reason(slot.getItem()) == null) {
            pending.remove(0); // moved or already reclaimed
            return;
        }
        if (ActionGate.tryAct(ActionGate.Actor.AUTO_RECLAIM_MENU, c)) {
            mc.gameMode.handleContainerInput(c.getMenu().containerId, slot.index, 0, ContainerInput.QUICK_MOVE, mc.player);
            pending.remove(0);
            invIndex = want;
            claimed = false;
            ticks = 0;
            st = St.ANVIL;
        }
    }

    private static boolean anvilShown(AbstractContainerScreen<?> c) {
        return c.getMenu().slots.size() > ANVIL_SLOT && c.getMenu().getSlot(ANVIL_SLOT).getItem().is(Items.ANVIL);
    }

    private static void anvil(Minecraft mc, AbstractContainerScreen<?> c) {
        if (c == null || !TITLE.equals(c.getTitle().getString())) {
            stop("the /reclaim menu closed");
            return;
        }
        boolean shown = anvilShown(c);
        if (!claimed) {
            if (shown && ActionGate.tryAct(ActionGate.Actor.AUTO_RECLAIM_MENU, c)) {
                mc.gameMode.handleContainerInput(c.getMenu().containerId, ANVIL_SLOT, 0, ContainerInput.PICKUP, mc.player);
                claimed = true;
                ticks = 0;
            } else if (!shown && ticks > ANVIL_TIMEOUT) {
                refused++;
                skipSlots.add(invIndex);
                finishItem(mc, "/reclaim did not accept it");
            }
        } else if (!shown) {
            reclaimed++;
            skipSlots.add(invIndex); // done: the item may still read as a match, never again
            finishItem(mc, "reclaimed");
        } else if (ticks > DONE_TIMEOUT) {
            stop("the claim did not finish");
        }
    }

    /** Closes the menu (the item comes back) and reopens it for the next one. */
    private static void finishItem(Minecraft mc, String what) {
        ModChat.log(AutoReclaimFeature.CHAT, ModChat.text("Inventory slot " + invIndex + ": " + what));
        mc.player.closeContainer();
        if (pending.isEmpty()) {
            scan(); // a click of his own can spoil one: anything /reclaim has not refused goes again
            if (pending.isEmpty() || retries++ >= 3) {
                stop("done");
                return;
            }
        }
        st = St.RECLAIM_OPEN;
        ticks = -1;
    }

    /** For the testkit. */
    public static int[] counts() {
        return new int[]{pulled, reclaimed, refused};
    }
}
