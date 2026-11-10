package com.killer560.hub.dungeonqol;

import com.killer560.hub.pricetooltip.ItemKeys;
import com.killer560.hub.util.BoxBatch;
import com.killer560.hub.util.EspTypes;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ItemNbt;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Iced Mob Boxes (Devonian BoxIcedMobs, featE-1108): right-clicking an Ice Spray Wand freezes the mobs in front of
 *  you for 5 seconds. A frozen mob shows ice blocks on its head (killer560, 2026-11-09, screenshot), so after a click
 *  this boxes exactly the mobs that carry that marker, for as long as it is there: an ice block in the mob's head slot,
 *  or an armor stand holding one within reach of its head. If no marker shows up within {@link #MARKER_WAIT} ticks of
 *  the click it falls back to the estimate: every living non-player mob within {@link #RADIUS} blocks and in front of
 *  you at the click, for 5 seconds. Each click also logs the entities around the nearest mob's head. */
public final class IcedMobsFeature {

    static final String WAND = "ICE_SPRAY_WAND";
    static final int RADIUS = 6;
    static final int FREEZE_TICKS = 100;
    private static final int MAX = 128;
    /** Ticks after a click to wait for the ice marker before the estimate is used. */
    static final int MARKER_WAIT = 4;

    /** Entity id to the game tick its freeze ends. Client thread only; pruned each tick, cleared on world change. */
    private static final Map<Integer, Long> ICED = new HashMap<>();
    /** Mobs carrying the ice marker now (rebuilt every other tick while a click is recent); drawn instead of the estimate. */
    private static Set<Integer> marked = Set.of();
    /** The estimate from the last click, promoted to {@link #ICED} when no marker shows within {@link #MARKER_WAIT}. */
    private static final Map<Integer, Long> PENDING = new HashMap<>();
    private static long sprayAt = Long.MIN_VALUE;
    private static boolean sawMarker;
    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-icedmobs");
    private static final BoxBatch LINES = new BoxBatch();
    /** For the testkit: how many mobs the last wand click boxed. */
    static volatile int lastCount;

    private IcedMobsFeature() {
    }

    public static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player
                    && DungeonQolConfig.getInstance().isIcedBoxes()
                    && WAND.equals(ItemKeys.id(ItemNbt.view(player.getItemInHand(hand))))) {
                spray(Minecraft.getInstance());
            }
            return InteractionResult.PASS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("IcedMobsFeature", IcedMobsFeature::tick));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ICED.clear();
            PENDING.clear();
            marked = Set.of();
            sprayAt = Long.MIN_VALUE;
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(IcedMobsFeature::render);
    }

    /** Starts a click: the estimate waits in {@link #PENDING} for the ice marker. */
    public static int spray(Minecraft client) {
        if (client.level == null || client.player == null) {
            return 0;
        }
        Player me = client.player;
        Vec3 look = me.getLookAngle();
        long end = client.level.getGameTime() + FREEZE_TICKS;
        sprayAt = client.level.getGameTime();
        sawMarker = false;
        ICED.clear();
        PENDING.clear();
        int n = 0;
        Entity nearest = null;
        double nearestD = Double.MAX_VALUE;
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity) || e instanceof Player || e instanceof ArmorStand || !e.isAlive()
                    || ICED.size() >= MAX) {
                continue;
            }
            double dx = e.getX() - me.getX(), dy = e.getY() - me.getY(), dz = e.getZ() - me.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= RADIUS * RADIUS && dx * look.x + dz * look.z > 0) {
                PENDING.put(e.getId(), end);
                n++;
                if (d < nearestD) {
                    nearestD = d;
                    nearest = e;
                }
            }
        }
        if (nearest != null) {
            dumpAround(client, nearest);
        }
        lastCount = n;
        return n;
    }

    /** One INFO dump per click of what stands around the nearest mob's head, so a log shows what carries the marker. */
    private static void dumpAround(Minecraft client, Entity mob) {
        Vec3 head = mob.getEyePosition();
        StringBuilder sb = new StringBuilder("[IcedMobs] click: nearest ").append(mob.getType().toShortString())
                .append(" head=").append(head).append(" helmet=")
                .append(mob instanceof LivingEntity le ? le.getItemBySlot(EquipmentSlot.HEAD).getItem() : "-");
        int n = 0;
        for (Entity e : client.level.getEntities(mob, new AABB(head, head).inflate(1.5))) {
            if (n++ >= 12) {
                break;
            }
            sb.append(" | ").append(e.getType().toShortString()).append(' ').append(e.position());
            if (e instanceof LivingEntity le) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack st = le.getItemBySlot(slot);
                    if (!st.isEmpty()) {
                        sb.append(' ').append(slot.getName()).append('=').append(st.getItem());
                    }
                }
            }
        }
        LOGGER.info(sb.toString());
    }

    private static boolean ice(ItemStack stack) {
        return stack.is(Items.ICE) || stack.is(Items.PACKED_ICE) || stack.is(Items.BLUE_ICE);
    }

    /** The mobs that carry the ice marker: an ice block in the head slot, or an armor stand holding one near the head. */
    private static Set<Integer> scanMarked(Minecraft client) {
        List<Entity> mobs = new ArrayList<>();
        List<Entity> stands = new ArrayList<>();
        for (Entity e : client.level.entitiesForRendering()) {
            if (e instanceof ArmorStand st) {
                if (ice(st.getItemBySlot(EquipmentSlot.HEAD)) || ice(st.getMainHandItem())) {
                    stands.add(e);
                }
            } else if (e instanceof LivingEntity && !(e instanceof Player) && e.isAlive()) {
                mobs.add(e);
            }
        }
        Set<Integer> out = new HashSet<>();
        for (Entity m : mobs) {
            if (out.size() < MAX && ice(((LivingEntity) m).getItemBySlot(EquipmentSlot.HEAD))) {
                out.add(m.getId());
            }
        }
        for (Entity st : stands) {
            for (Entity m : mobs) {
                AABB bb = m.getBoundingBox();
                if (out.size() < MAX && st.getX() >= bb.minX - 0.8 && st.getX() <= bb.maxX + 0.8
                        && st.getZ() >= bb.minZ - 0.8 && st.getZ() <= bb.maxZ + 0.8
                        && st.getY() >= bb.minY && st.getY() <= bb.maxY + 1.5) {
                    out.add(m.getId());
                }
            }
        }
        return out;
    }

    /** Mobs boxed now, marker or estimate (for the testkit). */
    public static int icedCount() {
        return marked.isEmpty() ? ICED.size() : marked.size();
    }

    /** Mobs boxed because they carry the ice marker (for the testkit). */
    public static int markedCount() {
        return marked.size();
    }

    private static void tick(Minecraft client) {
        if (client.level == null) {
            ICED.clear();
            PENDING.clear();
            marked = Set.of();
            return;
        }
        long now = client.level.getGameTime();
        if (!ICED.isEmpty()) {
            ICED.values().removeIf(end -> end <= now);
        }
        // Scan for the marker only around a click (every other tick for 5 s plus a little), never all the time.
        if (now >= sprayAt && now - sprayAt <= FREEZE_TICKS + 20) {
            if ((now & 1) == 0 && DungeonQolConfig.getInstance().isIcedBoxes()) {
                marked = scanMarked(client);
                if (!marked.isEmpty()) {
                    sawMarker = true;
                    PENDING.clear();
                    ICED.clear();
                }
            }
        } else if (!marked.isEmpty()) {
            marked = Set.of();
        }
        if (!sawMarker && !PENDING.isEmpty() && now - sprayAt >= MARKER_WAIT) {
            ICED.putAll(PENDING);
            PENDING.clear();
        }
    }

    private static void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        Collection<Integer> ids = marked.isEmpty() ? ICED.keySet() : marked;
        if (ids.isEmpty() || client.level == null || !DungeonQolConfig.getInstance().isIcedBoxes()) {
            return;
        }
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        int argb = DungeonQolConfig.getInstance().getIcedColor();
        float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        LINES.clear();
        for (int id : ids) {
            Entity e = client.level.getEntity(id);
            if (e == null || e.isRemoved()) {
                continue;
            }
            double dx = Mth.lerp(partial, e.xo, e.getX()) - e.getX();
            double dy = Mth.lerp(partial, e.yo, e.getY()) - e.getY();
            double dz = Mth.lerp(partial, e.zo, e.getZ()) - e.getZ();
            AABB bb = e.getBoundingBox();
            LINES.add(bb.minX + dx, bb.minY + dy, bb.minZ + dz, bb.maxX + dx, bb.maxY + dy, bb.maxZ + dz, r, g, b, 1f);
        }
        LINES.drawOutlines(context, EspTypes.lines(true), 2f);
    }
}
