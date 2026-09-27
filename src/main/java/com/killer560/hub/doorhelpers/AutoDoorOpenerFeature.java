package com.killer560.hub.doorhelpers;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Auto Door Opener - port of QUOI {@code module/impl/dungeon/AutoDoorOpener.kt} (runs on tick end, clear
 * phase only, not while dead):
 * <ul>
 * <li>Aura: closest locked door (block at (door.x, 69, door.z)) whose centre is within Range of the eyes.
 * <li>Triggerbot: the crosshair block, when it is within {@code blockInteractionRange}, at y 69..73, and within 2
 * blocks (x and z) of a locked door of the matching material - COAL_BLOCK for Wither, RED_TERRACOTTA for Blood.
 * <li>One attempt per Retry delay (the timer restarts on every attempt, as in QUOI), optional main-hand swing,
 * nothing while a CONTAINER screen is open (see {@code ActionGate}).
 * </ul>
 * The click is QUOI {@code AuraManager.interactBlock} -> {@code BlockInteract}: the face comes from
 * {@code VecUtils.getHitResult} (clip the block's outline shape from the eyes through its centre; no shape = no
 * click), sent here through {@code gameMode.useItemOn} like {@code SecretAuraFeature}. QUOI's one-tick
 * "another use-item packet just went out, queue this one" spacer is not ported (no packet hook here; Retry delay
 * is at least 100ms anyway).
 * <p>
 * killer560, 2026-09-27: "Remove look at doors as a setting from door helpers. can you make it so triggerbot and
 * aura only click the door the second the key is grabbed for door helpers. And rename it to auto door opener."
 * Before this it clicked ANY nearby/looked-at locked door continuously (every Retry delay), key or no key - so a
 * door you had no key for still got clicked forever. Now it does nothing at all until the matching key (read off
 * Hypixel's own chat lines, same three patterns Look At Door used to trust) is picked up, then gets one short
 * window ({@link #PENDING_WINDOW_TICKS}) to land a single click on a door of that key's type before it goes
 * quiet again - not a continuous scan.
 */
public final class AutoDoorOpenerFeature {

    /** How long after a key is picked up this will keep trying to land its one click - long enough to walk the
     *  last couple of blocks up to the door and look at/approach it, not so long it starts to look like the old
     *  continuous behaviour. */
    private static final int PENDING_WINDOW_TICKS = 60;

    private static long lastClick = 0L;
    private static BlockPos lastLoggedPos = null;
    private static long lastLogMs = 0L;

    private static int pendingTicks = 0;
    private static DoorScanner.DoorType pendingType = null;

    private AutoDoorOpenerFeature() {
    }

    /** Same three chat patterns {@code doorhelpers}' old Look At Door trusted for key state - arms the pending
     *  window for the matching door type. Not filtered by whose name is in the message: any party member
     *  obtaining the key means a door of that type is about to need clicking, same as the feature it replaces. */
    static void onChat(String raw) {
        String msg = ChatFormatting.stripFormatting(raw);
        if (msg == null) {
            return;
        }
        if (msg.contains("has obtained Wither Key") || msg.contains("Wither Key was picked up")
                || msg.startsWith("RIGHT CLICK on a WITHER door")) {
            arm(DoorScanner.DoorType.WITHER);
        } else if (msg.contains("has obtained Blood Key") || msg.contains("Blood Key was picked up")
                || msg.startsWith("RIGHT CLICK on the BLOOD DOOR")) {
            arm(DoorScanner.DoorType.BLOOD);
        } else if (msg.contains("opened a WITHER door")) {
            cancelIfPending(DoorScanner.DoorType.WITHER, "door already opened");
        } else if (msg.contains("The BLOOD DOOR has been opened")) {
            cancelIfPending(DoorScanner.DoorType.BLOOD, "door already opened");
        }
    }

    private static void arm(DoorScanner.DoorType type) {
        pendingType = type;
        pendingTicks = PENDING_WINDOW_TICKS;
        DoorHelpersFeature.LOGGER.info("[DoorHelpers] {} Key obtained - Auto Door Opener armed for {} ticks", type,
                PENDING_WINDOW_TICKS);
    }

    private static void cancelIfPending(DoorScanner.DoorType type, String why) {
        if (pendingTicks > 0 && pendingType == type) {
            pendingTicks = 0;
            DoorHelpersFeature.LOGGER.info("[DoorHelpers] Auto Door Opener stood down ({})", why);
        }
    }

    /** Called by {@link DoorHelpersFeature} whenever the clear phase is left - a stale armed window from the
     *  previous room/run should not carry over. */
    static void cancelPending(String why) {
        if (pendingTicks > 0) {
            pendingTicks = 0;
            DoorHelpersFeature.LOGGER.info("[DoorHelpers] Auto Door Opener stood down ({})", why);
        }
    }

    static void tick(Minecraft client, DoorHelpersConfig cfg) {
        // killer560: "only click the door the second the key is grabbed" - nothing to do outside the pickup
        // window; no continuous scan of every locked door regardless of key any more.
        if (pendingTicks <= 0) {
            return;
        }
        pendingTicks--;
        // Screen rules live in ActionGate now, not here: a world action is refused while a CONTAINER
        // screen is open (server-visible) and allowed while a purely client-side one is - this mod's
        // menu, chat, pause. That is what the old "In Menus" toggle was reaching for, so it is gone.
        if (DoorHelpersFeature.isDead(client)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastClick < cfg.getAutoDoorRetryDelayMs()) {
            return;
        }
        List<DoorScanner.Door> matchingDoors = DoorScanner.lockedDoors(client).stream()
                .filter(d -> d.type() == pendingType).toList();
        BlockPos doorPos = switch (cfg.getAutoDoorMode()) {
            case AURA -> findClosestDoor(client.player, matchingDoors, cfg.getAutoDoorRange());
            case TRIGGERBOT -> findLookedAtDoor(client, matchingDoors);
        };
        if (doorPos == null) {
            return;
        }
        // Real gap found (2026-09-21, gate audit): the comment at the top of this method has said since the
        // gate landed that "screen rules live in ActionGate now", and ActionGate.Actor.DOOR_OPENER was
        // declared for this feature - but nothing here ever called tryAct. So the old "In Menus" check was
        // deleted and NOTHING replaced it: this happily right-clicked a door with a container screen open
        // (the exact server-visible tell the gate exists to stop), and it could share a tick with Breaker
        // Aura / Secret Aura / a lever flick. Claimed here, as the last check before lastClick moves, so a
        // denied tick costs nothing - the same door is simply clicked on the next tick the gate allows.
        if (!com.killer560.hub.util.ActionGate.tryAct(com.killer560.hub.util.ActionGate.Actor.DOOR_OPENER)) {
            return;
        }
        boolean sent = interactBlock(client, doorPos);
        if (cfg.isAutoDoorSwing()) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
        lastClick = now;
        if (sent) {
            // One click is the whole point now - stop trying for this pickup rather than keep retrying
            // every Retry delay for the rest of the window.
            pendingTicks = 0;
        }
        if (!doorPos.equals(lastLoggedPos) || now - lastLogMs >= 5000L) {
            lastLoggedPos = doorPos;
            lastLogMs = now;
            DoorHelpersFeature.LOGGER.info("[DoorHelpers] Auto Door Opener {} {} at {} ({})",
                    cfg.getAutoDoorMode(), sent ? "clicked" : "had no hit on", doorPos.toShortString(),
                    client.level.getBlockState(doorPos).getBlock());
        }
    }

    private static BlockPos findClosestDoor(LocalPlayer player, List<DoorScanner.Door> doors, double range) {
        Vec3 eye = player.getEyePosition();
        double rangeSq = range * range;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (DoorScanner.Door door : doors) {
            BlockPos pos = door.basePos();
            double distSq = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (distSq <= rangeSq && distSq < bestDist) {
                bestDist = distSq;
                best = pos;
            }
        }
        return best;
    }

    private static BlockPos findLookedAtDoor(Minecraft client, List<DoorScanner.Door> doors) {
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        LocalPlayer player = client.player;
        double interactionRange = player.blockInteractionRange();
        if (player.getEyePosition().distanceToSqr(hit.getLocation()) > interactionRange * interactionRange) {
            return null;
        }
        BlockPos hitPos = hit.getBlockPos();
        if (hitPos.getY() < 69 || hitPos.getY() > 73) {
            return null;
        }
        Block block = client.level.getBlockState(hitPos).getBlock();
        for (DoorScanner.Door door : doors) {
            if (Math.abs(hitPos.getX() - door.x()) > 2 || Math.abs(hitPos.getZ() - door.z()) > 2) {
                continue;
            }
            boolean matches = switch (door.type()) {
                case WITHER -> block == Blocks.COAL_BLOCK;
                case BLOOD -> block == Blocks.RED_TERRACOTTA;
                default -> false;
            };
            if (matches) {
                return hitPos.immutable();
            }
        }
        return null;
    }

    /** QUOI {@code BlockInteract.execute}. @return false when the block has no outline shape to hit. */
    private static boolean interactBlock(Minecraft client, BlockPos pos) {
        BlockHitResult hit = hitResult(client, pos);
        if (hit == null) {
            return false;
        }
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        return true;
    }

    /** QUOI {@code BlockPos.getHitResult(force = false)} (same as {@code BreakerAuraFeature.hitResult}). */
    static BlockHitResult hitResult(Minecraft client, BlockPos pos) {
        VoxelShape shape = client.level.getBlockState(pos).getShape(client.level, pos);
        if (shape.isEmpty()) {
            return null;
        }
        Vec3 eye = client.player.getEyePosition();
        Vec3 centre = shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
        Vec3 dir = centre.subtract(eye).normalize();
        Vec3 end = eye.add(dir.scale(eye.distanceTo(centre) + 1.5));
        return shape.clip(eye, end, pos);
    }
}
