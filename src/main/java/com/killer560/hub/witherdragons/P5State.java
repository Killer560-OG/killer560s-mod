package com.killer560.hub.witherdragons;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.secrets.DungeonState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared M7 Phase 5 state for the Wither Dragons / King Relics features.
 * <ul>
 * <li>P5 detection = Odin {@code DungeonUtils.getF7Phase() == M7Phases.P5}: floor 7 + in boss + player y &lt;= 45
 * (https://github.com/odtheking/Odin/blob/main/src/main/kotlin/com/odtheking/odin/utils/skyblock/dungeon/DungeonUtils.kt).
 * "In boss" reuses {@link DungeonState#isBossPhaseActive()} (Maxor's line / {@code /killer560 sim}); as a
 * fallback for a late join (Maxor's line never seen) F7/M7 + x,z &gt;= 0 also counts - the dungeon room grid is
 * entirely at negative x/z (origin -185,-185), the boss arena at positive. On p3sim.net the y &lt;= 45 check
 * alone is used, since its sidebar/chat format isn't confirmed.
 * <li>Blessings = Odin {@code DungeonListener}'s {@code ClientboundTabListPacket} footer parse with
 * {@code Blessing.POWER/TIME} regexes (DungeonEnums.kt) - fed by {@code WitherDragonsPacketMixin}.
 * </ul>
 */
public final class P5State {

    // Odin DungeonEnums.kt Blessing.POWER / Blessing.TIME
    private static final Pattern POWER = Pattern.compile("Blessing of Power (X{0,3}(IX|IV|V?I{0,3}))");
    private static final Pattern TIME = Pattern.compile("Blessing of Time (V)");

    private static int powerBlessing = 0;
    private static int timeBlessing = 0;

    private P5State() {
    }

    public static boolean inP5() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return false;
        }
        if (client.player.getY() > 45) {
            return false;
        }
        if (DungeonState.isBossPhaseActive() || isP3Sim(client)) {
            return true;
        }
        return DungeonState.isF7OrM7() && client.player.getX() >= 0 && client.player.getZ() >= 0;
    }

    public static boolean isP3Sim(Minecraft client) {
        ServerData server = client.getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("p3sim");
    }

    /** From the tab footer (only parsed in a dungeon, like Odin). */
    static void onTabFooter(Component footer) {
        if (footer == null || !DungeonState.isInDungeon()) {
            return;
        }
        String text = ChatFormatting.stripFormatting(footer.getString());
        if (text == null) {
            return;
        }
        Matcher m = POWER.matcher(text);
        if (m.find()) {
            powerBlessing = romanToInt(m.group(1));
        }
        m = TIME.matcher(text);
        if (m.find()) {
            timeBlessing = romanToInt(m.group(1));
        }
    }

    static void reset() {
        powerBlessing = 0;
        timeBlessing = 0;
    }

    public static int powerBlessing() {
        return powerBlessing;
    }

    public static int timeBlessing() {
        return timeBlessing;
    }

    /** Your class: the config override if set, else Hypixel's dungeon tab list (via {@link PartyTracker}). */
    public static DungeonClass selfClass() {
        DungeonClass override = WitherDragonsConfig.getInstance().getClassOverride().clazz;
        if (override != null) {
            return override;
        }
        return PartyTracker.selfClass();
    }

    static int romanToInt(String roman) {
        if (roman == null || roman.isEmpty()) {
            return 0;
        }
        int total = 0;
        int prev = 0;
        for (int i = roman.length() - 1; i >= 0; i--) {
            int v = switch (roman.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                default -> 0;
            };
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return Math.max(0, total);
    }
}
