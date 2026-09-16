package com.killer560.hub.leapmenu;

import com.killer560.hub.dungeonclass.ClassOverrides;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who is in your party, in the order Hypixel lists them, plus each teammate's dungeon class when known.
 * Used by the Leap Order editor (which needs names even outside a dungeon) and the custom leap menu.
 * <ul>
 * <li>Inside a dungeon the tab list is authoritative: "[lvl] Name (Class ...)" entries, same regex as NoammAddons'
 * DungeonListener (see {@code dungeonalerts/ClassColors}).
 * <li>Anywhere else, Hypixel's party chat lines: the "/party list" block, joins, leaves, kicks, disbands, Party
 * Finder joins and party-chat senders.
 * </ul>
 */
public final class PartyTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-leapmenu");

    private static final String NAME = "(?:\\[[^]]+] )?([A-Za-z0-9_]{1,16})";
    private static final Pattern TAB_REGEX = Pattern.compile("^\\[\\d+] (?:\\[[^]]+] )*([A-Za-z0-9_]{1,16}) .*\\((\\w+)(?: (\\w+))?\\)$");
    /** Trailing IGN of a leap-menu head name - the same pattern the custom leap menu reads its heads with. */
    private static final Pattern LEAP_HEAD_IGN = Pattern.compile("([A-Za-z0-9_]{1,16})\\s*$");
    private static final Pattern LIST_HEADER = Pattern.compile("^Party Members \\(\\d+\\)$");
    private static final Pattern LIST_LINE = Pattern.compile("^Party (?:Leader|Moderators|Members): (.+)$");
    private static final Pattern LIST_ENTRY = Pattern.compile(NAME + " ●");
    private static final Pattern JOINED = Pattern.compile("^" + NAME + " joined the party\\.$");
    private static final Pattern YOU_JOINED = Pattern.compile("^You have joined " + NAME + "'s? party!$");
    private static final Pattern PARTYING_WITH = Pattern.compile("^You'll be partying with: (.+)$");
    private static final Pattern PF_JOINED = Pattern.compile("^Party Finder > ([A-Za-z0-9_]{1,16}) joined the dungeon group! \\((\\w+) Level \\d+\\)$");
    private static final Pattern PARTY_CHAT = Pattern.compile("^Party > " + NAME + ": .*$");
    private static final Pattern LEFT = Pattern.compile("^" + NAME + " (?:has left the party\\.|has been removed from the party\\.|was removed from your party because they disconnected\\.?)$");
    private static final Pattern KICKED_OFFLINE = Pattern.compile("^Kicked " + NAME + " because they were offline\\.$");
    // Odin/NoammAddons/QUOI PartyUtils transferLeave: the old leader left (no separate "has left the party." line).
    private static final Pattern TRANSFER_LEAVE = Pattern.compile("^The party was transferred to " + NAME + " because " + NAME + " left$");
    private static final Pattern CLEARED = Pattern.compile("^(?:You left the party\\.|You are not currently in a party\\.|You have been kicked from the party by .+|"
            + ".+ has disbanded the party!|The party was disbanded because .+|You are not in a party.*)$");

    /** Names as Hypixel spells them, in listing order. Includes yourself when Hypixel lists you. */
    private static final Set<String> MEMBERS = new LinkedHashSet<>();
    private static final Map<String, DungeonClass> CLASSES = new HashMap<>();
    /** Lowercase names the dungeon tab list currently shows as "(DEAD)". */
    private static final Set<String> DEAD = new HashSet<>();
    private static boolean wasInDungeon = false;
    private static boolean readingList = false;
    private static int tickCounter = 0;
    private static String lastLogged = "";

    private PartyTracker() {
    }

    public static void register() {
        ChatObserver.subscribe(PartyTracker::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++tickCounter < 10) {
                return;
            }
            tickCounter = 0;
            try {
                readTabList(client);
            } catch (RuntimeException e) {
                LOGGER.warn("[PartyTracker] Tab list read failed: {}", e.toString());
            }
        });
    }

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message);
        if (plain == null) {
            return;
        }
        plain = plain.trim();
        if (plain.isEmpty()) {
            return;
        }
        if (LIST_HEADER.matcher(plain).matches()) {
            MEMBERS.clear();
            readingList = true;
            return;
        }
        Matcher m = LIST_LINE.matcher(plain);
        if (m.matches()) {
            Matcher entry = LIST_ENTRY.matcher(m.group(1));
            while (entry.find()) {
                add(entry.group(1));
            }
            return;
        }
        if (readingList && !plain.startsWith("Party ") && !plain.startsWith("-")) {
            readingList = false;
        }
        if ((m = JOINED.matcher(plain)).matches() || (m = PARTY_CHAT.matcher(plain)).matches()) {
            add(m.group(1));
        } else if ((m = YOU_JOINED.matcher(plain)).matches()) {
            MEMBERS.clear();
            add(m.group(1));
        } else if ((m = PARTYING_WITH.matcher(plain)).matches()) {
            for (String part : m.group(1).split(", ")) {
                Matcher n = Pattern.compile("^" + NAME + "$").matcher(part.trim());
                if (n.matches()) {
                    add(n.group(1));
                }
            }
        } else if ((m = PF_JOINED.matcher(plain)).matches()) {
            add(m.group(1));
            DungeonClass c = parseClass(m.group(2));
            if (c != null) {
                CLASSES.put(m.group(1).toLowerCase(Locale.US), c);
            }
        } else if ((m = LEFT.matcher(plain)).matches() || (m = KICKED_OFFLINE.matcher(plain)).matches()) {
            remove(m.group(1));
        } else if ((m = TRANSFER_LEAVE.matcher(plain)).matches()) {
            add(m.group(1));
            remove(m.group(2));
        } else if (CLEARED.matcher(plain).matches()) {
            MEMBERS.clear();
        } else {
            return;
        }
        logIfChanged();
    }

    private static void readTabList(Minecraft client) {
        boolean inDungeon = client.getConnection() != null && DungeonState.isInDungeon();
        if (!inDungeon) {
            if (wasInDungeon) {
                // Classes/deaths from the finished run must not leak into the next one (or into p3sim, where the tab
                // list has no class entries and selfClass() would otherwise keep returning last run's class).
                wasInDungeon = false;
                CLASSES.clear();
                DEAD.clear();
            }
            return;
        }
        wasInDungeon = true;
        List<String> fromTab = new ArrayList<>();
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String plain = ChatFormatting.stripFormatting(display.getString());
            if (plain == null) {
                continue;
            }
            Matcher m = TAB_REGEX.matcher(plain.trim());
            if (!m.matches()) {
                continue;
            }
            String name = m.group(1);
            fromTab.add(name);
            if ("DEAD".equals(m.group(2))) {
                DEAD.add(name.toLowerCase(Locale.US));
            } else {
                DEAD.remove(name.toLowerCase(Locale.US));
            }
            // "DEAD" / "EMPTY" keep whatever class was known before (same as NoammAddons).
            DungeonClass c = parseClass(m.group(2));
            if (c != null) {
                CLASSES.put(name.toLowerCase(Locale.US), c);
            }
        }
        if (!fromTab.isEmpty() && !new ArrayList<>(MEMBERS).equals(fromTab)) {
            MEMBERS.clear();
            MEMBERS.addAll(fromTab);
            logIfChanged();
        }
    }

    private static DungeonClass parseClass(String raw) {
        if (raw == null) {
            return null;
        }
        return "Berserk".equalsIgnoreCase(raw) ? DungeonClass.BERSERKER : DungeonClass.byName(raw);
    }

    private static void add(String name) {
        for (String existing : MEMBERS) {
            if (existing.equalsIgnoreCase(name)) {
                return;
            }
        }
        MEMBERS.add(name);
    }

    private static void remove(String name) {
        MEMBERS.removeIf(existing -> existing.equalsIgnoreCase(name));
    }

    /** Called once a leap menu's slots have settled - the heads in it are definitely teammates, and the order they
     *  come in is Hypixel's own leap menu order (container slot order), which the Leap Order editor lays its spots
     *  out in. Remembered (and persisted) so the editor's spots start where the real menu puts them. */
    public static void noteTeammates(List<String> namesInSlotOrder) {
        // Normalised here, not at the call sites: the custom leap menu already hands over bare IGNs but the fast-leap
        // path hands over raw head names ("[MVP+] Name"). Un-normalised those would enter MEMBERS as a second,
        // never-matching "teammate", would match nobody in #teammatesInLeapOrder, and - when both paths poll the same
        // open menu - would make the two disagree about the stored order and save the config on every tick.
        List<String> names = new ArrayList<>(namesInSlotOrder.size());
        for (String raw : namesInSlotOrder) {
            String name = leapIgn(raw);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (String n : names) {
            int before = MEMBERS.size();
            add(n);
            changed |= MEMBERS.size() != before;
        }
        if (changed) {
            logIfChanged();
        }
        LeapMenuConfig cfg = LeapMenuConfig.getInstance();
        if (cfg.setLastLeapOrder(names)) {
            cfg.save();
            LOGGER.info("[PartyTracker] Leap menu order: {}", names);
        }
    }

    /** The IGN at the end of a leap-menu head name (rank prefix and formatting dropped), else the plain name. */
    private static String leapIgn(String raw) {
        String plain = ChatObserver.strip(raw);
        Matcher m = LEAP_HEAD_IGN.matcher(plain);
        return m.find() ? m.group(1) : plain;
    }

    /** Teammates in the order the real Spirit Leap menu last showed them (anyone it hasn't shown yet keeps Hypixel's
     *  listing order, after the ones it has). The Leap Order editor fills its 4 spots from this, so an untouched
     *  layout matches the live menu instead of being one position out. */
    public static List<String> teammatesInLeapOrder() {
        List<String> remaining = teammates();
        List<String> order = LeapMenuConfig.getInstance().getLastLeapOrder();
        if (order.isEmpty()) {
            return remaining;
        }
        List<String> out = new ArrayList<>(remaining.size());
        for (String wanted : order) {
            for (int i = 0; i < remaining.size(); i++) {
                if (remaining.get(i).equalsIgnoreCase(wanted)) {
                    out.add(remaining.remove(i));
                    break;
                }
            }
        }
        out.addAll(remaining);
        return out;
    }

    private static void logIfChanged() {
        String summary = MEMBERS + " " + CLASSES;
        if (!summary.equals(lastLogged)) {
            lastLogged = summary;
            LOGGER.info("[PartyTracker] Party: {}", summary);
        }
    }

    /** Everyone known to be in your party except you, in Hypixel's listing order. */
    public static List<String> teammates() {
        Minecraft client = Minecraft.getInstance();
        String self = client.player == null ? null : client.player.getGameProfile().name();
        List<String> out = new ArrayList<>();
        for (String n : MEMBERS) {
            if (self == null || !n.equalsIgnoreCase(self)) {
                out.add(n);
            }
        }
        return out;
    }

    /** True while the dungeon tab list shows this teammate as dead. */
    public static boolean isDead(String name) {
        return name != null && DEAD.contains(name.toLowerCase(Locale.US));
    }

    /** A teammate's class: the mod-wide manual override for that IGN when one is set ({@link ClassOverrides} -
     *  killer560: "five mages but one mage is doing berserk's term"), else what the dungeon tab list / Party Finder
     *  showed. The leap menus, Live Map, Run Stats, Teammates, Ability Cooldown, Run Summary, Core Entry Times and
     *  {@code P5State.selfClass()} all read through here, so one override fixes all of them at once. */
    public static DungeonClass classOf(String name) {
        return name == null ? null : ClassOverrides.classOf(name, CLASSES.get(name.toLowerCase(Locale.US)));
    }

    /** Your own class from the dungeon tab list, or null outside a dungeon / before the tab list shows it. */
    public static DungeonClass selfClass() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? null : classOf(client.player.getGameProfile().name());
    }
}
