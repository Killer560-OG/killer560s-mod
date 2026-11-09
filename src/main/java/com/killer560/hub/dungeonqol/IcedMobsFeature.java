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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** Iced Mob Boxes (Devonian BoxIcedMobs, featE-1108): right-clicking an Ice Spray Wand freezes the mobs in front of
 *  you for 5 seconds, and this boxes them for that long. The server tells the client nothing about the freeze, so it
 *  is an estimate: every living non-player mob within {@link #RADIUS} blocks and in front of you at the click. */
public final class IcedMobsFeature {

    static final String WAND = "ICE_SPRAY_WAND";
    static final int RADIUS = 6;
    static final int FREEZE_TICKS = 100;
    private static final int MAX = 128;

    /** Entity id to the game tick its freeze ends. Client thread only; pruned each tick, cleared on world change. */
    private static final Map<Integer, Long> ICED = new HashMap<>();
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
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ICED.clear());
        LevelRenderEvents.COLLECT_SUBMITS.register(IcedMobsFeature::render);
    }

    /** Boxes every mob the wand would reach from where the player stands now. */
    public static int spray(Minecraft client) {
        if (client.level == null || client.player == null) {
            return 0;
        }
        Player me = client.player;
        Vec3 look = me.getLookAngle();
        long end = client.level.getGameTime() + FREEZE_TICKS;
        int n = 0;
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity) || e instanceof Player || e instanceof ArmorStand || !e.isAlive()
                    || ICED.size() >= MAX) {
                continue;
            }
            double dx = e.getX() - me.getX(), dy = e.getY() - me.getY(), dz = e.getZ() - me.getZ();
            if (dx * dx + dy * dy + dz * dz <= RADIUS * RADIUS && dx * look.x + dz * look.z > 0) {
                ICED.put(e.getId(), end);
                n++;
            }
        }
        lastCount = n;
        return n;
    }

    /** Ids still boxed (for the testkit). */
    public static int icedCount() {
        return ICED.size();
    }

    private static void tick(Minecraft client) {
        if (!ICED.isEmpty() && client.level != null) {
            long now = client.level.getGameTime();
            ICED.values().removeIf(end -> end <= now);
        } else if (client.level == null) {
            ICED.clear();
        }
    }

    private static void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (ICED.isEmpty() || client.level == null || !DungeonQolConfig.getInstance().isIcedBoxes()) {
            return;
        }
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        int argb = DungeonQolConfig.getInstance().getIcedColor();
        float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        LINES.clear();
        for (int id : ICED.keySet()) {
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
