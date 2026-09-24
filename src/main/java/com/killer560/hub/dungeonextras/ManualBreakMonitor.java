package com.killer560.hub.dungeonextras;

import com.killer560.hub.secrets.DungeonState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * What HE does when he breaks blocks by hand, counted the same way Breaker Aura counts itself.
 * <p>
 * killer560 (2026-09-24): "Can you add in somethign to see how it looks when I break them normally. I swear me
 * holding break breaks faster than the auto breaker."
 * <p>
 * He is probably right, and this is the only way to settle it - three explanations for the aura's speed have
 * already died tonight (the action gate was not starving it, there were no wasted ticks, and its reach already
 * beats QUOI's), every one of them killed by a measurement rather than by an argument. So rather than reason
 * about what vanilla does when the attack key is held, this counts it.
 * <p>
 * It measures, it does not act: nothing here sends a packet or changes what the game does. Vanilla's own
 * {@code startDestroyBlock} is the hook, so what is counted is exactly what his hand produced.
 * <p>
 * The tally is deliberately in the same shape and the same units as the aura's, so the two lines can be read
 * against each other: ticks held, blocks broken, blocks a second, and the ceiling of 20 a second that one break
 * per client tick allows.
 */
public final class ManualBreakMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-dungeonextras");
    private static final long WINDOW_MS = 5_000L;

    /** Distinct blocks broken in this window - the same thing the aura's "sent" counts. */
    private static final Set<BlockPos> broken = new HashSet<>();
    private static long windowFrom;
    private static int ticksHeld;
    private static int startCalls;
    private static int continueCalls;
    private static int refused;
    /** Has the continueDestroyBlock hook EVER fired? The mixin config requires nothing, so a wrong signature
     *  fails silently - and a count of zero would then read as a finding about the game rather than about the
     *  hook. This tells the two apart instead of leaving it to be guessed at. */
    private static boolean continueHookSeen;

    private ManualBreakMonitor() {
    }

    /** Vanilla began a break. {@code took} is its own return value: true when the block actually went. */
    public static void onStartDestroyBlock(BlockPos pos, boolean took) {
        if (!counting()) {
            return;
        }
        startCalls++;
        if (took) {
            broken.add(pos.immutable());
        } else {
            refused++;
        }
    }

    /** Vanilla is still holding the break down on a block (the every-tick call while the key is held). */
    public static void onContinueDestroyBlock() {
        continueHookSeen = true;
        if (counting()) {
            continueCalls++;
        }
    }

    private static boolean counting() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null && DungeonState.isInDungeon();
    }

    public static void onClientTick(Minecraft client) {
        if (client.player == null || client.level == null || !DungeonState.isInDungeon()) {
            return;
        }
        boolean held = client.options.keyAttack.isDown();
        if (held) {
            ticksHeld++;
        }
        long now = System.currentTimeMillis();
        if (windowFrom == 0) {
            windowFrom = now;
            return;
        }
        if (now - windowFrom < WINDOW_MS) {
            return;
        }
        double secs = (now - windowFrom) / 1000.0;
        // Only worth a line when he was actually breaking something by hand.
        if (ticksHeld > 0 && (startCalls > 0 || !broken.isEmpty())) {
            LOGGER.info("[DungeonExtras] BY HAND over {}s: {} block(s) broken ({} a second, ceiling 20)"
                            + " | attack held {} tick(s) | {} startDestroy call(s) | continueDestroy {}"
                            + " | {} refused",
                    String.format("%.1f", secs), broken.size(),
                    String.format("%.1f", broken.size() / secs), ticksHeld, startCalls,
                    continueHookSeen ? String.valueOf(continueCalls) : "hook never fired - ignore", refused);
        }
        windowFrom = now;
        ticksHeld = 0;
        startCalls = 0;
        continueCalls = 0;
        refused = 0;
        broken.clear();
    }
}
