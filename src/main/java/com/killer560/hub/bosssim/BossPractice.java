package com.killer560.hub.bosssim;

import com.google.gson.JsonObject;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.shared.SharedWriter;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Device practice for the F7 boss sim (simdevpractice-1118): Simon Says or the fourth device (Target) alone in P3, with a
 * Restart button beside the standing spot. The devices are {@link BossDevices}' own (same timings, same lamps and targets);
 * this class only times an attempt, counts misses and says the result. Time runs on the device clock (server ticks): from
 * the Simon Says start press, or the first lit target, to the device's completion. Nothing here runs outside a practice.
 */
public final class BossPractice {
    private static final Logger LOGGER = ModLog.get("killer560smod-bosssim");

    public static final int SS = 1, I4 = 4;
    private static final String[] KEY = {"", "ss", "", "", "i4"};
    private static final String[] NAME = {"", "Simon Says", "", "", "Fourth Device"};

    /** The button's block and the block under it: beside the standing spot, clear of the device's own buttons / the plate. */
    private static final BlockPos SS_BUTTON = new BlockPos(107, 121, 95), I4_BUTTON = new BlockPos(65, 128, 35);

    private static int device;
    private static BlockPos button;
    private static ArmorStand label;
    private static boolean wasPressed, finished;
    /** Device clock at the attempt's first action (-1: not started) and at the last finished Simon Says round. */
    private static int startTick = -1, lastMark;
    private static int clicks, wrong, ignored, shots, hitArrows, hitTargets;
    private static final List<String> rounds = new ArrayList<>();
    private static volatile int lastMs, lastMisses;
    private static final int[] sessionBest = new int[5];
    private static volatile JsonObject data;

    private BossPractice() {
    }

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (device == I4 && startTick >= 0 && !finished && e instanceof AbstractArrow) {
                shots++;
            }
        });
    }

    static boolean active() {
        return device != 0;
    }

    /** Sets the device up: the Restart button and its label beside the standing spot, a clean attempt. */
    static void begin(ServerLevel level, int dev) {
        stop();
        device = dev;
        button = dev == SS ? SS_BUTTON : I4_BUTTON;
        BossBlocks.set(level, button.below(), BossBlocks.of("minecraft:stone_bricks"));
        BossBlocks.set(level, button, BossBlocks.of("minecraft:stone_button[face=floor,facing=north,powered=false]"));
        label = BossFight.label(button.getX() + 0.5, button.getY() + 0.45, button.getZ() + 0.5, "§a§lRestart");
        wasPressed = false;
        resetAttempt();
        java.util.Arrays.fill(sessionBest, 0);
        if (data == null) {
            SharedWriter.submit(() -> data = SharedRecords.load(SharedRecords.file(RecordCatalog.SIM_DEVICE_PRACTICE)));
        }
        LOGGER.info("[BossSim] {} practice begins, Restart button at {}", NAME[dev], button.toShortString());
    }

    /** Server thread: the button is read the way the Simon Says buttons are (a press raises POWERED; it pops up 3 ticks later). */
    static void tick(ServerLevel level) {
        boolean now = BossDevices.powered(level.getBlockState(button));
        if (now && !wasPressed) {
            BossDevices.popUp(level, button, 3);
            restart(level);
        }
        wasPressed = now;
    }

    /** The Restart button: the device back to its start at once (also mid-attempt) and a new attempt timed from zero. */
    static void restart(ServerLevel level) {
        if (device == SS) {
            BossDevices.practiceResetSs(level);
        } else {
            BossDevices.practiceResetTarget(level);
            AABB area = new AABB(55, 115, 30, 80, 140, 55);
            level.getEntitiesOfClass(AbstractArrow.class, area).forEach(AbstractArrow::discard);
        }
        resetAttempt();
        LOGGER.info("[BossSim] {} practice restarted", NAME[device]);
    }

    private static void resetAttempt() {
        startTick = -1;
        clicks = wrong = ignored = shots = hitArrows = hitTargets = 0;
        finished = false;
        rounds.clear();
    }

    /** The fight ends or practice is left: the label goes (its blocks belong to the arena, rebuilt for the next fight). */
    static void stop() {
        if (label != null && !label.isRemoved()) {
            label.discard();
        }
        forget();
    }

    /** The world is gone: drop the state without touching its entities. */
    static void forget() {
        label = null;
        button = null;
        device = 0;
    }

    // ---- Simon Says hooks (BossDevices) ------------------------------------------------------------------------------

    static void ssStarted() {
        if (device == SS && startTick < 0) {
            startTick = lastMark = BossDevices.now();
        }
    }

    static void ssClick(boolean right) {
        if (device == SS) {
            clicks++;
            wrong += right ? 0 : 1;
        }
    }

    /** A press the device did not take (a button of an old show, a press while it shows or is done). */
    static void ssIgnored() {
        if (device == SS && !finished) {
            ignored++;
        }
    }

    static void ssRound(int round) {
        if (device == SS) {
            int now = BossDevices.now();
            rounds.add(round + " " + secs((now - lastMark) * 50));
            lastMark = now;
        }
    }

    // ---- Target (i4) hooks -------------------------------------------------------------------------------------------

    /** A new attempt on the plate. */
    static void i4Reset() {
        if (device == I4) {
            startTick = -1;
            shots = hitArrows = hitTargets = 0;
        }
    }

    static void i4Shown() {
        if (device == I4 && startTick < 0) {
            startTick = BossDevices.now();
        }
    }

    static void i4Hit(int arrows) {
        if (device == I4) {
            hitArrows += arrows;
            hitTargets++;
        }
    }

    // ---- the result --------------------------------------------------------------------------------------------------

    /** The device's own completion (BossDevices.done); another section's device is not this practice's. */
    static void finished(ServerLevel level, int section) {
        if (finished || section != device || startTick < 0) {
            return;
        }
        finished = true;
        int ms = (BossDevices.now() - startTick) * 50;
        boolean ss = device == SS;
        int misses = ss ? wrong : Math.max(0, Math.max(shots, hitArrows) - hitArrows);
        lastMs = ms;
        lastMisses = misses;
        int pb = pbMs(device);
        boolean newPb = pb <= 0 || ms < pb;
        sessionBest[device] = sessionBest[device] <= 0 ? ms : Math.min(sessionBest[device], ms);
        save(KEY[device], newPb ? ms : pb);
        ModChat.send("Practice", ModChat.text(NAME[device] + ": "), ModChat.value(secs(ms)),
                ModChat.text("   Misses: "), misses == 0 ? ModChat.good("0") : ModChat.bad(String.valueOf(misses)),
                ModChat.text(ss ? "   Clicks: " : "   Arrows: "), ModChat.value(String.valueOf(ss ? clicks : Math.max(shots, hitArrows))),
                ss && ignored > 0 ? ModChat.dim(" (ignored " + ignored + ")") : ModChat.text(""));
        if (ss) {
            ModChat.send("Practice", ModChat.text("Rounds: "), ModChat.value(String.join("   ", rounds)));
        } else {
            int arrows = Math.max(shots, hitArrows);
            ModChat.send("Practice", ModChat.text("Targets: "), ModChat.value(hitTargets + "/9"), ModChat.text("   Accuracy: "),
                    ModChat.value((arrows == 0 ? 100 : hitArrows * 100 / arrows) + "%"));
        }
        ModChat.send("Practice", ModChat.text("Session best: "), ModChat.value(secs(sessionBest[device])),
                ModChat.text("   Personal best: "), ModChat.value(secs(newPb ? ms : pb)),
                newPb ? ModChat.good(pb <= 0 ? "  First time!" : "  New personal best!") : ModChat.text(""));
        LOGGER.info("[BossSim] {} practice done in {} ms, {} miss(es), {} click(s)/arrow(s)", NAME[device], ms, misses,
                ss ? clicks : Math.max(shots, hitArrows));
    }

    private static String secs(int ms) {
        return String.format(Locale.ROOT, "%.2fs", ms / 1000.0);
    }

    /** The saved personal best for {@code dev} in ms, or 0. */
    public static int pbMs(int dev) {
        JsonObject d = data;
        return d != null && d.has(KEY[dev]) && d.get(KEY[dev]).isJsonObject() && d.getAsJsonObject(KEY[dev]).has("bestMs")
                ? d.getAsJsonObject(KEY[dev]).get("bestMs").getAsInt() : 0;
    }

    /** Records an attempt: the best time (smaller wins) and one more run, merged into the shared store off this thread. */
    private static void save(String key, int bestMs) {
        JsonObject d = data == null ? new JsonObject() : data.deepCopy();
        JsonObject e = d.has(key) && d.get(key).isJsonObject() ? d.getAsJsonObject(key) : new JsonObject();
        e.addProperty("bestMs", bestMs);
        e.addProperty("runs", (e.has("runs") ? e.get("runs").getAsInt() : 0) + 1);
        d.add(key, e);
        data = d;
        SharedWriter.submit(() -> data = SharedRecords.save(SharedRecords.file(RecordCatalog.SIM_DEVICE_PRACTICE), d));
    }

    /** Testkit: forgets the in-memory record and reads the file again (what a restart does). */
    public static void reloadForTest() {
        SharedWriter.drain(5000);
        data = SharedRecords.load(SharedRecords.file(RecordCatalog.SIM_DEVICE_PRACTICE));
    }

    /** Testkit: the attempt as it stands. */
    public static String stats() {
        return "dev=" + device + " started=" + (startTick >= 0) + " clicks=" + clicks + " wrong=" + wrong + " ignored=" + ignored
                + " shots=" + shots + " arrowHits=" + hitArrows + " targets=" + hitTargets + " finished=" + finished
                + " lastMs=" + lastMs + " lastMisses=" + lastMisses + " rounds=" + rounds.size();
    }

    /** Testkit: the Restart button's block, or null. */
    public static int[] buttonAt() {
        return button == null ? null : new int[]{button.getX(), button.getY(), button.getZ()};
    }
}
