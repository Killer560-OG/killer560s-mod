package com.killer560.hub.storageoverlay;

import com.killer560.hub.cheatutils.CheatUtils;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What one icon of Hypixel's "Storage" menu stands for: a real Ender Chest page or backpack, or one of the two kinds of
 * nothing (killer560's screenshots, 2026-10-07):
 * <ul>
 *   <li>a locked Ender Chest page: <b>Locked Page</b>, lore "Unlock more Ender Chest pages in the community shop!";</li>
 *   <li>an unlocked backpack slot with no backpack in it: <b>Empty Backpack Slot 16</b>, lore "Left-click a backpack item
 *       on this slot to place it!" - clicking that while holding a backpack PLACES it.</li>
 * </ul>
 * Read by the Storage Overlay's overview scan (what gets a "Click to load" panel) and by Storage Search's Scan All (which
 * pages it visits), so the two cannot disagree. Every pattern is anchored and bounded, the text is capped before it is
 * matched, and {@link #classify} never throws: it runs on the render path.
 */
public final class StoragePageSlots {

    public enum Kind {
        /** A real page or backpack: worth opening. */
        PAGE,
        /** Not unlocked (a "Locked Page"). */
        LOCKED,
        /** Unlocked backpack slot with no backpack in it. */
        EMPTY_BACKPACK,
        /** No item, or Hypixel's blank filler pane. */
        NONE
    }

    /** "Locked Page"; also any other "Locked <words> [N]" Hypixel may use for a slot that is not bought yet. */
    private static final Pattern LOCKED_NAME = Pattern.compile("^Locked [A-Za-z ]{1,24}(?: #?[0-9]{1,2})?$");
    private static final Pattern LOCKED_LORE =
            Pattern.compile("^Unlock more Ender Chest pages in the community shop!?$");
    /** "Empty Backpack Slot 16" (no '#' on the live server; '#' accepted too). */
    private static final Pattern EMPTY_NAME = Pattern.compile("^Empty Backpack Slot #?[0-9]{1,2}$");
    private static final Pattern EMPTY_LORE =
            Pattern.compile("^Left-click a backpack item on this slot to place it!?$");

    /** Longest name / joined lore looked at; Hypixel's are far shorter. */
    private static final int MAX_TEXT = 200;

    private StoragePageSlots() {
    }

    public static Kind classify(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) {
                return Kind.NONE;
            }
            String name = cap(CheatUtils.plainName(stack).strip());
            if (name.isEmpty()) {
                return Kind.NONE;
            }
            if (EMPTY_NAME.matcher(name).matches()) {
                return Kind.EMPTY_BACKPACK;
            }
            if (LOCKED_NAME.matcher(name).matches()) {
                return Kind.LOCKED;
            }
            // The lore is wrapped over two lines in the menu; joined it is one sentence.
            String lore = joinedLore(stack);
            if (EMPTY_LORE.matcher(lore).matches()) {
                return Kind.EMPTY_BACKPACK;
            }
            if (LOCKED_LORE.matcher(lore).matches()) {
                return Kind.LOCKED;
            }
            return Kind.PAGE;
        } catch (RuntimeException e) {
            // Never on the render path. An icon that cannot be read is not clicked.
            return Kind.NONE;
        }
    }

    private static String joinedLore(ItemStack stack) {
        List<String> lines = CheatUtils.lore(stack);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
            if (sb.length() > MAX_TEXT) {
                break;
            }
        }
        return cap(sb.toString());
    }

    private static String cap(String s) {
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    /** Overview slot of Ender Chest page {@code page} (1-9): slots 9-17. */
    public static int enderChestSlot(int page) {
        return 8 + page;
    }

    /** Overview slot of backpack {@code n} (1-18): slots 27-44. */
    public static int backpackSlot(int n) {
        return 26 + n;
    }
}
