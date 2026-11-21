package com.killer560.hub.dungeonqol;

import com.killer560.hub.secrets.DungeonState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Block Ender Pearls In Boss (killer560, 2026-11-10): his own right click with a pearl in hand does nothing while
 *  {@link DungeonState#isBossPhaseActive()} (F7/M7, the only boss phase the mod detects). Called from
 *  {@code mixin/BossPearlMixin} on {@code Minecraft.startUseItem}, the path of real input only: the mod's own pearls
 *  (Walk With Pearls, Auto Gfs, AP3) call {@code MultiPlayerGameMode.useItem} and never pass here; Auto Routes and Auto
 *  Clear holding the use key do, and are let through. Leaps (Spirit Leap, Infinileap) are pearl items too and are never
 *  blocked (killer560, 2026-11-21): only a plain Ender Pearl is. */
public final class BossPearlBlock {

    private BossPearlBlock() {
    }

    public static boolean shouldBlock(Minecraft mc) {
        if (mc.player == null || !DungeonQolConfig.getInstance().isBlockBossPearls() || !DungeonState.isBossPhaseActive()) {
            return false;
        }
        boolean pearl = plainPearl(mc.player.getMainHandItem())
                || mc.player.getMainHandItem().isEmpty() && plainPearl(mc.player.getOffhandItem());
        if (!pearl || com.killer560.hub.autoroutes.RouteExecutor.isRunning()
                || com.killer560.hub.autoclear.AutoClearFeature.isBusy()) {
            return false;
        }
        return true;
    }

    /** Only a regular Ender Pearl: Spirit Leap and Infinileap (and any other Skyblock item on a pearl) are never blocked. */
    private static boolean plainPearl(ItemStack stack) {
        if (!stack.is(Items.ENDER_PEARL)) {
            return false;
        }
        String id = com.killer560.hub.cheatutils.CheatUtils.skyblockId(stack);
        return id == null || "ENDER_PEARL".equals(id);
    }
}
