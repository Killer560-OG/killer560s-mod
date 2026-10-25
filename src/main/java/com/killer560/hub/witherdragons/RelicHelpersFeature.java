package com.killer560.hub.witherdragons;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.autoroutes.MimicKiller;
import com.killer560.hub.autoroutes.RouteExecutor;
import com.killer560.hub.autoroutes.RouteRotation;
import com.killer560.hub.cheatutils.CheatUtilsConfig;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.BlockHits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.RightClick;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;

/**
 * M7 Phase 5 relic helpers, cheat build only, both OFF by default (dungeon-gaps-1025 item 10, C1). After NoammAddons'
 * {@code floor7/M7Relics.kt} (Relic Triggerbot, Relic Look) and RSA's {@code dungeon/boss/p5/Relics.java} - ideas only.
 * <ul>
 * <li><b>Relic Triggerbot</b>: when the crosshair is ALREADY on a relic (not holding one) or on the held relic's own
 * cauldron, it clicks it - one interaction, at START_CLIENT_TICK, through {@link ActionGate}. It never turns anything to
 * find a target. The click goes only when the look the server last heard (the movement packets) meets the same target
 * too, so the server judges it along a look that hits: a crosshair moved by this frame's mouse but not yet reported waits
 * a tick. Reach is measured eye to BOX: 3.0 for the relic's armor stand (an entity), 4.5 for the cauldron. Optionally
 * selects the relic's hotbar slot one tick before the cauldron click (RSA swaps to slot 9 first).</li>
 * <li><b>Relic Look</b>: turns toward the nearest unpicked relic within Look Range, then (once it is in the inventory) its
 * cauldron - through Auto Routes' {@link RouteRotation} (a humanized approach, then a plain follow while he walks), the
 * same path the routes use: a delta from the running yaw (never clamped or wrapped), pitch held in -90..90. Moving the
 * mouse hands the camera back at once and that target is left alone until the next one.</li>
 * </ul>
 * Stands aside while Auto Routes, Kill Mimic or AP3 own {@link RouteRotation}.
 */
public final class RelicHelpersFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-relichelpers");

    private static long lastClickTick = Long.MIN_VALUE / 4;
    private static Object lastLevel;
    /** Relic stands this client clicked (entity id -> tick), so a stand is not clicked again while the server answers;
     *  one still standing a second later (the click was refused) may be clicked again. */
    private static final java.util.Map<Integer, Long> clickedStands = new java.util.HashMap<>();
    private static final int RETRY_TICKS = 20;
    /** The tick the hotbar was moved to the relic for a cauldron click, or MIN: the click goes the tick after. */
    private static long swappedTick = Long.MIN_VALUE / 4;

    /** Whether {@link RouteRotation} is ours right now. */
    private static boolean looking;
    /** The target the look is on ("stand:<id>" / "cauldron:<relic>"), and one the player took the camera back from. */
    private static String lookTarget;
    private static String releasedTarget;

    private static int clicksSent;
    private static int looksStarted;

    private RelicHelpersFeature() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        RelicHelpersConfig.getInstance();
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("RelicHelpersFeature.tick", RelicHelpersFeature::tick));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FeatureGuard.afterFeatures(
                com.killer560.hub.util.FeatureBits.register("Relic Look frame", () -> RelicHelpersConfig.getInstance().isLookEnabledRaw()),
                ctx -> frame()));
    }

    /** Clicks sent by the triggerbot (ever) - read by the testkit. */
    public static int clicksSent() {
        return clicksSent;
    }

    /** Look approaches begun (ever) - read by the testkit. */
    public static int looksStarted() {
        return looksStarted;
    }

    public static boolean isLooking() {
        return looking;
    }

    // ------------------------------------------------------------------------------------------- tick

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            clickedStands.clear();
            releasedTarget = null;
            stopLook("world changed");
        }
        RelicHelpersConfig cfg = RelicHelpersConfig.getInstance();
        LocalPlayer player = client.player;
        boolean on = cfg.isTriggerEnabled() || cfg.isLookEnabled();
        if (!on || player == null || client.level == null || client.gameMode == null || !inRelicPhase(client)
                || player.isDeadOrDying()) {
            stopLook("not in M7 P5");
            return;
        }
        KingRelic held = heldRelic(player);
        if (cfg.isLookEnabled()) {
            tickLook(client, player, cfg, held);
        } else {
            stopLook("Relic Look off");
        }
        if (cfg.isTriggerEnabled() && McCompat.screen(client) == null) {
            tickTrigger(client, player, cfg, held);
        }
    }

    /** M7 (or p3sim) Phase 5: {@link P5State#inP5} on floor 7 - F7 has no relics. */
    static boolean inRelicPhase(Minecraft client) {
        if (!P5State.inP5()) {
            return false;
        }
        return P5State.isP3Sim(client) || DungeonState.isSimOverrideActive() || !"F7".equals(DungeonState.getFloor())
                && DungeonState.isF7OrM7();
    }

    // ------------------------------------------------------------------------------------------- triggerbot

    private static void tickTrigger(Minecraft client, LocalPlayer player, RelicHelpersConfig cfg, KingRelic held) {
        long now = ActionGate.currentTick();
        if (now - lastClickTick < ActionGate.delayTicks(cfg.getCooldownMs())) {
            return;
        }
        float[] heard = RightClick.heardLook();
        if (heard == null) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 heardLook = Vec3.directionFromRotation(heard[1], heard[0]);
        HitResult crosshair = client.hitResult;
        if (held == null && cfg.isTriggerPickup() && crosshair instanceof EntityHitResult eh
                && eh.getEntity() instanceof ArmorStand stand && relicOnStand(stand) != null && !isPlaced(stand)
                && !recentlyClicked(stand.getId(), now)) {
            AABB box = stand.getBoundingBox();
            double reach = CheatUtilsConfig.MEASURED_MAX_ENTITY_REACH;
            if (BlockHits.boxDistanceSq(eye, box) > reach * reach) {
                return;
            }
            Optional<Vec3> point = box.clip(eye, eye.add(heardLook.scale(reach)));
            if (point.isEmpty()) {
                return; // the server's look does not meet it yet: the movement packet carries the turn first
            }
            if (!ActionGate.tryAct(ActionGate.Actor.RELIC)) {
                return;
            }
            client.gameMode.interact(player, stand, new EntityHitResult(stand, point.get()), InteractionHand.MAIN_HAND);
            player.swing(InteractionHand.MAIN_HAND);
            clickedStands.put(stand.getId(), now);
            lastClickTick = now;
            clicksSent++;
            LOGGER.info("[RelicHelpers] picked up {} relic: stand {} at {}, box distance {}, tick {}", relicOnStand(stand).colourName,
                    stand.getId(), stand.position(), String.format(Locale.ROOT, "%.2f", Math.sqrt(BlockHits.boxDistanceSq(eye, box))), now);
            return;
        }
        if (held != null && cfg.isTriggerPlace() && crosshair instanceof BlockHitResult bh && crosshair.getType() == HitResult.Type.BLOCK
                && bh.getBlockPos().equals(held.cauldronPos)) {
            BlockPos pos = held.cauldronPos;
            double reach = CheatUtilsConfig.MEASURED_MAX_REACH;
            if (BlockHits.boxDistanceSq(eye, pos) > reach * reach) {
                return;
            }
            BlockHitResult srv = client.level.clip(new ClipContext(eye, eye.add(heardLook.scale(reach)), ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, player));
            if (srv.getType() != HitResult.Type.BLOCK || !srv.getBlockPos().equals(pos)) {
                return;
            }
            if (cfg.isSwapToRelic()) {
                int slot = hotbarSlotOf(player, held);
                if (slot >= 0 && player.getInventory().getSelectedSlot() != slot) {
                    com.killer560.hub.util.HotbarSelect.select(client, player, slot);
                    swappedTick = now;
                    return; // click next tick, after the server has the held item
                }
                if (now == swappedTick) {
                    return;
                }
            }
            if (!ActionGate.tryAct(ActionGate.Actor.RELIC)) {
                return;
            }
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, srv);
            player.swing(InteractionHand.MAIN_HAND);
            lastClickTick = now;
            clicksSent++;
            LOGGER.info("[RelicHelpers] placed {} relic on its cauldron {}, tick {}", held.colourName, pos.toShortString(), now);
        }
    }

    // ------------------------------------------------------------------------------------------- look

    private static void tickLook(Minecraft client, LocalPlayer player, RelicHelpersConfig cfg, KingRelic held) {
        if (RouteExecutor.isRunning() || MimicKiller.isBusy() || com.killer560.hub.ap3.Ap3Executor.isRunning()) {
            looking = false; // theirs now; never clear() a rotation another executor owns
            lookTarget = null;
            return;
        }
        String target = null;
        Vec3 aim = null;
        if (held != null) {
            if (cfg.isLookAtCauldron()) {
                target = "cauldron:" + held.name();
                BlockPos p = held.cauldronPos;
                aim = new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
            }
        } else if (cfg.isLookAtRelic()) {
            ArmorStand stand = nearestRelic(client, player, cfg.getLookRange());
            if (stand != null) {
                target = "stand:" + stand.getId();
                aim = stand.getBoundingBox().getCenter();
            }
        }
        if (target == null) {
            stopLook("no target");
            releasedTarget = null;
            return;
        }
        if (target.equals(releasedTarget)) {
            return;
        }
        if (looking && RouteRotation.userMovedCamera(player)) {
            releasedTarget = target;
            stopLook("you moved the camera");
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 d = aim.subtract(eye);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        float pitch = Mth.clamp((float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG), -90f, 90f);
        if (!target.equals(lookTarget) || !looking) {
            RouteRotation.clearUserMoved();
            RouteRotation.rebase();
            RouteRotation.beginApproach(yaw, pitch, false, 0f, 0f);
            looking = true;
            lookTarget = target;
            looksStarted++;
            LOGGER.info("[RelicHelpers] look: turning to {} (yaw {} pitch {}), tick {}", target, String.format(Locale.ROOT, "%.1f", yaw),
                    String.format(Locale.ROOT, "%.1f", pitch), ActionGate.currentTick());
        } else if (RouteRotation.settled(1.0f) || !RouteRotation.isActive()) {
            RouteRotation.follow(yaw, pitch); // he walks toward it: keep the aim on it, no fresh randomness
        }
    }

    private static void stopLook(String why) {
        if (!looking) {
            return;
        }
        looking = false;
        lookTarget = null;
        if (!RouteExecutor.isRunning() && !MimicKiller.isBusy() && !com.killer560.hub.ap3.Ap3Executor.isRunning()) {
            RouteRotation.clear();
        }
        LOGGER.info("[RelicHelpers] look released: {}", why);
    }

    /** Per render frame: the smooth camera step, while the look owns RouteRotation (Auto Routes steps its own). */
    private static void frame() {
        if (looking && !RouteExecutor.isRunning() && !MimicKiller.isBusy() && !com.killer560.hub.ap3.Ap3Executor.isRunning()) {
            RouteRotation.frame();
        }
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static boolean recentlyClicked(int id, long now) {
        Long at = clickedStands.get(id);
        return at != null && now - at < RETRY_TICKS;
    }

    /** The relic an armor stand wears on its head, or null. */
    static KingRelic relicOnStand(ArmorStand stand) {
        var head = stand.getItemBySlot(EquipmentSlot.HEAD);
        if (head.isEmpty()) {
            return null;
        }
        return KingRelic.fromStack(head);
    }

    /** A relic stand standing on a cauldron is one already placed (NoammAddons' check, KingRelicsFeature's too). */
    static boolean isPlaced(Entity stand) {
        for (KingRelic r : KingRelic.values()) {
            double dx = stand.getX() - r.placedX;
            double dz = stand.getZ() - r.placedZ;
            if (dx * dx + dz * dz < 1.5 * 1.5) {
                return true;
            }
        }
        return false;
    }

    private static ArmorStand nearestRelic(Minecraft client, LocalPlayer player, double range) {
        ArmorStand best = null;
        double bestSq = range * range;
        Vec3 eye = player.getEyePosition();
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand) || !stand.isAlive() || recentlyClicked(stand.getId(), ActionGate.currentTick())
                    || relicOnStand(stand) == null || isPlaced(stand)) {
                continue;
            }
            double sq = BlockHits.boxDistanceSq(eye, stand.getBoundingBox());
            if (sq <= bestSq) {
                bestSq = sq;
                best = stand;
            }
        }
        return best;
    }

    static KingRelic heldRelic(LocalPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            KingRelic r = KingRelic.fromStack(inv.getItem(i));
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private static int hotbarSlotOf(LocalPlayer player, KingRelic relic) {
        for (int i = 0; i < 9; i++) {
            if (KingRelic.fromStack(player.getInventory().getItem(i)) == relic) {
                return i;
            }
        }
        return -1;
    }
}
