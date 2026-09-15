package com.killer560.hub.livemap.autoclear;

import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapConfig;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Auto Blood Rush (cheat): "replicate me pressing the keybind to go to wither doors until blood door opens". Repeats the
 * Interactive Map's Locked Door action: teleport-path ({@link AutoClearUtils#pathToDoor}) to the next locked door,
 * wait there for it to open (optionally clicking it once - door opening itself belongs to Door Helpers), then the
 * next. The next door is the first locked door on the room path toward the Blood door when that door is known (so wither
 * doors come first and side doors like Fairy's are skipped), else the closest locked wither door, else the Blood door.
 * Stops when the Blood door opens (chat or block), on movement/click input, death, boss, leaving the dungeon, world
 * change, repeated path failures, or waiting longer than the door timeout. No walking pathfinder.
 */
public final class BloodRush {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-interactivemap");
    private static final String CHAT = "Blood Rush";
    private static final int MAX_PATH_FAILURES = 3;

    private static boolean running = false;
    private static volatile boolean bloodOpenedMessage = false;
    private static int targetDoor = -1;
    private static long targetSinceMs = 0;
    private static boolean clickedTarget = false;
    private static int pathFailures = 0;
    private static boolean pathStarted = false;
    private static int cooldownTicks = 0;
    private static Object lastLevel = null;

    private BloodRush() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BloodRush::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) {
                return;
            }
            String plain = ChatFormatting.stripFormatting(message.getString());
            if (plain != null && plain.contains("The BLOOD DOOR has been opened!")) {
                bloodOpenedMessage = true;
            }
        });
    }

    public static boolean isRunning() {
        return running;
    }

    public static void toggle() {
        if (running) {
            stop("Stopped");
        } else {
            start();
        }
    }

    public static void start() {
        Minecraft client = Minecraft.getInstance();
        if (!LiveMapConfig.getInstance().isBloodRushEnabled() || client.player == null) {
            return;
        }
        if (!DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            ModChat.send(CHAT, ModChat.bad("Not in dungeon clear"));
            return;
        }
        if (bloodOpenedMessage) {
            ModChat.send(CHAT, ModChat.dim("Blood door already opened"));
            return;
        }
        running = true;
        targetDoor = -1;
        pathFailures = 0;
        pathStarted = false;
        cooldownTicks = 0;
        ModChat.send(CHAT, ModChat.good("Started"));
        LOGGER.info("[BloodRush] Started");
    }

    public static void stop(String reason) {
        if (!running) {
            return;
        }
        running = false;
        targetDoor = -1;
        ClearExecutor.cancel();
        ModChat.send(CHAT, ModChat.text(reason));
        LOGGER.info("[BloodRush] Stopped: {}", reason);
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            bloodOpenedMessage = false;
            if (running) {
                stop("Stopped (world changed)");
            }
        }
        if (!running) {
            return;
        }
        LocalPlayer player = client.player;
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        if (player == null || !cfg.isBloodRushEnabled()) {
            stop("Stopped");
            return;
        }
        if (!DungeonState.isInDungeon()) {
            stop("Stopped (left the dungeon)");
            return;
        }
        if (LiveMapFeature.isInBoss()) {
            stop("Stopped (boss)");
            return;
        }
        if (player.isDeadOrDying() || PartyTracker.isDead(player.getGameProfile().name())) {
            stop("Stopped (dead)");
            return;
        }
        if (userInput(client)) {
            stop("Stopped (input)");
            return;
        }
        if (bloodOpenedMessage) {
            stop("Blood door opened");
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        if (ClearExecutor.isBusy()) {
            return;
        }
        if (pathStarted && ClearExecutor.lastPathFailed()) {
            pathStarted = false;
            if (++pathFailures >= MAX_PATH_FAILURES) {
                stop("Stopped (no path)");
                return;
            }
            cooldownTicks = 10;
            return;
        }
        pathStarted = false;

        DungeonLayout layout = DungeonLayout.capture();
        int blood = layout.bloodDoor();
        if (blood >= 0 && !layout.isLocked(blood) && client.level.isLoaded(DungeonLayout.doorBlock(blood))) {
            stop("Blood door opened");
            return;
        }
        int door = nextDoor(layout);
        if (door < 0) {
            stop("Stopped (no locked door found)");
            return;
        }
        long now = System.currentTimeMillis();
        if (door != targetDoor) {
            targetDoor = door;
            targetSinceMs = now;
            clickedTarget = false;
            pathFailures = 0;
        }
        if (now - targetSinceMs > cfg.getBloodRushDoorTimeoutSec() * 1000L) {
            stop("Stopped (stuck at door)");
            return;
        }
        int room = layout.currentRoom();
        BlockPos approach = room < 0 ? null : DungeonMapPathfinder.getDoorPos(layout, room, door);
        if (approach == null) {
            cooldownTicks = 10;
            return;
        }
        double dx = player.getX() - (approach.getX() + 0.5);
        double dz = player.getZ() - (approach.getZ() + 0.5);
        if (dx * dx + dz * dz <= 2.5 * 2.5 && Math.abs(player.getY() - (approach.getY() + 1)) <= 2.0) {
            // Arrived: wait for the door to open (Door Helpers / the player open it; optionally click it once).
            if (cfg.isBloodRushClickDoor() && !clickedTarget) {
                clickedTarget = true;
                clickDoor(client, door);
            }
            return;
        }
        if (!AutoClearUtils.canPath(layout)) {
            return; // e.g. mid-air after a warp; try again next tick
        }
        if (AutoClearUtils.pathToDoor(layout, door, cfg.isFaceDoorOnArrival())) {
            pathStarted = true;
            cooldownTicks = 2;
        } else if (++pathFailures >= MAX_PATH_FAILURES) {
            stop("Stopped (no path)");
        } else {
            cooldownTicks = 10;
        }
    }

    /** First locked door on the room path toward the Blood door, else nearest wither, else nearest locked door. */
    static int nextDoor(DungeonLayout layout) {
        int room = layout.currentRoom();
        if (room < 0) {
            return -1;
        }
        int blood = layout.bloodDoor();
        if (blood >= 0) {
            int[] resolved = DungeonMapPathfinder.resolve(layout, room, blood, true);
            if (resolved != null) {
                int bloodSide = layout.roomOfCell(resolved[1]);
                List<DungeonMapPathfinder.RoomStep> path = bloodSide == room ? List.of()
                        : DungeonMapPathfinder.findPath(layout, room, bloodSide, true);
                if (path != null) {
                    for (DungeonMapPathfinder.RoomStep step : path) {
                        if (step.door() >= 0 && layout.isLocked(step.door())) {
                            return step.door();
                        }
                    }
                    if (layout.isLocked(blood)) {
                        return blood;
                    }
                }
            }
        }
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int idx = 0; idx < 121; idx++) {
            if (layout.doorType(idx) != DungeonLayout.DOOR_WITHER || !layout.isLocked(idx)) {
                continue;
            }
            int d = DungeonMapPathfinder.getDistToDoor(layout, room, idx, true);
            if (d != Integer.MAX_VALUE && d < bestDist) {
                best = idx;
                bestDist = d;
            }
        }
        return best >= 0 ? best : AutoClearUtils.getLockedDoor(layout);
    }

    private static boolean userInput(Minecraft client) {
        var o = client.options;
        return client.screen == null && (o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()
                || o.keyJump.isDown() || o.keyAttack.isDown() || o.keyUse.isDown());
    }

    private static void clickDoor(Minecraft client, int door) {
        if (client.player == null || client.gameMode == null) {
            return;
        }
        BlockPos lock = DungeonLayout.doorBlock(door);
        BlockPos target = new BlockPos(lock.getX(), 70, lock.getZ());
        Vec3 eye = client.player.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(target);
        if (eye.distanceToSqr(centre) > 4.5 * 4.5) {
            return;
        }
        net.minecraft.core.Direction face = net.minecraft.core.Direction.getApproximateNearest(
                eye.x - centre.x, eye.y - centre.y, eye.z - centre.z);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(centre, face, target, false));
        client.player.swing(InteractionHand.MAIN_HAND);
    }
}
