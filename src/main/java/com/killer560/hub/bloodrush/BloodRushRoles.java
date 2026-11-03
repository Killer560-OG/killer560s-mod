package com.killer560.hub.bloodrush;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blood rush roles, with the reference mod adm's conventions (facts from its BrRoles; our own code) so a party mixing
 * the two mods numbers its roles the same way (killer560, 2026-11-03: "I just don't want someone on their mod to be player
 * 1 and ours 2 and they go for the same mobs").
 *
 * <p><b>Who kills.</b> Killers 2 to 4 (the door runner not counted) take roles 1..N; the door runner kills nothing, unless
 * Door Kills has him kill: then he is one more role, the last. Door Kills After Fairy has him kill only in rooms the rush
 * reaches after fairy, and with Drop Bottom After Fairy the highest numbered killer leaves the rush there to clear.
 * Killers "Just Me" (1) is roles off: every spot is his.
 *
 * <p><b>Master mode.</b> In an M floor his role comes from his class: 1 Archer, 2 Mage, 3 Berserk, 4 Tank, the Healer on
 * the door - when that role fits the killer count. Party chat claims override it.
 *
 * <p><b>Party chat</b>, read from PARTY chat only:
 * <ul>
 *   <li>{@code !3br 2} - "3 of us are killing, I am role 2" (killers 2 to 4, role 1 to 4).</li>
 *   <li>{@code !br 2} - role 2 at the killer count already set (an earlier line's, else our Killers setting).</li>
 *   <li>{@code !br d} / {@code !3br d} - "I am on the door".</li>
 * </ul>
 * Anyone in the party may claim. A line with a DIFFERENT count is a new sync: every role taken before it is forgotten. A
 * role above the killer count is ignored. Claims last until the dungeon they were made for is over (the first world change
 * after a dungeon), so a claim made in the hub before warping in still counts. Anchored on Hypixel's own
 * {@code Party > [RANK] name: } line with the name {@code [A-Za-z0-9_]{1,16}}, so a line in any other channel (which
 * starts with that channel's own prefix) never matches. Nothing is ever sent.
 */
public final class BloodRushRoles {

    private static final Logger LOGGER = ModLog.get("killer560smod-bloodrush");
    /** "Party > [MVP+] Name: !3br 2", "Party > Name: !br d". The rank bracket is absent for a player without one. */
    static final Pattern CLAIM = Pattern.compile("^Party > (?:\\[[A-Z+]{1,10}\\] )?([A-Za-z0-9_]{1,16}): !([2-4])?br ([1-4]|d)$");

    private static Integer chatCount;
    /** Who claimed which role, in the order they said it (lower-case name -> {display name, role}). */
    private static final Map<String, Object[]> TAKEN = new LinkedHashMap<>();
    private static String door;
    private static boolean seen;
    /** Test hook: stands in for the tab list's class and the floor (null = read the game). */
    private static DungeonClass testClass;
    private static Boolean testMaster;

    private BloodRushRoles() {
    }

    /** A plain (codes stripped) chat line. True if it was a claim. */
    static boolean onChat(String line, Minecraft client) {
        if (line == null || !line.startsWith("Party > ")) {
            return false;
        }
        Matcher m = CLAIM.matcher(line);
        if (!m.matches()) {
            return false;
        }
        String name = m.group(1);
        String key = name.toLowerCase(Locale.ROOT);
        Integer n = m.group(2) == null ? null : Integer.parseInt(m.group(2));
        String what = m.group(3);
        seen = true;
        if (n != null && !n.equals(chatCount)) {
            TAKEN.clear();
            door = null;
        }
        if (n != null) {
            chatCount = n;
        }
        if (what.equals("d")) {
            door = name;
            TAKEN.remove(key);
            LOGGER.info("[BloodRush] role claim: {} is on the door ({} killing)", name, chatCount);
            return true;
        }
        int killing = n != null ? n : chatCount != null ? chatCount : Math.max(2, BloodRushConfig.getInstance().getKillers());
        int role = Integer.parseInt(what);
        if (role > killing) {
            return true;
        }
        chatCount = killing;
        TAKEN.remove(key);
        TAKEN.put(key, new Object[]{name, role});
        if (door != null && door.equalsIgnoreCase(name)) {
            door = null;
        }
        LOGGER.info("[BloodRush] role claim: {} is role {} of {}", name, role, killing);
        return true;
    }

    /** A new world: claims go once the dungeon they were for is over. */
    static void newWorld(boolean dungeonOver) {
        if (dungeonOver) {
            reset();
        }
    }

    static void reset() {
        chatCount = null;
        TAKEN.clear();
        door = null;
        seen = false;
    }

    private static String me() {
        Minecraft c = Minecraft.getInstance();
        return c.player == null ? null : c.player.getGameProfile().name();
    }

    static boolean master() {
        if (testMaster != null) {
            return testMaster;
        }
        String f = com.killer560.hub.secrets.DungeonState.getFloor();
        return f != null && f.startsWith("M");
    }

    /** His role before party chat: in master mode his class's (0 = the door), else the setting. Null = roles off. */
    private static Integer baseRole(BloodRushConfig cfg) {
        if (cfg.getKillers() < 2) {
            return null;
        }
        if (master()) {
            DungeonClass c = testClass != null ? testClass : com.killer560.hub.leapmenu.PartyTracker.selfClass();
            if (c != null) {
                return switch (c) {
                    case ARCHER -> 1;
                    case MAGE -> 2;
                    case BERSERKER -> 3;
                    case TANK -> 4;
                    case HEALER -> 0;
                };
            }
        }
        return Math.min(cfg.getRole(), cfg.getKillers());
    }

    private static Integer chatMine() {
        String me = me();
        Object[] v = me == null ? null : TAKEN.get(me.toLowerCase(Locale.ROOT));
        return v == null ? null : (Integer) v[1];
    }

    /** How many kill: party chat's, else the setting; 0 is roles off (Just Me, or a class role past the count). */
    static int count(BloodRushConfig cfg) {
        if (chatCount != null) {
            return chatCount;
        }
        Integer b = baseRole(cfg);
        return b == null || b > cfg.getKillers() ? 0 : cfg.getKillers();
    }

    /** Whether he is on the door: claimed in chat, or his base role and chat gave him no role. */
    static boolean onDoor(BloodRushConfig cfg) {
        String me = me();
        if (door != null) {
            return door.equalsIgnoreCase(me);
        }
        Integer b = baseRole(cfg);
        return b != null && b == 0 && chatMine() == null;
    }

    /** His killing role (1..count) before Door Kills and fairy: chat's claim, else his base role; null on the door. */
    static Integer mine(BloodRushConfig cfg) {
        if (onDoor(cfg)) {
            return null;
        }
        Integer c = chatMine();
        if (c != null) {
            return c;
        }
        Integer b = baseRole(cfg);
        int n = count(cfg);
        return b != null && b >= 1 && b <= n ? b : null;
    }

    static boolean doorerKills(BloodRushConfig cfg, boolean late) {
        return cfg.getDoorKills() == BloodRushConfig.DoorKills.ALWAYS
                || (cfg.getDoorKills() == BloodRushConfig.DoorKills.AFTER_FAIRY && late);
    }

    /** Whether the highest numbered killer has left the rush, in a room past fairy. */
    static boolean dropped(BloodRushConfig cfg, boolean late) {
        return late && cfg.getDoorKills() == BloodRushConfig.DoorKills.AFTER_FAIRY && cfg.isDropBottom() && count(cfg) > 0;
    }

    static boolean youDropped(BloodRushConfig cfg, boolean late) {
        Integer m = mine(cfg);
        return dropped(cfg, late) && m != null && m == count(cfg);
    }

    /** How many the room's split is for: those killing, less the one who dropped, plus the door runner when he kills. */
    static int planCount(BloodRushConfig cfg, boolean late) {
        int n = count(cfg);
        if (n == 0) {
            return 0;
        }
        return n - (dropped(cfg, late) ? 1 : 0) + (doorerKills(cfg, late) ? 1 : 0);
    }

    /** The role whose spots are his in a room: his own, or the door runner's (the last) when he kills; null for none. */
    static Integer playing(BloodRushConfig cfg, boolean late) {
        if (youDropped(cfg, late)) {
            return null;
        }
        Integer m = mine(cfg);
        if (m != null) {
            return m;
        }
        int p = planCount(cfg, late);
        return doorerKills(cfg, late) && onDoor(cfg) && p > 0 ? p : null;
    }

    /** Whether anyone in the party has claimed a role this run (adm's party chat). */
    public static boolean admInParty() {
        return seen;
    }

    /** The name that claimed {@code role}, or null. */
    static String holder(int role) {
        for (Object[] v : TAKEN.values()) {
            if ((Integer) v[1] == role) {
                return (String) v[0];
            }
        }
        return null;
    }

    /** "1 Bob, 2 you, door Jim (2 killing)" for the HUD line; empty without claims. */
    public static String summary() {
        if (!seen) {
            return "";
        }
        String me = me();
        List<String> parts = new ArrayList<>();
        for (Object[] v : TAKEN.values()) {
            String n = (String) v[0];
            parts.add(v[1] + " " + (n.equalsIgnoreCase(me) ? "you" : n));
        }
        if (door != null) {
            parts.add("door " + (door.equalsIgnoreCase(me) ? "you" : door));
        }
        return String.join(", ", parts) + (chatCount != null ? " (" + chatCount + " killing)" : "");
    }

    // ---- test hooks ----

    /** "count=N mine=R door=b playing=P seen=b summary=..." as the run reads it now (a room before fairy). */
    public static String stateForTest() {
        BloodRushConfig cfg = BloodRushConfig.getInstance();
        return "count=" + count(cfg) + " mine=" + mine(cfg) + " door=" + onDoor(cfg) + " playing=" + playing(cfg, false)
                + " seen=" + seen + " summary=" + summary();
    }

    /** Stands in for his class and the floor (pass null, null to read the game again). */
    public static void testClassAndFloor(String cls, Boolean master) {
        testClass = cls == null ? null : DungeonClass.valueOf(cls);
        testMaster = master;
    }

    public static void resetForTest() {
        reset();
        testClass = null;
        testMaster = null;
    }
}
