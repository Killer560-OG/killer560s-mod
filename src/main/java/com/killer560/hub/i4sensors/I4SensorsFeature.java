package com.killer560.hub.i4sensors;

import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * "I4" (a.k.a. "Pre4" - the area right before Necron's P4 phase starts, real confirmed name/location
 * from QUOI's own {@code AutoLeap.kt}) diagnostic logger - killer560's "add a bunch of sensors for this
 * as well so we can make a spec safe auto i4" request.
 * <p>
 * <b>Honesty note:</b> Hypixel's real chat line confirms SOMEONE "completed a device" at this spot
 * (the exact regex is reused by {@link com.killer560.hub.autoleap.AutoLeapFeature} to trigger a leap
 * there), but this session has no confirmed data on what the device itself actually requires you to
 * click/interact with to complete it - unlike Simon Says, no reference mod's source for the device's
 * own solving logic was found. Rather than guess at that (the same category of risk this mod's own
 * history says not to take), this is a plain block-state diff logger over the real Pre4 area, same
 * safe technique the original Simon Says pass used before real data existed for it - run this during a
 * real F7 boss fight and search the log for "[I4Sensors]" to see exactly what changes there, which is
 * the real data an actual i4 solver would need.
 */
public final class I4SensorsFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-i4sensors");
    // Real AABB from QUOI's AutoLeap.kt (pre4Box) - the area right before Necron's P4 starts.
    private static final AABB PRE4_BOX = new AABB(60, 125, 32, 67, 132, 39);

    private static Map<BlockPos, BlockState> lastStates = new HashMap<>();
    private static boolean wasActive = false;

    private I4SensorsFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void onChatMessage(Component message) {
        if (!I4SensorsConfig.getInstance().isEnabled()) {
            return;
        }
        String raw = message.getString();
        if (raw.contains("completed a device")) {
            LOGGER.info("[I4Sensors] Device-completion chat line: \"{}\"", raw);
        }
    }

    private static void tick() {
        I4SensorsConfig cfg = I4SensorsConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        boolean active = cfg.isEnabled() && DungeonState.isBossPhaseActive()
                && client.player != null && client.level != null;

        if (!active) {
            if (wasActive) {
                lastStates = new HashMap<>();
            }
            wasActive = false;
            return;
        }
        if (!wasActive) {
            LOGGER.info("[I4Sensors] Logging started - watching the real Pre4 area for block changes.");
            lastStates = new HashMap<>();
        }
        wasActive = true;

        Map<BlockPos, BlockState> current = new HashMap<>();
        for (int x = (int) PRE4_BOX.minX; x <= (int) PRE4_BOX.maxX; x++) {
            for (int y = (int) PRE4_BOX.minY; y <= (int) PRE4_BOX.maxY; y++) {
                for (int z = (int) PRE4_BOX.minZ; z <= (int) PRE4_BOX.maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = client.level.getBlockState(pos);
                    current.put(pos, state);
                    BlockState previous = lastStates.get(pos);
                    if (previous != null && !previous.equals(state)) {
                        LOGGER.info("[I4Sensors] Block changed at {}: {} -> {}", pos, previous, state);
                    }
                }
            }
        }
        lastStates = current;
    }
}
