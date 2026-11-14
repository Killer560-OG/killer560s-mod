package com.killer560.hub.dungeonbreaker;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Dungeon Breaker's guard hook: a click on a block {@link BreakerGuard} protects never starts a dig. The charges
 * are read from the item's lore ("Charges: 3/5"). The old client-side zero ping was removed 2026-11-11: vanilla's
 * startDestroyBlock already removes a block the held tool breaks in one hit, so it never acted.
 */
public final class DungeonBreakerFeature {

    private static final String DUNGEON_BREAKER_SKYBLOCK_ID = "DUNGEONBREAKER";
    private static final Pattern CHARGES_PATTERN = Pattern.compile("Charges: (\\d+)/(\\d+)");

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-dungeonbreaker");
    private static String lastLogKey;
    private static long lastLogTick;

    private DungeonBreakerFeature() {
    }

    /** Rate-limited (same key at most once per 20 ticks): holding attack on one block calls startDestroyBlock every tick. */
    private static void logOnce(String key, String fmt, Object... args) {
        long now = com.killer560.hub.util.SessionRecorderSink.tick();
        if (key.equals(lastLogKey) && now - lastLogTick < 20) {
            return;
        }
        lastLogKey = key;
        lastLogTick = now;
        LOGGER.info(fmt, args);
    }

    /** Called from {@code DungeonBreakerMixin} at the HEAD of {@code startDestroyBlock}: true when the click must
     *  not start a dig at all (nothing sent, nothing predicted) because a Dungeon Breaker with charges is in hand and
     *  {@link BreakerGuard} protects the block (a Fairy room, or a secret with "Don't break secrets" on). */
    public static boolean shouldBlockStart(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED || client.player == null || client.level == null) {
            return false;
        }
        int charges = getBreakerCharges(client.player.getMainHandItem());
        if (charges <= 0) {
            return false;
        }
        boolean blocked = BreakerGuard.protects(client.level, pos);
        if (blocked) {
            logOnce("guard " + pos.toShortString(),
                    "[Breaker] click canceled at startDestroyBlock HEAD: pos={} block={} charges={} fairyRoom={} tick={}"
                            + " - BreakerGuard protects it, nothing sent", pos.toShortString(),
                    client.level.getBlockState(pos).getBlock().getName().getString(), charges,
                    BreakerGuard.inFairyRoom(pos), com.killer560.hub.util.SessionRecorderSink.tick());
        }
        return blocked;
    }

    /** Real charges reading, ported directly from QUOI's own confirmed {@code ItemUtils.getBreakerCharges}
     *  - the real DUNGEONBREAKER item's Skyblock id lives in its real "extra attributes" NBT
     *  ({@code DataComponents.CUSTOM_DATA} -&gt; the real {@code "id"} tag), and its current charge count
     *  is parsed straight out of its own real visible lore line (e.g. "Charges: 3/5"), not a guessed NBT
     *  field - Hypixel doesn't expose charges anywhere else. */
    private static int getBreakerCharges(ItemStack stack) {
        if (stack.isEmpty() || !DUNGEON_BREAKER_SKYBLOCK_ID.equals(getSkyblockId(stack))) {
            return 0;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return 0;
        }
        for (Component line : lore.lines()) {
            Matcher m = CHARGES_PATTERN.matcher(line.getString());
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String getSkyblockId(ItemStack stack) {
        return com.killer560.hub.autoroutes.ItemIdentity.skyblockId(stack); // read-only view, no deep copy
    }
}
