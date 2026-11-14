package com.killer560.hub.leapmenu;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.dungeonclass.ClassOverrides;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

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

    private static final Logger LOGGER = ModLog.get("killer560smod-leapmenu");

    private static final String NAME = "(?:\\[[^]]+] )?([A-Za-z0-9_]{1,16})";
    /** Hoisted (2026-09-20, FPS pass): this was compiled fresh for every comma-separated member of
     *  every "You'll be partying with" line. */
    private static final Pattern NAME_ONLY = Pattern.compile("^" + NAME + "$");
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
    // Names anchored like everywhere else: ".+ has disbanded the party!" let ANY player wipe the member list by
    // typing that line in any chat (found by the testkit logic suite, 2026-10-04).
    private static final String ANY_NAME = "(?:\\[[^]]+] )?[A-Za-z0-9_]{1,16}";
    private static final Pattern CLEARED = Pattern.compile("^(?:You left the party\\.|You are not currently in a party\\.|You have been kicked from the party by " + ANY_NAME + "|"
            + ANY_NAME + " has disbanded the party!|The party was disbanded because .+|You are not in a party.*)$");
    // Hypixel's own lines naming YOUR class (leaporder-1102, all seen in his logs): the class menu, the dungeon's
    // pre-start broadcast (checked against your own name), a Party Finder group, and the run's first line when your class
    // is unique. Anchored on the whole line with a bare name group, so no player's chat can forge one.
    private static final String CLASS_WORD = "(Mage|Tank|Healer|Archer|Berserk|Berserker)";
    private static final Pattern YOU_SELECTED = Pattern.compile("^You have selected the " + CLASS_WORD + " Dungeon Class!$");
    private static final Pattern NAME_SELECTED = Pattern.compile("^([A-Za-z0-9_]{1,16}) selected the " + CLASS_WORD + " Class!$");
    private static final Pattern PF_SET_CLASS = Pattern.compile("^Party Finder > ([A-Za-z0-9_]{1,16}) set their class to " + CLASS_WORD + " Level \\d{1,3}!$");
    private static final Pattern STATS_DOUBLED = Pattern.compile("^Your " + CLASS_WORD + " stats are doubled because you are the only player using this class!$");

    /** Names as Hypixel spells them, in listing order. Includes yourself when Hypixel lists you. */
    private static final Set<String> MEMBERS = new LinkedHashSet<>();
    private static final Map<String, DungeonClass> CLASSES = new HashMap<>();
    /** Lowercase names the dungeon tab list currently shows as "(DEAD)". */
    private static final Set<String> DEAD = new HashSet<>();
    /** Lowercase names that left or were kicked: Hypixel's dungeon tab keeps them until the run ends, so the tab read
     *  must not put them back (partyleft-1110). Cleared on a rejoin, a new party and the end of the dungeon. */
    private static final Set<String> LEFT_NAMES = new HashSet<>();
    private static boolean wasInDungeon = false;
    private static boolean readingList = false;
    private static int tickCounter = 0;
    /** The connection the party was learned on (weak: a closed one is not kept alive). Render thread only. */
    private static java.lang.ref.WeakReference<Object> lastConnection = new java.lang.ref.WeakReference<>(null);

    private PartyTracker() {
    }

    public static void register() {
        ChatObserver.subscribe(PartyTracker::onChat);
        // A new connection starts with nobody known. Hypixel's leave, kick and disband lines cannot reach a player who
        // is offline, so names kept from before a rejoin were never taken out, and a solo player's Custom Party Finder
        // listing went up as 5/5 and came straight down as "your party is full" (killer560, 2026-10-22; testkit
        // 1022-hx-pf-solo-queue). JOIN runs on the render thread (handleLogin), before any chat of the new connection.
        // Only a NEW connection: Hypixel fires JOIN again on every server switch (party warp, island, hub) over the same
        // Connection, and the party lives on through it. Clearing there split a party's relay room: the warped member
        // forgot the leader while the leader kept the member, so Song Tag and Mod Chat went to two different rooms
        // (spotshare-1105, his log 2026-11-05 23:06-23:08 with autowd; testkit 1105-hx-songtag-party-warp).
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (lastConnection.get() != handler.getConnection()) {
                lastConnection = new java.lang.ref.WeakReference<>(handler.getConnection());
                clearParty();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("PartyTracker", client -> {
            if (++tickCounter < 10) {
                return;
            }
            tickCounter = 0;
            try {
                readTabList(client);
            } catch (RuntimeException e) {
                LOGGER.warn("[PartyTracker] Tab list read failed: {}", e.toString());
            }
        }));
    }

    /** A party line that never reaches chat because a feature hid it (Kick Rejoin Timer's own /pl reply): read it as if it had. */
    public static void feed(Component message) {
        onChat(message);
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
        if (plain.startsWith("Party ") && LIST_HEADER.matcher(plain).matches()) {
            MEMBERS.clear();
            readingList = true;
            return;
        }
        Matcher m;
        if (plain.startsWith("Party ") && (m = LIST_LINE.matcher(plain)).matches()) {
            Matcher entry = LIST_ENTRY.matcher(m.group(1));
            while (entry.find()) {
                add(entry.group(1));
            }
            return;
        }
        if (readingList && !plain.startsWith("Party ") && !plain.startsWith("-")) {
            readingList = false;
        }
        if (plain.endsWith("Class!") || plain.endsWith("using this class!") || plain.startsWith("Party Finder > ")
                && plain.contains(" set their class to ")) {
            if (onClassLine(plain)) {
                return;
            }
        }
        // Every pattern below needs "party"/"Party" in the line, except KICKED_OFFLINE's "Kicked ...": a cheap test
        // before eleven Matchers (fps-idle-1015; LIST_HEADER / LIST_LINE above start with "Party " too).
        if (!plain.contains("arty") && !plain.startsWith("Kicked ")) {
            return;
        }
        if ((m = JOINED.matcher(plain)).matches() || (m = PARTY_CHAT.matcher(plain)).matches()) {
            add(m.group(1));
        } else if ((m = YOU_JOINED.matcher(plain)).matches()) {
            MEMBERS.clear();
            LEFT_NAMES.clear();
            add(m.group(1));
        } else if ((m = PARTYING_WITH.matcher(plain)).matches()) {
            for (String part : m.group(1).split(", ")) {
                Matcher n = NAME_ONLY.matcher(part.trim());
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
            LEFT_NAMES.clear();
        }
    }

    /** One of Hypixel's class lines: yours is remembered as the class you are playing next, a teammate's Party Finder
     *  line is their class until the tab list says otherwise. @return true when the line was one. */
    private static boolean onClassLine(String plain) {
        Matcher m;
        String who;
        DungeonClass cls;
        if ((m = YOU_SELECTED.matcher(plain)).matches() || (m = STATS_DOUBLED.matcher(plain)).matches()) {
            who = null;
            cls = parseClass(m.group(1));
        } else if ((m = NAME_SELECTED.matcher(plain)).matches() || (m = PF_SET_CLASS.matcher(plain)).matches()) {
            who = m.group(1);
            cls = parseClass(m.group(2));
        } else {
            return false;
        }
        if (cls == null) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        String self = client.player == null ? null : client.player.getGameProfile().name();
        if (who == null || who.equalsIgnoreCase(self)) {
            LeapMenuConfig cfg = LeapMenuConfig.getInstance();
            if (cfg.setLastSelectedClass(cls)) {
                cfg.save();
            }
            if (who != null && self != null && !plain.startsWith("Party Finder > ") && DungeonState.isInDungeon()) {
                // The dungeon's broadcast: the tab list's class for you is out of date until its next refresh.
                CLASSES.put(self.toLowerCase(Locale.US), cls);
            }
        } else if (plain.startsWith("Party Finder > ") || DungeonState.isInDungeon()) {
            CLASSES.put(who.toLowerCase(Locale.US), cls);
        }
        return true;
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
                LEFT_NAMES.clear();
            }
            return;
        }
        wasInDungeon = true;
        List<String> fromTab = new ArrayList<>();
        // In the order the tab list draws them, which is Hypixel's party order: the dungeon map's player markers come in
        // that order and InteractiveMapFeature.players pairs them up by position (getListedOnlinePlayers is a hash set).
        for (PlayerInfo info : com.killer560.hub.util.TabOrder.listed(client)) {
            String plain = com.killer560.hub.util.TabText.plain(info);
            // TAB_REGEX needs "[" first and ")" last once trimmed; most of the ~80 lines are neither, and skipping
            // them spares a trim and a Matcher each (95-perf, 2026-10-15).
            if (plain == null || !com.killer560.hub.util.TabText.bracketedLine(plain)) {
                continue;
            }
            Matcher m = TAB_REGEX.matcher(plain.trim());
            if (!m.matches()) {
                continue;
            }
            String name = m.group(1);
            if (!LEFT_NAMES.isEmpty() && LEFT_NAMES.contains(name.toLowerCase(Locale.US))) {
                continue;
            }
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
        }
    }

    /** Forgets the party: a new connection, where nothing said before it can be trusted. */
    static void clearParty() {
        MEMBERS.clear();
        CLASSES.clear();
        DEAD.clear();
        LEFT_NAMES.clear();
        readingList = false;
        wasInDungeon = false;
    }

    private static DungeonClass parseClass(String raw) {
        return raw == null ? null : "Berserk".equalsIgnoreCase(raw) ? DungeonClass.BERSERKER : DungeonClass.byName(raw);
    }

    private static void add(String name) {
        LEFT_NAMES.remove(name.toLowerCase(Locale.US));
        if (MEMBERS.stream().noneMatch(existing -> existing.equalsIgnoreCase(name))) {
            MEMBERS.add(name);
        }
    }

    private static void remove(String name) {
        if (LEFT_NAMES.size() < 64) {
            LEFT_NAMES.add(name.toLowerCase(Locale.US));
        }
        MEMBERS.removeIf(existing -> existing.equalsIgnoreCase(name));
    }

    /** Called once a leap menu's slots have settled - the heads in it are definitely teammates, and the order they
     *  come in is Hypixel's own leap menu order (container slot order), which the Leap Order editor lays its spots
     *  out in. Remembered (and persisted) so the editor's spots start where the real menu puts them. */
    public static void noteTeammates(List<String> namesInSlotOrder) {
        // Normalized here, not at the call sites: the custom leap menu already hands over bare IGNs but the fast-leap
        // path hands over raw head names ("[MVP+] Name"). Un-normalized those would enter MEMBERS as a second,
        // never-matching "teammate", would match nobody in #teammatesInLeapOrder, and - when both paths poll the same
        // open menu - would make the two disagree about the stored order and save the config on every tick.
        List<String> names = namesInSlotOrder.stream().map(PartyTracker::leapIgn).filter(n -> !n.isEmpty()).toList();
        if (names.isEmpty()) {
            return;
        }
        names.forEach(PartyTracker::add);
        LeapMenuConfig cfg = LeapMenuConfig.getInstance();
        if (cfg.setLastLeapOrder(names)) {
            cfg.save();
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

    /** Everyone known to be in your party except you, in Hypixel's listing order. */
    public static List<String> teammates() {
        Minecraft client = Minecraft.getInstance();
        String self = client.player == null ? null : client.player.getGameProfile().name();
        return MEMBERS.stream().filter(n -> self == null || !n.equalsIgnoreCase(self)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
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

    /** What the dungeon tab list / Party Finder said this teammate is, ignoring any manual override (null = nothing yet).
     *  The Leap Order's class wizard pre-fills from this. */
    public static DungeonClass detectedClassOf(String name) {
        return name == null ? null : CLASSES.get(name.toLowerCase(Locale.US));
    }

    /** Your own class: a class pick on your own name when you set one (killer560 11-13: "leaporder keeps ignoring my
     *  custom class selection for myself"), else the dungeon tab list, else null. leaporder-1102 had let the tab list win
     *  because the removed Class Selection Overlay left stale picks; picks now come only from the Leap Order editor. */
    public static DungeonClass selfClass() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return null;
        }
        return classOf(client.player.getGameProfile().name());
    }

    /** The class you play next as Hypixel last announced it (its class menu, a Party Finder group, the dungeon's
     *  pre-start line), or null when none has been seen; kept across restarts. */
    public static DungeonClass selectedClass() {
        return LeapMenuConfig.getInstance().getLastSelectedClass();
    }
}
