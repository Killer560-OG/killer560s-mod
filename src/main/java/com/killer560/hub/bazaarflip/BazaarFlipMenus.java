package com.killer560.hub.bazaarflip;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reading Hypixel's Bazaar and {@code /trades} menus BY CONTENTS.
 *
 * <p>No slot index is hardcoded anywhere in this class, on purpose. Auto Croesus can hardcode slots because
 * its layout came from a real published source (UnclaimedBloom6's AutoCroesus, via QUOI) that had been
 * checked against the live menus. <b>No such source exists for the Bazaar product menu or for
 * {@code /trades}</b>, and a wrong hardcoded index in a menu that spends coins is the worst possible failure
 * mode, so everything here matches on an item's visible name the way the class-selection overlay does.
 *
 * <h2>What is verified and what is not</h2>
 * Verified: the two data endpoints, the arithmetic, and that {@code /bz <name>} and {@code /trades} are real
 * Hypixel server commands (the mod already forwards {@code /bz} to Hypixel - see
 * {@code auction.BazaarFeature}).
 *
 * <p><b>NOT verified, and therefore not clicked:</b> the internal flow of Hypixel's Bazaar product menu (what
 * the instant-buy button is called today, whether it leads to a fixed-quantity page or a sign-input custom
 * amount, and what the confirmation looks like) and the internal flow of the {@code /trades} NPC menu (whether
 * selling is a shift-click on the player's own inventory, a per-item button, or a "sell inventory" button).
 * None of that is documented anywhere this could be read from, and it cannot be observed from the repository.
 * {@link #describe} exists to close that gap: the runner opens each menu, dumps exactly what is in it, and
 * stops. One dry run in the Hub produces the real names and the real flow, and then
 * {@link BazaarFlipFeature#MENU_CLICKS_VERIFIED} can be flipped and the click steps written against facts.
 */
public final class BazaarFlipMenus {

    private BazaarFlipMenus() {
    }

    /** Container slots that belong to the MENU rather than to the player's own inventory (the last 36 slots
     *  of any container are always the player's own main storage then hotbar). */
    public static List<Slot> menuSlots(AbstractContainerMenu menu) {
        int total = menu.slots.size();
        int end = Math.max(0, total - 36);
        return menu.slots.subList(0, end);
    }

    /** The player's own 36 inventory slots as they appear inside an open container. */
    public static List<Slot> playerSlots(AbstractContainerMenu menu) {
        int total = menu.slots.size();
        return menu.slots.subList(Math.max(0, total - 36), total);
    }

    /** Plain, colour-code-free name of whatever is in a slot, or "" for an empty slot. */
    public static String nameOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        String raw = stack.getHoverName().getString();
        String stripped = ChatFormatting.stripFormatting(raw);
        return stripped == null ? "" : stripped.trim();
    }

    /** Plain title of an open container screen. */
    public static String titleOf(AbstractContainerScreen<?> screen) {
        String stripped = ChatFormatting.stripFormatting(screen.getTitle().getString());
        return stripped == null ? "" : stripped.trim();
    }

    /**
     * First MENU slot whose item name contains any of {@code needles}, case-insensitively. Menu slots only -
     * never the player's own inventory, so a needle like "Sell" can't match an item he happens to be
     * carrying.
     *
     * @return the container slot index to click, or -1 if nothing matched
     */
    public static int findMenuSlot(AbstractContainerMenu menu, String... needles) {
        for (Slot slot : menuSlots(menu)) {
            String name = nameOf(slot.getItem()).toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            for (String needle : needles) {
                if (needle != null && !needle.isBlank() && name.contains(needle.toLowerCase(Locale.ROOT))) {
                    return slot.index;
                }
            }
        }
        return -1;
    }

    /** How many units of {@code productDisplayName} the player is currently carrying, counted across their
     *  own 36 slots of the open container by visible name. */
    public static int countInInventory(AbstractContainerMenu menu, String productDisplayName) {
        if (productDisplayName == null || productDisplayName.isBlank()) {
            return 0;
        }
        String needle = productDisplayName.toLowerCase(Locale.ROOT);
        int total = 0;
        for (Slot slot : playerSlots(menu)) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (nameOf(stack).toLowerCase(Locale.ROOT).contains(needle)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Empty slots among the player's own 36, inside an open container. */
    public static int freePlayerSlots(AbstractContainerMenu menu) {
        int free = 0;
        for (Slot slot : playerSlots(menu)) {
            if (slot.getItem().isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /**
     * Every non-empty MENU slot as {@code index=Name xN} text. This is the whole point of the dry run: it is
     * what turns "I don't know Hypixel's Bazaar buy flow" into a list of real button names that a click step
     * can then be written against.
     */
    public static String describe(AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        List<String> parts = new ArrayList<>();
        for (Slot slot : menuSlots(menu)) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            parts.add(slot.index + "=" + nameOf(stack) + (stack.getCount() > 1 ? " x" + stack.getCount() : ""));
        }
        return "\"" + titleOf(screen) + "\" [" + String.join(", ", parts) + "]";
    }
}
