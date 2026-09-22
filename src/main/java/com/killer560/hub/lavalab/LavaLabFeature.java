package com.killer560.hub.lavalab;

import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Lava Lab: a read-only measurement tool. killer560 wants real per-tick data from actual Hypixel Skyblock
 * dungeon runs on how the lava-bounce mechanic behaves - "you bounce once you hit lava", "you go higher
 * looking up", "holding movement in lava tends to make you not bounce" - so a movement model can be fit to
 * it later. This only ever READS game state (position, velocity, keys, blocks, fluid) and writes a log; it
 * never calls {@code setPos}/{@code setDeltaMovement} or touches a {@code KeyMapping}'s {@code setDown}, so
 * (per the mod's own "No Direct Movement Writes" rule) there is nothing here for an anti-cheat to see and
 * nothing cheat-build-only about it - it ships in both jars and is only gated by its own OFF-by-default
 * setting plus the same "paused outside Skyblock/p3sim" gate every feature respects.
 * <p>
 * <b>Arm/disarm.</b> While {@link LavaLabConfig#isEnabled()}, this arms itself the instant
 * {@link LocalPlayer#isInLava()} first goes true and keeps sampling every client tick until
 * {@link LavaLabConfig#getAutoArmTailTicks()} ticks pass with no lava contact at all (default 60), so a
 * session always has room to capture entry, the bounce itself, and whatever happens right after. A hard
 * safety cap ({@link #HARD_MAX_SESSION_TICKS}) also ends a session outright if something keeps it armed
 * far longer than any real bounce test should run. {@code /lavalab on}/{@code off} (see
 * {@link LavaLabCommands}) arm/disarm manually on top of that, for starting a recording before you jump in.
 * <p>
 * <b>Bounce detection.</b> A bounce is the tick vertical velocity flips from falling to rising while
 * touching lava (see {@link #processBounceDetection}). Detection doesn't fire the summary the instant that
 * happens - it keeps watching for up to {@link #APEX_TIMEOUT_TICKS} more ticks to find the actual peak of
 * the resulting arc (peak height, and the horizontal speed at that peak), then logs one line with exactly
 * the numbers needed to test killer560's two claims: entry/exit vertical speed and peak height (does
 * looking up give more height?), and whether any movement key was held in the ticks around it (does
 * holding movement suppress the bounce?). A small rolling window ({@link #WINDOW}) is all that's kept in
 * memory for this - the full session is streamed to disk via {@link LavaLabCsvWriter} instead of held here.
 * <p>
 * <b>What this can't tell you from code alone</b> - see the tool's own report for the full list, but in
 * short: whether "bounce" here matches Hypixel's own (possibly modified) lava mechanic exactly, and how
 * fast this needs to arm/sample relative to real network tick smoothing - both need real dungeon runs with
 * the CSVs this produces, which is the entire point of building the tool this way instead of guessing.
 */
public final class LavaLabFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-lavalab");
    private static final String FEATURE = "Lava Lab";

    /** ~20 minutes at 20 tps - generous for any real bounce test, a safety net against a session that
     *  never sees its tail expire (e.g. stuck touching lava). */
    private static final int HARD_MAX_SESSION_TICKS = 24_000;
    /** How long to keep watching after a bounce trigger before giving up on finding the apex. */
    private static final int APEX_TIMEOUT_TICKS = 60;
    /** How far back from the bounce tick to look for a held movement key. */
    private static final int KEY_LOOKBACK_TICKS = 10;
    /** Must comfortably cover KEY_LOOKBACK_TICKS behind a bounce and APEX_TIMEOUT_TICKS ahead of it. */
    private static final int WINDOW_CAP = 160;

    private static boolean armed = false;
    private static int sessionTick;
    private static int ticksSinceLastLavaContact;
    private static int bounceCountThisSession;
    private static LavaLabCsvWriter writer;

    private static final Deque<TickSample> WINDOW = new ArrayDeque<>();
    private static TickSample prev;
    private static PendingBounce pending;
    private static boolean lavaEpisodeActive;
    private static double episodeEntryVerticalSpeed;

    private LavaLabFeature() {
    }

    public static void register() {
        LavaLabConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(LavaLabFeature::onClientTick);
    }

    // ---- per-tick loop ----

    private static void onClientTick(Minecraft client) {
        LavaLabConfig cfg = LavaLabConfig.getInstance();
        if (!cfg.isEnabled()) {
            if (armed) {
                endSession("feature turned off");
            }
            return;
        }
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            if (armed) {
                endSession("left the world");
            }
            return;
        }

        if (!armed) {
            if (!player.isInLava()) {
                return;
            }
            startSession();
        }

        TickSample sample = sample(client, player, level);
        processBounceDetection(sample);
        appendWindow(sample);
        writer.addTickLine(toCsvLine(sample));
        sessionTick++;
        ticksSinceLastLavaContact = sample.inLava ? 0 : ticksSinceLastLavaContact + 1;
        prev = sample;

        if (ticksSinceLastLavaContact > cfg.getAutoArmTailTicks()) {
            endSession("auto - " + cfg.getAutoArmTailTicks() + " ticks since last lava contact");
        } else if (sessionTick >= HARD_MAX_SESSION_TICKS) {
            endSession("auto - safety cap of " + HARD_MAX_SESSION_TICKS + " ticks reached");
        }
    }

    // ---- arm/disarm (shared by the auto logic above and the manual commands) ----

    private static void startSession() {
        writer = LavaLabCsvWriter.startSession();
        sessionTick = 0;
        ticksSinceLastLavaContact = 0;
        bounceCountThisSession = 0;
        prev = null;
        pending = null;
        lavaEpisodeActive = false;
        episodeEntryVerticalSpeed = 0.0;
        WINDOW.clear();
        armed = true;
        ModChat.send(FEATURE, ModChat.text("Recording started - "),
                ModChat.value(writer.ticksPath().getFileName().toString()));
        LOGGER.info("[LavaLab] Session started: {}", writer.ticksPath());
    }

    private static void endSession(String reason) {
        if (!armed) {
            return;
        }
        armed = false;
        int ticks = sessionTick;
        int bounces = bounceCountThisSession;
        LavaLabCsvWriter finished = writer;
        writer = null;
        pending = null;
        WINDOW.clear();
        if (finished != null) {
            finished.finish();
            ModChat.send(FEATURE, ModChat.text("Recording stopped ("),
                    ModChat.dim(reason), ModChat.text("): "),
                    ModChat.value(ticks + " tick" + (ticks == 1 ? "" : "s") + ", " + bounces + " bounce" + (bounces == 1 ? "" : "s")));
            LOGGER.info("[LavaLab] Session ended ({}): {} ticks, {} bounces -> {}", reason, ticks, bounces, finished.ticksPath());
        }
    }

    /** {@code /lavalab on} - arms immediately regardless of lava contact, so a session can start before
     *  you jump in. @return status text for the command to send, or {@code null} if a chat line was
     *  already sent by {@link #startSession()}. */
    static String armManual() {
        if (armed) {
            return "Already recording (" + sessionTick + " tick" + (sessionTick == 1 ? "" : "s") + " so far).";
        }
        startSession();
        return null;
    }

    /** {@code /lavalab off} - ends and saves the current session early. */
    static String disarmManual() {
        if (!armed) {
            return "Not recording.";
        }
        endSession("manual");
        return null;
    }

    /** {@code /lavalab clear} - discards the in-progress session's files instead of keeping a half-finished
     *  recording. Different from {@code off}: nothing from this session is written to disk. */
    static String clearCurrent() {
        if (!armed) {
            return "Nothing to clear - not recording.";
        }
        armed = false;
        int ticks = sessionTick;
        int bounces = bounceCountThisSession;
        LavaLabCsvWriter aborted = writer;
        writer = null;
        pending = null;
        WINDOW.clear();
        if (aborted != null) {
            aborted.abortAndDelete();
        }
        return "Cleared in-progress session (" + ticks + " tick" + (ticks == 1 ? "" : "s") + ", " + bounces
                + " bounce" + (bounces == 1 ? "" : "s") + ") - discarded, nothing was saved.";
    }

    /** {@code /lavalab status}. */
    static String status() {
        LavaLabConfig cfg = LavaLabConfig.getInstance();
        StringBuilder sb = new StringBuilder();
        sb.append("Enabled: ").append(cfg.isEnabledRaw() ? "ON" : "OFF");
        sb.append(", auto-arm tail: ").append(cfg.getAutoArmTailTicks()).append(" ticks");
        if (armed) {
            sb.append(" - RECORDING: ").append(sessionTick).append(" tick").append(sessionTick == 1 ? "" : "s");
            sb.append(", ").append(bounceCountThisSession).append(" bounce").append(bounceCountThisSession == 1 ? "" : "s");
            sb.append(", ").append(ticksSinceLastLavaContact).append(" tick").append(ticksSinceLastLavaContact == 1 ? "" : "s")
                    .append(" since last lava contact");
            if (writer != null) {
                sb.append(" -> ").append(writer.ticksPath().getFileName());
            }
        } else {
            sb.append(" - not recording.");
        }
        return sb.toString();
    }

    // ---- bounce detection ----

    private static void processBounceDetection(TickSample s) {
        boolean enteringLavaEpisode = s.inLava && (prev == null || !prev.inLava);
        if (enteringLavaEpisode) {
            lavaEpisodeActive = true;
            episodeEntryVerticalSpeed = s.vy;
        }
        if (!s.inLava && prev != null && prev.inLava) {
            lavaEpisodeActive = false;
        }

        if (pending != null) {
            if (s.y > pending.peakY) {
                pending.peakY = s.y;
                pending.peakHorizSpeed = horizontal(s.vx, s.vz);
            }
            pending.ticksWaited++;
            boolean apexReached = s.vy <= 0.0;
            if (apexReached || pending.ticksWaited >= APEX_TIMEOUT_TICKS) {
                finalizeBounce(pending);
                pending = null;
            }
        }

        // The moment vertical velocity flips from falling to rising while touching lava (this tick or the
        // one just before it - the push happens right as you leave the fluid, so "was falling" can still
        // read inLava = true on the earlier sample).
        if (pending == null && prev != null && prev.vy < 0.0 && s.vy > 0.0 && (s.inLava || prev.inLava)) {
            PendingBounce b = new PendingBounce();
            b.bounceTick = s.tick;
            b.bounceWallMs = s.wallMs;
            b.bounceX = s.x;
            b.bounceY = s.y;
            b.bounceZ = s.z;
            b.entryVerticalSpeed = lavaEpisodeActive ? episodeEntryVerticalSpeed : prev.vy;
            b.bounceVerticalVelocity = s.vy;
            b.horizontalSpeedBefore = horizontal(prev.vx, prev.vz);
            b.pitchAtBounce = s.pitch;
            b.peakY = s.y;
            b.peakHorizSpeed = horizontal(s.vx, s.vz);
            b.ticksWaited = 0;
            pending = b;
        }
    }

    private static void finalizeBounce(PendingBounce b) {
        double peakHeightGained = b.peakY - b.bounceY;
        boolean keyHeld = anyMovementKeyHeldInRange(b.bounceTick - KEY_LOOKBACK_TICKS, b.bounceTick + b.ticksWaited);
        bounceCountThisSession++;

        String bounceLine = String.join(",",
                Integer.toString(b.bounceTick), Long.toString(b.bounceWallMs),
                fmt(b.bounceX), fmt(b.bounceY), fmt(b.bounceZ),
                fmt(b.entryVerticalSpeed), fmt(b.bounceVerticalVelocity), fmt(peakHeightGained),
                fmt(b.horizontalSpeedBefore), fmt(b.peakHorizSpeed), fmt(b.pitchAtBounce),
                Boolean.toString(keyHeld));
        if (writer != null) {
            writer.addBounceLine(bounceLine);
        }

        String summary = String.format(Locale.ROOT,
                "Bounce #%d: entry vy=%.3f -> exit vy=%.3f (peak +%.2f blocks), horiz %.2f -> %.2f blk/tick, "
                        + "pitch=%.1f, movement key held nearby: %s",
                bounceCountThisSession, b.entryVerticalSpeed, b.bounceVerticalVelocity, peakHeightGained,
                b.horizontalSpeedBefore, b.peakHorizSpeed, b.pitchAtBounce, keyHeld ? "yes" : "no");
        ModChat.send(FEATURE, ModChat.text(summary));
        LOGGER.info("[LavaLab] {}", summary);
    }

    private static boolean anyMovementKeyHeldInRange(int fromTick, int toTick) {
        for (TickSample s : WINDOW) {
            if (s.tick >= fromTick && s.tick <= toTick && s.anyMovementKey) {
                return true;
            }
        }
        return false;
    }

    private static void appendWindow(TickSample s) {
        WINDOW.addLast(s);
        while (WINDOW.size() > WINDOW_CAP) {
            WINDOW.removeFirst();
        }
    }

    // ---- sampling ----

    private static TickSample sample(Minecraft client, LocalPlayer player, ClientLevel level) {
        TickSample s = new TickSample();
        s.tick = sessionTick;
        s.wallMs = System.currentTimeMillis();
        Vec3 pos = player.position();
        s.x = pos.x;
        s.y = pos.y;
        s.z = pos.z;
        Vec3 vel = player.getDeltaMovement();
        s.vx = vel.x;
        s.vy = vel.y;
        s.vz = vel.z;
        // Raw getYRot()/getXRot(), exactly as Minecraft holds them - same rule as PositionFeature: never
        // wrap/clamp a rotation value here, this is read-only observation.
        s.yaw = player.getYRot();
        s.pitch = player.getXRot();
        s.onGround = player.onGround();
        s.inLava = player.isInLava();
        s.inWater = player.isInWater();
        s.boxIntersectsLava = boxIntersectsLava(level, player.getBoundingBox());
        BlockPos feet = BlockPos.containing(pos.x, pos.y, pos.z);
        FluidState fluidState = level.getFluidState(feet);
        s.fluidHeightAtFeet = fluidState.isEmpty() ? -1.0 : fluidState.getHeight(level, feet);
        s.sprinting = player.isSprinting();
        s.sneaking = player.isCrouching();
        var opts = client.options;
        s.keyForward = opts.keyUp.isDown();
        s.keyBack = opts.keyDown.isDown();
        s.keyLeft = opts.keyLeft.isDown();
        s.keyRight = opts.keyRight.isDown();
        s.keyJump = opts.keyJump.isDown();
        s.keySneak = opts.keyShift.isDown();
        s.anyMovementKey = s.keyForward || s.keyBack || s.keyLeft || s.keyRight || s.keyJump || s.keySneak;
        s.blockAtFeet = blockId(level.getBlockState(feet));
        s.blockBelow = blockId(level.getBlockState(feet.below()));
        s.movementSpeedAttr = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
        return s;
    }

    /** Vanilla's own {@code isInLava()} uses a slightly deflated box; this checks every block the player's
     *  ACTUAL bounding box overlaps, so the two columns can disagree near an edge - exactly the kind of
     *  extra fidelity a movement-model fit benefits from. */
    private static boolean boxIntersectsLava(ClientLevel level, AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed(box)) {
            if (level.getFluidState(pos).getType().is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static double horizontal(double vx, double vz) {
        return Math.sqrt(vx * vx + vz * vz);
    }

    private static String toCsvLine(TickSample s) {
        return String.join(",",
                Integer.toString(s.tick), Long.toString(s.wallMs),
                fmt(s.x), fmt(s.y), fmt(s.z),
                fmt(s.vx), fmt(s.vy), fmt(s.vz),
                fmt(s.yaw), fmt(s.pitch),
                bool(s.onGround), bool(s.inLava), bool(s.inWater), bool(s.boxIntersectsLava),
                fmt(s.fluidHeightAtFeet),
                bool(s.sprinting), bool(s.sneaking),
                bool(s.keyForward), bool(s.keyBack), bool(s.keyLeft), bool(s.keyRight), bool(s.keyJump), bool(s.keySneak),
                s.blockAtFeet, s.blockBelow,
                fmt(s.movementSpeedAttr));
    }

    /** Locale.ROOT so a comma-decimal locale (e.g. most of Europe) can never slip a "," into a CSV cell. */
    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.5f", v);
    }

    private static String bool(boolean b) {
        return Boolean.toString(b);
    }

    /** One tick's worth of everything Lava Lab records, plus the couple of fields bounce detection needs
     *  that aren't written to the CSV directly (they're derived into columns at finalize time instead). */
    private static final class TickSample {
        int tick;
        long wallMs;
        double x, y, z;
        double vx, vy, vz;
        float yaw, pitch;
        boolean onGround;
        boolean inLava;
        boolean inWater;
        boolean boxIntersectsLava;
        double fluidHeightAtFeet;
        boolean sprinting;
        boolean sneaking;
        boolean keyForward, keyBack, keyLeft, keyRight, keyJump, keySneak;
        boolean anyMovementKey;
        String blockAtFeet;
        String blockBelow;
        double movementSpeedAttr;
    }

    /** A bounce being watched from its trigger tick until the resulting arc's apex is found (or the
     *  {@link #APEX_TIMEOUT_TICKS} watch window runs out). */
    private static final class PendingBounce {
        int bounceTick;
        long bounceWallMs;
        double bounceX, bounceY, bounceZ;
        double entryVerticalSpeed;
        double bounceVerticalVelocity;
        double horizontalSpeedBefore;
        float pitchAtBounce;
        double peakY;
        double peakHorizSpeed;
        int ticksWaited;
    }
}
