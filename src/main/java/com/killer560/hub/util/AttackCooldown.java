package com.killer560.hub.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import org.slf4j.Logger;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How often an automated click may land with full damage: the held weapon's attack cooldown.
 * <p>
 * killer560, 2026-10-15: "limit it to only be able to attack as fast as their attack speed allows them to." On Hypixel
 * a melee hit leaves the mob invulnerable for {@code round(10 / (1 + AS/100))} ticks (AS = the Attack Speed stat, 0-100;
 * a shortbow's shot cooldown is the same with {@code ceil}) - hypixelskyblock.minecraft.wiki/w/Attack_Speed, read
 * 2026-10-15, the same formulas {@code roomsim/SimAttackSpeed} uses. A hit inside that window does nothing, so a click
 * faster than it is wasted and not something a player gains by. Off Hypixel the vanilla cooldown applies: a click
 * before {@code getCurrentItemAttackStrengthDelay()} ticks does reduced damage.
 * <p>
 * Where Attack Speed comes from (atkspeed-1103; killer560, 2026-11-03: "The weapon shouldn't be on cooldown that long I
 * don't think. Have it read attack speed. Most of the time they will be at max attack speed. Don't ignore it but fire it
 * as soon as possible"): the tab list's stats line ("Attack Speed: ⚔45", the Stats widget and the dungeon's Skills and
 * Stats widget; on newer Hypixel the icon is a private-use glyph), the same source SkyHanni's SkyblockStat reads. The
 * last value seen there is remembered for the session, so a tab list without the widget (a dungeon's early tab, a
 * reconnect) keeps it. With no reading at all he is taken to be at the cap ({@link #MAX_ATTACK_SPEED}, 5 ticks): most
 * players are, and on Hypixel the window is the HIT MOB's immunity (the wiki: Attack Speed works "by reducing the damage
 * immunity frames of the entity hit"), so a click 5 ticks after a hit can only be wasted on that same mob when he is
 * slower - the click 5 ticks later still lands no later than a 10-tick wait would have. Guessing slow instead cost every
 * mob that appeared inside the 10 ticks its first-tick hit (nmflake-1103). The old fallback, summing the held item's and
 * armor's "Attack Speed: +N%" lore lines, could only read low (it misses pets - Golden Dragon +50, Griffin +25 - and
 * anything not worn), so it is gone.
 */
public final class AttackCooldown {

    /** Hypixel's normal Attack Speed cap (Thermodynamic armor's 150 is not assumed). */
    public static final int MAX_ATTACK_SPEED = 100;
    private static final Logger LOGGER = ModLog.get("killer560smod-attackcooldown");
    /** " Attack Speed: ⚔45", "Attack Speed: 100", a private-use icon glyph, "45.5"; at most two icon characters. */
    private static final Pattern TAB = Pattern.compile(
            "^\\s*Attack Speed: [^\\d\\s]{0,2}\\s?(\\d{1,3})(?:\\.\\d{1,2})?%?(?:\\s.{0,24})?$");
    /** The last tab reading, or -1; cleared with the server (a new login can have another profile). */
    private static int remembered = -1;
    private static Object rememberedFor;
    private static String lastSource = "none";
    private static int lastLogged = Integer.MIN_VALUE;

    private AttackCooldown() {
    }

    /** Ticks that must pass between two clicks for each to do full damage with what he holds now. At least 1. */
    public static int intervalTicks(Minecraft client) {
        if (client.player == null) {
            return 10;
        }
        if (onHypixel(client)) {
            return hypixelTicks(attackSpeed(client), isShortbow(client.player.getMainHandItem()));
        }
        return Math.max(1, (int) Math.ceil(client.player.getCurrentItemAttackStrengthDelay() - 1e-4));
    }

    /** Hypixel: {@code round(10 / (1 + AS/100))} for a melee hit, {@code ceil} for a shortbow's shot. */
    public static int hypixelTicks(int attackSpeed, boolean shortbow) {
        double t = 10.0 / (1.0 + Math.max(0, Math.min(MAX_ATTACK_SPEED, attackSpeed)) / 100.0);
        return Math.max(1, (int) (shortbow ? Math.ceil(t - 1e-9) : Math.round(t)));
    }

    public static boolean onHypixel(Minecraft client) {
        var server = client.getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel.net");
    }

    /** His Attack Speed stat, 0-100: the tab list now, else the last tab reading, else the cap (see the class doc). */
    public static int attackSpeed(Minecraft client) {
        Object conn = client.getConnection();
        if (conn != rememberedFor) {
            rememberedFor = conn;
            remembered = -1;
        }
        int live = -1;
        if (client.getConnection() != null) {
            for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
                int v = parseTabLine(TabText.plain(info));
                if (v >= 0) {
                    live = v;
                    break;
                }
            }
        }
        int as;
        String source;
        if (live >= 0) {
            remembered = live;
            as = live;
            source = "tab list";
        } else if (remembered >= 0) {
            as = remembered;
            source = "last tab reading";
        } else {
            as = MAX_ATTACK_SPEED;
            source = "not shown, taken as the cap";
        }
        lastSource = source;
        if (as != lastLogged) {
            lastLogged = as;
            LOGGER.info("[AttackCooldown] Attack Speed {} ({}): {} ticks between hits", as, source, hypixelTicks(as, false));
        }
        return as;
    }

    /** One tab line's Attack Speed, 0-100, or -1 when the line is not the stat. */
    public static int parseTabLine(String line) {
        if (line == null) {
            return -1;
        }
        Matcher m = TAB.matcher(ChatObserver.stripCodes(line));
        if (!m.matches()) {
            return -1;
        }
        try {
            return clamp(Integer.parseInt(m.group(1)));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Where the last {@link #attackSpeed} came from (testkit, logging). */
    public static String attackSpeedSource() { return lastSource; }

    /** A Hypixel shortbow says so in its lore ("Shortbow: Instantly shoots!"). */
    public static boolean isShortbow(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return false;
        }
        for (Component line : lore.lines()) {
            if (ChatObserver.stripCodes(line.getString()).trim().startsWith("Shortbow: ")) {
                return true;
            }
        }
        return false;
    }

    private static int clamp(int as) { return Math.max(0, Math.min(MAX_ATTACK_SPEED, as)); }
}
