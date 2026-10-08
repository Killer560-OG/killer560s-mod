package com.killer560.hub.autoanvil;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.itemprotect.ItemProtect;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Auto Anvil (killer560, 2026-10-07: "look at quoi's auto anvil combiner and if you can improve it great but create
 * that as well. make sure it only combines the same tier of the exact same book.").
 * <p>
 * <b>What QUOI's {@code AutoBookCombine} does</b> (quoi-1.1.1+26.1.jar, read with {@code javap -c -p}): on every
 * client tick end, while a container titled exactly "Anvil" is open in SkyBlock, it scans the player-inventory slots
 * for {@code minecraft:enchanted_book} stacks whose {@code custom_data.enchantments} has exactly one key, skips level
 * 5 and level 10 outright, and takes the first two with the same id and level. It then shift-clicks the first, waits
 * {@code clickDelay+1} ticks (default 4), shift-clicks the second, waits again, and left-clicks slot 22 twice
 * {@code resultDelay+1} ticks apart (default 10) - first to combine, second to take the result - checking only that
 * the same container id is still open. It never reads what landed in the anvil or what came out, so a slow server or
 * a surprise layout is clicked through blind, and its "not level 5" rule lets Looting III + III, Fire Aspect II + II
 * or Sharpness VI + VI through while refusing Infinite Quiver V + V, which the anvil does make.
 * <p>
 * <b>What this does instead.</b> The same shift-click / combine / take flow, but every step waits for the server to
 * show its result before the next click, and checks it: the book is in an input slot and gone from the inventory,
 * both inputs are the expected book, a result the anvil previews in slot 13 is exactly the book one level up, both
 * inputs were consumed, and the inventory gained exactly that book. Anything else stops it with the reason in chat -
 * nothing is clicked through. The claim click on 22 is only sent while the result is still sitting in the anvil
 * (where QUOI always sends it). The cap is per enchantment ({@link AnvilBooks#canCombine}), not "not V". Books are
 * paired lowest level first, so with Combine Results Again on, four III books become two IV and then a V in one go.
 * Item Protect is honoured: a protected book or one in a locked slot is never put in.
 * <p>
 * <b>Same book, same tier</b> is {@link AnvilBooks.Book#sameBookAs}, read from the ExtraAttributes Hypixel sends as
 * {@code custom_data} ({@link #readBook}); a book with more than one enchantment, a recombobulated book, a stack of more
 * than one, or anything whose SkyBlock id is not {@code ENCHANTED_BOOK} is never a candidate.
 * <p>
 * <b>Not verified against live Hypixel</b> (no server here): where exactly the combined book goes. The wiki's anvil UI
 * shows slot 13 as the result and 22 as "Combine Items"; QUOI's double click on 22 implies the result is then taken
 * with 22. This handles both a result left in 13 (claimed with 22, then 13 if that did nothing) and one delivered
 * straight to the inventory.
 */
public final class AutoAnvilFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoanvil");

    /** Hypixel's anvil (hypixelskyblock.minecraft.wiki Anvil/UI, 2026-10-07): 6 rows, upgrade input at row 4
     *  column 3, sacrifice input at row 4 column 7, the Combine Items anvil at row 3 column 5, result at row 2
     *  column 5. */
    static final int SLOT_RESULT = 13;
    static final int SLOT_COMBINE = 22;
    static final int SLOT_LEFT = 29;
    static final int SLOT_RIGHT = 33;
    static final int TOP_SLOTS = 54;

    /** How long the server gets to show the outcome of one click before it counts as a surprise. */
    private static final long SERVER_TIMEOUT_MS = 3000L;
    /** A runaway guard: no anvil session ever needs this many clicks. */
    private static final int MAX_CLICKS = 400;

    private enum Phase {
        PICK, INSERT_A, AWAIT_A, INSERT_B, AWAIT_B, COMBINE, AWAIT_COMBINE, CLAIM, AWAIT_CLAIM
    }

    private static int containerId = -1;
    /** The anvil this mod already finished or stopped in; nothing more happens there until another one opens. */
    private static int finishedContainerId = -1;
    private static Phase phase = Phase.PICK;
    private static long nextActionAtMs;
    private static long deadlineMs;
    private static long carriedSinceMs;
    /** The menu's state id when the last click went out; the server's next update changes it. */
    private static int clickStateId;
    private static int clicks;
    private static int combined;
    private static int claimClicks;
    private static int slotA = -1;
    private static int slotB = -1;
    private static AnvilBooks.Book pairBook;
    private static AnvilBooks.Book resultBook;
    private static int resultCountBefore;
    /** Starting counts per book when Combine Results Again is off (books this session made are not reused). */
    private static Map<String, Integer> budget;
    private static final List<String> made = new ArrayList<>();

    private AutoAnvilFeature() {
    }

    public static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoAnvilFeature.tick", AutoAnvilFeature::tick));
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> reset());
    }

    /** For the GUI and tests: whether a session is running in an open anvil. */
    public static boolean isRunning() {
        return containerId != -1;
    }

    /** For tests: books combined in the current or last session. */
    public static int combinedCount() {
        return combined;
    }

    private static void tick(Minecraft client) {
        try {
            tickUnsafe(client);
        } catch (Exception e) {
            LOGGER.error("[AutoAnvil] tick failed - stopping", e);
            stop("internal error (see log)");
        }
    }

    private static void tickUnsafe(Minecraft client) {
        AbstractContainerScreen<?> screen = McCompat.screen(client) instanceof AbstractContainerScreen<?> s ? s : null;
        boolean anvil = screen != null && isAnvil(screen);
        if (containerId != -1) {
            if (!AutoAnvilConfig.getInstance().isEnabled()) {
                stop("Auto Anvil was turned off");
                return;
            }
            if (!anvil || screen.getMenu().containerId != containerId) {
                stop("the anvil closed");
                return;
            }
        } else {
            if (!anvil || !AutoAnvilConfig.getInstance().isEnabled() || client.player == null) {
                if (!anvil) {
                    finishedContainerId = -1;
                }
                return;
            }
            int id = screen.getMenu().containerId;
            if (id == finishedContainerId || !layoutReady(screen.getMenu())) {
                return;
            }
            begin(id);
        }
        run(client, screen);
    }

    private static void begin(int id) {
        containerId = id;
        phase = Phase.PICK;
        carriedSinceMs = 0L;
        clicks = 0;
        combined = 0;
        budget = null;
        made.clear();
        nextActionAtMs = System.currentTimeMillis() + randomDelay();
    }

    private static void run(Minecraft client, AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        long now = System.currentTimeMillis();
        // A left click on a menu button makes the CLIENT predict picking the button up until the server's resend
        // arrives (seen on 26.2: the Combine Items anvil sat on the cursor for a tick and stopped the run). So a cursor
        // stack mid-pair is waited out; only one that is still there after the server timeout, or one present before
        // a pair starts (his own), stops it.
        if (!menu.getCarried().isEmpty()) {
            if (carriedSinceMs == 0L) {
                carriedSinceMs = now;
            }
            if ((phase == Phase.PICK && now >= nextActionAtMs) || now - carriedSinceMs > SERVER_TIMEOUT_MS) {
                stop("something is on your cursor (" + describe(menu.getCarried()) + ")");
            }
            return;
        }
        carriedSinceMs = 0L;
        if (clicks >= MAX_CLICKS) {
            stop("safety limit of " + MAX_CLICKS + " clicks reached");
            return;
        }
        switch (phase) {
            case PICK -> {
                if (now < nextActionAtMs) {
                    return;
                }
                if (!menu.getSlot(SLOT_LEFT).getItem().isEmpty() || !menu.getSlot(SLOT_RIGHT).getItem().isEmpty()) {
                    stop("the anvil's input slots are not empty - take those items out first");
                    return;
                }
                if (isSkyblockBook(menu.getSlot(SLOT_RESULT).getItem())) {
                    stop("a book is waiting in the anvil's result slot - take it out first");
                    return;
                }
                List<AnvilBooks.Candidate> candidates = candidates(menu);
                if (budget == null && !AutoAnvilConfig.getInstance().isCascade()) {
                    budget = new HashMap<>();
                    for (AnvilBooks.Candidate c : candidates) {
                        budget.merge(AnvilBooks.budgetKey(c.book()), 1, Integer::sum);
                    }
                }
                int[] pair = AnvilBooks.pickPair(candidates, budget);
                if (pair == null) {
                    finish(client);
                    return;
                }
                slotA = pair[0];
                slotB = pair[1];
                pairBook = bookIn(menu, slotA);
                resultBook = pairBook == null ? null : pairBook.combined();
                if (pairBook == null || resultBook == null || !pairBook.sameBookAs(bookIn(menu, slotB))) {
                    stop("the chosen books changed under it");
                    return;
                }
                resultCountBefore = countInInventory(menu, resultBook);
                claimClicks = 0;
                LOGGER.info("[AutoAnvil] pairing {} from slots {} and {}", pairBook, slotA, slotB);
                phase = Phase.INSERT_A;
            }
            case INSERT_A -> {
                if (now >= nextActionAtMs && click(client, screen, slotA, ContainerInput.QUICK_MOVE)) {
                    phase = Phase.AWAIT_A;
                    deadlineMs = now + SERVER_TIMEOUT_MS;
                }
            }
            case AWAIT_A -> {
                if (!serverAnswered(now)) {
                    return;
                }
                boolean gone = menu.getSlot(slotA).getItem().isEmpty();
                int inputs = inputsHolding(menu, pairBook);
                if (gone && inputs == 1) {
                    phase = Phase.INSERT_B;
                    nextActionAtMs = now + randomDelay();
                } else if (now > deadlineMs) {
                    stop("the first book did not arrive in the anvil");
                }
            }
            case INSERT_B -> {
                if (now < nextActionAtMs) {
                    return;
                }
                if (!pairBook.sameBookAs(bookIn(menu, slotB))) {
                    stop("the second book changed before it was put in");
                    return;
                }
                if (click(client, screen, slotB, ContainerInput.QUICK_MOVE)) {
                    phase = Phase.AWAIT_B;
                    deadlineMs = now + SERVER_TIMEOUT_MS;
                }
            }
            case AWAIT_B -> {
                if (!serverAnswered(now)) {
                    return;
                }
                boolean gone = menu.getSlot(slotB).getItem().isEmpty();
                if (gone && inputsHolding(menu, pairBook) == 2) {
                    phase = Phase.COMBINE;
                    nextActionAtMs = now + randomDelay();
                } else if (now > deadlineMs) {
                    stop("the second book did not arrive in the anvil");
                }
            }
            case COMBINE -> {
                if (now < nextActionAtMs) {
                    return;
                }
                // The last look before the only click that spends books: both inputs are exactly the pair, and a
                // book the anvil previews in the result slot is exactly the one we expect to make.
                AnvilBooks.Book left = bookIn(menu, SLOT_LEFT);
                AnvilBooks.Book right = bookIn(menu, SLOT_RIGHT);
                if (!pairBook.sameBookAs(left) || !pairBook.sameBookAs(right)) {
                    stop("the anvil does not hold two " + pairBook + " books - nothing combined, close it to get them back");
                    return;
                }
                ItemStack preview = menu.getSlot(SLOT_RESULT).getItem();
                if (isSkyblockBook(preview) && !resultBook.sameBookAs(readBook(preview))) {
                    stop("the anvil previews " + describe(preview) + ", not " + resultBook + " - nothing combined");
                    return;
                }
                if (menu.getSlot(SLOT_COMBINE).getItem().getItem() != Items.ANVIL) {
                    stop("the Combine Items button is missing");
                    return;
                }
                if (click(client, screen, SLOT_COMBINE, ContainerInput.PICKUP)) {
                    phase = Phase.AWAIT_COMBINE;
                    deadlineMs = now + SERVER_TIMEOUT_MS;
                }
            }
            case AWAIT_COMBINE -> {
                if (!serverAnswered(now)) {
                    return;
                }
                boolean consumed = menu.getSlot(SLOT_LEFT).getItem().isEmpty() && menu.getSlot(SLOT_RIGHT).getItem().isEmpty();
                if (!consumed) {
                    if (now > deadlineMs) {
                        stop("the anvil did not combine the books - close it to get them back");
                    }
                    return;
                }
                ItemStack out = menu.getSlot(SLOT_RESULT).getItem();
                if (isSkyblockBook(out)) {
                    if (!resultBook.sameBookAs(readBook(out))) {
                        stop("the anvil made " + describe(out) + ", not " + resultBook);
                        return;
                    }
                    phase = Phase.CLAIM;
                    nextActionAtMs = now + randomDelay();
                } else if (countInInventory(menu, resultBook) > resultCountBefore) {
                    pairDone(now);
                } else if (now > deadlineMs) {
                    stop("the books were used but no " + resultBook + " appeared");
                }
            }
            case CLAIM -> {
                if (now < nextActionAtMs) {
                    return;
                }
                // QUOI takes the result with a second click on 22; if that has not moved it, try the result itself.
                int target = claimClicks == 0 ? SLOT_COMBINE : SLOT_RESULT;
                if (claimClicks >= 2) {
                    stop("the " + resultBook + " is still in the anvil - take it out by hand");
                    return;
                }
                if (click(client, screen, target, ContainerInput.PICKUP)) {
                    claimClicks++;
                    phase = Phase.AWAIT_CLAIM;
                    deadlineMs = now + SERVER_TIMEOUT_MS;
                }
            }
            case AWAIT_CLAIM -> {
                if (!serverAnswered(now)) {
                    return;
                }
                boolean stillThere = isSkyblockBook(menu.getSlot(SLOT_RESULT).getItem());
                if (!stillThere && countInInventory(menu, resultBook) > resultCountBefore) {
                    pairDone(now);
                } else if (!stillThere && now > deadlineMs) {
                    stop("the " + resultBook + " left the anvil but is not in your inventory");
                } else if (stillThere && now > deadlineMs) {
                    phase = Phase.CLAIM;
                    nextActionAtMs = now;
                }
            }
        }
    }

    private static void pairDone(long now) {
        combined++;
        made.add(resultBook.toString());
        if (budget != null) {
            budget.merge(AnvilBooks.budgetKey(pairBook), -2, Integer::sum);
        }
        LOGGER.info("[AutoAnvil] made {} ({} so far)", resultBook, combined);
        phase = Phase.PICK;
        nextActionAtMs = now + randomDelay();
    }

    /**
     * Whether the server has sent anything for this menu since the last click. The client PREDICTS a click's effect
     * (a shift-clicked book appears in the anvil at once), so a slot read alone could be the prediction; the menu's
     * state id only changes when the server's own slot/content packets arrive. Past the timeout it reports true so the
     * await below it can give its reason.
     */
    private static boolean serverAnswered(long now) {
        Minecraft client = Minecraft.getInstance();
        AbstractContainerMenu menu = client.player == null ? null : client.player.containerMenu;
        return menu == null || menu.getStateId() != clickStateId || now > deadlineMs;
    }

    /** One click, through the gate. False when the gate wants us to wait a tick. */
    private static boolean click(Minecraft client, AbstractContainerScreen<?> screen, int slot, ContainerInput input) {
        if (!ActionGate.tryAct(ActionGate.Actor.AUTO_ANVIL, screen)) {
            return false;
        }
        clickStateId = screen.getMenu().getStateId();
        client.gameMode.handleContainerInput(screen.getMenu().containerId, slot, 0, input, client.player);
        clicks++;
        return true;
    }

    // ---- reading the menu ---------------------------------------------------------------------------------------

    /** Title "Anvil" and the 6-row layout with the Combine Items anvil in slot 22. */
    static boolean isAnvil(AbstractContainerScreen<?> screen) {
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
        return title != null && title.trim().equals("Anvil") && screen.getMenu().slots.size() == TOP_SLOTS + 36;
    }

    /** The menu's contents have arrived and the inputs are free. */
    private static boolean layoutReady(AbstractContainerMenu menu) {
        return menu.getSlot(SLOT_COMBINE).getItem().getItem() == Items.ANVIL;
    }

    private static List<AnvilBooks.Candidate> candidates(AbstractContainerMenu menu) {
        List<AnvilBooks.Candidate> out = new ArrayList<>();
        for (int i = TOP_SLOTS; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            ItemStack stack = slot.getItem();
            AnvilBooks.Book book = readBook(stack);
            if (book == null) {
                continue;
            }
            if (ItemProtect.isProtectedItem(stack)) {
                continue;
            }
            out.add(new AnvilBooks.Candidate(i, book));
        }
        return out;
    }

    private static AnvilBooks.Book bookIn(AbstractContainerMenu menu, int slot) {
        return readBook(menu.getSlot(slot).getItem());
    }

    /** How many of the two input slots hold this exact book. */
    private static int inputsHolding(AbstractContainerMenu menu, AnvilBooks.Book book) {
        int n = 0;
        if (book.sameBookAs(bookIn(menu, SLOT_LEFT))) {
            n++;
        }
        if (book.sameBookAs(bookIn(menu, SLOT_RIGHT))) {
            n++;
        }
        return n;
    }

    private static int countInInventory(AbstractContainerMenu menu, AnvilBooks.Book book) {
        int n = 0;
        for (int i = TOP_SLOTS; i < menu.slots.size(); i++) {
            if (book.sameBookAs(bookIn(menu, i))) {
                n++;
            }
        }
        return n;
    }

    private static CompoundTag extraAttributes(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
    }

    /** A SkyBlock enchanted book by its data (any number of enchants), whatever it is named. */
    static boolean isSkyblockBook(ItemStack stack) {
        CompoundTag tag = extraAttributes(stack);
        return tag != null && "ENCHANTED_BOOK".equals(tag.getStringOr("id", ""));
    }

    /**
     * The book this stack is, or null when it is not a single-enchantment SkyBlock book that may be combined: a
     * vanilla {@code enchanted_book} item, ExtraAttributes id {@code ENCHANTED_BOOK}, a stack of one, exactly one key in
     * {@code enchantments} with an integer level, and not recombobulated ({@code rarity_upgrades}). The display name is
     * never read.
     */
    static AnvilBooks.Book readBook(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem() != Items.ENCHANTED_BOOK || stack.getCount() != 1) {
            return null;
        }
        CompoundTag tag = extraAttributes(stack);
        if (tag == null || !"ENCHANTED_BOOK".equals(tag.getStringOr("id", ""))) {
            return null;
        }
        CompoundTag ench = tag.getCompoundOrEmpty("enchantments");
        if (ench.keySet().size() != 1) {
            return null;
        }
        String enchant = ench.keySet().iterator().next();
        var level = ench.getInt(enchant);
        if (level.isEmpty() || level.get() < 1) {
            return null;
        }
        if (tag.keySet().contains("rarity_upgrades")) {
            return null;
        }
        // Everything else the item carries must match between the two books; sorted so key order cannot matter.
        Map<String, String> extras = new TreeMap<>();
        for (String key : tag.keySet()) {
            if (key.equals("id") || key.equals("enchantments") || key.equals("uuid") || key.equals("timestamp")
                    || key.equals("originTag")) {
                continue;
            }
            extras.put(key, String.valueOf(tag.get(key)));
        }
        return new AnvilBooks.Book(enchant, level.get(), extras.toString());
    }

    private static String describe(ItemStack stack) {
        AnvilBooks.Book b = readBook(stack);
        return b != null ? b.toString() : ChatFormatting.stripFormatting(stack.getHoverName().getString());
    }

    // ---- ending -------------------------------------------------------------------------------------------------

    private static void finish(Minecraft client) {
        int id = containerId;
        if (combined > 0) {
            ModChat.send("AutoAnvil", ModChat.good("Done - "), ModChat.value(combined + " combined"),
                    ModChat.dim(" (" + String.join(", ", made) + ")"));
            if (AutoAnvilConfig.getInstance().isCloseWhenDone() && client.player != null) {
                client.player.closeContainer();
            }
        }
        LOGGER.info("[AutoAnvil] finished in container {}: {} combined", id, combined);
        containerId = -1;
        finishedContainerId = id;
    }

    private static void stop(String reason) {
        if (containerId == -1) {
            return;
        }
        LOGGER.info("[AutoAnvil] stopped: {} ({} combined)", reason, combined);
        ModChat.send("AutoAnvil", ModChat.bad("Stopped: "), ModChat.text(reason),
                ModChat.dim(" (" + combined + " combined)"));
        finishedContainerId = containerId;
        containerId = -1;
    }

    private static void reset() {
        containerId = -1;
        finishedContainerId = -1;
    }

    private static long randomDelay() {
        AutoAnvilConfig cfg = AutoAnvilConfig.getInstance();
        int lo = cfg.getMinDelayMs();
        int hi = Math.max(lo, cfg.getMaxDelayMs());
        return lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
    }
}
