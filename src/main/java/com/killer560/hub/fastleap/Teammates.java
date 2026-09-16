package com.killer560.hub.fastleap;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeon teammates (name, class, dead) from the tab list - QUOI {@code Dungeon.dungeonTeammates} equivalent. Uses the
 * same NoammAddons tab regex as {@link com.killer560.hub.dungeonalerts.ClassColors} ("[lvl] [rank] Name ... (Class
 * XL)"); a "(DEAD)" entry keeps the previously known class and marks the player dead, so class/name leaps skip
 * them like QUOI's {@code !it.isDead} filter. Polled every 10 client ticks while in a dungeon; cleared on world change.
 */
public final class Teammates {

    private static final Pattern TAB_REGEX = Pattern.compile("^\\[\\d+] (?:\\[[^]]+] )*([A-Za-z0-9_]{1,16}) .*\\((\\w+)(?: (\\w+))?\\)$");

    public record Teammate(String name, DungeonClass clazz, boolean dead) {
    }

    private static final Map<String, Teammate> TEAMMATES = new LinkedHashMap<>();
    /** Class level per name (for QUOI's mage cooldown multiplier); kept across a "(DEAD)" entry. */
    private static final Map<String, Integer> LEVELS = new LinkedHashMap<>();
    private static int tickCounter = 0;

    private Teammates() {
    }

    static void tick(Minecraft client) {
        if (++tickCounter < 10) {
            return;
        }
        tickCounter = 0;
        if (client.getConnection() == null || !DungeonState.isInDungeon()) {
            return;
        }
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
            String clazzText = m.group(2);
            Teammate old = TEAMMATES.get(name);
            if ("DEAD".equalsIgnoreCase(clazzText)) {
                TEAMMATES.put(name, new Teammate(name, old == null ? null : old.clazz(), true));
            } else if ("EMPTY".equalsIgnoreCase(clazzText)) {
                TEAMMATES.remove(name);
                LEVELS.remove(name);
            } else {
                DungeonClass parsed = "Berserk".equalsIgnoreCase(clazzText) ? DungeonClass.BERSERKER : DungeonClass.byName(clazzText);
                TEAMMATES.put(name, new Teammate(name, parsed, false));
                int level = parseLevel(m.group(3));
                if (level > 0) {
                    LEVELS.put(name, level);
                }
            }
        }
    }

    static void clear() {
        TEAMMATES.clear();
        LEVELS.clear();
        tickCounter = 0;
    }

    /** QUOI {@code Dungeon.getMageCooldownMultiplier()}: 1.0 unless you're a Mage, else
     *  {@code 1 - 0.25 - floor(level / 2) / 100 * (only mage ? 2 : 1)}. Unknown class/level = the non-mage 1.0. */
    public static double mageCooldownMultiplier() {
        String self = selfName();
        Teammate me = null;
        for (Teammate t : TEAMMATES.values()) {
            if (t.name().equalsIgnoreCase(self)) {
                me = t;
                break;
            }
        }
        if (me == null || me.clazz() != DungeonClass.MAGE) {
            return 1.0;
        }
        int level = LEVELS.getOrDefault(me.name(), 0);
        int mages = 0;
        for (Teammate t : TEAMMATES.values()) {
            if (t.clazz() == DungeonClass.MAGE) {
                mages++;
            }
        }
        return 1.0 - 0.25 - (Math.floor(level / 2.0) / 100.0) * (mages == 1 ? 2 : 1);
    }

    /** Roman ("XLII") or decimal class level from the tab list, else 0. */
    private static int parseLevel(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        int total = 0;
        int prev = 0;
        String upper = text.toUpperCase(Locale.ROOT);
        for (int i = upper.length() - 1; i >= 0; i--) {
            int v = switch (upper.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> -1;
            };
            if (v < 0) {
                return 0;
            }
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return Math.max(0, total);
    }

    public static String selfName() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? "" : ChatObserver.strip(client.player.getName().getString());
    }

    /** All known teammates except yourself. */
    public static List<Teammate> noSelf() {
        String self = selfName();
        List<Teammate> out = new ArrayList<>();
        for (Teammate t : TEAMMATES.values()) {
            if (!t.name().equalsIgnoreCase(self)) {
                out.add(t);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static boolean isKnown() {
        return !TEAMMATES.isEmpty();
    }

    /** @return the teammate (not yourself) with that exact name, ignoring case, or null. */
    public static Teammate byName(String name) {
        if (name == null) {
            return null;
        }
        String lower = ChatObserver.strip(name).toLowerCase(Locale.ROOT);
        for (Teammate t : noSelf()) {
            if (t.name().toLowerCase(Locale.ROOT).equals(lower)) {
                return t;
            }
        }
        return null;
    }

    /** QUOI {@code dungeonTeammatesNoSelf.firstOrNull { !it.isDead && it.clazz == clazz }}. */
    public static Teammate firstAliveOfClass(DungeonClass clazz) {
        if (clazz == null) {
            return null;
        }
        for (Teammate t : noSelf()) {
            if (!t.dead() && t.clazz() == clazz) {
                return t;
            }
        }
        return null;
    }
}
