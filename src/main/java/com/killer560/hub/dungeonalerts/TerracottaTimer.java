package com.killer560.hub.dungeonalerts;

import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Terracotta Timer (F6/M6 boss) - ported from Odin {@code features/impl/boss/TerracottaTimer.kt}, cross-checked
 * with NoammAddons (26.1.2 upstream) {@code features/impl/dungeon/TerracottaTimer.kt}. Both agree: when a block
 * changes into a {@link FlowerPotBlock} during the F6/M6 boss fight, a terracotta will respawn there in 15 s
 * (F6) / 12 s (M6) (Noamm: 300 / 240 server ticks). Odin's text: "§a" &gt; 5 s, "§6" &gt; 2 s, else "§c",
 * value to 2 decimals + "s", scale 2 at the block center. Noamm's extra: clear everything 10 ticks after
 * "[BOSS] Sadan: ENOUGH!".
 * <p>
 * Deviation: both references count down on SERVER ticks; this mod has no server-tick source, so this counts
 * client ticks (identical on a healthy connection, drifts under server lag).
 */
final class TerracottaTimer {

    private static final class Terracotta {
        final BlockPos pos;
        int ticksLeft;

        Terracotta(BlockPos pos, int ticksLeft) {
            this.pos = pos;
            this.ticksLeft = ticksLeft;
        }
    }

    private static final List<Terracotta> SPAWNS = new ArrayList<>();
    private static int clearInTicks = -1;

    private TerracottaTimer() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null) {
                return;
            }
            SPAWNS.removeIf(t -> --t.ticksLeft <= 0);
            if (clearInTicks >= 0 && clearInTicks-- == 0) {
                DungeonAlertsFeature.LOGGER.info("[DungeonAlerts] Terracotta timers cleared (Sadan ENOUGH! +10t)");
                SPAWNS.clear();
            }
        });
        ChatObserver.subscribe(TerracottaTimer::onChat);
    }

    private static boolean active() {
        return DungeonAlertsConfig.getInstance().terracottaEnabled && com.killer560.hub.util.SkyblockGate.allows() && DungeonAlertsFeature.floorNumber() == 6
                && DungeonAlertsFeature.inBoss();
    }

    private static void onChat(Component message) {
        if (active() && "[BOSS] Sadan: ENOUGH!".equals(ChatObserver.strip(message).trim())) {
            clearInTicks = 10;
        }
    }

    /** Called on the main thread for every server block change (single + section updates). */
    static void onBlockChange(BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof FlowerPotBlock) || !active()) {
            return;
        }
        for (Terracotta t : SPAWNS) {
            if (t.pos.equals(pos)) {
                return;
            }
        }
        int ticks = DungeonAlertsFeature.isMasterMode() ? 240 : 300;
        SPAWNS.add(new Terracotta(pos.immutable(), ticks));
        DungeonAlertsFeature.LOGGER.info("[DungeonAlerts] Terracotta died at {} - respawn in {} ticks", pos.toShortString(), ticks);
    }

    static void onWorldChange() {
        SPAWNS.clear();
        clearInTicks = -1;
    }

    static void onWorldRender(LevelRenderContext context) {
        if (SPAWNS.isEmpty() || !active()) {
            return;
        }
        for (Terracotta t : SPAWNS) {
            float seconds = t.ticksLeft / 20f;
            char color = seconds > 5f ? 'a' : seconds > 2f ? '6' : 'c';
            Vec3 c = t.pos.getCenter();
            DungeonAlertsFeature.renderWorldText(context, "§" + color + String.format(Locale.US, "%.2f", seconds) + "s",
                    c.x, c.y, c.z, 2f);
        }
    }
}
