package com.killer560.hub.dungeonalerts;

import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.SkyblockGate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.HashSet;
import java.util.Set;

/**
 * Rare Drop Alerts (Devonian RareDungeonMobDropAlert; featB-1108): a title and a sound when an Ice Spray Wand or a Skeleton
 * Master Chestplate drops, read off the dropped item's name tag (an armor stand or the item entity, whose custom name is
 * the item's name). The name arrives in the entity data after it spawns, so this looks every 5 ticks, only in a dungeon with
 * the switch on, and each entity alerts once.
 */
final class RareDropAlert {

    static final String KEY = "raredrop";
    private static final Set<Integer> seen = new HashSet<>();
    private static int poll = 0;

    private RareDropAlert() {
    }

    static void tick(Minecraft client) {
        if (!DungeonAlertsConfig.getInstance().rareDropAlert || client.level == null || !DungeonState.isInDungeon()
                || !SkyblockGate.allows() || ++poll < 5) {
            return;
        }
        poll = 0;
        if (seen.size() > 256) {
            seen.clear();
        }
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand || e instanceof ItemEntity) || !e.hasCustomName()) {
                continue;
            }
            String name = ChatFormatting.stripFormatting(e.getName().getString());
            if (("Ice Spray Wand".equals(name) || "Skeleton Master Chestplate".equals(name)) && seen.add(e.getId())) {
                DungeonAlertsFeature.alert(KEY, "§d" + name + " §7dropped!", "Rare drop");
            }
        }
    }

    static void onWorldChange() {
        seen.clear();
    }
}
