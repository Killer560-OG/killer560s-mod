package com.killer560.hub.experiments;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per killer560's request: before an autonomous run (re)starts, back out to {@code /pets} and equip
 * whichever owned Guardian pet is best, then reopen the table, since Guardian is the Enchanting pet
 * (bonus Enchanting Wisdom, a chance at extra Superpairs books). A small state machine of its own,
 * separate from {@link ExperimentNavigator}, since it deliberately reaches outside the table
 * entirely (a screen titled "Pets" would otherwise be completely ignored by everything else in this
 * package - see the screen-title gate in {@link ExperimentsFeature}).
 * <p>
 * Real bug found and fixed (2026-09-06) from an actual screenshot: the real /pets screen title is
 * "(1/2) Pets" (page indicator prefix) when the pets don't all fit on one page, not a bare "Pets" -
 * the original exact-match check against "Pets" alone meant the screen was never recognized at all,
 * so nothing downstream of that could ever have worked. {@link #PETS_TITLE_PATTERN} now matches both
 * the paginated and bare forms and reads off the current/total page numbers, which also drives
 * multi-page scanning (see {@link #findBestGuardianSlot}/{@link #findNextPageSlot}): if a Guardian
 * isn't found on the current page and more pages exist, the real "Next Page" navigation item
 * (confirmed from the same screenshot) is clicked and the next page is scanned the same way, until
 * either a Guardian is found or the last page is reached with none.
 * <p>
 * Per killer560's explicit simplification: "best" just means the first Guardian encountered scanning
 * top-left to bottom-right, since Hypixel's own /pets menu already sorts owned pets by rarity then
 * level - no rarity/level parsing needed (the real tooltip doesn't even reliably show a rarity line
 * at all, confirmed from a screenshot). See {@link #findBestGuardianSlot}.
 */
final class GuardianPetSwapper {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-guardian-swap");
    private static final String MAIN_MENU_TITLE = "Experimentation Table";
    private static final Pattern PETS_TITLE_PATTERN = Pattern.compile("^(?:\\((\\d+)/(\\d+)\\)\\s*)?Pets$");
    private static final int PLAYER_INVENTORY_SLOTS = 36;
    private static final long STEP_DELAY_MS = 1000;

    enum Action { NONE, CLOSE_TABLE, RUN_PETS_COMMAND, CLICK_SLOT, NEXT_PAGE, CLOSE_NOT_FOUND, CLOSE_AND_REOPEN }

    record Result(Action action, int slot) {
        private static final Result NONE_RESULT = new Result(Action.NONE, -1);
    }

    /** Per killer560's explicit correction: closing the table and sending {@code /pets} are two
     *  separate 1s-paced steps now, not one bundled action - {@code WAITING_TO_SEND_PETS} is the gap
     *  between "table just closed" and "command actually sent." */
    private enum State { IDLE, WAITING_TO_SEND_PETS, AWAITING_PETS_SCREEN, CLICKED_PET }

    private State state = State.IDLE;
    private boolean doneThisRun = false;
    /** -1 = pacing clock not started yet - see {@link #tick}'s use of it. */
    private long lastActionAtMs = -1;

    void reset() {
        state = State.IDLE;
        doneThisRun = false;
        lastActionAtMs = -1;
    }

    /** @return whether this run still needs to swap to a Guardian pet - used by
     *  {@link ExperimentsFeature} to hold off showing the "Start ETable" button until the swap (if
     *  enabled) has actually finished. */
    boolean isPending() {
        return !doneThisRun;
    }

    /** Called every tick (autonomous mode + this feature both on) with whatever screen title is
     *  currently open ("" if no screen at all - the gap between closing the table and the Pets
     *  screen opening is a real state this needs to keep ticking through). Returns the action
     *  {@link ExperimentsFeature} should take this tick, or {@code NONE}. */
    Result tick(String title, ChestMenu menu, long now) {
        if (doneThisRun) {
            return Result.NONE_RESULT;
        }
        if (lastActionAtMs < 0) {
            LOGGER.info("[t={}] Pacing clock started (state={}, title=\"{}\")", now, state, title);
            lastActionAtMs = now;
            return Result.NONE_RESULT;
        }
        long elapsed = now - lastActionAtMs;
        if (elapsed < STEP_DELAY_MS) {
            return Result.NONE_RESULT;
        }
        LOGGER.info("[t={}] 1s gate cleared ({}ms since last action, state={}, title=\"{}\")", now, elapsed, state, title);

        switch (state) {
            case IDLE -> {
                if (!title.equals(MAIN_MENU_TITLE)) {
                    return Result.NONE_RESULT;
                }
                state = State.WAITING_TO_SEND_PETS;
                lastActionAtMs = now;
                LOGGER.info("[t={}] Firing CLOSE_TABLE", now);
                return new Result(Action.CLOSE_TABLE, -1);
            }
            case WAITING_TO_SEND_PETS -> {
                // Per killer560's explicit correction: don't send /pets in the same instant the table
                // closes - wait the same full 1s gap here too before actually sending it, regardless
                // of what (if anything) is open right now.
                state = State.AWAITING_PETS_SCREEN;
                lastActionAtMs = now;
                LOGGER.info("[t={}] Firing RUN_PETS_COMMAND", now);
                return new Result(Action.RUN_PETS_COMMAND, -1);
            }
            case AWAITING_PETS_SCREEN -> {
                Matcher pageMatch = PETS_TITLE_PATTERN.matcher(title);
                if (!pageMatch.matches() || menu == null) {
                    LOGGER.info("[t={}] Waiting for the Pets screen - currently seeing \"{}\"", now, title);
                    return Result.NONE_RESULT;
                }
                int currentPage = pageMatch.group(1) != null ? Integer.parseInt(pageMatch.group(1)) : 1;
                int totalPages = pageMatch.group(2) != null ? Integer.parseInt(pageMatch.group(2)) : 1;
                GuardianScan scan = findBestGuardianSlot(menu);
                lastActionAtMs = now;
                if (scan.alreadyActive()) {
                    // Real bug found and fixed (2026-09-06) from a real screenshot: clicking a pet in
                    // /pets TOGGLES it - a Guardian that's already the summoned pet shows "Click to
                    // despawn!" in its own lore, and clicking it again would despawn it instead of
                    // leaving it active. Per killer560's exact diagnosis ("if it has that click to
                    // despawn subtext then i already have it out"), nothing needs to be clicked at
                    // all here - the goal (a Guardian active) is already satisfied.
                    LOGGER.info("[t={}] A Guardian pet is already active (shows \"Click to despawn!\") - nothing to do", now);
                    doneThisRun = true;
                    state = State.IDLE;
                    return new Result(Action.CLOSE_AND_REOPEN, -1);
                }
                if (scan.slotToSummon() >= 0) {
                    state = State.CLICKED_PET;
                    LOGGER.info("[t={}] Firing CLICK_SLOT on slot {} (page {}/{})", now, scan.slotToSummon(), currentPage, totalPages);
                    return new Result(Action.CLICK_SLOT, scan.slotToSummon());
                }
                if (currentPage < totalPages) {
                    int nextPageSlot = findNextPageSlot(menu);
                    if (nextPageSlot >= 0) {
                        LOGGER.info("[t={}] No Guardian on page {}/{} - clicking Next Page (slot {})",
                                now, currentPage, totalPages, nextPageSlot);
                        return new Result(Action.NEXT_PAGE, nextPageSlot);
                    }
                    LOGGER.warn("[t={}] On page {}/{} but couldn't find a \"Next Page\" item to click", now, currentPage, totalPages);
                }
                // Either the last page, or no "Next Page" item could be found - nothing left to try.
                LOGGER.warn("[t={}] No Guardian pet found after checking page {}/{} - see the item list logged above",
                        now, currentPage, totalPages);
                doneThisRun = true;
                state = State.IDLE;
                return new Result(Action.CLOSE_NOT_FOUND, -1);
            }
            case CLICKED_PET -> {
                doneThisRun = true;
                state = State.IDLE;
                lastActionAtMs = now;
                LOGGER.info("[t={}] Firing CLOSE_AND_REOPEN - swap complete", now);
                return new Result(Action.CLOSE_AND_REOPEN, -1);
            }
        }
        return Result.NONE_RESULT;
    }

    /** @param slotToSummon the best currently-inactive Guardian's slot to click (-1 if none found),
     *  {@code alreadyActive} whether ANY Guardian found is already the summoned pet (shows "Click to
     *  despawn!" in its own lore) - when true, {@code slotToSummon} is meaningless and nothing should
     *  be clicked at all, since a Guardian is already out. */
    private record GuardianScan(int slotToSummon, boolean alreadyActive) {
    }

    /** Diagnostic-only: logs every non-empty item name (and lore, joined) actually seen in the Pets
     *  container, once per page scanned - the real /pets item format has no client-jar ground truth
     *  to verify against (see the class doc), so if Guardian-picking ever misfires, this log is the
     *  fastest way to see exactly what the screen actually contained and fix the name/lore matching.
     *  Real bug found and fixed (2026-09-06) from a screenshot: a pet's own lore ends in either
     *  "Click to despawn!" (already the active/summoned pet) or "Left-click to summon!" (not active) -
     *  clicking an ALREADY-active Guardian would toggle it off, so that's recognized and never clicked.
     *  <p>
     *  Per killer560's explicit simplification: picks the FIRST Guardian found scanning slots in plain
     *  top-left-to-bottom-right order (standard chest-menu slot index order - already the order
     *  {@code menu.slots} iterates in), rather than trying to parse rarity/level from lore that
     *  doesn't reliably show rarity at all (see the class doc) - Hypixel's own /pets menu already
     *  sorts by rarity then level, so the first Guardian encountered IS the best one owned. */
    private GuardianScan findBestGuardianSlot(ChestMenu menu) {
        int containerSlotCount = menu.slots.size() - PLAYER_INVENTORY_SLOTS;
        for (Slot slot : menu.slots) {
            if (slot.index >= containerSlotCount) continue;
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) continue;
            String name = stack.getHoverName().getString();
            ItemLore lore = stack.get(DataComponents.LORE);
            String loreJoined = lore == null ? "" : String.join(" | ", lore.lines().stream()
                    .map(Component::getString).toList());
            LOGGER.info("Pets slot {}: name=\"{}\" lore=[{}]", slot.index, name, loreJoined);
            if (!name.contains("Guardian")) continue;
            if (loreJoined.contains("Click to despawn!")) {
                LOGGER.info("  -> matched Guardian, already active - stopping here");
                return new GuardianScan(-1, true);
            }
            LOGGER.info("  -> matched inactive Guardian at slot {} - picking it (first in reading order)", slot.index);
            return new GuardianScan(slot.index, false);
        }
        return new GuardianScan(-1, false);
    }

    /** @return the slot of the real "Next Page" navigation item (confirmed from a real screenshot of
     *  this exact screen), or -1 if not found. */
    private int findNextPageSlot(ChestMenu menu) {
        int containerSlotCount = menu.slots.size() - PLAYER_INVENTORY_SLOTS;
        for (Slot slot : menu.slots) {
            if (slot.index >= containerSlotCount) continue;
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) continue;
            if (stack.getHoverName().getString().contains("Next Page")) {
                return slot.index;
            }
        }
        return -1;
    }
}
