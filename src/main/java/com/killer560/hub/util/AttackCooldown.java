package com.killer560.hub.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

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
 * Attack Speed is read from the tab list's Stats widget ("Attack Speed: ⚔45") when he shows it, otherwise summed from
 * the "Attack Speed: +N%" lines of the held item and the armour worn. That sum can only miss sources (pets, potions),
 * never invent them, so when it is wrong it is wrong on the slow, safe side. Capped at Hypixel's normal 100.
 */
public final class AttackCooldown {

    /** Hypixel's normal Attack Speed cap (Thermodynamic armour's 150 is not assumed). */
    public static final int MAX_ATTACK_SPEED = 100;
    private static final Pattern LORE = Pattern.compile("^Attack Speed: \\+(\\d{1,3})%?");
    private static final Pattern TAB = Pattern.compile("^\\s*Attack Speed: \\D{0,2}(\\d{1,3})\\s*$");

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

    /** His Attack Speed stat, 0-100 (see the class doc for where it is read). */
    public static int attackSpeed(Minecraft client) {
        if (client.getConnection() != null) {
            for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
                String line = TabText.plain(info);
                if (line == null) {
                    continue;
                }
                Matcher m = TAB.matcher(line);
                if (m.matches()) {
                    return clamp(Integer.parseInt(m.group(1)));
                }
            }
        }
        int sum = 0;
        if (client.player != null) {
            sum += loreAttackSpeed(client.player.getMainHandItem());
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET}) {
                sum += loreAttackSpeed(client.player.getItemBySlot(slot));
            }
        }
        return clamp(sum);
    }

    /** The "Attack Speed: +N%" line of one item's lore, or 0. */
    public static int loreAttackSpeed(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return 0;
        }
        for (Component line : lore.lines()) {
            Matcher m = LORE.matcher(ChatObserver.stripCodes(line.getString()).trim());
            if (m.find()) {
                return clamp(Integer.parseInt(m.group(1)));
            }
        }
        return 0;
    }

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

    private static int clamp(int as) {
        return Math.max(0, Math.min(MAX_ATTACK_SPEED, as));
    }
}
