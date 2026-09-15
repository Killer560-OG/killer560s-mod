package com.killer560.hub.cheatutils;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Ult - uses the class Ultimate on specific F7/M7 boss lines, ported from NoammAddons 26.1.2
 * {@code dungeon/Abilities.kt} (CHEAT block) + {@code PlayerUtils.useDungeonClassAbility(ult = true)}:
 * <ul>
 * <li>Triggers (exact plain-text match, floor 7, Healer/Tank only - Noamm's {@code ultMessages}):
 * "⚠ Maxor is enraged! ⚠" and "[BOSS] Goldor: You have done it, you destroyed the factory…".
 * (Noamm's F5 Livid / F6 Sadan triggers are out of scope - F7/M7 boss only per task.)
 * <li>Action: {@code ServerboundPlayerActionPacket(DROP_ITEM, BlockPos.ZERO, Direction.DOWN)} - the Drop (Q)
 * key. Confirmed against Hypixel's own text: "... is ready to use! Press DROP to activate it!" (QUOI
 * ChatReplacements) and Mort "Left-click (or Drop) to use your Ultimate!" (Odin/QUOI SplitsManager).
 * <li>Own class: tab list, Odin {@code DungeonUtils.tablistRegex} ({@code [lvl] name ... (Class L)}), or a
 * manual override. Each trigger fires at most once per world.
 * </ul>
 */
public final class AutoUltFeature {

    private static final String MAXOR_ENRAGED = "⚠ Maxor is enraged! ⚠";
    private static final String GOLDOR_FACTORY = "[BOSS] Goldor: You have done it, you destroyed the factory…";
    private static final Set<DungeonClass> ULT_CLASSES = EnumSet.of(DungeonClass.HEALER, DungeonClass.TANK);
    private static final Pattern TABLIST = Pattern.compile("^\\[(\\d+)] (?:\\[\\w+] )*(\\w+) .*?\\((\\w+)(?: (\\w+))*\\)$");

    private static final Set<String> firedThisWorld = new HashSet<>();
    private static Object lastLevel = null;
    private static DungeonClass tabClass = null;
    private static int classScanCooldown = 0;

    private AutoUltFeature() {
    }

    static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            firedThisWorld.clear();
            tabClass = null;
        }
        if (!CheatUtilsConfig.getInstance().isAutoUltEnabled() || !DungeonState.isInDungeon()) {
            return;
        }
        if (--classScanCooldown <= 0) {
            classScanCooldown = 40;
            DungeonClass found = scanTabClass(client);
            if (found != null && found != tabClass) {
                CheatUtils.LOGGER.info("[CheatUtils] AutoUlt tab-list class detected: {} (was {})", found, tabClass);
                tabClass = found;
            }
        }
    }

    static void onChat(String plain) {
        CheatUtilsConfig cfg = CheatUtilsConfig.getInstance();
        if (!cfg.isAutoUltEnabled()) {
            return;
        }
        String trigger;
        if (MAXOR_ENRAGED.equals(plain.trim()) && cfg.isUltMaxorEnraged()) {
            trigger = "Maxor enraged";
        } else if (GOLDOR_FACTORY.equals(plain.trim()) && cfg.isUltGoldorFactory()) {
            trigger = "Goldor factory destroyed";
        } else {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !CheatUtils.isOnDungeonServer(client)) {
            return;
        }
        if (!DungeonState.isF7OrM7() || !LiveMapFeature.isInBoss()) {
            CheatUtils.LOGGER.info("[CheatUtils] AutoUlt '{}' seen but not in F7/M7 boss (floor={}) - skipped",
                    trigger, DungeonState.getFloor());
            return;
        }
        DungeonClass clazz = effectiveClass(cfg);
        if (clazz == null || !ULT_CLASSES.contains(clazz)) {
            CheatUtils.LOGGER.info("[CheatUtils] AutoUlt '{}' skipped: class={} (needs Healer/Tank)", trigger, clazz);
            return;
        }
        if (!firedThisWorld.add(trigger)) {
            return;
        }
        client.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.DROP_ITEM, BlockPos.ZERO, Direction.DOWN));
        CheatUtils.LOGGER.info("[CheatUtils] AutoUlt used Ultimate on '{}' as {}", trigger, clazz);
        ModChat.send(CheatUtils.CHAT_TAG, ModChat.text("Used Ultimate "), ModChat.dim("(" + trigger + ")"));
    }

    private static DungeonClass effectiveClass(CheatUtilsConfig cfg) {
        String override = cfg.getUltClassOverride();
        if (override != null && !"AUTO".equalsIgnoreCase(override)) {
            return DungeonClass.byName(override);
        }
        if (tabClass == null) {
            tabClass = scanTabClass(Minecraft.getInstance());
        }
        return tabClass;
    }

    private static DungeonClass scanTabClass(Minecraft client) {
        if (client.player == null || client.getConnection() == null) {
            return null;
        }
        String self = client.player.getGameProfile().name();
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String plain = ChatFormatting.stripFormatting(display.getString());
            if (plain == null) {
                continue;
            }
            Matcher m = TABLIST.matcher(plain.trim());
            if (m.find() && m.group(2).equals(self)) {
                String name = m.group(3);
                return "Berserk".equalsIgnoreCase(name) ? DungeonClass.BERSERKER : DungeonClass.byName(name);
            }
        }
        return null;
    }
}
