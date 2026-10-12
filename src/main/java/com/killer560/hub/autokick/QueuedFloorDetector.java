package com.killer560.hub.autokick;

import com.killer560.hub.autokick.AutoKickConfig.Floor;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.partyfinder.PartyFinderParser;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Kick's "Detect Queued Floor" (killer560, 2026-10-11: "have it be so it detects which floor I just queued for").
 * Two sources, newest wins:
 * <ol>
 *   <li><b>Party Finder.</b> While the Party Finder's "Group Builder" menu (where the leader picks Dungeon and Floor
 *   before "Start A New Queue" lists the group, hypixelskyblock.minecraft.wiki/w/Party_Finder) is open, its items are
 *   read for the chosen dungeon type and floor. That pick only becomes the queued floor when Hypixel then says
 *   "Party Finder > Your party has been queued in the dungeon finder!" (the exact line Odin's PartyUtils matches). The
 *   menu is the server's own, and the chat line is anchored on both ends and starts with "Party Finder > ", which no
 *   player's chat line can (a player line starts with a rank or a name, and a name has no spaces), so another player
 *   can neither forge the floor nor the commit.</li>
 *   <li><b>/joininstance.</b> Any outgoing {@code joininstance [master_]catacombs_floor_<word>} - typed, or sent by this
 *   mod's {@code /f7}/{@code /m7} shortcuts (which expand to exactly that, see {@code CommandShortcutsFeature}). Only
 *   HIS client sends commands, so nobody else can reach this either.</li>
 * </ol>
 * Entrance has no Auto Kick times, so queuing it leaves no floor (Auto Kick says so instead of judging by the old one).
 */
public final class QueuedFloorDetector {

    private static final Logger LOGGER = ModLog.get("killer560smod-autokick");

    /** The Party Finder menu the leader builds his group in. */
    public static final String GROUP_BUILDER_TITLE = "Group Builder";

    static final Pattern QUEUED = Pattern.compile("^Party Finder > Your party has been queued in the dungeon finder!$");

    /** "Floor: Floor VII" (the Party Finder listing's own lore shape, PartyFinderParser.FLOOR), "Currently Selected:
     *  Floor VII", a bare "Floor VII" item name, and the Entrance forms of each. */
    private static final Pattern BUILDER_FLOOR = Pattern.compile(
            "^(?:[A-Za-z ]{1,24}: )?(?:Floor ([IV]{1,3})|(?:The Catacombs - )?(Entrance))$");
    /** "Dungeon: Master Mode The Catacombs" (the listing's own lore shape), "MM The Catacombs", "The Catacombs". */
    private static final Pattern BUILDER_TYPE = Pattern.compile(
            "^(?:[A-Za-z ]{1,24}: )?(Master Mode |MM )?The Catacombs$");
    private static final Pattern JOIN_INSTANCE = Pattern.compile(
            "^/?joininstance (master_)?catacombs_floor_(entrance|one|two|three|four|five|six|seven)$");
    private static final String[] WORDS = {"entrance", "one", "two", "three", "four", "five", "six", "seven"};

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
    /** Whether anything has been detected since the game started, and what (null floor = the Entrance). */
    private static boolean detected = false;
    private static Floor detectedFloor = null;
    private static String detectedSource = "";

    private QueuedFloorDetector() {
    }

    /** @return true once a queue has been seen; {@link #detectedFloor()} is then its floor (null = the Entrance). */
    public static boolean hasDetection() {
        return detected;
    }

    public static Floor detectedFloor() {
        return detectedFloor;
    }

    /** "party finder" or "joininstance" - for the log and the testkit. */
    public static String detectedSource() {
        return detectedSource;
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
        detectedSource = "";
    }

    // ---- Party Finder -----------------------------------------------------------------------------------------------

    /** Every client tick: while the Group Builder is open, read its choice. */
    static void tick(Minecraft client) {
        if (client.player != null && McCompat.screen(client) instanceof AbstractContainerScreen<?> screen
                && GROUP_BUILDER_TITLE.equals(screen.getTitle().getString())) {
            List<String> lines = new ArrayList<>();
            for (Slot slot : screen.getMenu().slots) {
                if (slot.container == client.player.getInventory()) {
                    continue;
                }
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) {
                    continue;
                }
                lines.add(stack.getHoverName().getString());
                lines.addAll(PartyFinderParser.loreStrings(stack));
            }
            onBuilderLines(lines);
        }
    }

    /**
     * The Group Builder's item names and lore lines (colour already stripped). Exactly one floor and at most one dungeon
     * type must be named; a menu that lists several floors (a picker) is not a choice and is ignored.
     */
    public static void onBuilderLines(List<String> lines) {
        Set<Integer> floors = new LinkedHashSet<>();
        Set<Boolean> types = new LinkedHashSet<>();
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            Matcher f = BUILDER_FLOOR.matcher(line);
            if (f.matches()) {
                floors.add(f.group(2) != null ? 0 : roman(f.group(1)));
                continue;
            }
            Matcher t = BUILDER_TYPE.matcher(line);
            if (t.matches()) {
                types.add(t.group(1) != null);
            }
        }
        if (floors.size() != 1 || types.size() > 1) {
            return;
        }
        int floor = floors.iterator().next();
        if (floor < 0 || floor > 7) {
            return;
        }
        Pick pick = new Pick(!types.isEmpty() && types.iterator().next(), floor);
        if (!pick.equals(builderPick)) {
            builderPick = pick;
            LOGGER.info("[AutoKick] Group Builder shows {}", pick.describe());
        }
    }

    /** One chat line, colour stripped and trimmed. */
    static void onLine(String plain) {
        if (QUEUED.matcher(plain).matches() && builderPick != null) {
            commit(builderPick, "party finder");
        }
    }

    // ---- /joininstance ----------------------------------------------------------------------------------------------

    /** An outgoing command, with or without its slash. */
    public static void onCommand(String command) {
        if (command == null) {
            return;
        }
        Matcher m = JOIN_INSTANCE.matcher(command.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return;
        }
        int floor = 0;
        for (int i = 0; i < WORDS.length; i++) {
            if (WORDS[i].equals(m.group(2))) {
                floor = i;
            }
        }
        commit(new Pick(m.group(1) != null, floor), "joininstance");
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

    private static void commit(Pick pick, String source) {
        detected = true;
        detectedFloor = pick.toFloor();
        detectedSource = source;
        LOGGER.info("[AutoKick] Queued floor {} (from {})", pick.describe(), source);
    }
}
