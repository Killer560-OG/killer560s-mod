package com.killer560.hub.dungeonextras;

import com.killer560.hub.secrets.DungeonState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Watches block-break packets leave the client and describes whoever sent them, so ANOTHER mod's breaker aura can be
 * measured instead of guessed at.
 * <p>
 * killer560 (2026-09-27): "rsa has a breaker aura that I really like. Can you put loggers into my mod to see how the
 * rsa one acts and I will run around testing their breaker so we can somewhat try to replicate it."
 * <p>
 * It hooks the OUTBOUND packet stream ({@code ClientCommonPacketListenerImpl.send}) rather than
 * {@code MultiPlayerGameMode}, because that is the one path every mod must come through. A breaker that calls
 * vanilla's {@code startDestroyBlock}, one that builds its own {@code ServerboundPlayerActionPacket}, and one that
 * goes through the prediction system all land here; hooking the game-mode methods would only have caught the first
 * kind and reported "no activity" for the others - which is exactly the sort of silent nothing that cost a night of
 * wrong conclusions on the neo.
 * <p>
 * <b>Whose packet is it?</b> Three cases, separated so the numbers mean something:
 * <ul>
 *   <li>OURS - Breaker Aura sent it this tick (it says so through {@link #ours()}).</li>
 *   <li>HAND - the attack key is physically down, so it is him mining.</li>
 *   <li>FOREIGN - neither. Our aura did not send it and he is not holding the button, so another mod did. That is
 *       the RSA column.</li>
 * </ul>
 * <p>
 * What it measures is chosen to answer the questions replication actually needs: how many breaks land on ONE tick,
 * how far the furthest one was (to the block's box AND to its centre, because different mods measure differently -
 * QUOI uses centre-from-feet, we use box-from-eye), whether line of sight was required, and whether it swings or
 * swaps the hotbar. Read-only throughout: nothing here sends, cancels or alters a packet.
 */
public final class ForeignBreakerProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-dungeonextras");
    private static final long WINDOW_MS = 5_000L;

    /** Set by Breaker Aura on the tick it sends, so its own packets are not counted as somebody else's. */
    private static long oursOnTick = -1;
    private static long tick;

    private static long windowFrom;
    private static final Set<BlockPos> foreignBlocks = new HashSet<>();
    private static int foreignSends;
    private static int handSends;
    private static int ourSends;
    private static int aborts;
    private static int stops;
    private static int swings;
    private static int hotbarSwaps;
    private static int foreignOnThisTick;
    private static int foreignMaxPerTick;
    private static long foreignTickMark = -1;
    private static int foreignTicks;
    private static double maxBoxReach;
    private static double maxCentreReach;
    private static int losClear;
    private static int losBlocked;
    private static int foreignRepeats;

    private ForeignBreakerProbe() {
    }

    /** Breaker Aura calls this immediately before it sends, so this tick's packets are attributed to us. */
    public static void ours() {
        oursOnTick = tick;
    }

    public static void onClientTick(Minecraft client) {
        tick++;
        if (client.player == null || client.level == null) {
            return;
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
        // Only speak when somebody else was breaking, or this would drown the log during his own runs.
        if (foreignSends > 0) {
            LOGGER.info("[DungeonExtras] FOREIGN BREAKER over {}s: {} send(s) across {} tick(s), {} distinct block(s)"
                            + " | {} a second | MOST ON ONE TICK {} | furthest: {} to the block, {} to its centre"
                            + " | line of sight {} clear / {} blocked | {} repeat(s)"
                            + " | {} swing(s), {} hotbar swap(s) | (his hand {}, ours {}, aborts {}, stops {})",
                    fmt(secs), foreignSends, foreignTicks, foreignBlocks.size(),
                    fmt(foreignSends / secs), foreignMaxPerTick, fmt(maxBoxReach), fmt(maxCentreReach),
                    losClear, losBlocked, foreignRepeats, swings, hotbarSwaps,
                    handSends, ourSends, aborts, stops);
        }
        windowFrom = now;
        foreignBlocks.clear();
        foreignSends = 0;
        handSends = 0;
        ourSends = 0;
        aborts = 0;
        stops = 0;
        swings = 0;
        hotbarSwaps = 0;
        foreignMaxPerTick = 0;
        foreignOnThisTick = 0;
        foreignTicks = 0;
        foreignRepeats = 0;
        foreignTickMark = -1;
        maxBoxReach = 0;
        maxCentreReach = 0;
        losClear = 0;
        losBlocked = 0;
    }

    /** A START_DESTROY_BLOCK left the client. */
    public static void onStartDestroy(BlockPos pos, Direction face, int sequence) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return;
        }
        if (oursOnTick == tick) {
            ourSends++;
            return;
        }
        if (mc.options != null && mc.options.keyAttack.isDown()) {
            handSends++;
            return;
        }
        // Our aura did not ask for this and he is not holding the button: another mod is driving.
        foreignSends++;
        if (!foreignBlocks.add(pos.immutable())) {
            foreignRepeats++;
        }
        if (foreignTickMark != tick) {
            foreignTickMark = tick;
            foreignOnThisTick = 0;
            foreignTicks++;
        }
        foreignOnThisTick++;
        foreignMaxPerTick = Math.max(foreignMaxPerTick, foreignOnThisTick);

        Vec3 eye = p.getEyePosition();
        double box = Math.sqrt(boxDistSq(eye, pos));
        double centre = Vec3.atCenterOf(pos).distanceTo(eye);
        maxBoxReach = Math.max(maxBoxReach, box);
        maxCentreReach = Math.max(maxCentreReach, centre);
        boolean los = lineOfSight(mc, p, pos);
        if (los) {
            losClear++;
        } else {
            losBlocked++;
        }
        LOGGER.info("[DungeonExtras] foreign break t{} #{} at {} face {} seq {} | {} to the block,"
                        + " {} to its centre | line of sight {} | this tick: {}",
                tick, foreignSends, pos.toShortString(), face, sequence, fmt(box), fmt(centre),
                los ? "clear" : "BLOCKED", foreignOnThisTick);
    }

    public static void onAbortDestroy() {
        if (oursOnTick != tick) {
            aborts++;
        }
    }

    public static void onStopDestroy() {
        if (oursOnTick != tick) {
            stops++;
        }
    }

    public static void onSwing() {
        if (oursOnTick != tick) {
            swings++;
        }
    }

    public static void onHotbarSwap() {
        if (oursOnTick != tick) {
            hotbarSwaps++;
        }
    }

    /** Squared distance from the eye to the nearest point of the block - what the server measures a break against. */
    private static double boxDistSq(Vec3 eye, BlockPos pos) {
        double cx = Math.max(pos.getX(), Math.min(eye.x, pos.getX() + 1.0));
        double cy = Math.max(pos.getY(), Math.min(eye.y, pos.getY() + 1.0));
        double cz = Math.max(pos.getZ(), Math.min(eye.z, pos.getZ() + 1.0));
        double dx = eye.x - cx;
        double dy = eye.y - cy;
        double dz = eye.z - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Is the block actually visible from the eye? QUOI's breaker requires this; whether RSA's does is the question. */
    private static boolean lineOfSight(Minecraft mc, LocalPlayer p, BlockPos pos) {
        try {
            BlockHitResult hit = mc.level.clip(new ClipContext(p.getEyePosition(), Vec3.atCenterOf(pos),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    /** True while there is any point counting - outside a dungeon this is noise. */
    public static boolean active() {
        return DungeonState.isInDungeon();
    }
}
