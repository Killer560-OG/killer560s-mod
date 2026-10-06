package com.killer560.hub.terminals;

import com.killer560.hub.util.ModLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The two F7/M7 terminal and device layouts, old and new (Hypixel's 2026-10-06 Skyblock update, killer560: Melody has
 * 3 rows instead of 4, "Click in order!" has 10 numbers instead of 14, and the P3 Simon Says device has 4 rounds
 * instead of 5).
 *
 * <p>Everything that READS a live board or device works out which layout it is from what is actually on screen or in
 * the world - Melody's button rows from the terracotta buttons present ({@link #melodyButtonSlots}), Numbers from the
 * numbered panes present, Simon Says from the steps it reveals and the device's own completion line - never from a
 * date or a switch. What those readers saw last is remembered here, so code that has to answer before a board is
 * open (party percentages, Termism's practice boards) follows the layout that is actually live.
 *
 * <p>{@link #ASSUME_NEW_LAYOUT} is the ONE place that picks a layout when nothing has been seen yet this session. It
 * only affects that fallback and the boards this mod BUILDS (Termism); no live reader consults it. Flip it to
 * {@code true} once the new layout is confirmed live on Hypixel.
 */
public final class TerminalLayouts {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoterminal");

    /** THE layout switch for anything that has seen no live board yet. false = old (4-row Melody, 14 numbers,
     *  5-round Simon Says); true = new (3 / 10 / 4). True since the official SkyBlock 0.27.2 patch notes confirmed
     *  all three ("Reduced the amount of rows on the Melody Terminal from 4 -> 3", Numbers 14 -> 10, "Removed one set
     *  of lights from the Simon Says terminal"). */
    public static final boolean ASSUME_NEW_LAYOUT = true;

    public static final int OLD_MELODY_ROWS = 4;
    public static final int NEW_MELODY_ROWS = 3;
    public static final int OLD_NUMBERS_COUNT = 14;
    public static final int NEW_NUMBERS_COUNT = 10;
    public static final int OLD_SIMON_ROUNDS = 5;
    public static final int NEW_SIMON_ROUNDS = 4;

    /** Melody's button column. Unchanged by the update as far as is known; buttons are FOUND by item, this only
     *  picks which column to look in and where Termism draws them. */
    public static final int MELODY_BUTTON_COLUMN = 7;

    /** The button slots used only when a Melody board shows no terracotta button at all (a board that has not
     *  populated yet): the assumed layout's. 16/25/34 for the new board is NoammAddons 1.2.9 MelodyTerminal's
     *  {@code claySlots} (its "remove 43" todo applied for 0.27.2). */
    private static final int[] LEGACY_MELODY_BUTTONS = ASSUME_NEW_LAYOUT ? new int[]{16, 25, 34} : new int[]{16, 25, 34, 43};

    private static int seenMelodyRows = -1;
    private static int seenNumbersCount = -1;
    private static int seenSimonRounds = -1;

    private TerminalLayouts() {
    }

    // ------------------------------------------------------------------ Melody

    /** @return the slots of Melody's clickable row buttons, top row first: every slot in the button column whose
     *  item is a terracotta (lime on the row being played, another colour on the others). A 4-row board gives
     *  16/25/34/43; a 3-row board gives three. With no terracotta on the board yet, the assumed layout's slots that
     *  fit inside the grid. */
    public static int[] melodyButtonSlots(List<ItemStack> items) {
        List<Integer> found = new ArrayList<>(4);
        for (int slot = MELODY_BUTTON_COLUMN; slot < items.size(); slot += 9) {
            if (isTerracotta(items.get(slot))) {
                found.add(slot);
            }
        }
        if (found.isEmpty()) {
            for (int slot : LEGACY_MELODY_BUTTONS) {
                if (slot < items.size()) {
                    found.add(slot);
                }
            }
        }
        int[] out = new int[found.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = found.get(i);
        }
        return out;
    }

    /** Records how many button rows a live Melody board showed (only call with a board that has terracotta). */
    public static void noteMelodyRows(int rows) {
        if (rows <= 0 || rows == seenMelodyRows) {
            return;
        }
        LOGGER.info("[Terminals] Melody board has {} button rows ({} layout)", rows, describe(rows, OLD_MELODY_ROWS, NEW_MELODY_ROWS));
        seenMelodyRows = rows;
    }

    /** @return the row count of the last live Melody board seen this session, else the assumed layout's. */
    public static int melodyRows() {
        return seenMelodyRows > 0 ? seenMelodyRows : ASSUME_NEW_LAYOUT ? NEW_MELODY_ROWS : OLD_MELODY_ROWS;
    }

    public static boolean isTerracotta(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return path.equals("terracotta") || path.endsWith("_terracotta");
    }

    // ------------------------------------------------------------------ Numbers

    /** Records how many numbered panes a live "Click in order!" board showed when it settled. */
    static void noteNumbersCount(int count) {
        if (count <= 0 || count == seenNumbersCount) {
            return;
        }
        LOGGER.info("[Terminals] Click in order board has {} numbers ({} layout)", count, describe(count, OLD_NUMBERS_COUNT, NEW_NUMBERS_COUNT));
        seenNumbersCount = count;
    }

    /** @return the number count of the last live "Click in order!" board seen this session, else the assumed layout's. */
    public static int numbersCount() {
        return seenNumbersCount > 0 ? seenNumbersCount : ASSUME_NEW_LAYOUT ? NEW_NUMBERS_COUNT : OLD_NUMBERS_COUNT;
    }

    // ------------------------------------------------------------------ Simon Says

    /** Records how many rounds the Simon Says device was seen to have: 5 when a fifth step was revealed, 4 when the
     *  device completed right after its fourth round. */
    public static void noteSimonRounds(int rounds, String why) {
        if (rounds <= 0 || rounds == seenSimonRounds) {
            return;
        }
        LOGGER.info("[SimonSays] Device has {} rounds ({} layout): {}", rounds, describe(rounds, OLD_SIMON_ROUNDS, NEW_SIMON_ROUNDS), why);
        seenSimonRounds = rounds;
    }

    /** @return true once a live device has shown its round count this session. */
    public static boolean simonRoundsSeen() {
        return seenSimonRounds > 0;
    }

    /** @return the Simon Says round count last seen live this session, else the assumed layout's. */
    public static int simonRounds() {
        return seenSimonRounds > 0 ? seenSimonRounds : ASSUME_NEW_LAYOUT ? NEW_SIMON_ROUNDS : OLD_SIMON_ROUNDS;
    }

    /** Forgets every layout seen this session. Nothing in the mod calls it; the testkit runs old and new boards in
     *  one client and uses it between them. */
    static void resetSeen() {
        seenMelodyRows = -1;
        seenNumbersCount = -1;
        seenSimonRounds = -1;
    }

    private static String describe(int n, int oldValue, int newValue) {
        return n == oldValue ? "old" : n == newValue ? "new" : "unknown";
    }
}
