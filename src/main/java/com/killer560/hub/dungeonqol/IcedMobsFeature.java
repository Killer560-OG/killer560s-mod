package com.killer560.hub.dungeonqol;

import com.killer560.hub.mobesp.GhostMobs;
import com.killer560.hub.util.BoxBatch;
import com.killer560.hub.util.EspTypes;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;

/** Iced Mob Boxes (icedmobs-1118, killer560 2026-11-18: "if he does [have the ice on his head] thats the only time it should
 *  show the blue outline"). A mob frozen by an Ice Spray Wand, anyone's, shows an ice block on its head; this outlines a mob
 *  exactly while the server shows that ice: an ice block in the mob's own head slot, or an armor stand wearing one at the
 *  mob's head. Read only when the server changes an entity's equipment (DungeonQolEquipmentMixin, client thread), never
 *  scanned per frame or tick; a thaw (the old helmet put back) or the stand losing its ice ends the box at once. Nothing is
 *  guessed from wand clicks any more. Cleared on every world change. */
public final class IcedMobsFeature {

    private static final int MAX = 128;
    /** Boxed mob id to the id carrying its ice (itself for a head slot, else the armor stand). Client thread only. */
    private static final Map<Integer, Integer> ICED = new HashMap<>();
    private static final BoxBatch LINES = new BoxBatch();
    private static ClientLevel level;
    private static int drawn;

    private IcedMobsFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("IcedMobsFeature", c -> {
            if (c.level != level) {
                level = c.level;
                ICED.clear();
            }
        }));
        LevelRenderEvents.COLLECT_SUBMITS.register(IcedMobsFeature::render);
    }

    static boolean ice(ItemStack stack) {
        return stack.is(Items.ICE) || stack.is(Items.PACKED_ICE) || stack.is(Items.BLUE_ICE);
    }

    private static boolean mob(Entity e) {
        return e instanceof LivingEntity && !(e instanceof Player) && !(e instanceof ArmorStand) && e.isAlive();
    }

    /** The server set entity {@code e}'s equipment (after vanilla applied it). */
    public static void onEquipment(Entity e) {
        if (!DungeonQolConfig.getInstance().isIcedBoxes() || !(e instanceof LivingEntity le)
                || !(e.level() instanceof ClientLevel cl)) {
            return;
        }
        if (cl != level) { // a packet of a new world can come before its first tick
            level = cl;
            ICED.clear();
        }
        int id = e.getId();
        boolean iced = ice(le.getItemBySlot(EquipmentSlot.HEAD));
        if (e instanceof ArmorStand) {
            ICED.values().removeIf(carrier -> carrier == id);
            if (iced || ice(le.getMainHandItem())) {
                Entity m = mobUnder(e);
                if (m != null && ICED.size() < MAX) {
                    ICED.put(m.getId(), id);
                }
            }
        } else if (mob(e)) {
            if (iced && ICED.size() < MAX) {
                ICED.put(id, id);
            } else if (!iced) {
                ICED.remove(id, id);
            }
        }
    }

    /** The mob whose head a stand holding ice sits at: inside its box grown 0.8 sideways and 1.5 up, nearest first. */
    private static Entity mobUnder(Entity stand) {
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity m : stand.level().getEntities(stand, stand.getBoundingBox().inflate(1.5, 3, 1.5), IcedMobsFeature::mob)) {
            AABB bb = m.getBoundingBox();
            if (stand.getX() >= bb.minX - 0.8 && stand.getX() <= bb.maxX + 0.8 && stand.getZ() >= bb.minZ - 0.8
                    && stand.getZ() <= bb.maxZ + 0.8 && stand.getY() >= bb.minY && stand.getY() <= bb.maxY + 1.5) {
                double d = m.distanceToSqr(stand);
                if (d < bestD) {
                    bestD = d;
                    best = m;
                }
            }
        }
        return best;
    }

    /** Mobs boxed now, and boxes the last frame drew (for the testkit). */
    public static int icedCount() {
        return ICED.size();
    }

    public static int drawnCount() {
        return drawn;
    }

    private static void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (ICED.isEmpty() || client.level == null || client.level != level || !DungeonQolConfig.getInstance().isIcedBoxes()) {
            drawn = 0;
            return;
        }
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        int argb = DungeonQolConfig.getInstance().getIcedColor();
        float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        LINES.clear();
        var it = ICED.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            Entity e = client.level.getEntity(en.getKey());
            Entity carrier = en.getValue().equals(en.getKey()) ? e : client.level.getEntity(en.getValue());
            if (e == null || e.isRemoved() || !e.isAlive() || carrier == null || carrier.isRemoved()) {
                it.remove(); // died, despawned or its stand went: the ice is gone with it
                continue;
            }
            if (GhostMobs.hidden(e)) {
                continue;
            }
            double dx = Mth.lerp(partial, e.xo, e.getX()) - e.getX();
            double dy = Mth.lerp(partial, e.yo, e.getY()) - e.getY();
            double dz = Mth.lerp(partial, e.zo, e.getZ()) - e.getZ();
            AABB bb = e.getBoundingBox();
            LINES.add(bb.minX + dx, bb.minY + dy, bb.minZ + dz, bb.maxX + dx, bb.maxY + dy, bb.maxZ + dz, r, g, b, 1f);
        }
        drawn = LINES.size();
        LINES.drawOutlines(context, EspTypes.lines(true), 2f);
    }
}
