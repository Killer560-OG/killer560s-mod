package com.killer560.hub.smoothtp;

import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Smooth Teleport (killer560, 2026-10-07: "add smooth teleporting, I believe skyhanni and skyblocker have it"): when a
 * teleport ability moves him, the CAMERA glides from where it was to where he landed over a short time instead of
 * snapping. Skyblocker 6.9.1 has this as "Smooth AOTE"; SkyHanni 7.48.0 has nothing like it.
 *
 * <p><b>Purely visual.</b> The player's position is the server's from the moment its position packet is handled -
 * nothing here writes it, sends a packet, or touches rotation. The only write is {@code Camera.setPosition} at the end
 * of {@code Camera.alignWithEntity} ({@code mixin/SmoothTpCameraMixin}), once per rendered frame. The crosshair pick
 * ({@code GameRenderer.pick} -> {@code LocalPlayer.raycastHitResult}) casts from {@code Entity.getEyePosition}, never
 * from the camera (javap, 26.1.2 and 26.2), so what he would click, and every automation ray, is the landed one from
 * the first tick; the glide only changes where the world is drawn from.
 *
 * <p><b>What counts as a teleport.</b> A teleport is a server position packet that answers a use of a teleport item
 * this client sent: every outbound use packet ({@code ServerboundUseItemPacket} / {@code ServerboundUseItemOnPacket},
 * one per client tick) holding a teleport item is queued with that item's range and the look it was sent with, and the
 * next position packet is matched against the oldest one still under {@link #PENDING_MS}. It must land within the
 * item's range (plus slack) and in the direction he was looking. Anything else - a lag-back, a correction, /warp, a
 * server tp, a teleport with no use behind it - cancels any glide in progress and the camera is where he is on the
 * next frame: a correction is honoured, never smoothed over. Skyblocker's default mode instead smooths ANY position
 * packet while a teleport item is held, which would glide a lag-back too.
 *
 * <p><b>Glide.</b> At the first frame after an accepted packet the camera's offset from its vanilla position (which
 * vanilla already put at the landing: {@code setValuesFromPositionPacket} resets the old position too) is taken, and
 * shrinks linearly to zero over the duration: {@code camera = vanilla + offset * (1 - t)}. Because it is an offset on
 * top of vanilla's camera, walking or falling after the landing shows at once. A second teleport mid-glide starts from
 * the camera's CURRENT (mid-glide) spot, so a chain never jumps back. First person only: in a detached camera the
 * player model is drawn at the landing, so a gliding camera would show it ahead of itself - the glide ends instead.
 */
public final class SmoothTeleport {

    private static final Logger LOGGER = ModLog.get("killer560smod-smoothtp");

    /** How long a sent use may wait for its position packet. Hypixel answers well inside a second; this is slack. */
    static final long PENDING_MS = 2000;
    /** Blocks beyond the item's own range a landing may be (eye vs feet, standing on top of the target block). */
    static final double RANGE_SLACK = 3.0;
    /** A landing this close to where he was moved nothing worth gliding. */
    static final double MIN_GLIDE = 0.5;
    /** The landing must lie within ~60 degrees of the look the use was sent with. */
    static final double MIN_COS = 0.5;

    enum Kind { ETHERWARP, INSTANT_TRANSMISSION, WITHER_IMPACT, OTHER }

    private record PendingUse(long sentMs, Kind kind, double range, Vec3 look) {
    }

    private static final ArrayDeque<PendingUse> PENDING = new ArrayDeque<>();
    private static long clientTicks;
    private static long lastQueuedTick = -1;

    // ---- glide state (render thread) --------------------------------------------------------------------------
    /** Where the camera was drawn last frame, and in which level - the start of the next glide. */
    private static Vec3 lastDrawn;
    private static Level lastDrawnLevel;
    /** Set by an accepted position packet: the glide starts from here at the next frame. */
    private static Vec3 startFrom;
    private static Vec3 offset;
    private static long glideStartNs;
    private static long glideNs;
    private static Level glideLevel;

    // ---- counters, for the testkit and the log ----------------------------------------------------------------
    private static int glides;
    private static int snaps;
    private static String lastDecision = "none";

    private SmoothTeleport() {
    }

    public static void register() {
        SmoothTeleportConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(client -> clientTicks++);
    }

    // ---- outbound: a use of a teleport item -------------------------------------------------------------------

    /** From {@code SmoothTpOutboundMixin}, for every packet this client sends. Read-only. */
    public static void onPacketSent(Packet<?> packet) {
        InteractionHand hand;
        Vec3 look;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (packet instanceof ServerboundUseItemPacket use) {
            hand = use.getHand();
            // The rotation the server snaps to before using the item (handleUseItem).
            look = Vec3.directionFromRotation(use.getXRot(), use.getYRot());
        } else if (packet instanceof ServerboundUseItemOnPacket useOn) {
            hand = useOn.getHand();
            look = Vec3.directionFromRotation(player.getXRot(), player.getYRot());
        } else {
            return;
        }
        if (!mc.isSameThread()) {
            return;
        }
        SmoothTeleportConfig cfg = SmoothTeleportConfig.getInstance();
        if (!cfg.isEnabled()) {
            PENDING.clear();
            return;
        }
        ItemStack held = player.getItemInHand(hand);
        Kind kind = kindOf(held, player.isShiftKeyDown());
        if (kind == null) {
            return;
        }
        double range = rangeOf(kind, held);
        long now = System.currentTimeMillis();
        // A block click sends use-on-block AND use in the same tick; Hypixel teleports once.
        if (lastQueuedTick == clientTicks && !PENDING.isEmpty()) {
            PENDING.removeLast();
        }
        while (PENDING.size() >= 8) {
            PENDING.removeFirst();
        }
        PENDING.addLast(new PendingUse(now, kind, range, look));
        lastQueuedTick = clientTicks;
    }

    /** Which teleport this item does, or null for an item that does not teleport. */
    static Kind kindOf(ItemStack stack, boolean sneaking) {
        CompoundTag tag = tag(stack);
        if (tag == null) {
            return null;
        }
        String id = tag.getStringOr("id", "");
        boolean ether = tag.getIntOr("ethermerge", 0) == 1 || "ETHERWARP_CONDUIT".equals(id);
        if (ether && sneaking) {
            return Kind.ETHERWARP;
        }
        return switch (id) {
            case "ASPECT_OF_THE_END", "ASPECT_OF_THE_VOID", "ETHERWARP_CONDUIT" -> Kind.INSTANT_TRANSMISSION;
            case "HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE" -> Kind.WITHER_IMPACT;
            case "SINSEEKER_SCYTHE", "ASPECT_OF_THE_LEECH_1", "ASPECT_OF_THE_LEECH_2" -> Kind.OTHER;
            default -> null;
        };
    }

    /** Hypixel's ranges: 57 etherwarp and 8 Instant Transmission plus the item's own tuners (docs/LESSONS.md), 10 for
     *  Wither Impact; Sinseeker 4 plus tuners and Leech 3/4 are Skyblocker's numbers. */
    static double rangeOf(Kind kind, ItemStack stack) {
        CompoundTag tag = tag(stack);
        int tuners = tag == null ? 0 : Math.max(0, Math.min(4, tag.getIntOr("tuned_transmission", 0)));
        return switch (kind) {
            case ETHERWARP -> 57 + tuners;
            case INSTANT_TRANSMISSION -> 8 + tuners;
            case WITHER_IMPACT -> 10;
            case OTHER -> 4 + tuners;
        };
    }

    private static CompoundTag tag(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
    }

    private static boolean kindEnabled(SmoothTeleportConfig cfg, Kind kind) {
        return switch (kind) {
            case ETHERWARP -> cfg.isEtherwarp();
            case INSTANT_TRANSMISSION -> cfg.isInstantTransmission();
            case WITHER_IMPACT -> cfg.isWitherImpact();
            case OTHER -> cfg.isOtherTeleports();
        };
    }

    // ---- inbound: the server moved him ------------------------------------------------------------------------

    /**
     * From {@code SmoothTpPositionMixin}, on the client thread, BEFORE vanilla applies the packet: {@code from} is
     * where the client still has him, {@code to} where the packet puts him. Decides glide or snap; moves nothing.
     */
    public static void onServerPosition(Vec3 from, Vec3 to) {
        SmoothTeleportConfig cfg = SmoothTeleportConfig.getInstance();
        if (!cfg.isEnabled()) {
            PENDING.clear();
            cancel();
            return;
        }
        long now = System.currentTimeMillis();
        while (!PENDING.isEmpty() && now - PENDING.peekFirst().sentMs() > PENDING_MS) {
            PENDING.removeFirst();
        }
        Vec3 move = to.subtract(from);
        double dist = move.length();
        PendingUse use = PENDING.peekFirst();
        String verdict = judge(use, move, dist);
        if (verdict != null) {
            // Not ours: a correction, a lag-back, a warp. Honour it - no glide, and stop any glide in progress.
            boolean wasGliding = isGliding();
            cancel();
            snaps++;
            lastDecision = String.format(Locale.ROOT, "snap (%s) %.2f blocks%s", verdict, dist,
                    wasGliding ? ", glide cancelled" : "");
            LOGGER.debug("[SmoothTeleport] {}", lastDecision);
            return;
        }
        PENDING.removeFirst();
        if (dist < MIN_GLIDE || !kindEnabled(cfg, use.kind())) {
            cancel();
            lastDecision = String.format(Locale.ROOT, "%s %.2f blocks, not glided", use.kind(), dist);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (lastDrawn == null || mc.player == null || lastDrawnLevel != mc.player.level()) {
            cancel();
            lastDecision = "teleport with no camera frame to start from";
            return;
        }
        startFrom = lastDrawn;
        glideNs = cfg.getDurationMs() * 1_000_000L;
        glides++;
        lastDecision = String.format(Locale.ROOT, "glide %s %.2f blocks over %d ms", use.kind(), dist,
                cfg.getDurationMs());
        LOGGER.debug("[SmoothTeleport] {}", lastDecision);
    }

    /** Null when this packet answers {@code use}; otherwise why it does not. */
    private static String judge(PendingUse use, Vec3 move, double dist) {
        if (use == null) {
            return "no teleport use pending";
        }
        if (dist > use.range() + RANGE_SLACK) {
            return String.format(Locale.ROOT, "%.1f blocks is past the %s's %.0f", dist, use.kind(), use.range());
        }
        if (dist >= MIN_GLIDE) {
            // Etherwarp aims the EYE at the block it lands on, Instant Transmission moves the feet along the look:
            // accept either reading of the direction.
            Vec3 dir = move.normalize();
            Vec3 fromEye = move.subtract(0, 1.62, 0);
            double cos = Math.max(dir.dot(use.look()),
                    fromEye.lengthSqr() < 1e-6 ? -1 : fromEye.normalize().dot(use.look()));
            if (cos < MIN_COS) {
                return String.format(Locale.ROOT, "not along the look (cos %.2f)", cos);
            }
        }
        return null;
    }

    // ---- the camera (render thread, every frame) --------------------------------------------------------------

    /**
     * From {@code SmoothTpCameraMixin} at the end of {@code Camera.alignWithEntity}: where to draw from this frame,
     * given where vanilla put the camera. Returns {@code vanilla} itself when there is nothing to do.
     */
    public static Vec3 cameraPosition(Entity cameraEntity, boolean detached, Vec3 vanilla) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || cameraEntity != player) {
            cancel();
            lastDrawn = null;
            lastDrawnLevel = null;
            return vanilla;
        }
        Level level = player.level();
        if (startFrom != null) {
            Vec3 off = startFrom.subtract(vanilla);
            startFrom = null;
            if (lastDrawnLevel == level && !detached && off.lengthSqr() > 1e-6) {
                offset = off;
                glideStartNs = System.nanoTime();
                glideLevel = level;
            } else {
                cancel();
            }
        }
        Vec3 out = vanilla;
        if (offset != null) {
            double t = (System.nanoTime() - glideStartNs) / (double) glideNs;
            if (t >= 1.0 || detached || glideLevel != level || !SmoothTeleportConfig.getInstance().isEnabled()) {
                cancel();
            } else {
                out = vanilla.add(offset.scale(1.0 - Math.max(0.0, t)));
            }
        }
        lastDrawn = out;
        lastDrawnLevel = level;
        return out;
    }

    private static void cancel() {
        offset = null;
        startFrom = null;
        glideLevel = null;
    }

    // ---- read-only state --------------------------------------------------------------------------------------

    /** True while the camera is drawn somewhere other than where vanilla would put it. */
    public static boolean isGliding() {
        return offset != null || startFrom != null;
    }

    public static int glideCount() {
        return glides;
    }

    public static int snapCount() {
        return snaps;
    }

    public static String lastDecision() {
        return lastDecision;
    }
}
