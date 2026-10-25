package com.killer560.hub.dungeonalerts;

import com.killer560.hub.secrets.DungeonState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.HashSet;
import java.util.Set;

/**
 * Key Spawn Alert (Odin {@code DoorHighlight.kt}'s "Wither Key spawned!", from the 2026-10-18 review): a title and a
 * sound when a Wither Key or Blood Key armor stand appears in the clear. The stand is recognized the way Door Keys
 * recognizes it - an armor stand whose custom name is exactly "Wither Key" / "Blood Key" - and each stand alerts once.
 * The name comes in the entity's data after it spawns, so this looks every few ticks instead of on the spawn event.
 */
public final class KeySpawnAlert {

    private static final int POLL_TICKS = 4;
    private static final Set<Integer> seen = new HashSet<>();
    private static int poll = 0;
    private static int alertsFired = 0;
    private static String lastAlertTitle = null;

    private KeySpawnAlert() {
    }

    static void tick(Minecraft client) {
        if (!DungeonAlertsConfig.getInstance().isKeySpawnAlert() || client.level == null || !DungeonState.isInDungeon()
                || DungeonState.isBossPhaseActive() || !com.killer560.hub.util.SkyblockGate.allows()) {
            return;
        }
        if (++poll < POLL_TICKS) {
            return;
        }
        poll = 0;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand) || !entity.hasCustomName()) {
                continue;
            }
            String name = ChatFormatting.stripFormatting(entity.getName().getString());
            boolean wither = "Wither Key".equals(name);
            if ((wither || "Blood Key".equals(name)) && seen.add(entity.getId())) {
                alert(wither);
            }
        }
    }

    private static void alert(boolean wither) {
        String title = wither ? "§8Wither Key §7spawned!" : "§cBlood Key §7spawned!";
        lastAlertTitle = title;
        alertsFired++;
        DungeonAlertsFeature.showTitle(Component.literal(title), Component.empty(), 0, 30, 5);
        DungeonAlertsFeature.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.5f);
    }

    static void onWorldChange() {
        seen.clear();
    }

    /** For the testkit: alerts shown since the client started. */
    public static int alertsFired() {
        return alertsFired;
    }

    /** For the testkit: the last alert's title. */
    public static String lastAlertTitle() {
        return lastAlertTitle;
    }
}
