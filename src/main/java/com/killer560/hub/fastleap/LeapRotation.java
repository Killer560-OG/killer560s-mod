package com.killer560.hub.fastleap;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.autopuzzles.AutoPuzzleUtil;
import com.killer560.hub.compat.McPackets;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Leap rotation (leaprot-1104, killer560: "sometimes you leap to someone and you will be rotated 180 degrees").
 *
 * <p>What his own log showed (26.3, 63 teleports with the since-removed logger): Hypixel sends a teleport's look ABSOLUTE, and
 * for a leap that look is the leaped-to player's ("if it is longer then it is the rotation of the player you leap to and
 * that is what you want"). But a teleport that comes within about 1 s of the one before (every one at 221-997 ms) has its
 * look RELATIVE 0/0, "on any teleport to not turn you": a leap in that window keeps his own look instead of the target's.
 *
 * <p><b>Leap Takes Target's Look</b> (Fast Leap tab, cheat block, default OFF): when such a relative-look teleport that
 * moved him {@link #LEAP_MIN_BLOCKS}+ blocks and Hypixel's own-leap line "You have teleported to Name!" come within
 * {@link #PAIR_MS} of each other (either order), the camera turns to Name's look once that player is loaded within
 * {@link #NEAR_BLOCKS} blocks (at most {@link #FIND_MS}). It is a plain turn: the shortest way from his yaw (never
 * wrapped), pitch inside -90..90, sent by the next movement packet as any turn is; position is never touched. It runs
 * first in Fast Leap's tick, so a use later in that tick (his or Fast Leap's) carries the turned look, and waits a tick if
 * a use already went out at the old one (GrimAC BadPacketsJ; testkit 1105-leaplook-same-*). Every other
 * teleport (etherwarp, setback, an absolute leap) is left as vanilla applies it. Hypixel 0.27.3 sends a pearl and AOTE/AOTV
 * teleport with a relative 0/0 look too, so the player's own pearl / AOTE-AOTV use drops a pending teleport and leap line
 * (a use is always before its teleport; the leap's own teleport comes after the leap's use). Event-driven; the tick does work only
 * while a turn is pending.
 */
public final class LeapRotation {

    private static final Logger LOGGER = ModLog.get("killer560smod-leaprot");
    static final double LEAP_MIN_BLOCKS = 2.0;
    static final long PAIR_MS = 1500L;
    static final long FIND_MS = 2000L;
    static final double NEAR_BLOCKS = 6.0;

    /** handleMovePlayer's before-half (position), read by its after-half (client thread, never nested). */
    private static Vec3 beforePos;
    private static boolean inTeleport;
    /** The last relative-look teleport that moved him, and the last own-leap line: a pair makes a turn. */
    private static long relTeleportMs;
    private static long leapLineMs;
    private static String leapLineName;
    /** The pending turn: whose look, until when. */
    private static String target;
    private static long targetUntilMs;
    private static long taken;
    /** A use_item went on the wire since the last client_tick_end, at the look before any turn now (leapsame-1105). */
    private static boolean useThisTick;

    private LeapRotation() {}

    static boolean on() {
        return BuildVariant.CHEAT_FEATURES_ENABLED;
    }

    /** Leaps whose look Leap Takes Target's Look set (testkit). */
    public static long taken() {
        return taken;
    }

    public static void clear() {
        relTeleportMs = 0L;
        leapLineMs = 0L;
        leapLineName = null;
        target = null;
    }

    /** Before vanilla applies a server teleport. */
    public static void beforeTeleport(ClientboundPlayerPositionPacket packet) {
        LocalPlayer p = Minecraft.getInstance().player;
        inTeleport = on() && p != null && packet != null && packet.change() != null;
        if (inTeleport) {
            beforePos = p.position();
        }
    }

    /** After vanilla applied it (and sent the accept). */
    public static void afterTeleport(ClientboundPlayerPositionPacket packet) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (!inTeleport || p == null) {
            inTeleport = false;
            return;
        }
        inTeleport = false;
        long now = System.currentTimeMillis();
        double moved = p.position().distanceTo(beforePos);
        if (packet.relatives().contains(Relative.Y_ROT) && moved >= LEAP_MIN_BLOCKS) {
            relTeleportMs = now;
            pair(now);
        }
    }

    /** Hypixel's "You have teleported to Name!" (LeapManager.onChat): his own leap, by hand or by the mod. */
    static void onOwnLeap(String name) {
        if (on()) {
            leapLineMs = System.currentTimeMillis();
            leapLineName = name;
            pair(leapLineMs);
        }
    }

    private static void pair(long now) {
        if (leapLineName == null || now - relTeleportMs > PAIR_MS || now - leapLineMs > PAIR_MS
                || !FastLeapConfig.getInstance().isLeapTakesTargetLook()) {
            return;
        }
        target = leapLineName;
        targetUntilMs = now + FIND_MS;
        relTeleportMs = 0L;
        leapLineName = null;
    }

    /** Start of each client tick: one null check unless a turn is pending. */
    static void tick(Minecraft mc) {
        if (target == null) {
            return;
        }
        if (useThisTick) {
            // GrimAC BadPacketsJ: this tick's movement packet must report the look that use carried, so turn next tick
            return;
        }
        LocalPlayer me = mc.player;
        if (me == null || mc.level == null || System.currentTimeMillis() > targetUntilMs) {
            LOGGER.info("[LeapRotation] Leap Takes Target's Look: {} never showed up near you, look left as it is", target);
            target = null;
            return;
        }
        for (AbstractClientPlayer other : mc.level.players()) {
            if (other != me && other.getName().getString().equalsIgnoreCase(target)
                    && other.distanceTo(me) <= NEAR_BLOCKS) {
                float yaw = me.getYRot() + Mth.wrapDegrees(other.getYHeadRot() - me.getYRot());
                float pitch = Mth.clamp(other.getXRot(), -90f, 90f);
                me.setYRot(yaw);
                me.setXRot(pitch);
                taken++;
                LOGGER.info(String.format(Locale.ROOT, "[LeapRotation] Leap Takes Target's Look: turned to %s's look "
                        + "%.1f/%.1f", target, yaw, pitch));
                target = null;
                return;
            }
        }
    }

    /** Every packet that went on the wire (Connection.send's tail): a use_item since the last client_tick_end. */
    public static void onSent(Object packet) {
        if (packet instanceof ServerboundUseItemPacket use) {
            useThisTick = true;
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null && (leapLineName != null || relTeleportMs != 0L)) {
                ItemStack held = p.getItemInHand(McPackets.useItemHand(use));
                if (held.isEmpty() || held.is(Items.ENDER_PEARL) || AutoPuzzleUtil.isAotv(held)) { // a lone pearl is already consumed client-side when its use is sent (held is then empty)
                    // 0.27.3: a pearl / AOTE teleport also keeps the look (relative 0/0), so it is not the leap's (fastleap-guard-0273)
                    clear();
                }
            }
        } else if (packet instanceof ServerboundClientTickEndPacket) {
            useThisTick = false;
        }
    }
}
