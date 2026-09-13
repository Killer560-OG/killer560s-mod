package com.killer560.hub.simonsays;

import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Simon Says diagnostic logger - <b>not</b> a solver. Killer560's original request was explicit: "Put in
 * an immense amount of loggers to get as much information from me as possible so that way I can manually
 * solve lots of Simon Says instances to build the best auto Simon Says" - this is that logging pass, on
 * its own before any actual solving logic.
 * <p>
 * <b>Honesty note:</b> this mod doesn't have confirmed data on exactly how the real Simon Says puzzle
 * presents its sequence (which block/item changes, what the "showing the sequence" phase looks like vs.
 * "your turn" - none of that has been decompiled or logged from a real run yet). Rather than guess at a
 * Mixin target inside {@code ClientPacketListener} (a real, first-choice place to intercept this, but one
 * this session's research couldn't confirm exact method names for against a real decompile - a wrong
 * Mixin injection target is a hard, whole-mod-breaking failure at launch, not just a broken feature,
 * exactly the kind of HIGH-RISK guess this codebase's own history (see {@link DungeonState}'s doc
 * comments) says not to make), this instead uses a plain-Java, mixin-free approach: every tick, while
 * enabled and actually inside a dungeon, it re-reads the real {@link BlockState} of every block in a
 * small box around you (config-adjustable radius) using nothing but {@code Level#getBlockState}, a
 * completely safe, long-stable public API, and logs any position whose state changed since the previous
 * tick. Also logs any chat line containing "Simon Says" for timing correlation. Run a real Simon Says
 * room with this on, then search the log for "[SimonSays]" - the changed-block log lines should show
 * exactly which blocks light up/change and in what order, which is the real data needed to build an
 * actual solver next.
 */
public final class SimonSaysFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-simonsays");

    private static Map<BlockPos, BlockState> lastStates = new HashMap<>();
    private static boolean wasActive = false;

    private SimonSaysFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void onChatMessage(Component message) {
        String raw = message.getString();
        if (raw.toLowerCase(java.util.Locale.ROOT).contains("simon says")) {
            LOGGER.info("[SimonSays] Chat line mentioning Simon Says: \"{}\"", raw);
        }
    }

    private static void tick() {
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        boolean active = cfg.isEnabled() && DungeonState.isInDungeon()
                && client.player != null && client.level != null;

        if (!active) {
            if (wasActive) {
                LOGGER.info("[SimonSays] Logging stopped (left dungeon or disabled) - clearing tracked block states.");
                lastStates = new HashMap<>();
            }
            wasActive = false;
            return;
        }
        if (!wasActive) {
            LOGGER.info("[SimonSays] Logging started - watching a {}x{}x{} box around you for block changes.",
                    cfg.getHorizontalRadius() * 2 + 1, cfg.getVerticalRadius() * 2 + 1, cfg.getHorizontalRadius() * 2 + 1);
            lastStates = new HashMap<>();
        }
        wasActive = true;

        BlockPos center = client.player.blockPosition();
        int hr = cfg.getHorizontalRadius();
        int vr = cfg.getVerticalRadius();
        Map<BlockPos, BlockState> currentStates = new HashMap<>();

        for (int dx = -hr; dx <= hr; dx++) {
            for (int dy = -vr; dy <= vr; dy++) {
                for (int dz = -hr; dz <= hr; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = client.level.getBlockState(pos);
                    currentStates.put(pos, state);
                    BlockState previous = lastStates.get(pos);
                    if (previous != null && !previous.equals(state)) {
                        LOGGER.info("[SimonSays] Block changed at {}: {} -> {}", pos, previous, state);
                    }
                }
            }
        }
        lastStates = currentStates;
    }
}
