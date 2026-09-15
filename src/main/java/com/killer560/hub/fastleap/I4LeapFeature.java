package com.killer560.hub.fastleap;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.fastleap.I4LeapConfig.BackupType;
import com.killer560.hub.fastleap.I4LeapConfig.TargetType;
import com.killer560.hub.util.ModChat;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * I4 leap out - QUOI {@code AutoLeap.kt}'s "I4 leap" (+ "Auto", "Leap Melody"), split into its own independent feature
 * (killer560, 2026-09-15). Options:
 * <ul>
 * <li><b>Auto</b> (QUOI): your own "&lt;you&gt; completed a device!" line while standing in QUOI's pre4 box, in the
 * F7/M7 boss.</li>
 * <li><b>Fast Leap</b> (QUOI): left-clicking a Spirit Leap while in the pre4 box (handled in
 * {@link FastLeapFeature#onLeftClick()}).</li>
 * <li><b>Target</b>: a Class (first alive teammate), a Player name, or Melody - QUOI's melody detection: the last
 * "Name:" in a party line containing a Melody progress token ("1/4", "2/4", "3/4", "25%", "50%", "75%"). Melody has a
 * Class/Player backup used when no melody player is known, they can't be leapt to, or they're not in the leap menu.</li>
 * <li><b>Prevent Inputs</b>: while Auto i4 is on and you're on the i4 pad (x 62-65, z 34-37, |y-127| &lt; 0.5) after the
 * device has started (a target block on the wall lit) and until it completes or you step off, movement keys are held up and mouse
 * clicks, scrolling and camera look are cancelled; after an auto/fast i4 leap is requested the same block stays on
 * until the leap finishes, and the leap itself runs with {@link LeapManager}'s "block inputs".</li>
 * </ul>
 */
public final class I4LeapFeature {

    private static final AABB PRE4_BOX = new AABB(62.0, 127.0, 34.0, 65.0, 130.0, 37.0);
    // NoammAddons I4Helper.devBlocks (same wall as i4sensors)
    private static final List<BlockPos> DEV_BLOCKS = List.of(
            new BlockPos(68, 130, 50), new BlockPos(66, 130, 50), new BlockPos(64, 130, 50),
            new BlockPos(68, 128, 50), new BlockPos(66, 128, 50), new BlockPos(64, 128, 50),
            new BlockPos(68, 126, 50), new BlockPos(66, 126, 50), new BlockPos(64, 126, 50));
    private static final Set<String> MELODY_PROGRESS = Set.of("1/4", "2/4", "3/4", "25%", "50%", "75%");
    private static final Pattern MELODY_PLAYER_REGEX = Pattern.compile("([A-Za-z0-9_]{3,16}):");
    // QUOI deviceDoneRegex, plus an optional suffix (Odin's Terminal Splits appends times)
    private static final Pattern DEVICE_DONE_REGEX = Pattern.compile("^(\\w+) completed a device! \\((.*?)\\)(?:\\s.*)?$");
    private static final int RELIGHT_GRACE_TICKS = 40;
    // Safety: no lit target for this long = stop treating the device as running, so a missed completion line can
    // never keep the player's inputs blocked on the pad.
    private static final int IDLE_DONE_TICKS = 60;

    private static String melodyTarget = null;
    private static boolean deviceStarted = false;
    private static boolean deviceCompleted = false;
    private static int ticksSinceCompleted = 0;
    private static int ticksSinceLit = 0;
    private static boolean lastAnyLit = false;
    private static boolean awaitingLeap = false;

    private I4LeapFeature() {
    }

    // ------------------------------------------------------------------------------------------------------------

    static void onWorldChange() {
        melodyTarget = null;
        deviceStarted = false;
        deviceCompleted = false;
        lastAnyLit = false;
        awaitingLeap = false;
    }

    static void onChat(String unformatted) {
        I4LeapConfig cfg = I4LeapConfig.getInstance();
        if (!cfg.isEnabled() || !Floor7Tracker.inF7Boss()) {
            return;
        }
        if (cfg.getTargetType() == TargetType.MELODY && unformatted.contains("Party")) {
            for (String token : MELODY_PROGRESS) {
                if (unformatted.contains(token)) {
                    Matcher m = MELODY_PLAYER_REGEX.matcher(unformatted);
                    String last = null;
                    while (m.find()) {
                        last = m.group(1);
                    }
                    if (last != null && !last.equalsIgnoreCase(Teammates.selfName())) {
                        melodyTarget = last;
                        FastLeapFeature.LOGGER.info("[I4Leap] Melody player: {}", last);
                    }
                    break;
                }
            }
        }
        Matcher done = DEVICE_DONE_REGEX.matcher(unformatted);
        if (!done.matches() || !done.group(1).equals(Teammates.selfName())) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && isOnPad(player.position())) {
            deviceCompleted = true;
            ticksSinceCompleted = 0;
        }
        if (cfg.isAuto() && isAtPre4()) {
            FastLeapFeature.LOGGER.info("[I4Leap] Own device completed at pre4 -> i4 leap");
            leapToTarget();
        }
    }

    static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (awaitingLeap && !LeapManager.isBusy()) {
            awaitingLeap = false;
        }
        if (player == null || client.level == null || !Floor7Tracker.inF7Boss() || !isOnPad(player.position())) {
            deviceStarted = false;
            deviceCompleted = false;
            lastAnyLit = false;
            ticksSinceLit = 0;
            return;
        }
        boolean anyLit = false;
        for (BlockPos pos : DEV_BLOCKS) {
            if (client.level.getBlockState(pos).is(Blocks.EMERALD_BLOCK)) {
                anyLit = true;
                break;
            }
        }
        if (anyLit) {
            deviceStarted = true;
            ticksSinceLit = 0;
        } else if (deviceStarted && !deviceCompleted && ++ticksSinceLit > IDLE_DONE_TICKS) {
            FastLeapFeature.LOGGER.info("[I4Leap] No lit target for {} ticks - treating the device as done", IDLE_DONE_TICKS);
            deviceCompleted = true;
            ticksSinceCompleted = RELIGHT_GRACE_TICKS + 1;
        }
        if (deviceCompleted) {
            ticksSinceCompleted++;
            // a finished device lighting up again (p3sim restart without leaving the pad) is a new attempt
            if (anyLit && !lastAnyLit && ticksSinceCompleted > RELIGHT_GRACE_TICKS) {
                deviceCompleted = false;
            }
        }
        lastAnyLit = anyLit;
    }

    // ------------------------------------------------------------------------------------------------------------

    /** True when a left click with a Spirit Leap should do the i4 fast leap. */
    static boolean wantsFastLeap() {
        I4LeapConfig cfg = I4LeapConfig.getInstance();
        return cfg.isEnabled() && cfg.isFastLeap() && isAtPre4();
    }

    /** "Prevent Inputs" state (movement keys, clicks, scroll, camera). The device-running part only applies while
     *  Auto i4 is on: without it the player has to shoot (clicks) and could never finish the device or step off the pad
     *  (movement) - a hard lock. */
    public static boolean blocksInput() {
        I4LeapConfig cfg = I4LeapConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isPreventInputs()) {
            return false;
        }
        return awaitingLeap || autoI4StillShooting();
    }

    /** The device on your pad has started and not completed while Auto i4 is on (it still has shots to do). */
    static boolean autoI4StillShooting() {
        return deviceStarted && !deviceCompleted
                && com.killer560.hub.i4sensors.I4SensorsConfig.getInstance().isAutoI4Enabled();
    }

    /** QUOI {@code leapToPre4Target()}. */
    static void leapToTarget() {
        I4LeapConfig cfg = I4LeapConfig.getInstance();
        boolean block = cfg.isPreventInputs();
        switch (cfg.getTargetType()) {
            case CLASS -> {
                DungeonClass clazz = cfg.getTargetClass();
                if (clazz == null) {
                    ModChat.send("I4 Leap", ModChat.bad("No target class set"));
                    return;
                }
                LeapManager.leap(clazz, block, false, false);
            }
            case PLAYER -> {
                if (cfg.getTargetName().isBlank()) {
                    ModChat.send("I4 Leap", ModChat.bad("No target player set"));
                    return;
                }
                LeapManager.leap(cfg.getTargetName(), block, false, false);
            }
            default -> {
                LeapManager.Target backup = resolveBackup(cfg);
                String melody = melodyTarget;
                if (melody != null) {
                    LeapManager.leap(melody, backup, block, false, false);
                } else if (backup != null) {
                    FastLeapFeature.LOGGER.info("[I4Leap] No melody player known - using backup {}", backup.name());
                    LeapManager.leap(backup.name(), block, false, false);
                } else {
                    ModChat.send("I4 Leap", ModChat.text("No melody player known and no backup set"));
                    return;
                }
            }
        }
        awaitingLeap = LeapManager.isBusy();
    }

    private static LeapManager.Target resolveBackup(I4LeapConfig cfg) {
        if (cfg.getBackupType() == BackupType.CLASS) {
            return cfg.getBackupClass() == null ? null : LeapManager.resolveClass(cfg.getBackupClass());
        }
        return cfg.getBackupName().isBlank() ? null : LeapManager.resolveName(cfg.getBackupName());
    }

    static String melodyTarget() {
        return melodyTarget;
    }

    private static boolean isAtPre4() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && Floor7Tracker.inF7Boss() && PRE4_BOX.contains(player.position());
    }

    private static boolean isOnPad(Vec3 p) {
        return Math.abs(p.y - 127.0) < 0.5 && p.x >= 62.0 && p.x <= 65.0 && p.z >= 34.0 && p.z <= 37.0;
    }
}
