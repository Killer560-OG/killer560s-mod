package com.killer560.hub.teleportlog;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.roomsim.SimAbilities;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Records what a teleport actually does on the real server, beside what the sim's model says it should.
 *
 * <p>killer560 (2026-09-30): "can you make a teleport logger so i can go on the main server and we get the
 * regular teleports right?" The sim's Instant Transmission has been rewritten three times off single in-game
 * reports - it put him inside blocks, then refused fourteen times in ten seconds, then needed a lift-a-block
 * case - and each rewrite was reasoning from one observation. This replaces that with measurements.
 *
 * <p><b>It sends nothing.</b> No packets, no input, no rotation: it watches the player's own position and the
 * item in his hand and appends to a file. That is what makes it safe to run on Hypixel, and it is why nothing
 * in this class acts.
 *
 * <p><b>Detection watches the MOVE, not the click.</b> The first version hooked {@link UseItemCallback} and
 * snapshotted there, and it recorded nothing at all when tested: scenario 70's teleport is triggered by calling
 * the ability directly, so no use event fires. That would have been worse on Hypixel than in the test, because
 * Hypixel resolves the ability server-side and there is no guarantee about what the client sees first. So the
 * trigger is now the only thing that is certainly observable - the player's position jumping further in one
 * tick than walking can manage - and the click is used only to notice a teleport that did NOT happen.
 *
 * <p><b>Why it logs the sim's prediction too.</b> "Hypixel moved me 11.9 blocks" says nothing on its own about
 * whether the sim is right; the difference is the whole point. So every tick that he holds one of these items,
 * {@link SimAbilities#dashTarget} is asked where the sim would land him - the real method the sim uses, not a
 * copy - and that answer is kept for one tick so it can be printed against what actually happened. A logger
 * comparing Hypixel against its own copy of the model would agree with the model however wrong the model was.
 */
public final class TeleportLoggerFeature {

    private static final org.slf4j.Logger LOGGER = ModLog.get("killer560smod-teleportlog");

    private static final Path LOG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-teleports.log");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** How long after a right-click a teleport is still expected, before it is recorded as not having happened. */
    private static final int WATCH_TICKS = 40;

    /**
     * How far the player must move in ONE tick for it to be a teleport rather than movement.
     *
     * <p>Sprinting covers about 0.28 blocks a tick and a sprint-jump about 0.6, so past 1.5 cannot be walked.
     * Deliberately well clear of both rather than tight to them: a false positive writes a wrong line into the
     * very data the sim is going to be corrected against.
     */
    private static final double JUMP_BLOCKS = 1.5;

    /** Last tick's state, which is what a teleport detected THIS tick started from. */
    private static Snapshot previous;

    /** Ticks left to see a teleport after a right-click, or 0 when nothing is expected. */
    private static int awaiting;

    /** The state the awaited teleport would have started from, kept so a no-move can be reported against it. */
    private static Snapshot awaitingFrom;

    private static int written;

    private TeleportLoggerFeature() {
    }

    private record Snapshot(Vec3 pos, float yaw, float pitch, boolean onGround, boolean sneaking,
                            String itemId, double range, Vec3 predicted, String rayHit,
                            String feet, String head, String below) {
    }

    public static void register() {
        TeleportLogConfig cfg = TeleportLogConfig.getInstance();
        UseItemCallback.EVENT.register((player, level, hand) -> {
            try {
                Minecraft client = Minecraft.getInstance();
                if (TeleportLogConfig.getInstance().isEnabled() && client.player != null
                        && player == client.player
                        && ItemIdentity.isEtherwarpItem(player.getItemInHand(hand))
                        && previous != null) {
                    awaiting = WATCH_TICKS;
                    awaitingFrom = previous;
                }
            } catch (RuntimeException e) {
                // A logger must never be why an interaction fails, and this runs on the path a real
                // right-click takes.
                LOGGER.warn("[TeleportLog] use hook failed", e);
            }
            return InteractionResult.PASS;
        });
        // START, not END. This repo's rule: an END handler runs after the tick's movement packet, so the
        // position read there is one tick stale. Nothing here sends anything, so it is not a flagging risk -
        // it is an accuracy one, and accuracy is the entire purpose of the file.
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                LOGGER.warn("[TeleportLog] tick failed", e);
            }
        });
        LOGGER.info("[TeleportLog] Registered (enabled={}, writing to {})", cfg.isEnabled(), LOG_PATH);
    }

    private static void tick(Minecraft client) {
        if (!TeleportLogConfig.getInstance().isEnabled() || client.player == null || client.level == null) {
            previous = null;
            awaiting = 0;
            awaitingFrom = null;
            return;
        }
        var player = client.player;
        Vec3 now = player.position();
        Snapshot before = previous;

        // Detect the teleport FIRST, against last tick's state, before this tick's snapshot replaces it.
        if (before != null && before.pos().distanceTo(now) >= JUMP_BLOCKS) {
            record(client, before, now);
            awaiting = 0;
            awaitingFrom = null;
        } else if (awaiting > 0 && --awaiting == 0 && awaitingFrom != null) {
            recordNoMove(client, awaitingFrom);
            awaitingFrom = null;
        }

        // This tick's snapshot, for the next one. Only while he is holding something that can teleport, so the
        // prediction is not computed for every tick of normal play.
        ItemStack held = player.getMainHandItem();
        if (!ItemIdentity.isEtherwarpItem(held)) {
            previous = null;
            return;
        }
        boolean sneak = player.isShiftKeyDown();
        double range = SimAbilities.instantTransmissionRange(held);
        // Etherwarp goes wherever the crosshair is, which is a different calculation; the sim's dash model is
        // only the Instant Transmission one, so no prediction is claimed for a sneaking click.
        Vec3 predicted = sneak ? null : SimAbilities.dashTarget(client, range);
        BlockPos feet = BlockPos.containing(now.x, now.y, now.z);
        previous = new Snapshot(now, player.getYRot(), player.getXRot(), player.onGround(), sneak,
                ItemIdentity.skyblockId(held), range, predicted, rayHit(client, range),
                blockName(client, feet), blockName(client, feet.above()), blockName(client, feet.below()));
    }

    private static void record(Minecraft client, Snapshot from, Vec3 to) {
        double dx = to.x - from.pos().x;
        double dy = to.y - from.pos().y;
        double dz = to.z - from.pos().z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        BlockPos land = BlockPos.containing(to.x, to.y, to.z);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%s  %s  item=%s range=%.1f yaw=%.1f pitch=%.1f onGround=%s%n",
                LocalDateTime.now().format(STAMP), from.sneaking() ? "ETHERWARP" : "TRANSMISSION",
                from.itemId(), from.range(), from.yaw(), from.pitch(), from.onGround()));
        sb.append(String.format(Locale.ROOT, "    from %.3f %.3f %.3f  ->  %.3f %.3f %.3f%n",
                from.pos().x, from.pos().y, from.pos().z, to.x, to.y, to.z));
        sb.append(String.format(Locale.ROOT, "    actual   dxz=%.3f dy=%+.3f total=%.3f%n",
                flat, dy, Math.sqrt(flat * flat + dy * dy)));
        Vec3 q = from.predicted();
        if (q == null) {
            sb.append("    the sim has no prediction for this kind ")
                    .append(from.sneaking() ? "(etherwarp aims at the crosshair)" : "(nowhere along the look fit)")
                    .append('\n');
        } else {
            double pdx = q.x - from.pos().x;
            double pdy = q.y - from.pos().y;
            double pdz = q.z - from.pos().z;
            double pflat = Math.sqrt(pdx * pdx + pdz * pdz);
            double apart = q.distanceTo(to);
            sb.append(String.format(Locale.ROOT, "    sim says %.3f %.3f %.3f   dxz=%.3f dy=%+.3f%n",
                    q.x, q.y, q.z, pflat, pdy));
            // In words, because this file gets read by eye. A quarter block is the sim's own step size, so
            // anything inside that is as close as its model can express.
            sb.append(String.format(Locale.ROOT, "    %s - %.3f blocks apart%n",
                    apart <= 0.25 ? "MATCH" : "MISMATCH", apart));
        }
        sb.append(String.format(Locale.ROOT,
                "    stood in feet=%s head=%s below=%s   landed in feet=%s below=%s%n",
                from.feet(), from.head(), from.below(),
                blockName(client, land), blockName(client, land.below())));
        sb.append("    crosshair ray: ").append(from.rayHit()).append('\n');
        append(sb.toString());
        if (TeleportLogConfig.getInstance().isChatEcho()) {
            boolean agree = q != null && q.distanceTo(to) <= 0.25;
            String verdict = q == null ? "no sim prediction"
                    : agree ? "sim agrees"
                            : String.format(Locale.ROOT, "sim off by %.2f", q.distanceTo(to));
            ModChat.send("TeleportLog",
                    ModChat.text(String.format(Locale.ROOT, "moved %.2f (dy %+.2f) - ", flat, dy)),
                    agree ? ModChat.good(verdict) : ModChat.bad(verdict));
        }
    }

    private static void recordNoMove(Minecraft client, Snapshot from) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%s  %s  item=%s range=%.1f yaw=%.1f pitch=%.1f onGround=%s%n",
                LocalDateTime.now().format(STAMP), from.sneaking() ? "ETHERWARP" : "TRANSMISSION",
                from.itemId(), from.range(), from.yaw(), from.pitch(), from.onGround()));
        sb.append(String.format(Locale.ROOT, "    from %.3f %.3f %.3f%n",
                from.pos().x, from.pos().y, from.pos().z));
        // Just as informative as a move. If Hypixel refused and the sim would have gone twelve blocks, the sim
        // is too permissive - the opposite of what it was last accused of.
        sb.append("    NO MOVE within ").append(WATCH_TICKS)
                .append(" ticks - out of mana, on cooldown, or Hypixel refused it\n");
        if (from.predicted() != null) {
            Vec3 q = from.predicted();
            sb.append(String.format(Locale.ROOT,
                    "    but the sim would have moved him to %.3f %.3f %.3f (%.3f blocks)%n",
                    q.x, q.y, q.z, q.distanceTo(from.pos())));
        }
        sb.append(String.format(Locale.ROOT, "    stood in feet=%s head=%s below=%s%n",
                from.feet(), from.head(), from.below()));
        sb.append("    crosshair ray: ").append(from.rayHit()).append('\n');
        append(sb.toString());
    }

    private static String blockName(Minecraft client, BlockPos at) {
        if (client.level == null) {
            return "?";
        }
        var state = client.level.getBlockState(at);
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    /**
     * Appends to the log, capped.
     *
     * <p>Capped rather than rotated: this is a diagnostic he will send me and then we delete, and a file that
     * quietly grows on a friend's machine forever is the kind of thing a logger should not do. When the cap is
     * reached it says so once rather than dropping entries silently.
     */
    private static void append(String text) {
        if (written > TeleportLogConfig.MAX_LINES) {
            return;
        }
        try {
            Files.createDirectories(LOG_PATH.getParent());
            if (!Files.exists(LOG_PATH)) {
                Files.write(LOG_PATH, List.of(
                        "killer560s-mod teleport log",
                        "Every Instant Transmission and etherwarp: what it actually did, and what the sim's own",
                        "model says it should have done. The MISMATCH lines are the ones worth reading.",
                        ""), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Files.writeString(LOG_PATH, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            written++;
            if (written > TeleportLogConfig.MAX_LINES) {
                Files.writeString(LOG_PATH, "--- cap of " + TeleportLogConfig.MAX_LINES
                                + " entries reached, nothing further recorded this session ---\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            LOGGER.warn("[TeleportLog] could not write {}", LOG_PATH, e);
        }
    }

    /** Where the log is, for telling him and for the tests. */
    public static Path logPath() {
        return LOG_PATH;
    }

    /** How many teleports this session has recorded - the number a test asserts on. */
    public static int recorded() {
        return written;
    }
    /**
     * What the crosshair ray hits within the ability's range, and how far away it is.
     *
     * <p>Added after the first 52 samples showed the sim's model was wrong (it agreed on one of them) but did
     * NOT settle what the right one is. The landings clearly snap to a block - 51 of 52 on a block centre in
     * x/z and 52 of 52 on a whole y - and travel tracks the look vector, but the distance does not simply
     * equal the range, so something is stopping it short. This is the missing datum: if the landing turns out
     * to be keyed to where the crosshair ray lands, this line will show it directly instead of leaving it to
     * be inferred from geometry.
     */
    private static String rayHit(Minecraft client, double range) {
        if (client.player == null || client.level == null) {
            return "no level";
        }
        Vec3 eye = client.player.getEyePosition();
        Vec3 end = eye.add(client.player.getViewVector(1.0f).scale(range));
        var hit = client.level.clip(new net.minecraft.world.level.ClipContext(eye, end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, client.player));
        if (hit == null || hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            if (hit == null) {
                return "none";
            }
            return String.format(Locale.ROOT, "%s at %s, %.3f blocks from the eye, face %s",
                    blockName(client, hit.getBlockPos()), hit.getBlockPos().toShortString(),
                    eye.distanceTo(hit.getLocation()), hit.getDirection());
        }
        return String.format(Locale.ROOT, "nothing within %.1f blocks", range);
    }

}
