package com.killer560.hub.autokick;

import com.killer560.hub.autokick.AutoKickConfig.Floor;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.partyfinder.PartyFinderParser;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Kick's "Detect Queued Floor" - Party Finder ONLY (killer560, 2026-10-11: "auto kick detection should happen
 * whenever I queue on party finder only. Whenever I queue it shows what floor I am queuing for and if it is master mode
 * or not. Read that."). /joininstance and the /f7-style shortcuts are deliberately NOT sources.
 * <p>
 * From his screenshots of the real menus: Party Finder's "Start a New Queue" opens the "Group Builder", whose
 * "Select Dungeon Type" item carries the lore line "Currently Selected: Master Mode The Catacombs" (or "... The
 * Catacombs") and whose "Select Floor" item carries "Currently Selected: Floor IV". Both are found by item NAME, never
 * by slot, with colour codes stripped. That reading becomes the queued floor only when Hypixel says
 * "Party Finder > Your party has been queued in the dungeon finder!" (the line Odin's PartyUtils matches). The menu is
 * the server's own, and the chat line is anchored on both ends and starts with "Party Finder > ", which no player's
 * chat line can (a player line starts with a rank or a name, and a name has no spaces), so another player can forge
 * neither. Entrance has no Auto Kick times, so queuing it leaves no floor (nobody is judged).
 * <p>
 * The same commit also dual-lists the party in the Custom Party Finder ({@code lfg.LfgFeature#onHypixelDungeonQueued}),
 * whether or not Auto Kick is on: this detector runs from Auto Kick's chat and tick listeners, which are never gated.
 */
public final class QueuedFloorDetector {

    private static final Logger LOGGER = ModLog.get("killer560smod-autokick");

    public static final String GROUP_BUILDER_TITLE = "Group Builder";
    static final String DUNGEON_TYPE_ITEM = "Select Dungeon Type";
    static final String FLOOR_ITEM = "Select Floor";

    static final Pattern QUEUED = Pattern.compile("^Party Finder > Your party has been queued in the dungeon finder!$");
    private static final Pattern SELECTED_TYPE = Pattern.compile("^Currently Selected: (Master Mode )?The Catacombs$");
    private static final Pattern SELECTED_FLOOR = Pattern.compile(
            "^Currently Selected: (?:Floor ([IV]{1,3})|(?:The Catacombs - )?(Entrance))$");

    /** A floor reading: floor 0 is the Entrance. */
    private record Pick(boolean master, int floor) {
        Floor toFloor() {
            return floor <= 0 ? null : Floor.fromRaw((master ? "M" : "F") + floor);
        }

        String describe() {
            return floor <= 0 ? "Entrance" : (master ? "M" : "F") + floor;
        }
    }

    /** The Group Builder's current choice, waiting for the "queued" line. */
    private static Pick builderPick = null;
    private static boolean detected = false;
    private static Floor detectedFloor = null;

    private QueuedFloorDetector() {
    }

    /** @return true once a Party Finder queue has been seen; {@link #detectedFloor()} is its floor (null = Entrance). */
    public static boolean hasDetection() {
        return detected;
    }

    public static Floor detectedFloor() {
        return detectedFloor;
    }

    /** The Group Builder pick waiting for a queue line ("F7", "M3", "Entrance"), or null. */
    public static String builderPick() {
        return builderPick == null ? null : builderPick.describe();
    }

    /** Test/reset hook: forget everything detected. */
    public static void reset() {
        builderPick = null;
        detected = false;
        detectedFloor = null;
    }

    /** Every client tick: while the Group Builder is open, read its two "Currently Selected:" lines. */
    static void tick(Minecraft client) {
        if (client.player == null || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)
                || !GROUP_BUILDER_TITLE.equals(ChatObserver.stripCodes(screen.getTitle().getString()).trim())) {
            return;
        }
        List<String> typeLore = null;
        List<String> floorLore = null;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == client.player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String name = ChatObserver.stripCodes(stack.getHoverName().getString()).trim();
            if (name.equals(DUNGEON_TYPE_ITEM)) {
                typeLore = PartyFinderParser.loreStrings(stack);
            } else if (name.equals(FLOOR_ITEM)) {
                floorLore = PartyFinderParser.loreStrings(stack);
            }
        }
        if (typeLore != null && floorLore != null) {
            onBuilderItems(typeLore, floorLore);
        }
    }

    /** The two items' lore lines: "Currently Selected: [Master Mode ]The Catacombs" and "Currently Selected: Floor N". */
    public static void onBuilderItems(List<String> typeLore, List<String> floorLore) {
        Boolean master = null;
        for (String raw : typeLore) {
            Matcher t = SELECTED_TYPE.matcher(ChatObserver.stripCodes(raw).trim());
            if (t.matches()) {
                master = t.group(1) != null;
            }
        }
        Integer floor = null;
        for (String raw : floorLore) {
            Matcher f = SELECTED_FLOOR.matcher(ChatObserver.stripCodes(raw).trim());
            if (f.matches()) {
                floor = f.group(2) != null ? 0 : roman(f.group(1));
            }
        }
        if (master == null || floor == null || floor > 7) {
            return;
        }
        Pick pick = new Pick(master, floor);
        if (!pick.equals(builderPick)) {
            builderPick = pick;
            LOGGER.info("[AutoKick] Group Builder shows {}", pick.describe());
        }
    }

    /** One chat line, colour stripped and trimmed: the queued line commits the Group Builder's pick. */
    static void onLine(String plain) {
        if (builderPick != null && QUEUED.matcher(plain).matches()) {
            detected = true;
            detectedFloor = builderPick.toFloor();
            LOGGER.info("[AutoKick] Queued floor {} (from Party Finder)", builderPick.describe());
            // The Custom Party Finder lists the same party too ("dual list", killer560's to-do item 1, 2026-10-18).
            com.killer560.hub.lfg.LfgFeature.onHypixelDungeonQueued(builderPick.describe());
        }
    }

    /** I..VII (the pattern allows only I and V, up to three letters). */
    private static int roman(String r) {
        int total = 0;
        int last = 0;
        for (int i = r.length() - 1; i >= 0; i--) {
            int v = r.charAt(i) == 'V' ? 5 : 1;
            total += v < last ? -v : v;
            last = Math.max(last, v);
        }
        return total;
    }
}
