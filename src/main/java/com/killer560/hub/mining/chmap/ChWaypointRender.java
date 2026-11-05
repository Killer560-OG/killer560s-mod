package com.killer560.hub.mining.chmap;

import com.killer560.hub.islands.IslandRender;
import com.killer560.hub.mining.MiningIsland;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/** The player's own Crystal Hollows waypoints in the world (name and color from the map tab), and {@code /chmap}, which
 *  opens the map screen now that the tab has no Open Map button. */
public final class ChWaypointRender {

    private static final int AMBER = 0xFFCC6600;

    private ChWaypointRender() {
    }

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FeatureGuard.afterFeatures(
                FeatureBits.register("Crystal Hollows waypoints", () -> CrystalHollowsMapConfig.getInstance().isEnabled()),
                ChWaypointRender::render));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("chmap").executes(ctx -> {
                    if (!CrystalHollowsMapConfig.getInstance().isEnabled()) {
                        ModChat.send("Crystal Hollows Map", ModChat.text("Switch the Crystal Hollows Map on in the menu first."));
                        return 0;
                    }
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    mc.execute(() -> com.killer560.hub.compat.McCompat.setScreen(mc, new CrystalHollowsMapScreen()));
                    return 1;
                })));
    }

    private static void render(LevelRenderContext context) {
        if (!MiningIsland.crystalHollows()) {
            return;
        }
        for (CrystalHollowsWaypoint w : CrystalHollowsMapConfig.getInstance().getWaypoints()) {
            IslandRender.label(context, w.x, w.y + 1.6, w.z, w.name, w.drawColor(AMBER), true);
        }
    }
}
