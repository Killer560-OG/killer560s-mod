package com.killer560.hub.dungeonqol;

import com.killer560.hub.secrets.DungeonState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;

/** Block Ender Pearls In Boss (killer560, 2026-11-10): his own right click with a pearl in hand does nothing while
 *  {@link DungeonState#isBossPhaseActive()} (F7/M7, the only boss phase the mod detects). Called from
 *  {@code mixin/BossPearlMixin} on {@code Minecraft.startUseItem}, the path of real input only: the mod's own pearls
 *  (Walk With Pearls, Auto Gfs, AP3) call {@code MultiPlayerGameMode.useItem} and never pass here; Auto Routes and Auto
 *  Clear holding the use key do, and are let through. */
public final class BossPearlBlock {

    private BossPearlBlock() {
    }

    public static boolean shouldBlock(Minecraft mc) {
        if (mc.player == null || !DungeonQolConfig.getInstance().isBlockBossPearls() || !DungeonState.isBossPhaseActive()) {
            return false;
        }
        boolean pearl = mc.player.getMainHandItem().is(Items.ENDER_PEARL)
                || mc.player.getMainHandItem().isEmpty() && mc.player.getOffhandItem().is(Items.ENDER_PEARL);
        if (!pearl || com.killer560.hub.autoroutes.RouteExecutor.isRunning()
                || com.killer560.hub.autoclear.AutoClearFeature.isBusy()) {
            return false;
        }
        return true;
    }
}
