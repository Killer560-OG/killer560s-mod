package com.killer560.hub.leapmenu;

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

    /** Called when a leap menu opens - the heads in it are definitely teammates. */
    public static void noteTeammates(List<String> names) {
        boolean changed = false;
        for (String n : names) {
            int before = MEMBERS.size();
            add(n);
            changed |= MEMBERS.size() != before;
        }
        if (changed) {
            logIfChanged();
        }
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

    public static DungeonClass classOf(String name) {
        return name == null ? null : CLASSES.get(name.toLowerCase(Locale.US));
    }

    /** Your own class from the dungeon tab list, or null outside a dungeon / before the tab list shows it. */
    public static DungeonClass selfClass() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? null : classOf(client.player.getGameProfile().name());
    }
}
