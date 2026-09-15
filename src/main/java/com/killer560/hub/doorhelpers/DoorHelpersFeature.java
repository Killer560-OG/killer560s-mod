package com.killer560.hub.doorhelpers;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Door Helpers (cheat build only): {@link AutoDoorOpenerFeature} (QUOI {@code AutoDoorOpener.kt}) and
 * {@link LookAtDoorFeature}. Owns the shared gating (QUOI {@code Island.Dungeon(inClear = true)} + {@code Dungeon.isDead})
 * and drives {@link DoorScanner} while either feature is on.
 */
public final class DoorHelpersFeature {

    static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-doorhelpers");

    private static boolean wasInClear = false;
    private static String lastGateLog = null;

    private DoorHelpersFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(DoorHelpersFeature::onEndTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                LookAtDoorFeature.onChat(message.getString());
            }
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> LookAtDoorFeature.onFrame());
        LOGGER.info("[DoorHelpers] Registered (cheatBuild={})", com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED);
    }

    private static void onEndTick(Minecraft client) {
        DoorHelpersConfig cfg = DoorHelpersConfig.getInstance();
        boolean anyEnabled = cfg.isAutoDoorEnabled() || cfg.isLookAtDoorEnabled();
        String gate = gate(client, anyEnabled);
        boolean inClear = gate == null;
        if (wasInClear && !inClear) {
            DoorScanner.reset();
            LookAtDoorFeature.cancel("left clear (" + gate + ")");
        }
        wasInClear = inClear;
        logGate(gate == null ? "active" : gate);
        LookAtDoorFeature.tickAlways(client, inClear);
        if (!inClear) {
            return;
        }
        DoorScanner.tick(client);
        if (cfg.isAutoDoorEnabled()) {
            AutoDoorOpenerFeature.tick(client, cfg);
        }
        if (cfg.isLookAtDoorEnabled()) {
            LookAtDoorFeature.tick(client, cfg);
        }
    }

    /** @return null while Door Helpers may run (in a dungeon, clear phase, on Hypixel/p3sim), else the reason. */
    private static String gate(Minecraft client, boolean anyEnabled) {
        if (!anyEnabled) {
            return "disabled";
        }
        if (client.level == null || client.player == null || client.gameMode == null) {
            return "no-player";
        }
        if (!CheatUtils.isOnDungeonServer(client)) {
            return "not on hypixel/p3sim";
        }
        if (!DungeonState.isInDungeon()) {
            return "not in dungeon";
        }
        if (LiveMapFeature.isInBoss()) {
            return "in boss";
        }
        return null;
    }

    /** QUOI {@code Dungeon.isDead}: the tab list shows you as DEAD, or hotbar slot 1 is the ghost "Haunt" item. */
    static boolean isDead(Minecraft client) {
        if (client.player == null) {
            return true;
        }
        if (PartyTracker.isDead(client.player.getName().getString())) {
            return true;
        }
        ItemStack first = client.player.getInventory().getItem(0);
        return !first.isEmpty() && first.getHoverName().getString().contains("Haunt");
    }

    private static void logGate(String gate) {
        if (!gate.equals(lastGateLog)) {
            lastGateLog = gate;
            LOGGER.info("[DoorHelpers] state: {}", gate);
        }
    }
}
