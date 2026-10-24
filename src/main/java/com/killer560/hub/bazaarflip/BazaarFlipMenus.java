package com.killer560.hub.bazaarflip;

import com.killer560.hub.bazaar.BazaarPages;
import com.killer560.hub.croesus.DungeonChestValuer;
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
 * <p>No slot index is hardcoded here. Bazaar pages are recognized by {@link BazaarPages} (the Bazaar reskin's own
 * classifier, built from SkyHanni, Skyblocker and the wiki) and buttons are found by their visible name. The one
 * real dump on record (killer560's Untested Cheat client, 2026-10-08) confirmed the first hop: {@code /bz Lump of
 * Magma} opens {@code Bazaar ➜ "Lump of Magma"} with the product at slot 11 under its own name, and Hypixel's chat
 * confirmed an instant buy is direct ({@code [Bazaar] Executing instant buy...} then {@code [Bazaar] Bought 16x
 * Ender Pearl for 112.0 coins!}). Selling from {@code /trades} needs an active Booster Cookie (wiki, Trades).
 * {@link #describe} is still logged whenever a page is not the one expected, so a layout change names itself.
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

    /** Plain, color-code-free name of whatever is in a slot, or "" for an empty slot. */
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

    // ---- the flip's own reads (2026-10-11) -------------------------------------------------------------

    /** The open menu as {@link BazaarPages} classifies it - the same classifier the Bazaar reskin uses, so the
     *  flipper and the reskin can never disagree about which Bazaar page is open. */
    public static BazaarPages.Page page(AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        List<BazaarPages.Item> items = new ArrayList<>();
        for (Slot slot : menuSlots(menu)) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String name = nameOf(stack);
            items.add(new BazaarPages.Item(slot.index, name, DungeonChestValuer.cleanLore(stack), name.isBlank()));
        }
        int rows = menuSlots(menu).size() / 9;
        return BazaarPages.classify(titleOf(screen), rows, items);
    }

    /** The first item on {@code page} (content, sidebar or bar) whose name is exactly {@code name}, ignoring case. */
    public static BazaarPages.Item item(BazaarPages.Page page, String name) {
        for (List<BazaarPages.Item> part : List.of(page.content(), page.nav(), page.sidebar())) {
            for (BazaarPages.Item it : part) {
                if (it.name().equalsIgnoreCase(name)) {
                    return it;
                }
            }
        }
        return null;
    }

    /** Whether {@code stack} is the product: its Skyblock id when it has one, else its visible name. */
    public static boolean isProduct(ItemStack stack, String productId, String displayName) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = com.killer560.hub.autoroutes.ItemIdentity.skyblockId(stack);
        if (id != null && !id.isBlank()) {
            return id.equals(productId);
        }
        return displayName != null && nameOf(stack).equalsIgnoreCase(displayName);
    }

    /** Units of the product in the player's own 36 slots of the open container. */
    public static int countProduct(AbstractContainerMenu menu, String productId, String displayName) {
        int total = 0;
        for (Slot slot : playerSlots(menu)) {
            if (isProduct(slot.getItem(), productId, displayName)) {
                total += slot.getItem().getCount();
            }
        }
        return total;
    }

    /** How many more units of the product fit: empty slots at a full stack each, plus the gap on partial stacks. */
    public static int roomFor(AbstractContainerMenu menu, String productId, String displayName, int stackSize) {
        int room = 0;
        int max = Math.max(1, stackSize);
        for (Slot slot : playerSlots(menu)) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                room += max;
            } else if (isProduct(stack, productId, displayName)) {
                room += Math.max(0, max - stack.getCount());
            }
        }
        return room;
    }

    private static final java.util.regex.Pattern LORE_PRICE =
            java.util.regex.Pattern.compile("^(?:Price|Total|Cost): ([\\d,]{1,15}(?:\\.\\d{1,2})?) coins?$");

    /** "Amount: 2,304x" off an instant-buy option, or -1 when the lore has no such line ({@link BazaarPages#loreAmount}). */
    public static long loreAmount(List<String> lore) {
        return BazaarPages.loreAmount(lore);
    }

    /** "Price: 80,627 coins" off an instant-buy option, or -1 when the lore has no such line. */
    public static double lorePrice(List<String> lore) {
        for (String line : lore) {
            java.util.regex.Matcher m = LORE_PRICE.matcher(line);
            if (m.matches()) {
                return Double.parseDouble(m.group(1).replace(",", ""));
            }
        }
        return -1;
    }

    /** A cheap fingerprint of the menu's own items, so a page is judged only once its items stop changing. */
    public static String signature(AbstractContainerMenu menu) {
        StringBuilder sb = new StringBuilder();
        for (Slot slot : menuSlots(menu)) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                sb.append(slot.index).append('=').append(nameOf(stack)).append('x').append(stack.getCount()).append(';');
            }
        }
        return sb.toString();
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
