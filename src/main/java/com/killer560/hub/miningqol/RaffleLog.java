package com.killer560.hub.miningqol;

import com.killer560.hub.scoreboard.ScoreboardData;
import com.killer560.hub.util.TabOrder;
import com.killer560.hub.util.TabText;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owner evidence for the Raffle Ticket Highlight (to-do item 80): the ticket's entity shape on Hypixel is unverified (the
 * highlight boxes a dropped Name Tag, an item named "Raffle Ticket" or a stand named so). When a Raffle starts in the
 * Dwarven Mines - the sidebar or tab list shows the event ("RAFFLE"), or the highlight finds its first ticket - the
 * entities within {@link #RADIUS} blocks are logged once, each with its type, name and equipment, so one real Raffle says
 * what a ticket is. Once per Raffle: a new one needs {@link #QUIET_MS} with no sign of a Raffle. Only with the Raffle
 * highlight switched on (it is OFF by default). Logging only.
 */
public final class RaffleLog {

    private static final Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-miningqol");

    static final double RADIUS = 24.0;
    static final int MAX_ENTITIES = 40;
    static final long QUIET_MS = 60_000L;

    private static boolean inRaffle = false;
    private static long lastSignMs = 0L;
    private static int dumps = 0;
    private static int ticks = 0;
    private static String lastSign = "";

    private RaffleLog() {
    }

    /** Testkit: forget the Raffle in progress, so the next sign of one is a new Raffle. */
    public static void testReset() {
        inRaffle = false;
        lastSignMs = 0L;
    }

    /** Raffle entity dumps written since the game started (testkit). */
    public static int dumps() { return dumps; }

    static void tick(Minecraft client) {
        if (ticks++ % 5 != 0) {
            return;
        }
        if (client.level == null || client.player == null || !MiningState.dwarvenMines()
                || !MiningQolConfig.getInstance().isRaffle()) {
            return;
        }
        long now = System.currentTimeMillis();
        String sign = MiningHighlights.ticketCount() > 0 ? MiningHighlights.ticketCount() + " ticket(s) found" : null;
        if (sign == null && ticks % 20 == 1) {
            sign = eventLine(client);
        }
        if (sign == null) {
            if (inRaffle && now - lastSignMs > QUIET_MS) {
                inRaffle = false;
                LOGGER.info("[Raffle] no sign of a Raffle for {} s: the next one logs again", QUIET_MS / 1000);
            }
            return;
        }
        lastSignMs = now;
        lastSign = sign;
        if (inRaffle) {
            return;
        }
        inRaffle = true;
        dumps++;
        dump(client, sign);
    }

    /** A sidebar or tab line that names the Raffle event (not the "Lucky Raffle" commission), or null. */
    private static String eventLine(Minecraft client) {
        List<String> lines = new ArrayList<>(ScoreboardData.sidebarNow(client));
        if (client.getConnection() != null) {
            for (PlayerInfo info : TabOrder.listed(client)) {
                String plain = TabText.plain(info);
                if (plain != null) {
                    lines.add(plain);
                }
            }
        }
        for (String raw : lines) {
            String line = ChatFormatting.stripFormatting(raw);
            if (line == null || line.contains("Lucky Raffle") || line.contains("Raffle Ticket")) {
                continue;
            }
            if (line.contains("RAFFLE") || line.contains("Raffle")) {
                return "line \"" + line.trim() + "\"";
            }
        }
        return null;
    }

    private static void dump(Minecraft client, String sign) {
        var player = client.player;
        List<Entity> near = new ArrayList<>();
        for (Entity e : client.level.entitiesForRendering()) {
            if (e != player && e.distanceToSqr(player) <= RADIUS * RADIUS) {
                near.add(e);
            }
        }
        near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(player)));
        LOGGER.info("[Raffle] Raffle start ({}) at {}: {} entit(ies) within {} blocks{}", sign,
                player.blockPosition().toShortString(), near.size(), (int) RADIUS,
                near.size() > MAX_ENTITIES ? ", the nearest " + MAX_ENTITIES + " listed" : "");
        for (int i = 0; i < Math.min(MAX_ENTITIES, near.size()); i++) {
            LOGGER.info("[Raffle]   {}", describe(near.get(i), player));
        }
    }

    private static String describe(Entity e, Entity player) {
        StringBuilder sb = new StringBuilder();
        sb.append(EntityType.getKey(e.getType())).append(" #").append(e.getId())
                .append(String.format(Locale.ROOT, " %.1f,%.1f,%.1f (%.1f off)", e.getX(), e.getY(), e.getZ(),
                        Math.sqrt(e.distanceToSqr(player))))
                .append(" name=\"").append(e.getName().getString()).append('"');
        if (e.hasCustomName() && e.getCustomName() != null) {
            sb.append(" customName=\"").append(e.getCustomName().getString()).append("\" visible=")
                    .append(e.isCustomNameVisible());
        }
        if (e instanceof ItemEntity item) {
            sb.append(" item=").append(stack(item.getItem()));
        }
        if (e instanceof LivingEntity living) {
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.MAINHAND,
                    EquipmentSlot.OFFHAND}) {
                ItemStack s = living.getItemBySlot(slot);
                if (!s.isEmpty()) {
                    sb.append(' ').append(slot.name().toLowerCase(Locale.ROOT)).append('=').append(stack(s));
                }
            }
        }
        sb.append(" ticket=").append(MiningHighlights.isTicket(e));
        return sb.toString();
    }

    private static String stack(ItemStack s) {
        return s.isEmpty() ? "-" : s.getItem() + " x" + s.getCount() + " \"" + s.getHoverName().getString() + "\"";
    }
}
