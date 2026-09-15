package com.killer560.hub.routes;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only Skyblock area detection from the tab list's "Area: X" / "Dungeon: X" line (same
 *  {@code getListedOnlinePlayers().getTabListDisplayName()} read DungeonInfoFeature already uses).
 *  Refreshed once a second. Unknown area uses the {@link #GLOBAL} key. */
public final class SkyblockArea {

    public static final String GLOBAL = "*";
    private static final Pattern AREA_PATTERN = Pattern.compile("^(?:Area|Dungeon):\\s*(.+)$");

    private static String current = "";
    private static int ticks = 0;

    private SkyblockArea() {
    }

    public static void tick(Minecraft client) {
        if (client.level == null || client.getConnection() == null) {
            current = "";
            return;
        }
        if (ticks++ % 20 == 0) {
            current = read(client);
        }
    }

    /** Area name, or "" when unknown. */
    public static String current() {
        return current;
    }

    /** Key for the per-area active-route map. */
    public static String key() {
        return current.isEmpty() ? GLOBAL : current;
    }

    public static String label(String key) {
        return GLOBAL.equals(key) ? "Any Area" : key;
    }

    private static String read(Minecraft client) {
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String plain = ChatFormatting.stripFormatting(display.getString());
            if (plain == null) {
                continue;
            }
            Matcher m = AREA_PATTERN.matcher(plain.trim());
            if (m.matches()) {
                return m.group(1).trim();
            }
        }
        return "";
    }
}
