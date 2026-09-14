package com.killer560.hub.dungeonbreaker;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "0 Ping Dungeon Breaker" - killer560's request. Real reference: QUOI's own confirmed, compiling
 * {@code DungeonBreaker.kt} (credited "Kyleen" in that source) - a real Hypixel Skyblock item, the
 * {@code DUNGEONBREAKER} (its real internal Skyblock id), can insta-mine a block while it still has real
 * "Charges" left (read from the item's own real lore, e.g. "Charges: 3/5"), but a normal connection's
 * ping means there's a visible delay between starting to mine and the server confirming the block is
 * actually gone. The "Zero Ping" trick QUOI's own source uses: the moment the real client-side
 * {@code MultiPlayerGameMode#startDestroyBlock} call fires (the one real vanilla method invoked the
 * instant you start breaking a block, which already sends the real mine-start packet itself), locally
 * set that exact block to air immediately - a real, entirely client-side visual prediction (same category
 * of trick vanilla itself already does for normal mining, just applied instantly instead of over real
 * mining time) rather than any packet forgery; the server's own real response still governs whether the
 * block is actually gone.
 * <p>
 * Real safety checks ported directly from that source, not guessed: only applies while genuinely holding
 * a real DUNGEONBREAKER item with charges remaining, and only for the exact block position a real raycast
 * confirms the player is actually looking at right now (never blindly clears an unrelated position).
 * <p>
 * Scope note (2026-09-14): QUOI's own real "Dungeon Breaker" module also bundles a persisted block-route
 * list, a trigger bot, an auto-mining route-runner with FOV/range settings, and configurable render
 * styles for highlighting marked blocks - a substantially larger feature than what killer560 asked for by
 * name ("0 ping dungeon breaker"). Only the real "Zero Ping" insta-mine mechanic itself is implemented
 * here; the route/trigger-bot/auto-mine apparatus is a separate, much larger feature that would need its
 * own explicit request.
 */
public final class DungeonBreakerFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-dungeonbreaker");
    private static final String DUNGEON_BREAKER_SKYBLOCK_ID = "DUNGEONBREAKER";
    private static final Pattern CHARGES_PATTERN = Pattern.compile("Charges: (\\d+)/(\\d+)");

    private DungeonBreakerFeature() {
    }

    /** Called from {@code DungeonBreakerMixin} right after the real
     *  {@code MultiPlayerGameMode#startDestroyBlock} call for this exact position - the real mine-start
     *  packet has already been sent to the server by the time this runs. */
    public static void onStartDestroyBlock(BlockPos pos) {
        DungeonBreakerConfig cfg = DungeonBreakerConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || client.player == null || client.level == null) {
            return;
        }
        if (cfg.isFatigueOnly() && !client.player.hasEffect(MobEffects.MINING_FATIGUE)) {
            return;
        }
        if (getBreakerCharges(client.player.getMainHandItem()) <= 0) {
            return;
        }
        // Real raycast validation, ported directly from QUOI's own real clip check - only ever clears the
        // exact position the player is genuinely looking at right now, never trusting the position alone.
        HitResult clip = client.level.clip(new ClipContext(
                client.player.getEyePosition(), Vec3.atCenterOf(pos),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        if (clip.getType() != HitResult.Type.BLOCK || !(clip instanceof BlockHitResult blockHit)
                || !blockHit.getBlockPos().equals(pos)) {
            return;
        }
        client.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        LOGGER.info("[DungeonBreaker] Zero-ping insta-mined block at {}.", pos);
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
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.contains("id") ? tag.getStringOr("id", null) : null;
    }
}
